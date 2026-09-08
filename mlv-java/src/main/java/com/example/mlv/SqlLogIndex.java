package com.example.mlv;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.example.mlv.MyBatisBlockParser.SqlBlock;

public final class SqlLogIndex {

    private static final int BATCH_SIZE = 5000;
    /**
     * SQLite のページサイズ。
     *
     * <p>既定の 4096 より B-tree が浅くなり、DB が 2.5% 小さくなる
     * （1084MB / 250万 SQL で 834MB → 813MB）。構築時間はこの実装ではほぼ横ばい。
     * 最初のテーブル作成より前にしか効かないため {@link #initSchema} の先頭で設定する
     * （既存 DB では無視される。再構築時はファイルごと作り直すので新しい値が効く）。
     */
    private static final int PAGE_SIZE = 16384;
    /**
     * {@code ANALYZE} が走査する行数の上限。
     *
     * <p>完全な統計は 250万件で 1.94 秒かかるのに対し、サンプリングなら行数に関係なく
     * 数十ミリ秒で終わる。ただし精度は落ちる。低カーディナリティな先頭列
     * （{@code mapper} / {@code sql_type}）では「1 値あたりの行数」がこの値 + 1 の定数に
     * なり、実データを反映しない（50万件で mapper 62500 → 1001、sql_type 125000 → 1001）。
     * 値を上げても定数が変わるだけで精度は上がらない。
     *
     * <p>その結果 sql_type の等値条件が過度に選択的と見なされ、elapsed_ms と併用する
     * COUNT などで {@code idx_entries_elapsed} ではなく {@code idx_entries_type_ts} が
     * 選ばれることがある。どちらが速いかはデータ分布次第で、一覧・ページングの主要な
     * クエリ形状では完全統計と同じ計画になることを確認している。完全な統計のコストは
     * 構築時間の約 5% にあたるため、この偏りを許容して採用している。
     */
    private static final int ANALYSIS_LIMIT = 1000;
    private static final long PROGRESS_INTERVAL = 50_000L;
    private static final long COMMIT_INTERVAL = 200_000L;
    private static final int MAX_SKIPPED_SAMPLES = 5;
    /** パーサスレッド回収の上限時間。呼び出し側が待ち時間を決めるために公開する。 */
    public static final long PARSER_SHUTDOWN_TIMEOUT_MS = 60_000L;
    private static final long SHUTDOWN_TIMEOUT_NANOS =
            TimeUnit.MILLISECONDS.toNanos(PARSER_SHUTDOWN_TIMEOUT_MS);
    private static final long SHUTDOWN_POLL_MS = 50L;
    private static final int PREVIEW_MAX_LEN = 120;
    private static final String META_SKIPPED_LINES = "skipped_lines";
    private static final String META_SKIPPED_SAMPLES = "skipped_samples";

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc が見つかりません", e);
        }
    }

    private SqlLogIndex() {
    }

    public interface ProgressCallback {
        void onProgress(long count);
    }

    public static final class EntryRow {
        public long id;
        public long fileId;
        public long lineNo;
        public long byteOffset;
        public long endByteOffset;
        public long tsMillis;
        public long tsEndMillis;
        public String mapper;
        public String sqlType;
        public String sqlText;
        public String parameters;
        public Integer rowCount;
        public Integer elapsedMs;
        public String thread;
        public String level;
        public boolean complete;
        public String source;
    }

    public static Path indexDbPath(Path logRoot) {
        return IndexStore.indexDbPath(logRoot);
    }

    public static Connection openOrCreate(Path logRoot) throws SQLException, IOException {
        IndexStore.ensureTmpDirFor(logRoot);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + indexDbPath(logRoot).toString());
        initSchema(conn);
        return conn;
    }

    public static Connection openMemory() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try {
            initSchema(conn);
        } catch (IOException e) {
            throw new SQLException(e);
        }
        return conn;
    }

    private static void initSchema(Connection conn) throws SQLException, IOException {
        try (Statement st = conn.createStatement()) {
            // page_size は最初のテーブル作成より前でないと効かないため先頭に置く。
            st.execute("PRAGMA page_size = " + PAGE_SIZE);
            st.execute("PRAGMA journal_mode = MEMORY");
            st.execute("PRAGMA synchronous = OFF");
            st.execute("PRAGMA temp_store = MEMORY");
            st.execute("PRAGMA cache_size = -65536");
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS files ("
                    + "id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, "
                    + "mtime_secs INTEGER NOT NULL, size INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS entries ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, line_no INTEGER NOT NULL, "
                    + "byte_offset INTEGER NOT NULL, end_byte_offset INTEGER, "
                    + "ts_millis INTEGER NOT NULL, ts_end_millis INTEGER, "
                    + "mapper TEXT NOT NULL, sql_type TEXT NOT NULL, sql_text TEXT NOT NULL, "
                    + "parameters TEXT, row_count INTEGER, elapsed_ms INTEGER, "
                    + "thread TEXT NOT NULL, level TEXT NOT NULL, complete INTEGER NOT NULL DEFAULT 1)");
            // 索引は取込中に維持する。取込前に落として取込後にまとめて作る方式
            // （application-log-viewer で 30% 短縮した手法）も試したが、この実装では
            // 一貫して遅くなるため採用していない。この規模では索引 B-tree がページ
            // キャッシュ（cache_size = -65536 なので約 64MiB）に収まり行ごとの維持が
            // 安いのに対し、取込後の一括作成は
            // sql_text / parameters を含む幅の広い表を索引 4 本ぶん走査し直すため。
            //   実測 50万件・1ファイル（LogParser のスレッド判定を最適化した後）:
            //     索引なし 3.48s / 取込中に維持 3.68s（+0.20s） / 取込後に一括 4.48s（+1.12s）
            // 多数ファイルかつ高行数（8ファイル・240万件）では一括が 4% 有利に転じるが、
            // 単一ファイルでは約 20% 悪化するため、現状の方式を維持している。
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_ts ON entries(ts_millis, file_id, line_no)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_mapper ON entries(mapper)");
            // SQL 種別で絞りつつ時刻順に並べる一覧検索用。単独列の idx_entries_sql_type を包含する
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_type_ts "
                    + "ON entries(sql_type, ts_millis, file_id, line_no)");
            st.execute("DROP INDEX IF EXISTS idx_entries_sql_type");
            // FTS は廃止済み。旧世代の索引に残っていれば掃除する
            st.execute("DROP TABLE IF EXISTS entries_fts");
            st.execute("CREATE INDEX IF NOT EXISTS idx_entries_elapsed ON entries(elapsed_ms)");
            ensureEntriesColumns(st);
        }
    }

    /** 既存 DB 向けに列を追加（スキーマ拡張）。 */
    private static void ensureEntriesColumns(Statement st) throws SQLException {
        if (!columnExists(st, "entries", "complete")) {
            st.execute("ALTER TABLE entries ADD COLUMN complete INTEGER NOT NULL DEFAULT 1");
        }
    }

    private static boolean columnExists(Statement st, String table, String column) throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String fileFingerprint(List<Path> paths) throws IOException {
        List<String> parts = new ArrayList<>(paths.size());
        for (Path path : paths) {
            long mtime = Files.getLastModifiedTime(path).toMillis() / 1000L;
            long size = Files.size(path);
            parts.add(PathUtil.normalizePath(path) + ":" + mtime + ":" + size);
        }
        Collections.sort(parts);
        return String.join("\n", parts);
    }

    public static boolean needsRebuild(Connection conn, List<Path> paths)
            throws SQLException, IOException {
        if (paths.isEmpty()) {
            return false;
        }
        String fp = indexFingerprint(paths);
        String stored = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM meta WHERE key = 'fingerprint'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stored = rs.getString(1);
                }
            }
        }
        return !fp.equals(stored);
    }

    private static String indexFingerprint(List<Path> paths) throws IOException {
        // schema:5 で FTS を廃止。旧世代の索引は指紋不一致で再構築される
        return fileFingerprint(paths) + "\nschema:5";
    }

    public static void clearIndex(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM entries");
            st.execute("DELETE FROM files");
            st.execute("DELETE FROM meta WHERE key IN ('"
                    + META_SKIPPED_LINES + "', '" + META_SKIPPED_SAMPLES + "')");
        }
    }

    private static void abortIncompleteBuild(Connection conn) throws SQLException {
        clearIndex(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta WHERE key = 'fingerprint'");
        }
        conn.commit();
    }

    /**
     * クエリプランナ用の統計を生成する。
     * これが無いと SQLite が sql_type / ts_millis のインデックスを選び損ねることがある。
     *
     * <p>サンプリング（{@link #ANALYSIS_LIMIT}）のため行数に関係なく数十ミリ秒で終わる。
     * 索引構成を変えると既存の統計は古くなるので、有無を判定せず毎回作り直す。
     */
    public static void updateStatistics(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA analysis_limit = " + ANALYSIS_LIMIT);
            st.execute("ANALYZE");
        } catch (SQLException ignored) {
            // 統計が無くても検索自体は動くため失敗は無視する
        }
    }

    public static long entryCount(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM entries")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public static int getSkippedLineCount(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, META_SKIPPED_LINES);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1));
                }
            }
        }
        return 0;
    }

    public static List<SkippedLine> getSkippedLineSamples(Connection conn) throws SQLException {
        String json = null;
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, META_SKIPPED_SAMPLES);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    json = rs.getString(1);
                }
            }
        }
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        return deserializeSkippedSamples(json);
    }

    public static String filePath(Connection conn, long fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT path FROM files WHERE id = ?")) {
            ps.setLong(1, fileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static String[] timestampBounds(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MIN(ts_millis), MAX(ts_millis) FROM entries")) {
            if (rs.next()) {
                long min = rs.getLong(1);
                boolean hasMin = !rs.wasNull();
                long max = rs.getLong(2);
                boolean hasMax = !rs.wasNull();
                return new String[] {
                        hasMin ? TimeUtil.formatIso(min) : null,
                        hasMax ? TimeUtil.formatIso(max) : null,
                };
            }
        }
        return new String[] {null, null};
    }

    static final class Row {
        long fileId;
        long lineNo;
        long byteOffset;
        long endByteOffset;
        long tsMillis;
        long tsEndMillis;
        String mapper;
        String sqlType;
        String sqlText;
        String parameters;
        Integer rowCount;
        Integer elapsedMs;
        String thread;
        String level;
        boolean complete;
    }

    private static final List<Row> POISON = Collections.emptyList();

    public static final class BuildResult {
        public final long entryCount;
        public final int skippedLines;
        public final List<SkippedLine> skippedSamples;

        BuildResult(long entryCount, int skippedLines, List<SkippedLine> skippedSamples) {
            this.entryCount = entryCount;
            this.skippedLines = skippedLines;
            this.skippedSamples = skippedSamples;
        }
    }

    public static BuildResult buildIndex(Connection conn, List<Path> paths, ProgressCallback progress)
            throws SQLException, IOException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            return buildIndexTx(conn, paths, progress);
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    private static BuildResult buildIndexTx(Connection conn, List<Path> paths, ProgressCallback progress)
            throws SQLException, IOException {
        clearIndex(conn);
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM meta WHERE key = 'fingerprint'");
        }
        conn.commit();
        String fp = indexFingerprint(paths);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO files (id, path, mtime_secs, size) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < paths.size(); i++) {
                Path path = paths.get(i);
                ps.setLong(1, i + 1L);
                ps.setString(2, PathUtil.normalizePath(path));
                ps.setLong(3, Files.getLastModifiedTime(path).toMillis() / 1000L);
                ps.setLong(4, Files.size(path));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        conn.commit();

        int threads = Math.max(1, Math.min(paths.size(), Runtime.getRuntime().availableProcessors()));
        BlockingQueue<List<Row>> queue = new ArrayBlockingQueue<>(Math.max(8, threads * 4));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger remaining = new AtomicInteger(paths.size());
        AtomicLong skippedCounter = new AtomicLong();
        List<SkippedLine> skippedSamples = Collections.synchronizedList(new ArrayList<SkippedLine>());

        for (int i = 0; i < paths.size(); i++) {
            final long fileId = i + 1L;
            final Path path = paths.get(i);
            pool.submit(() -> {
                try {
                    parseFileInto(fileId, path, queue, skippedCounter, skippedSamples);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        putUninterruptibly(queue, POISON);
                    }
                }
            });
        }
        if (paths.isEmpty()) {
            putUninterruptibly(queue, POISON);
        }

        long total = 0;
        long nextId = 1;
        boolean buildComplete = false;
        try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO entries (id, file_id, line_no, byte_offset, end_byte_offset, "
                        + "ts_millis, ts_end_millis, mapper, sql_type, sql_text, parameters, "
                        + "row_count, elapsed_ms, thread, level, complete) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            while (true) {
                List<Row> batch;
                try {
                    batch = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (batch == POISON) {
                    buildComplete = true;
                    break;
                }
                for (Row r : batch) {
                    ins.setLong(1, nextId++);
                    ins.setLong(2, r.fileId);
                    ins.setLong(3, r.lineNo);
                    ins.setLong(4, r.byteOffset);
                    ins.setLong(5, r.endByteOffset);
                    ins.setLong(6, r.tsMillis);
                    ins.setLong(7, r.tsEndMillis);
                    ins.setString(8, r.mapper);
                    ins.setString(9, r.sqlType);
                    ins.setString(10, r.sqlText);
                    ins.setString(11, r.parameters);
                    if (r.rowCount != null) {
                        ins.setInt(12, r.rowCount);
                    } else {
                        ins.setNull(12, java.sql.Types.INTEGER);
                    }
                    if (r.elapsedMs != null) {
                        ins.setInt(13, r.elapsedMs);
                    } else {
                        ins.setNull(13, java.sql.Types.INTEGER);
                    }
                    ins.setString(14, r.thread);
                    ins.setString(15, r.level);
                    ins.setInt(16, r.complete ? 1 : 0);
                    ins.addBatch();
                }
                ins.executeBatch();
                long before = total;
                total += batch.size();
                if (before / COMMIT_INTERVAL != total / COMMIT_INTERVAL) {
                    conn.commit();
                }
                if (progress != null && before / PROGRESS_INTERVAL != total / PROGRESS_INTERVAL) {
                    progress.onProgress(total);
                }
            }
        } finally {
            shutdownParsers(pool, queue);
        }

        Throwable t = error.get();
        if (t != null || !buildComplete) {
            abortIncompleteBuild(conn);
            if (t != null) {
                throw new IOException("インデックス構築に失敗しました: " + t.getMessage(), t);
            }
            throw new IOException("インデックス構築が中断されました");
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('fingerprint', ?)")) {
            ps.setString(1, fp);
            ps.executeUpdate();
        }
        saveSkippedMeta(conn, (int) skippedCounter.get(), skippedSamples);
        conn.commit();

        if (progress != null) {
            progress.onProgress(total);
        }
        return new BuildResult(total, (int) skippedCounter.get(), new ArrayList<>(skippedSamples));
    }

    /**
     * パーサスレッドを確実に終了させる。
     *
     * <p>読み込み中断で消費側が止まると、パーサスレッドは {@code queue.put()} で
     * 永久にブロックしたままになる（ログファイルのハンドルも保持し続ける）。
     * 割り込みに加えてキューを排出し続けることで put を解放し、スレッドを回収する。
     */
    private static void shutdownParsers(ExecutorService pool, BlockingQueue<List<Row>> queue) {
        pool.shutdownNow();
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
            while (System.nanoTime() < deadline) {
                queue.clear();
                try {
                    if (pool.awaitTermination(SHUTDOWN_POLL_MS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            queue.clear();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void saveSkippedMeta(Connection conn, int count, List<SkippedLine> samples)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
            ps.setString(1, META_SKIPPED_LINES);
            ps.setString(2, String.valueOf(count));
            ps.executeUpdate();
            ps.setString(1, META_SKIPPED_SAMPLES);
            ps.setString(2, serializeSkippedSamples(samples));
            ps.executeUpdate();
        }
    }

    private static String serializeSkippedSamples(List<SkippedLine> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            SkippedLine s = samples.get(i);
            sb.append("{\"file_id\":").append(s.fileId)
                    .append(",\"line_no\":").append(s.lineNo)
                    .append(",\"preview\":").append(jsonString(s.preview)).append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<SkippedLine> deserializeSkippedSamples(String json) {
        List<SkippedLine> out = new ArrayList<>();
        int i = 0;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0) {
                break;
            }
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) {
                break;
            }
            String obj = json.substring(objStart + 1, objEnd);
            long fileId = extractJsonLong(obj, "file_id");
            long lineNo = extractJsonLong(obj, "line_no");
            String preview = extractJsonString(obj, "preview");
            out.add(new SkippedLine(fileId, lineNo, preview));
            i = objEnd + 1;
        }
        return out;
    }

    private static long extractJsonLong(String obj, String key) {
        String needle = "\"" + key + "\":";
        int idx = obj.indexOf(needle);
        if (idx < 0) {
            return 0L;
        }
        int start = idx + needle.length();
        int end = start;
        while (end < obj.length() && Character.isDigit(obj.charAt(end))) {
            end++;
        }
        return Long.parseLong(obj.substring(start, end));
    }

    private static String extractJsonString(String obj, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = obj.indexOf(needle);
        if (idx < 0) {
            return "";
        }
        int start = idx + needle.length();
        StringBuilder sb = new StringBuilder();
        for (int j = start; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (c == '\\' && j + 1 < obj.length()) {
                char next = obj.charAt(j + 1);
                if (next == 'n') {
                    sb.append('\n');
                } else if (next == 'r') {
                    sb.append('\r');
                } else if (next == 't') {
                    sb.append('\t');
                } else if (next == '\\' || next == '"') {
                    sb.append(next);
                } else {
                    sb.append(next);
                }
                j++;
                continue;
            }
            if (c == '"') {
                break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void parseFileInto(long fileId, Path path, BlockingQueue<List<Row>> queue,
            AtomicLong skippedCounter, List<SkippedLine> skippedSamples)
            throws IOException, InterruptedException {
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = new BufferedInputStream(raw, 1 << 16);
             ByteLineReader reader = new ByteLineReader(in)) {
            long lineNo = 0;
            Map<String, SqlBlock> pendingBlocks = new LinkedHashMap<>();
            List<Row> batch = new ArrayList<>(BATCH_SIZE);

            while (reader.next()) {
                lineNo++;
                if (reader.isBlankLine()) {
                    continue;
                }
                boolean header = LogParser.looksLikeHeader(reader.lineBuf, reader.lineLen);
                LogParser.ParsedLine parsed =
                        header ? LogParser.parse(reader.lineBuf, reader.lineLen) : null;

                if (parsed != null && MyBatisBlockParser.isPreparingLine(parsed)) {
                    MyBatisBlockParser.capIncompleteBlocksAt(pendingBlocks.values(), reader.lineStart);
                    String key = MyBatisBlockParser.blockKey(parsed.thread, parsed.logger);
                    SqlBlock existing = pendingBlocks.remove(key);
                    if (existing != null) {
                        batch = flushBlock(existing, reader.lineStart, batch, queue);
                    }
                    SqlBlock block = MyBatisBlockParser.startBlock(fileId, lineNo, reader.lineStart, parsed);
                    MyBatisBlockParser.noteRawLineEnd(block, reader.position());
                    pendingBlocks.put(key, block);
                } else if (parsed != null) {
                    String key = MyBatisBlockParser.blockKey(parsed.thread, parsed.logger);
                    SqlBlock pending = pendingBlocks.get(key);
                    if (pending != null
                            && MyBatisBlockParser.isBlockContinuation(parsed, pending.mapper, pending.thread)) {
                        MyBatisBlockParser.mergeLine(pending, parsed);
                        MyBatisBlockParser.noteRawLineEnd(pending, reader.position());
                        pending.captureTail = false;
                        if (MyBatisBlockParser.isBlockEnd(parsed)) {
                            pendingBlocks.remove(key);
                            batch = flushBlock(pending, reader.position(), batch, queue);
                        }
                    } else {
                        for (SqlBlock open : pendingBlocks.values()) {
                            if (parsed.thread.equals(open.thread)) {
                                MyBatisBlockParser.noteRawLineEnd(open, reader.position());
                                open.captureTail = true;
                            } else {
                                open.captureTail = false;
                            }
                        }
                    }
                } else if (!header) {
                    for (SqlBlock open : pendingBlocks.values()) {
                        if (open.captureTail) {
                            MyBatisBlockParser.noteRawLineEnd(open, reader.position());
                        }
                    }
                } else if (header) {
                    for (SqlBlock open : pendingBlocks.values()) {
                        open.captureTail = false;
                    }
                    skippedCounter.incrementAndGet();
                    if (skippedSamples.size() < MAX_SKIPPED_SAMPLES) {
                        skippedSamples.add(new SkippedLine(fileId, lineNo,
                                previewLine(reader.lineBuf, reader.lineLen)));
                    }
                }
            }

            for (SqlBlock pending : pendingBlocks.values()) {
                batch = flushBlock(pending, reader.position(), batch, queue);
            }
            if (!batch.isEmpty()) {
                queue.put(batch);
            }
        }
    }

    private static List<Row> flushBlock(SqlBlock block, long endOffset, List<Row> batch,
            BlockingQueue<List<Row>> queue) throws InterruptedException {
        MyBatisBlockParser.finalizeBlock(block, endOffset);
        batch.add(toRow(block));
        if (batch.size() >= BATCH_SIZE) {
            queue.put(batch);
            return new ArrayList<>(BATCH_SIZE);
        }
        return batch;
    }

    private static Row toRow(SqlBlock block) {
        Row row = new Row();
        row.fileId = block.fileId;
        row.lineNo = block.lineNo;
        row.byteOffset = block.byteOffset;
        row.endByteOffset = block.endByteOffset;
        row.tsMillis = block.tsMillis;
        row.tsEndMillis = block.tsEndMillis;
        row.mapper = block.mapper;
        row.sqlType = block.sqlType;
        row.sqlText = block.sqlText;
        row.parameters = block.parameters;
        row.rowCount = block.rowCount;
        row.elapsedMs = block.elapsedMs;
        row.thread = block.thread;
        row.level = block.level;
        row.complete = block.complete;
        return row;
    }

    private static String previewLine(byte[] buf, int len) {
        int end = len;
        while (end > 0 && (buf[end - 1] == '\n' || buf[end - 1] == '\r')) {
            end--;
        }
        String trimmed = new String(buf, 0, end, StandardCharsets.UTF_8);
        if (trimmed.length() <= PREVIEW_MAX_LEN) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_LEN - 3) + "...";
    }

    /**
     * 割り込みで諦めずに投入する。ただし消費側が既に停止している場合に
     * 永久ブロックしないよう上限時間を設ける（超過時は投入を諦める）。
     */
    private static <T> void putUninterruptibly(BlockingQueue<T> queue, T item) {
        boolean interrupted = Thread.interrupted();
        try {
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT_NANOS;
            while (System.nanoTime() < deadline) {
                try {
                    if (queue.offer(item, SHUTDOWN_POLL_MS, TimeUnit.MILLISECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static String readEntryRaw(Path path, long start, long end) throws IOException {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "r")) {
            file.seek(start);
            if (end <= start) {
                String line = file.readLine();
                return line == null ? "" : stripEol(new String(
                        line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
            int size = (int) Math.min(end - start, Integer.MAX_VALUE);
            byte[] buf = new byte[size];
            file.readFully(buf);
            return stripEol(new String(buf, StandardCharsets.UTF_8));
        }
    }

    private static String stripEol(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static final String SELECT_BASE =
            "SELECT e.id, e.file_id, e.line_no, e.byte_offset, e.end_byte_offset, "
                    + "e.ts_millis, e.ts_end_millis, e.mapper, e.sql_type, e.sql_text, "
                    + "e.parameters, e.row_count, e.elapsed_ms, e.thread, e.level, e.complete, f.path "
                    + "FROM entries e JOIN files f ON e.file_id = f.id ";

    static EntryRow rowFrom(ResultSet rs) throws SQLException {
        EntryRow e = new EntryRow();
        e.id = rs.getLong(1);
        e.fileId = rs.getLong(2);
        e.lineNo = rs.getLong(3);
        e.byteOffset = rs.getLong(4);
        e.endByteOffset = rs.getLong(5);
        e.tsMillis = rs.getLong(6);
        e.tsEndMillis = rs.getLong(7);
        e.mapper = rs.getString(8);
        e.sqlType = rs.getString(9);
        e.sqlText = rs.getString(10);
        e.parameters = rs.getString(11);
        int rc = rs.getInt(12);
        e.rowCount = rs.wasNull() ? null : rc;
        int em = rs.getInt(13);
        e.elapsedMs = rs.wasNull() ? null : em;
        e.thread = rs.getString(14);
        e.level = rs.getString(15);
        e.complete = rs.getInt(16) != 0;
        e.source = rs.getString(17);
        return e;
    }

    public static EntryRow findEntry(Connection conn, String source, long lineNo, String timestampIso)
            throws SQLException {
        if (timestampIso != null && !timestampIso.isEmpty()) {
            long tsMillis = TimeUtil.parseUiDatetime(timestampIso);
            try (PreparedStatement ps = conn.prepareStatement(
                    SELECT_BASE + "WHERE f.path = ? AND e.line_no = ? AND e.ts_millis = ? LIMIT 1")) {
                ps.setString(1, source);
                ps.setLong(2, lineNo);
                ps.setLong(3, tsMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rowFrom(rs) : null;
                }
            }
        }
        return findEntry(conn, source, lineNo);
    }

    public static EntryRow findEntry(Connection conn, String source, long lineNo) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                SELECT_BASE + "WHERE f.path = ? AND e.line_no = ? LIMIT 1")) {
            ps.setString(1, source);
            ps.setLong(2, lineNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowFrom(rs) : null;
            }
        }
    }

    static String selectBase() {
        return SELECT_BASE;
    }
}
