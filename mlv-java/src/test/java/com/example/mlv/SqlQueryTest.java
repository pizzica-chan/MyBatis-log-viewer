package com.example.mlv;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryTest {

    private static Path sampleLog() {
        Path sample = Paths.get("..", "samples", "mybatis-sample.log").toAbsolutePath().normalize();
        if (!sample.toFile().exists()) {
            sample = Paths.get("samples", "mybatis-sample.log").toAbsolutePath().normalize();
        }
        return sample;
    }

    @Test
    void filterByMapper() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);
            SqlQueryFilter f = new SqlQueryFilter();
            f.mapperRe = SqlQueryFilter.compileRegex("UserMapper");
            SqlQuery.Result r = SqlQuery.querySql(conn, f, 0, 100);
            assertTrue(r.total >= 4);
            for (SqlLogIndex.EntryRow e : r.page) {
                assertTrue(e.mapper.contains("UserMapper"));
            }
        }
    }

    @Test
    void sampleLogIndexesExpectedSqlBlockCount() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);
            // 6 完了 + 1 未完了(selectMissing) + 2 末尾インターリーブ = 9
            assertEquals(9, SqlQuery.querySql(conn, new SqlQueryFilter(), 0, 100).total);
        }
    }

    @Test
    void filterByRowCount() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);

            SqlQueryFilter zeroRows = new SqlQueryFilter();
            zeroRows.minRowCount = 0;
            zeroRows.maxRowCount = 0;
            SqlQuery.Result zero = SqlQuery.querySql(conn, zeroRows, 0, 100);
            assertTrue(zero.total >= 1);
            assertTrue(zero.page.stream().allMatch(e -> e.rowCount != null && e.rowCount == 0));

            SqlQueryFilter manyRows = new SqlQueryFilter();
            manyRows.minRowCount = 100;
            SqlQuery.Result heavy = SqlQuery.querySql(conn, manyRows, 0, 100);
            assertEquals(1, heavy.total);
            assertTrue(heavy.page.get(0).mapper.contains("ReportMapper.heavyQuery"));
            assertEquals(Integer.valueOf(150), heavy.page.get(0).rowCount);

            SqlQueryFilter incomplete = new SqlQueryFilter();
            incomplete.minRowCount = 1;
            incomplete.complete = Boolean.FALSE;
            assertEquals(0, SqlQuery.querySql(conn, incomplete, 0, 100).total);
        }
    }

    @Test
    void searchResultIncludesThread() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);
            SqlQueryFilter f = new SqlQueryFilter();
            f.mapperRe = SqlQueryFilter.compileRegex("selectById");
            SqlLogIndex.EntryRow row = SqlQuery.querySql(conn, f, 0, 1).page.get(0);
            assertEquals("http-nio-8080-exec-1", row.thread);
            assertNotNull(row.source);
        }
    }

    @Test
    void filterBySourceMatchesFullPath() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);

            assertEquals(9, queryBySource(conn, "samples").total);
            assertEquals(9, queryBySource(conn, "mybatis-sample\\.log").total);
        }
    }

    /**
     * SQL 押し下げパスと Java 走査パスが同じ結果になることを確認する。
     * 走査側は常に真となる source 正規表現を足すだけにして、絞り込み条件は揃える。
     */
    @Test
    void sqlPushdownMatchesJavaScan() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);
            for (SqlQueryFilter pushdown : pushdownFilters()) {
                SqlQueryFilter scan = copyOf(pushdown);
                scan.sourceRe = SqlQueryFilter.compileRegex(".");
                assertFalse(pushdown.needsJavaFilter());
                assertTrue(scan.needsJavaFilter());

                SqlQuery.Result a = SqlQuery.querySql(conn, pushdown, 0, 100);
                SqlQuery.Result b = SqlQuery.querySql(conn, scan, 0, 100);
                assertEquals(b.total, a.total);
                assertEquals(ids(b), ids(a));
            }
        }
    }

    @Test
    void pagingIsAppliedInSql() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, false);
            SqlQuery.Result all = SqlQuery.querySql(conn, new SqlQueryFilter(), 0, 100);
            SqlQuery.Result second = SqlQuery.querySql(conn, new SqlQueryFilter(), 2, 3);

            // total は offset/limit に影響されない
            assertEquals(all.total, second.total);
            assertEquals(3, second.page.size());
            assertEquals(ids(all).subList(2, 5), ids(second));

            // 範囲外 offset ではページが空になる
            assertEquals(0, SqlQuery.querySql(conn, new SqlQueryFilter(), all.total, 100).page.size());
        }
    }

    private static java.util.List<SqlQueryFilter> pushdownFilters() {
        java.util.List<SqlQueryFilter> filters = new java.util.ArrayList<>();
        filters.add(new SqlQueryFilter());

        SqlQueryFilter byType = new SqlQueryFilter();
        byType.sqlTypes = SqlQueryFilter.parseSqlTypeFilter("SELECT,UPDATE");
        filters.add(byType);

        SqlQueryFilter byElapsed = new SqlQueryFilter();
        byElapsed.minElapsed = 1;
        filters.add(byElapsed);

        SqlQueryFilter byRows = new SqlQueryFilter();
        byRows.minRowCount = 0;
        byRows.maxRowCount = 10;
        filters.add(byRows);

        SqlQueryFilter incomplete = new SqlQueryFilter();
        incomplete.complete = Boolean.FALSE;
        filters.add(incomplete);

        SqlQueryFilter byTime = new SqlQueryFilter();
        byTime.sinceMillis = TimeUtil.parseUiDatetime("2026-06-15 00:19:11.000");
        byTime.untilMillis = TimeUtil.parseUiDatetime("2026-06-15 00:19:12.000");
        filters.add(byTime);
        return filters;
    }

    private static SqlQueryFilter copyOf(SqlQueryFilter f) {
        SqlQueryFilter c = new SqlQueryFilter();
        c.sqlTypes = f.sqlTypes;
        c.sinceMillis = f.sinceMillis;
        c.untilMillis = f.untilMillis;
        c.minElapsed = f.minElapsed;
        c.maxElapsed = f.maxElapsed;
        c.minRowCount = f.minRowCount;
        c.maxRowCount = f.maxRowCount;
        c.complete = f.complete;
        return c;
    }

    private static java.util.List<Long> ids(SqlQuery.Result r) {
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (SqlLogIndex.EntryRow e : r.page) {
            out.add(e.id);
        }
        return out;
    }

    private static SqlQuery.Result queryBySource(Connection conn, String pattern) throws Exception {
        SqlQueryFilter f = new SqlQueryFilter();
        f.sourceRe = SqlQueryFilter.compileRegex(pattern);
        return SqlQuery.querySql(conn, f, 0, 100);
    }

    private static void assumeTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }
}
