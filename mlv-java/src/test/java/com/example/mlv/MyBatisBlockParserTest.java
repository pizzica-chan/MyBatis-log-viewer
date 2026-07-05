package com.example.mlv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MyBatisBlockParserTest {

    private static LogParser.ParsedLine line(String text) {
        LogParser.ParsedLine p = LogParser.parseLine(text);
        assertNotNull(p, text);
        return p;
    }

    @Test
    void selectBlock() {
        LogParser.ParsedLine prep = line(
                "2026-06-15 00:19:11.705[exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - ==>  Preparing: SELECT * FROM users WHERE id = ?");
        assertTrue(MyBatisBlockParser.isPreparingLine(prep));
        MyBatisBlockParser.SqlBlock block = MyBatisBlockParser.startBlock(1, 10, 100, prep);
        assertEquals("SELECT", block.sqlType);
        assertEquals("SELECT * FROM users WHERE id = ?", block.sqlText);

        LogParser.ParsedLine params = line(
                "2026-06-15 00:19:11.706[exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - ==> Parameters: 1(Long)");
        assertTrue(MyBatisBlockParser.isBlockContinuation(params, block.mapper, block.thread));
        MyBatisBlockParser.mergeLine(block, params);
        assertEquals("1(Long)", block.parameters);

        LogParser.ParsedLine total = line(
                "2026-06-15 00:19:11.708[exec-1][DEBUG][com.example.mapper.UserMapper.selectById] - <==      Total: 1");
        MyBatisBlockParser.mergeLine(block, total);
        assertTrue(MyBatisBlockParser.isBlockEnd(total));
        MyBatisBlockParser.finalizeBlock(block, 500);
        assertEquals(Integer.valueOf(1), block.rowCount);
        assertEquals(Integer.valueOf(3), block.elapsedMs);
    }

    @Test
    void updatesBlock() {
        LogParser.ParsedLine prep = line(
                "2026-06-15 00:19:13.200[exec-3][DEBUG][com.example.mapper.UserMapper.insert] - ==>  Preparing: INSERT INTO users (name) VALUES (?)");
        MyBatisBlockParser.SqlBlock block = MyBatisBlockParser.startBlock(1, 20, 200, prep);
        assertEquals("INSERT", block.sqlType);

        LogParser.ParsedLine updates = line(
                "2026-06-15 00:19:13.210[exec-3][DEBUG][com.example.mapper.UserMapper.insert] - <==    Updates: 1");
        MyBatisBlockParser.mergeLine(block, updates);
        MyBatisBlockParser.finalizeBlock(block, 400);
        assertEquals(Integer.valueOf(1), block.rowCount);
        assertEquals(Integer.valueOf(10), block.elapsedMs);
    }

    @Test
    void detectSqlType() {
        assertEquals("SELECT", MyBatisBlockParser.detectSqlType("SELECT id FROM t"));
        assertEquals("INSERT", MyBatisBlockParser.detectSqlType("INSERT INTO t VALUES (1)"));
        assertEquals("OTHER", MyBatisBlockParser.detectSqlType(""));
    }
}
