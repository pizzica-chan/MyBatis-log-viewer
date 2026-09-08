package com.example.mlv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogParserTest {

    @Test
    void tomcatFormat() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[http-nio-8080-exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - ==>  Preparing: SELECT 1");
        assertNotNull(p);
        assertEquals("DEBUG", p.level);
        assertEquals("com.example.mapper.UserMapper.selectById", p.logger);
        assertEquals("http-nio-8080-exec-1", p.thread);
        assertTrue(p.message.contains("Preparing:"));
    }

    @Test
    void ajpThreadWithIpAddressIsNotMapper() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[ajp-nio-127.0.0.1-8009-exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - ==>  Preparing: SELECT 1");
        assertNotNull(p);
        assertEquals("com.example.mapper.UserMapper.selectById", p.logger);
        assertEquals("ajp-nio-127.0.0.1-8009-exec-1", p.thread);
    }

    @Test
    void ajpThreadWithIpAddressInThirdField() {
        LogParser.ParsedLine p = LogParser.parseLine(
                "2026-06-15 00:19:11.705[com.example.mapper.UserMapper.selectById][DEBUG][ajp-nio-127.0.0.1-8009-exec-1] - ==>  Preparing: SELECT 1");
        assertNotNull(p);
        assertEquals("com.example.mapper.UserMapper.selectById", p.logger);
        assertEquals("ajp-nio-127.0.0.1-8009-exec-1", p.thread);
    }

    /**
     * スレッド判定の事前ふるいが、正規表現をそのまま呼んだ場合と同じ結果になること。
     *
     * <p>{@code THREAD_HINT} は {@code ^main} 以外のすべての選択肢が {@code '-'} を含むため、
     * {@code '-'} が無く "main" でも始まらない文字列は正規表現を呼ばずに不一致と判定している。
     * その境界（main の大小文字、4 文字未満、ハイフンはあるが一致しない、
     * ハイフンが無い FQCN）で判定が変わらないことを確認する。
     */
    @Test
    void threadHintPrefilterMatchesRegex() {
        // "main" は '-' を含まないが一致する（事前ふるいで落としてはいけない）
        LogParser.ParsedLine main = LogParser.parseLine(
                "2026-06-15 00:00:01.000[main][INFO][com.example.Boot] - started");
        assertNotNull(main);
        assertEquals("main", main.thread);
        assertEquals("com.example.Boot", main.logger);

        // '-' を含むので事前ふるいは indexOf('-') で通過する。ここで確認しているのは
        // regionMatches ではなく THREAD_HINT 側の CASE_INSENSITIVE
        LogParser.ParsedLine upper = LogParser.parseLine(
                "2026-06-15 00:00:01.000[com.example.Boot][INFO][MAIN:worker-3] - started");
        assertNotNull(upper);
        assertEquals("MAIN:worker-3", upper.thread);
        assertEquals("com.example.Boot", upper.logger);

        // '-' が無く大文字で始まるスレッド名。事前ふるいの regionMatches(true, ...) を
        // 通る唯一の経路で、大小文字を無視しないと thread と logger が入れ替わる
        LogParser.ParsedLine upperNoHyphen = LogParser.parseLine(
                "2026-06-15 00:00:01.000[Boot][INFO][MAIN] - x");
        assertNotNull(upperNoHyphen);
        assertEquals("MAIN", upperNoHyphen.thread);
        assertEquals("Boot", upperNoHyphen.logger);

        // "main" で始まるが ^main(?:$|:) には一致しない。事前ふるいは通過し、
        // 正規表現が false を返す（ふるいは必要条件なので通す側に緩くてよい）
        LogParser.ParsedLine mainish = LogParser.parseLine(
                "2026-06-15 00:00:01.000[mainThread][INFO][com.example.Svc] - ok");
        assertNotNull(mainish);
        assertEquals("mainThread", mainish.thread);
        assertEquals("com.example.Svc", mainish.logger);

        // '-' を含むが THREAD_HINT には一致しない。事前ふるいを通過し正規表現が false を返す
        // ため、'.' の有無による判定に落ちて FQCN 側が logger になる
        LogParser.ParsedLine hyphen = LogParser.parseLine(
                "2026-06-15 00:00:01.000[worker-alpha][INFO][com.example.Svc] - ok");
        assertNotNull(hyphen);
        assertEquals("worker-alpha", hyphen.thread);
        assertEquals("com.example.Svc", hyphen.logger);

        // "main" より短い文字列でも例外にならず、不一致として扱われる
        LogParser.ParsedLine shortName = LogParser.parseLine(
                "2026-06-15 00:00:01.000[ma][INFO][com.example.Svc] - ok");
        assertNotNull(shortName);
        assertEquals("ma", shortName.thread);
        assertEquals("com.example.Svc", shortName.logger);

        // '-' を含まない FQCN 同士。どちらも THREAD_HINT に一致せず既定の並びになる
        LogParser.ParsedLine neither = LogParser.parseLine(
                "2026-06-15 00:00:01.000[alpha][INFO][beta] - ok");
        assertNotNull(neither);
        assertEquals("alpha", neither.thread);
        assertEquals("beta", neither.logger);
    }

    /**
     * 事前ふるいが {@code THREAD_HINT} と常に同じ判定を返すこと（差分試験）。
     *
     * <p>固定の期待値ではなく正規表現そのものと突き合わせるので、ふるい側のロジックを
     * 変えると落ちる。ただし乱数入力は文字集合の中からしか作られないため、
     * 正規表現側に新しい選択肢が足されたことは検出できない。そちらは
     * {@link #threadHintAlternativesAllContainHyphen} で前提そのものを固定している。
     */
    @Test
    void threadHintPrefilterEqualsRegex() {
        String[] realistic = {
            "main", "MAIN", "Main:7", "main:12345", "mainThread", "ma", "", "-",
            "http-nio-8080-exec-1", "https-nio-8443-exec-2", "ajp-nio-8009-exec-24",
            "pool-1-thread-1", "scheduler-3", "catalina-exec-9", "worker-alpha",
            "com.example.mapper.UserMapper.selectById", "com.example.web.HogeController",
            "org.springframework.jdbc.core.JdbcTemplate", "Boot", "alpha", "beta",
            "task-exec-7", "my-logger.Class", "exec-1", "EXEC-1", "AJP-nio-8009",
        };
        for (String s : realistic) {
            assertEquals(LogParser.THREAD_HINT.matcher(s).find(), LogParser.looksLikeThread(s), s);
        }

        // ふるいの分岐（'-' の有無、main 始まりの大小文字、長さ 4 未満）を踏むよう
        // 文字集合を絞ったランダム入力で突き合わせる
        char[] alphabet = "mainMAIN-_.:$0123456789xyzXYZ ".toCharArray();
        java.util.Random rnd = new java.util.Random(20260909L);
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 200_000; i++) {
            sb.setLength(0);
            int len = rnd.nextInt(14);
            for (int j = 0; j < len; j++) {
                sb.append(alphabet[rnd.nextInt(alphabet.length)]);
            }
            String s = sb.toString();
            assertEquals(LogParser.THREAD_HINT.matcher(s).find(), LogParser.looksLikeThread(s), s);
        }
    }

    /**
     * 事前ふるいの前提 —— {@code ^main(?:$|:)} 以外の選択肢がすべて {@code '-'} を含むこと。
     *
     * <p>{@link LogParser#looksLikeThread} は「{@code '-'} が無く "main" でも始まらない
     * 文字列は一致しえない」という性質に依存している。{@code worker\d+} のように
     * ハイフンを含まない選択肢が足されると、ふるいが黙って誤判定するようになる。
     *
     * <p>差分試験は乱数の文字集合の外にある単語を作れないためこれを検出できない。
     * ここではパターン文字列そのものを見て前提を固定する。
     */
    @Test
    void threadHintAlternativesAllContainHyphen() {
        String pattern = LogParser.THREAD_HINT.pattern();
        assertTrue(pattern.startsWith("(?:") && pattern.endsWith(")"),
                "パターンの入れ物が変わった。ふるいの前提を見直すこと: " + pattern);
        String body = pattern.substring(3, pattern.length() - 1);

        String mainAlt = "^main(?:$|:)|";
        assertTrue(body.startsWith(mainAlt),
                "先頭の選択肢が変わった。ふるいの main 判定を見直すこと: " + body);

        for (String alt : body.substring(mainAlt.length()).split("\\|")) {
            assertTrue(alt.indexOf('-') >= 0,
                    "ハイフンを含まない選択肢が追加されている。looksLikeThread の"
                            + "事前ふるいが誤判定するので、ふるい側も直すこと: " + alt);
        }
    }
}
