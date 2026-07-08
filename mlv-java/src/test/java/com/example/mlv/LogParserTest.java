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
}
