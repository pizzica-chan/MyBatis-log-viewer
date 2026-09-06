package com.example.mlv;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.mlv.SqlStats.MapperStat;
import com.example.mlv.SqlStats.SlowSql;
import com.example.mlv.SqlStats.Summary;

import static org.junit.jupiter.api.Assertions.*;

class SqlStatsTest {

    @Test
    void summaryAndMappers() throws Exception {
        Path sample = Paths.get("..", "samples", "mybatis-sample.log").toAbsolutePath().normalize();
        if (!sample.toFile().exists()) {
            sample = Paths.get("samples", "mybatis-sample.log").toAbsolutePath().normalize();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(sample.toFile().exists());

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null);
            Summary s = SqlStats.summary(conn);
            assertTrue(s.total >= 6);
            assertFalse(s.bySqlType.isEmpty());

            List<MapperStat> mappers = SqlStats.searchMappers(conn, "Mapper");
            assertFalse(mappers.isEmpty());
            assertTrue(mappers.get(0).count >= 1);

            assertTrue(SqlStats.searchMappers(conn, " ").isEmpty());
            assertTrue(SqlStats.searchMappers(conn, "___no_such_mapper___").isEmpty());

            List<SlowSql> slow = SqlStats.searchSlowSql(conn, "Mapper", 20);
            for (SlowSql row : slow) {
                assertTrue(row.mapper.toLowerCase().contains("mapper"));
            }
            assertTrue(SqlStats.searchSlowSql(conn, "___no_such_mapper___", 20).isEmpty());
        }
    }
}
