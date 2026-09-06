package com.example.mlv;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SqlStats {

    private SqlStats() {
    }

    public static final class Summary {
        public long total;
        public String first;
        public String last;
        public Double avgElapsed;
        public Integer maxElapsed;
        public Map<String, Long> bySqlType = new LinkedHashMap<String, Long>();
    }

    public static final class MapperStat {
        public String mapper;
        public long count;
        public Double avgElapsed;
        public Integer maxElapsed;
    }

    public static final class SlowSql {
        public long id;
        public String timestamp;
        public String mapper;
        public String sqlType;
        public String sqlPreview;
        public Integer elapsedMs;
        public String source;
        public long lineNo;
    }

    public static Summary summary(Connection conn) throws SQLException {
        Summary s = new Summary();
        s.total = SqlLogIndex.entryCount(conn);
        String[] bounds = SqlLogIndex.timestampBounds(conn);
        s.first = bounds[0];
        s.last = bounds[1];

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT AVG(elapsed_ms), MAX(elapsed_ms) FROM entries WHERE elapsed_ms IS NOT NULL")) {
            if (rs.next()) {
                double avg = rs.getDouble(1);
                if (!rs.wasNull()) {
                    s.avgElapsed = avg;
                }
                int max = rs.getInt(2);
                if (!rs.wasNull()) {
                    s.maxElapsed = max;
                }
            }
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT sql_type, COUNT(*) FROM entries GROUP BY sql_type ORDER BY COUNT(*) DESC")) {
            while (rs.next()) {
                s.bySqlType.put(rs.getString(1), rs.getLong(2));
            }
        }
        return s;
    }

    /** Mapper 名の部分一致（大文字小文字無視）で集計する。 */
    public static List<MapperStat> searchMappers(Connection conn, String query) throws SQLException {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<MapperStat> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mapper, COUNT(*), AVG(elapsed_ms), MAX(elapsed_ms) "
                        + "FROM entries WHERE instr(lower(mapper), ?) > 0 "
                        + "GROUP BY mapper ORDER BY COUNT(*) DESC")) {
            ps.setString(1, needle);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MapperStat m = new MapperStat();
                    m.mapper = rs.getString(1);
                    m.count = rs.getLong(2);
                    double avg = rs.getDouble(3);
                    if (!rs.wasNull()) {
                        m.avgElapsed = avg;
                    }
                    int max = rs.getInt(4);
                    if (!rs.wasNull()) {
                        m.maxElapsed = max;
                    }
                    result.add(m);
                }
            }
        }
        return result;
    }

    /** Mapper 名の部分一致（大文字小文字無視）で、elapsed 上位を返す。 */
    public static List<SlowSql> searchSlowSql(Connection conn, String query, int limit) throws SQLException {
        if (query == null || query.trim().isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<SlowSql> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT e.id, e.ts_millis, e.mapper, e.sql_type, e.sql_text, e.elapsed_ms, f.path, e.line_no "
                        + "FROM entries e JOIN files f ON e.file_id = f.id "
                        + "WHERE e.elapsed_ms IS NOT NULL AND instr(lower(e.mapper), ?) > 0 "
                        + "ORDER BY e.elapsed_ms DESC LIMIT ?")) {
            ps.setString(1, needle);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSlowSqlRow(rs));
                }
            }
        }
        return result;
    }

    public static List<SlowSql> slowSql(Connection conn, int limit) throws SQLException {
        List<SlowSql> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT e.id, e.ts_millis, e.mapper, e.sql_type, e.sql_text, e.elapsed_ms, f.path, e.line_no "
                        + "FROM entries e JOIN files f ON e.file_id = f.id "
                        + "WHERE e.elapsed_ms IS NOT NULL "
                        + "ORDER BY e.elapsed_ms DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(readSlowSqlRow(rs));
                }
            }
        }
        return result;
    }

    private static SlowSql readSlowSqlRow(ResultSet rs) throws SQLException {
        SlowSql s = new SlowSql();
        s.id = rs.getLong(1);
        s.timestamp = TimeUtil.formatIso(rs.getLong(2));
        s.mapper = rs.getString(3);
        s.sqlType = rs.getString(4);
        String sql = rs.getString(5);
        s.sqlPreview = sql.length() > 80 ? sql.substring(0, 77) + "..." : sql;
        s.elapsedMs = rs.getInt(6);
        s.source = rs.getString(7);
        s.lineNo = rs.getLong(8);
        return s;
    }
}
