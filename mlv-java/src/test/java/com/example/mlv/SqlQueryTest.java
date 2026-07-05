package com.example.mlv;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryTest {

    @Test
    void filterByMapper() throws Exception {
        Path sample = Paths.get("..", "samples", "mybatis-sample.log").toAbsolutePath().normalize();
        if (!sample.toFile().exists()) {
            sample = Paths.get("samples", "mybatis-sample.log").toAbsolutePath().normalize();
        }
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

    private static void assumeTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }
}
