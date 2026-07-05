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
}
