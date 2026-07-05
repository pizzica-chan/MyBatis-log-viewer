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

import com.example.mlv.SqlLogIndex.EntryRow;

public final class SqlQuery {

    private static final String REGEX_META = ".^$*+?()[]{}|\\";
    private static final int FTS_MIN_LEN = 3;

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

    public static Result querySql(Connection conn, SqlQueryFilter filter, long offset, long limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder(SqlLogIndex.selectBase());
        sql.append("WHERE 1=1");
        List<Object> params = new ArrayList<>();

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
        if (filter.grepRe != null && filter.grepText != null
                && isPlainLiteral(filter.grepText) && SqlLogIndex.ftsAvailable(conn)) {
            sql.append(" AND e.id IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)");
            params.add(ftsMatchExpr(filter.grepText));
        }

        sql.append(" ORDER BY e.ts_millis, e.file_id, e.line_no");

        boolean needsRaw = filter.needsRaw();
        Map<String, RandomAccessFile> handles = needsRaw ? new HashMap<String, RandomAccessFile>() : null;

        long total = 0;
        List<EntryRow> page = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    EntryRow e = SqlLogIndex.rowFrom(rs);
                    if (!matchesIndexColumns(e, filter)) {
                        continue;
                    }
                    if (needsRaw) {
                        String raw = readRawCached(handles, e);
                        if (!matchesGrep(filter, raw)) {
                            continue;
                        }
                    }
                    if (total >= offset && page.size() < limit) {
                        page.add(e);
                    }
                    total++;
                }
            }
        } finally {
            if (handles != null) {
                for (RandomAccessFile f : handles.values()) {
                    try {
                        f.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
        }
        return new Result(total, page);
    }

    private static boolean isPlainLiteral(String text) {
        if (text.length() < FTS_MIN_LEN) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (REGEX_META.indexOf(text.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static String ftsMatchExpr(String literal) {
        return "\"" + literal.replace("\"", "\"\"") + "\"";
    }

    private static boolean matchesIndexColumns(EntryRow e, SqlQueryFilter f) {
        if (f.sqlTypes != null && !f.sqlTypes.contains(e.sqlType.toUpperCase())) {
            return false;
        }
        if (f.sinceMillis != null && e.tsMillis < f.sinceMillis) {
            return false;
        }
        if (f.untilMillis != null && e.tsMillis > f.untilMillis) {
            return false;
        }
        if (f.minElapsed != null && (e.elapsedMs == null || e.elapsedMs < f.minElapsed)) {
            return false;
        }
        if (f.maxElapsed != null && (e.elapsedMs == null || e.elapsedMs > f.maxElapsed)) {
            return false;
        }
        if (f.sourceRe != null && !f.sourceRe.matcher(e.source).find()) {
            return false;
        }
        if (f.mapperRe != null && !f.mapperRe.matcher(e.mapper).find()) {
            return false;
        }
        if (f.threadRe != null && !f.threadRe.matcher(e.thread).find()) {
            return false;
        }
        if (f.sqlRe != null && !f.sqlRe.matcher(e.sqlText).find()) {
            return false;
        }
        if (f.parametersRe != null) {
            String params = e.parameters != null ? e.parameters : "";
            if (!f.parametersRe.matcher(params).find()) {
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

    private static String readRawCached(Map<String, RandomAccessFile> handles, EntryRow e) {
        try {
            RandomAccessFile file = handles.get(e.source);
            if (file == null) {
                file = new RandomAccessFile(e.source, "r");
                handles.put(e.source, file);
            }
            file.seek(e.byteOffset);
            long size = e.endByteOffset > e.byteOffset ? e.endByteOffset - e.byteOffset : 0;
            if (size <= 0) {
                return "";
            }
            byte[] buf = new byte[(int) Math.min(size, Integer.MAX_VALUE)];
            file.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
