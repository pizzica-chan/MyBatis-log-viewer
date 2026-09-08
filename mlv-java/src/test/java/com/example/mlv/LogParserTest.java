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

        // 大文字でも一致する（regionMatches は大小文字を無視する）
        LogParser.ParsedLine upper = LogParser.parseLine(
                "2026-06-15 00:00:01.000[com.example.Boot][INFO][MAIN:worker-3] - started");
        assertNotNull(upper);
        assertEquals("MAIN:worker-3", upper.thread);
        assertEquals("com.example.Boot", upper.logger);

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
}
