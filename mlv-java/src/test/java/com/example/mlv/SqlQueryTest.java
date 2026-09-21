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
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
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
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
            // 6 完了 + 1 未完了(selectMissing) + 2 末尾インターリーブ = 9
            assertEquals(9, SqlQuery.querySql(conn, new SqlQueryFilter(), 0, 100).total);
        }
    }

    @Test
    void filterByRowCount() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);

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
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
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
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);

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
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
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
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
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

    /**
     * grep のリテラル経路（バイト列照合）と正規表現経路が同じ結果を返すこと。
     * 大文字小文字・多バイト・3 文字未満・SQL 本文やパラメータ内の語で確かめる。
     */
    @Test
    void grepLiteralPathMatchesRegexPath() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
            String[] words = {"SELECT", "select", "users", "Parameters", "id", "ユーザー",
                "alice@example.com", "見つからない語"};
            for (String word : words) {
                SqlQueryFilter literal = new SqlQueryFilter();
                literal.grepRe = SqlQueryFilter.compileRegex(word);
                literal.grepText = word;
                SqlQueryFilter regex = new SqlQueryFilter();
                regex.grepRe = SqlQueryFilter.compileRegex(
                        "(?:" + java.util.regex.Pattern.quote(word) + ")");
                regex.grepText = null; // メタ文字を含むので正規表現経路になる

                SqlQuery.Result byLiteral = SqlQuery.querySql(conn, literal, 0, 100);
                SqlQuery.Result byRegex = SqlQuery.querySql(conn, regex, 0, 100);
                assertEquals(byRegex.total, byLiteral.total, word);
                assertEquals(byRegex.page.size(), byLiteral.page.size(), word);
                for (int i = 0; i < byRegex.page.size(); i++) {
                    assertEquals(byRegex.page.get(i).id, byLiteral.page.get(i).id, word);
                }
            }
        }
    }

    /** メタ文字を含む指定は、リテラル照合ではなく正規表現として扱うこと。 */
    @Test
    void grepWithMetaCharsUsesRegexPath() throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormat.DEFAULT);
            // . を任意の 1 文字として解釈しないと 0 件になる指定
            SqlQueryFilter f = new SqlQueryFilter();
            f.grepRe = SqlQueryFilter.compileRegex("SELEC.");
            f.grepText = "SELEC.";
            assertTrue(SqlQuery.querySql(conn, f, 0, 100).total > 0,
                    "メタ文字は正規表現として扱う");
        }
    }

    /** ASCII だけを畳むこと（多バイト文字を取り違えない）。 */
    @Test
    void containsBytesIgnoreAsciiCaseFoldsOnlyAscii() {
        String haystack = "ぢから ABC";
        byte[] hay = haystack.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String needle : new String[] {"abc", "ABC", "ぢ", "あ", "から"}) {
            boolean byRegex = java.util.regex.Pattern
                    .compile(java.util.regex.Pattern.quote(needle),
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(haystack).find();
            byte[] lower = SqlLogIndex.toLowerAscii(
                    needle.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(byRegex, SqlLogIndex.containsBytesIgnoreAsciiCase(hay, hay.length, lower),
                    needle);
        }
    }

    /** ログファイルを読めなくなったら、黙って結果を欠けさせずエラーにすること。 */
    @Test
    void readFailureIsReportedNotSilentlyIgnored(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path sample = sampleLog();
        assumeTrue(sample.toFile().exists(), "sample log not found");
        Path copy = tmp.resolve("app.log");
        java.nio.file.Files.copy(sample, copy);

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(PathUtil.resolve(copy)), null,
                    LogFormat.DEFAULT);
            java.nio.file.Files.delete(copy);
            SqlQueryFilter f = new SqlQueryFilter();
            f.grepRe = SqlQueryFilter.compileRegex("SELECT");
            f.grepText = "SELECT";
            assertThrows(java.sql.SQLException.class, () -> SqlQuery.querySql(conn, f, 0, 100));
        }
    }

    /**
     * 前方向にまとめ読みするリーダが、都度読みと同じ内容を返すこと。
     * 戻る要求、窓（64 KiB）に収まらない大きさ、ファイル末尾を確かめる。
     */
    @Test
    void sequentialRawReaderMatchesDirectReads(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 251);
        }
        Path file = tmp.resolve("raw.bin");
        java.nio.file.Files.write(file, data);

        try (SqlLogIndex.SequentialRawReader reader =
                new SqlLogIndex.SequentialRawReader(file.toString())) {
            byte[] buf = new byte[300_000];
            for (long offset = 0; offset < 150_000; offset += 1000) {
                int n = reader.read(offset, 500, buf);
                assertEquals(500, n);
                assertArrayEquals(java.util.Arrays.copyOfRange(data, (int) offset, (int) offset + 500),
                        java.util.Arrays.copyOf(buf, n));
            }
            int n = reader.read(10, 100, buf); // 戻る（窓の外）
            assertEquals(100, n);
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 10, 110),
                    java.util.Arrays.copyOf(buf, n));
            n = reader.read(1000, 150_000, buf); // 窓に収まらない大きさ
            assertEquals(150_000, n);
            assertArrayEquals(java.util.Arrays.copyOfRange(data, 1000, 151_000),
                    java.util.Arrays.copyOf(buf, n));
            n = reader.read(data.length - 10, 100, buf); // 末尾は読めたぶんだけ
            assertEquals(10, n);
            assertArrayEquals(java.util.Arrays.copyOfRange(data, data.length - 10, data.length),
                    java.util.Arrays.copyOf(buf, n));
        }
    }
}
