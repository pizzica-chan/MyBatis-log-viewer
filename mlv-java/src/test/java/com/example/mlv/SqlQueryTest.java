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

    private static void assumeTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }
}
