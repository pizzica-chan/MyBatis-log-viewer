package com.example.mlv;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.example.mlv.SqlLogIndex.EntryRow;

public final class SqlQuery {

    private static final String ORDER_BY = " ORDER BY e.ts_millis, e.file_id, e.line_no";

    private SqlQuery() {
    }

    public static final class Result {
        public final long total;
        public final List<EntryRow> page;

        Result(long total, List<EntryRow> page) {
            this.total = total;
            this.page = page;
        }
    }

    /**
     * SQL 一覧を検索する。
     *
     * <p>SQL 種別を 1 つだけ指定した場合は複合索引 {@code idx_entries_type_ts} を
     * 順序どおり辿れるため、深い offset でも高速。カンマ区切りで複数指定すると
     * {@code IN} になり索引順を使えないため、SQLite は {@code idx_entries_ts} を
     * 走査して行ごとに種別を判定する（20 万件・offset 40000 で 0.03 秒 → 0.66 秒）。
     */
    public static Result querySql(Connection conn, SqlQueryFilter filter, long offset, long limit)
            throws SQLException {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();
        appendConditions(where, params, filter);
        if (filter.sourceRe != null) {
            where.append(sourceCondition(conn, filter.sourceRe));
        }

        if (!filter.needsJavaFilter()) {
            // 正規表現・grep がなければ件数もページングも SQL 側で完結できる
            long total = countMatches(conn, where, params);
            List<EntryRow> page = limit > 0
                    ? fetchPage(conn, where, params, offset, limit)
                    : new ArrayList<EntryRow>();
            return new Result(total, page);
        }
        return scanWithJavaFilter(conn, where, params, filter, offset, limit);
    }

    /** SQL 側で評価できる条件を WHERE 句に積む。 */
    private static void appendConditions(StringBuilder sql, List<Object> params,
            SqlQueryFilter filter) {
        if (filter.sqlTypes != null && !filter.sqlTypes.isEmpty()) {
            sql.append(" AND e.sql_type IN (");
            boolean first = true;
            for (String type : filter.sqlTypes) {
                sql.append(first ? "?" : ", ?");
                params.add(type);
                first = false;
            }
            sql.append(")");
        }
        if (filter.sinceMillis != null) {
            sql.append(" AND e.ts_millis >= ?");
            params.add(filter.sinceMillis);
        }
        if (filter.untilMillis != null) {
            sql.append(" AND e.ts_millis <= ?");
            params.add(filter.untilMillis);
        }
        if (filter.minElapsed != null) {
            sql.append(" AND e.elapsed_ms >= ?");
            params.add(filter.minElapsed);
        }
        if (filter.maxElapsed != null) {
            sql.append(" AND e.elapsed_ms <= ?");
            params.add(filter.maxElapsed);
        }
        if (filter.minRowCount != null) {
            sql.append(" AND e.row_count >= ?");
            params.add(filter.minRowCount);
        }
        if (filter.maxRowCount != null) {
            sql.append(" AND e.row_count <= ?");
            params.add(filter.maxRowCount);
        }
        if (filter.complete != null) {
            sql.append(" AND e.complete = ?");
            params.add(filter.complete ? 1 : 0);
        }
    }

    /**
     * source（ログファイルのパス）の正規表現を、files テーブルだけで判定して
     * {@code e.file_id IN (...)} の条件（先頭に {@code " AND "} 付き。絞り込まないなら空文字）にする。
     *
     * <p>パスはファイルごとに 1 つなので、エントリごとに照合しなくても結果は同じになる。
     * SQL 側で確定するので、source だけの絞り込みは {@code COUNT(*)} と {@code LIMIT} で返せる。
     * id は DB から取り出した整数なので、バインド変数の上限を気にせず SQL に直接埋め込む。
     *
     * <p>列に単項の {@code +} を付け、この条件を索引や結合順の選択に使わせない。付けておけば
     * 実行計画は source を指定しない場合と同じ形のまま、辿った行をこの条件でふるうだけになる。
     * 付けないと 3 ファイルの索引で files を外側に回し、並べ直しを入れる計画が選ばれる
     * （試験 {@code sourceConditionKeepsIndexOrder} で確認している）。
     * 実測は {@link #scanWithJavaFilter} のコメントを参照。
     */
    static String sourceCondition(Connection conn, Pattern sourceRe) throws SQLException {
        List<Long> ids = new ArrayList<>();
        int fileCount = 0;
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, path FROM files");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fileCount++;
                if (sourceRe.matcher(rs.getString(2)).find()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        if (ids.size() == fileCount) {
            // すべてのファイルが一致するなら絞り込むものはない
            return "";
        }
        if (ids.isEmpty()) {
            return " AND 0";
        }
        StringBuilder sql = new StringBuilder(" AND +e.file_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(ids.get(i).longValue());
        }
        return sql.append(")").toString();
    }

    private static long countMatches(Connection conn, CharSequence where, List<Object> params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM entries e " + where)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static List<EntryRow> fetchPage(Connection conn, CharSequence where, List<Object> params,
            long offset, long limit) throws SQLException {
        List<EntryRow> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                SqlLogIndex.selectBase() + where + ORDER_BY + " LIMIT ? OFFSET ?")) {
            int i = bindParams(ps, params);
            ps.setLong(i++, limit);
            ps.setLong(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    page.add(SqlLogIndex.rowFrom(rs));
                }
            }
        }
        return page;
    }

    private static int bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setObject(i + 1, params.get(i));
        }
        return params.size() + 1;
    }

    /**
     * 正規表現 / grep がある場合のみ、合致行を全件走査して Java 側で絞り込む。
     * 件数を確定させるため打ち切れない。
     *
     * <p>速度のために 3 つの手を使う。いずれも結果は変えない。
     * <ul>
     *   <li>元ファイルは {@link SqlLogIndex.SequentialRawReader} で前方向にまとめ読みする
     *       （エントリごとの seek を避ける）</li>
     *   <li>grep がメタ文字を含まないリテラルなら、UTF-8 デコードせずバイト列のまま探す
     *       （grep は ASCII だけ大文字小文字を無視するので、同じ畳み方で比べる）</li>
     *   <li>mapper / sql_text / parameters などの文字列は、ページに載る行でだけ取り出す。
     *       列の絞り込みがあるときも、照合に使う列だけを取り出す</li>
     * </ul>
     * 実測は {@code docs/performance-report.md} を参照。
     *
     * <p>列の絞り込みで照合に使う列だけを取り出し、source を SQL へ押し下げた
     * （{@link #sourceCondition}）ときの実測（30 万エントリ・90 万行・165 MB を 30 ファイルに
     * 分けたもの、Windows 11 / JDK 11、5 回の中央値を 3 ラウンド取った中央値）:
     * mapper 912ms → 334ms、sql 1,011ms → 428ms、parameters 872ms → 311ms、
     * thread 893ms → 295ms、mapper + grep 987ms → 399ms。
     * source は全ファイルに一致 887ms → 4ms、5 ファイルに一致 971ms → 14ms、
     * それに mapper を併用 994ms → 69ms。以前は 17 列をすべて作ってから照合していた。
     */
    private static Result scanWithJavaFilter(Connection conn, CharSequence where, List<Object> params,
            SqlQueryFilter filter, long offset, long limit) throws SQLException {
        StringBuilder sql = new StringBuilder(SqlLogIndex.selectBase());
        sql.append(where).append(ORDER_BY);

        boolean needsRaw = filter.needsRaw();
        boolean needsColumns = needsRegexColumns(filter);
        // メタ文字がなければ「部分一致」なので、正規表現を通さずバイト列で探せる
        byte[] literal = needsRaw && SqlQueryFilter.hasNoRegexMeta(filter.grepText)
                ? SqlLogIndex.toLowerAscii(filter.grepText.getBytes(StandardCharsets.UTF_8))
                : null;
        Map<String, SqlLogIndex.SequentialRawReader> readers =
                needsRaw ? new HashMap<String, SqlLogIndex.SequentialRawReader>() : null;
        Map<Long, String> paths = needsRaw ? new HashMap<Long, String>() : null;
        byte[] buffer = new byte[8192];

        long total = 0;
        List<EntryRow> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (needsColumns && !matchesRegexFilters(rs, filter)) {
                        continue;
                    }
                    if (needsRaw) {
                        long start = rs.getLong(4);
                        long end = rs.getLong(5);
                        int len = (int) Math.max(0, Math.min(end - start, Integer.MAX_VALUE));
                        if (len > buffer.length) {
                            buffer = new byte[len];
                        }
                        int read = len > 0 ? readRaw(rs, readers, paths, start, len, buffer) : 0;
                        if (literal != null) {
                            if (!SqlLogIndex.containsBytesIgnoreAsciiCase(buffer, read, literal)) {
                                continue;
                            }
                        } else if (!matchesGrep(filter,
                                new String(buffer, 0, read, StandardCharsets.UTF_8))) {
                            continue;
                        }
                    }
                    if (total >= offset && page.size() < limit) {
                        page.add(SqlLogIndex.rowFrom(rs));
                    }
                    total++;
                }
            }
        } catch (IOException ex) {
            // 読めなかった行を「一致しなかった」と同じ扱いにすると結果が静かにずれるため、
            // 黙って飛ばさずエラーにする
            throw new SQLException("ログファイルを読み出せません: " + ex.getMessage(), ex);
        } finally {
            SqlLogIndex.closeReaders(readers);
        }
        return new Result(total, page);
    }

    /**
     * SQL 側で表現できない正規表現条件だけを判定する。
     * sql_type / 時刻 / elapsed / row_count / complete / source は WHERE 句で既に絞り込み済み。
     *
     * <p>列の文字列は、照合に使う列だけを取り出す（一致しない行のために使わない文字列を作らない）。
     * 列番号は {@link SqlLogIndex#rowFrom} と同じ。
     */
    private static boolean matchesRegexFilters(ResultSet rs, SqlQueryFilter f) throws SQLException {
        if (f.mapperRe != null && !f.mapperRe.matcher(rs.getString(8)).find()) {
            return false;
        }
        if (f.threadRe != null && !f.threadRe.matcher(rs.getString(14)).find()) {
            return false;
        }
        if (f.sqlRe != null && !f.sqlRe.matcher(rs.getString(10)).find()) {
            return false;
        }
        if (f.parametersRe != null) {
            String params = rs.getString(11);
            if (!f.parametersRe.matcher(params != null ? params : "").find()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesGrep(SqlQueryFilter f, String raw) {
        if (f.grepRe == null) {
            return true;
        }
        return f.grepRe.matcher(raw).find();
    }

    /** いまの行の byte 範囲を読み出す。ファイルのパスは file_id ごとに 1 回だけ取り出す。 */
    private static int readRaw(ResultSet rs, Map<String, SqlLogIndex.SequentialRawReader> readers,
            Map<Long, String> paths, long start, int len, byte[] into)
            throws SQLException, IOException {
        long fileId = rs.getLong(2);
        String path = paths.get(fileId);
        if (path == null) {
            path = rs.getString(17);
            paths.put(fileId, path);
        }
        SqlLogIndex.SequentialRawReader reader = readers.get(path);
        if (reader == null) {
            reader = new SqlLogIndex.SequentialRawReader(path);
            readers.put(path, reader);
        }
        return reader.read(start, len, into);
    }

    /** 列（mapper / sql_text / parameters / thread）の絞り込みがあるか。source は SQL 側で絞り込み済み。 */
    private static boolean needsRegexColumns(SqlQueryFilter f) {
        return f.mapperRe != null || f.sqlRe != null || f.parametersRe != null
                || f.threadRe != null;
    }
}
