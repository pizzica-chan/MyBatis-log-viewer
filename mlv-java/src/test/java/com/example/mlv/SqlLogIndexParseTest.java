package com.example.mlv;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ログ解析の回帰テスト。
 * <ul>
 *   <li>Total/Updates 未到達の未完了ブロック（SQL 失敗など）</li>
 *   <li>Preparing〜Total の間に別スレッドのログが挟まるケース</li>
 *   <li>未完了ブロック詳細 raw が後続 SQL ブロックまで含まないこと</li>
 * </ul>
 */
class SqlLogIndexParseTest {

    private static Path sampleLog() {
        Path sample = Paths.get("..", "samples", "mybatis-sample.log").toAbsolutePath().normalize();
        if (!sample.toFile().exists()) {
            sample = Paths.get("samples", "mybatis-sample.log").toAbsolutePath().normalize();
        }
        return sample;
    }

    private static void assumeSampleExists(Path sample) {
        org.junit.jupiter.api.Assumptions.assumeTrue(sample.toFile().exists(), "sample log not found");
    }

    private static void indexLog(Connection conn, Path log) throws Exception {
        SqlLogIndex.buildIndex(conn, Collections.singletonList(log), null, LogFormatSpec.DEFAULT);
    }

    private static SqlLogIndex.EntryRow findByMapper(Connection conn, String mapperPattern) throws Exception {
        SqlQueryFilter f = new SqlQueryFilter();
        f.mapperRe = SqlQueryFilter.compileRegex(mapperPattern);
        return SqlQuery.querySql(conn, f, 0, 1).page.get(0);
    }

    /**
     * 既定以外の書式でも、MyBatis の SQL ブロックを同じように索引化できること。
     *
     * <p>書式が変わるのは SQL ブロックを囲むアプリログ側の前置きだけで、ブロックの
     * 組み立て（Preparing / Parameters / Total）は書式に依らないことを担保する。
     */
    @Test
    void indexesSqlBlocksWithNonDefaultFormat(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("spring.log");
        // Spring Boot 既定レイアウトの前置きで同じ SQL ブロックを書く
        Files.write(log, java.util.Arrays.asList(
                "2026-06-15 08:15:01.705 DEBUG 12345 --- [nio-8080-exec-1] "
                        + "com.example.mapper.UserMapper.selectById     "
                        + ": ==>  Preparing: SELECT id FROM users WHERE id = ?",
                "2026-06-15 08:15:01.706 DEBUG 12345 --- [nio-8080-exec-1] "
                        + "com.example.mapper.UserMapper.selectById     "
                        + ": ==> Parameters: 1(Long)",
                "2026-06-15 08:15:01.708 DEBUG 12345 --- [nio-8080-exec-1] "
                        + "com.example.mapper.UserMapper.selectById     "
                        + ": <==      Total: 1"),
                java.nio.charset.StandardCharsets.UTF_8);
        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.BuildResult r = SqlLogIndex.buildIndex(
                    conn, Collections.singletonList(log), null, LogFormatSpec.of(LogFormat.SPRING_BOOT));
            assertEquals(1, r.entryCount, "SQL ブロック 1 件として取り込めること");
            SqlLogIndex.EntryRow row = findByMapper(conn, "UserMapper");
            assertEquals("com.example.mapper.UserMapper.selectById", row.mapper);
            assertEquals("nio-8080-exec-1", row.thread);
            assertEquals(Integer.valueOf(1), row.rowCount, "Total が拾えていること");
        }
    }

    /**
     * 利用者定義の書式でも、SQL ブロックを同じように索引化できること。
     *
     * <p>組み込み書式のどれにも一致しない前置き（ここでは縦棒区切り）を正規表現で定義し、
     * Preparing / Parameters / Total が 1 件のブロックとして組み上がることを見る。
     * <strong>この経路が通らないと、行はすべて読めているのに SQL が 0 件になる</strong>
     * ―― 画面上は「取り込めているのに何も出ない」という、いちばん分かりにくい壊れ方をする。
     *
     * <p>継続行（スタックトレース）が混ざっても件数が増えないことも同時に確かめる。
     * 利用者定義の書式にはバイト列だけのヘッダ判定が無く、一致しない行を継続行として
     * 扱う作りにしているため、ここが崩れると件数がずれる。
     */
    @Test
    void indexesSqlBlocksWithCustomFormat(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("pipe.log");
        Files.write(log, Arrays.asList(
                "2026-06-15 00:19:11.705|DEBUG|exec-1|com.example.mapper.UserMapper.selectById"
                        + "|==>  Preparing: SELECT id FROM users WHERE id = ?",
                "2026-06-15 00:19:11.706|DEBUG|exec-1|com.example.mapper.UserMapper.selectById"
                        + "|==> Parameters: 1(Long)",
                "2026-06-15 00:19:11.708|DEBUG|exec-1|com.example.mapper.UserMapper.selectById"
                        + "|<==      Total: 1",
                "\tat com.example.Hoge.run(Hoge.java:12)"),
                StandardCharsets.UTF_8);
        CustomLogFormat custom = LogFormatStore.create("pipe", "縦棒区切り",
                "^(?<ts>[^|]+)\\|(?<level>[^|]*)\\|(?<thread>[^|]*)\\|(?<logger>[^|]*)"
                        + "\\|(?<message>.*)$",
                "yyyy-MM-dd HH:mm:ss.SSS");
        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.BuildResult r = SqlLogIndex.buildIndex(
                    conn, Collections.singletonList(log), null, LogFormatSpec.of(custom));
            assertEquals(1, r.entryCount, "SQL ブロック 1 件として取り込めること");
            SqlLogIndex.EntryRow row = findByMapper(conn, "UserMapper");
            assertEquals("com.example.mapper.UserMapper.selectById", row.mapper);
            assertEquals("exec-1", row.thread);
            assertEquals("SELECT id FROM users WHERE id = ?", row.sqlText);
            assertEquals("1(Long)", row.parameters);
            assertEquals(Integer.valueOf(1), row.rowCount, "Total が拾えていること");
        }
    }

    /**
     * 利用者定義の書式で<strong>日時だけ読めない行</strong>を、継続行にせず読み飛ばしとして
     * 数えること。
     *
     * <p>正規表現に一致したのに日時を読めない行は、直すべき書式がある行だ。継続行
     * （スタックトレース）と同じ扱いにすると、読み飛ばし件数は 0 のまま、その行は
     * どこにも出ない。ここでは <code>Parameters</code> の行だけ日時が壊れているので、
     * <strong>SQL はできるのにパラメータだけ空</strong>という、いちばん気づきにくい
     * 壊れ方になる。件数が出れば利用者は日時書式を疑える。
     */
    @Test
    void countsLinesWhoseTimestampIsUnreadable(@TempDir Path tmp) throws Exception {
        Path log = tmp.resolve("pipe.log");
        Files.write(log, Arrays.asList(
                "2026-06-15 00:19:11.705|DEBUG|exec-1|com.example.mapper.UserMapper.selectById"
                        + "|==>  Preparing: SELECT id FROM users WHERE id = ?",
                // 日時だけ壊れている（月が 13）。正規表現には一致する
                "2026-13-15 00:19:11.706|DEBUG|exec-1|com.example.mapper.UserMapper.selectById"
                        + "|==> Parameters: 1(Long)",
                "2026-06-15 00:19:11.708|DEBUG|exec-1|com.example.mapper.UserMapper.selectById"
                        + "|<==      Total: 1",
                // 正規表現にも一致しない行は、今までどおり継続行として扱う
                "\tat com.example.Hoge.run(Hoge.java:12)"),
                StandardCharsets.UTF_8);
        CustomLogFormat custom = LogFormatStore.create("pipe", "縦棒区切り",
                "^(?<ts>[^|]+)\\|(?<level>[^|]*)\\|(?<thread>[^|]*)\\|(?<logger>[^|]*)"
                        + "\\|(?<message>.*)$",
                "yyyy-MM-dd HH:mm:ss.SSS");
        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.BuildResult r = SqlLogIndex.buildIndex(
                    conn, Collections.singletonList(log), null, LogFormatSpec.of(custom));
            assertEquals(1, r.skippedLines,
                    "日時を読めなかった 1 行だけを数える（継続行は数えない）");
            assertEquals(1, r.entryCount);
            SqlLogIndex.EntryRow row = findByMapper(conn, "UserMapper");
            assertNull(row.parameters, "Parameters の行は取り込まれていない");
        }
    }

    // --- 指摘1: Preparing のみで Total が来ない（SQL 失敗） ---

    @Test
    void incompleteBlockWhenTotalNeverArrives(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("sql-failure.log");
        Files.write(log, Arrays.asList(
                "2026-06-15 00:19:16.100[exec-6][DEBUG][com.example.mapper.UserMapper.selectMissing] - ==>  Preparing: SELECT * FROM missing_table WHERE id = ?",
                "2026-06-15 00:19:16.101[exec-6][DEBUG][com.example.mapper.UserMapper.selectMissing] - ==> Parameters: 1(Long)",
                "2026-06-15 00:19:16.150[exec-6][ERROR][com.example.mapper.UserMapper.selectMissing] - org.springframework.jdbc.BadSqlGrammarException: bad SQL grammar"
        ), StandardCharsets.UTF_8);

        try (Connection conn = SqlLogIndex.openMemory()) {
            indexLog(conn, log);
            SqlLogIndex.EntryRow row = findByMapper(conn, "selectMissing");
            assertFalse(row.complete);
            assertNull(row.rowCount);
            assertEquals("SELECT * FROM missing_table WHERE id = ?", row.sqlText);
            assertEquals("1(Long)", row.parameters);
        }
    }

    @Test
    void incompleteBlockFilterableViaCompleteFlag() throws Exception {
        Path sample = sampleLog();
        assumeSampleExists(sample);

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormatSpec.DEFAULT);

            SqlQueryFilter incomplete = new SqlQueryFilter();
            incomplete.complete = Boolean.FALSE;
            SqlQuery.Result onlyIncomplete = SqlQuery.querySql(conn, incomplete, 0, 100);
            assertEquals(1, onlyIncomplete.total);
            assertEquals("selectMissing", onlyIncomplete.page.get(0).mapper.substring(
                    onlyIncomplete.page.get(0).mapper.lastIndexOf('.') + 1));

            SqlQueryFilter complete = new SqlQueryFilter();
            complete.complete = Boolean.TRUE;
            SqlQuery.Result all = SqlQuery.querySql(conn, new SqlQueryFilter(), 0, 100);
            SqlQuery.Result onlyComplete = SqlQuery.querySql(conn, complete, 0, 100);
            assertEquals(all.total - 1, onlyComplete.total);
            assertTrue(onlyComplete.page.stream().allMatch(e -> e.complete));
        }
    }

    // --- 指摘2: Preparing〜Total の間に別スレッドのログが挟まる ---

    @Test
    void interleavedThreadsBothBlocksComplete(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("interleaved.log");
        Files.write(log, Arrays.asList(
                "2026-06-15 00:20:00.000[exec-1][DEBUG][com.example.mapper.A.query] - ==>  Preparing: SELECT 1",
                "2026-06-15 00:20:00.010[exec-2][DEBUG][com.example.mapper.B.query] - ==>  Preparing: SELECT 2",
                "2026-06-15 00:20:00.011[exec-2][DEBUG][com.example.mapper.B.query] - ==> Parameters: 2(Long)",
                "2026-06-15 00:20:00.015[exec-2][DEBUG][com.example.mapper.B.query] - <==      Total: 2",
                "2026-06-15 00:20:00.012[exec-1][DEBUG][com.example.mapper.A.query] - ==> Parameters: 1(Long)",
                "2026-06-15 00:20:00.020[exec-1][DEBUG][com.example.mapper.A.query] - <==      Total: 1"
        ), StandardCharsets.UTF_8);

        try (Connection conn = SqlLogIndex.openMemory()) {
            indexLog(conn, log);
            assertEquals(2, SqlQuery.querySql(conn, new SqlQueryFilter(), 0, 100).total);

            SqlLogIndex.EntryRow a = findByMapper(conn, "mapper\\.A");
            assertTrue(a.complete);
            assertEquals(Integer.valueOf(1), a.rowCount);

            SqlLogIndex.EntryRow b = findByMapper(conn, "mapper\\.B");
            assertTrue(b.complete);
            assertEquals(Integer.valueOf(2), b.rowCount);
        }
    }

    @Test
    void interleavedOtherThreadSqlDoesNotOrphanTotal(@TempDir Path dir) throws Exception {
        // 単一 pending 実装だと exec-1 の Total が orphan になる回帰ケース
        Path log = dir.resolve("interleaved-orphan-regression.log");
        Files.write(log, lines(
                "2026-06-15 00:20:00.000[exec-1][DEBUG][com.example.mapper.A.query] - ==>  Preparing: SELECT 1",
                "2026-06-15 00:20:00.005[exec-2][DEBUG][com.example.mapper.B.query] - ==>  Preparing: SELECT 2",
                "2026-06-15 00:20:00.006[exec-2][DEBUG][com.example.mapper.B.query] - <==      Total: 1",
                "2026-06-15 00:20:00.010[exec-1][DEBUG][com.example.mapper.A.query] - <==      Total: 1"
        ), StandardCharsets.UTF_8);

        try (Connection conn = SqlLogIndex.openMemory()) {
            indexLog(conn, log);
            assertEquals(2, SqlQuery.querySql(conn, new SqlQueryFilter(), 0, 100).total);
            SqlLogIndex.EntryRow a = findByMapper(conn, "mapper\\.A");
            assertTrue(a.complete);
            assertEquals(Integer.valueOf(1), a.rowCount);
        }
    }

    @Test
    void interleavedDoesNotFlushIncompleteBlockOnOtherThreadActivity(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("interleaved-incomplete.log");
        Files.write(log, lines(
                "2026-06-15 00:21:00.000[exec-1][DEBUG][com.example.mapper.A.query] - ==>  Preparing: SELECT bad",
                "2026-06-15 00:21:00.005[exec-1][DEBUG][com.example.mapper.A.query] - ==> Parameters: 1(Long)",
                "2026-06-15 00:21:00.010[exec-2][INFO][com.example.web.Other] - unrelated log",
                "2026-06-15 00:21:00.015[exec-1][ERROR][com.example.mapper.A.query] - SQL failed"
        ), StandardCharsets.UTF_8);

        try (Connection conn = SqlLogIndex.openMemory()) {
            indexLog(conn, log);
            SqlLogIndex.EntryRow row = findByMapper(conn, "mapper\\.A");
            assertFalse(row.complete);
            assertNull(row.rowCount);

            String raw = SqlLogIndex.readEntryRaw(log, row.byteOffset, row.endByteOffset);
            assertTrue(raw.contains("Preparing: SELECT bad"));
            assertTrue(raw.contains("SQL failed"));
            // ファイル上 Preparing と ERROR の間に物理的に挟まるため、別スレッド行も byte 範囲に含まれる
            assertTrue(raw.contains("unrelated log"));
        }
    }

    @Test
    void sampleLogInterleavedAtEndCompletesBothBlocks() throws Exception {
        Path sample = sampleLog();
        assumeSampleExists(sample);

        try (Connection conn = SqlLogIndex.openMemory()) {
            SqlLogIndex.buildIndex(conn, Collections.singletonList(sample), null, LogFormatSpec.DEFAULT);

            SqlQueryFilter tailA = new SqlQueryFilter();
            tailA.mapperRe = SqlQueryFilter.compileRegex("selectById");
            tailA.grepRe = SqlQueryFilter.compileRegex("SELECT id FROM users");
            SqlLogIndex.EntryRow a = SqlQuery.querySql(conn, tailA, 0, 1).page.get(0);
            assertTrue(a.complete);
            assertEquals(Integer.valueOf(1), a.rowCount);

            SqlQueryFilter tailB = new SqlQueryFilter();
            tailB.mapperRe = SqlQueryFilter.compileRegex("selectByUserId");
            tailB.grepRe = SqlQueryFilter.compileRegex("Parameters: 2");
            SqlLogIndex.EntryRow b = SqlQuery.querySql(conn, tailB, 0, 1).page.get(0);
            assertTrue(b.complete);
            assertEquals(Integer.valueOf(1), b.rowCount);
        }
    }

    // --- 指摘3: 未完了詳細 raw が後続 SQL まで含まない ---

    @Test
    void incompleteRawStopsBeforeNextPreparing_exactUserScenario(@TempDir Path dir) throws Exception {
        // ユーザー報告: selectMissing 詳細に ReportMapper 以降の無関係ログが表示されていた
        Path log = dir.resolve("incomplete-raw-boundary.log");
        Files.write(log, lines(
                "2026-06-15 00:19:16.100[exec-6][DEBUG][com.example.mapper.UserMapper.selectMissing] - ==>  Preparing: SELECT * FROM missing_table WHERE id = ?",
                "2026-06-15 00:19:16.101[exec-6][DEBUG][com.example.mapper.UserMapper.selectMissing] - ==> Parameters: 1(Long)",
                "2026-06-15 00:19:16.150[exec-6][ERROR][com.example.mapper.UserMapper.selectMissing] - org.springframework.jdbc.BadSqlGrammarException: bad SQL grammar [SELECT * FROM missing_table WHERE id = ?]",
                "2026-06-15 00:19:16.500[exec-6][ERROR][com.example.web.UserController] - 処理失敗",
                "java.lang.RuntimeException: boom",
                "	at com.example.web.UserController.process(UserController.java:42)",
                "2026-06-15 00:19:17.600[exec-7][DEBUG][com.example.mapper.ReportMapper.heavyQuery] - ==>  Preparing: SELECT u.id FROM users u",
                "2026-06-15 00:19:17.650[exec-7][DEBUG][com.example.mapper.ReportMapper.heavyQuery] - <==      Total: 150",
                "2026-06-15 00:19:18.000[exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - ==>  Preparing: SELECT id FROM users WHERE id = ?",
                "2026-06-15 00:19:18.010[exec-2][DEBUG][com.example.mapper.OrderMapper.selectByUserId] - ==>  Preparing: SELECT * FROM orders WHERE user_id = ?",
                "2026-06-15 00:19:18.015[exec-2][DEBUG][com.example.mapper.OrderMapper.selectByUserId] - <==      Total: 1",
                "2026-06-15 00:19:18.020[exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - <==      Total: 1"
        ), StandardCharsets.UTF_8);

        try (Connection conn = SqlLogIndex.openMemory()) {
            indexLog(conn, log);
            SqlLogIndex.EntryRow row = findByMapper(conn, "selectMissing");
            assertFalse(row.complete);

            String raw = SqlLogIndex.readEntryRaw(log, row.byteOffset, row.endByteOffset);
            assertTrue(raw.contains("Preparing: SELECT * FROM missing_table"));
            assertTrue(raw.contains("BadSqlGrammarException"));
            assertTrue(raw.contains("処理失敗"));
            assertTrue(raw.contains("RuntimeException: boom"));
            assertTrue(raw.contains("UserController.process"));

            assertFalse(raw.contains("ReportMapper.heavyQuery"));
            assertFalse(raw.contains("16:45:18.000"));
            assertFalse(raw.contains("OrderMapper.selectByUserId"));
            assertFalse(raw.contains("Total: 150"));
        }
    }

    @Test
    void incompleteRawStopsBeforeNextPreparing_onSampleLog() throws Exception {
        Path sample = sampleLog();
        assumeSampleExists(sample);

        try (Connection conn = SqlLogIndex.openMemory()) {
            indexLog(conn, sample);
            SqlLogIndex.EntryRow row = findByMapper(conn, "selectMissing");
            assertFalse(row.complete);

            String raw = SqlLogIndex.readEntryRaw(sample, row.byteOffset, row.endByteOffset);
            assertTrue(raw.contains("Preparing: SELECT * FROM missing_table"));
            assertTrue(raw.contains("BadSqlGrammarException"));
            assertTrue(raw.contains("処理失敗"));
            assertFalse(raw.contains("ReportMapper.heavyQuery"));
            assertFalse(raw.contains("16:45:18.000"));
        }
    }

    private static List<String> lines(String... lines) {
        return Arrays.asList(lines);
    }
}
