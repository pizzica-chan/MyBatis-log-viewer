package com.example.mlv;

import java.util.LinkedHashMap;
import java.util.Map;

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
        assertTrue(block.complete);
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
        assertTrue(block.complete);
    }

    @Test
    void incompleteBlockWithoutTotal() {
        LogParser.ParsedLine prep = line(
                "2026-06-15 00:19:16.100[exec-6][DEBUG][com.example.mapper.UserMapper.selectMissing] - ==>  Preparing: SELECT * FROM missing_table WHERE id = ?");
        MyBatisBlockParser.SqlBlock block = MyBatisBlockParser.startBlock(1, 30, 300, prep);
        assertFalse(block.complete);

        LogParser.ParsedLine params = line(
                "2026-06-15 00:19:16.101[exec-6][DEBUG][com.example.mapper.UserMapper.selectMissing] - ==> Parameters: 1(Long)");
        MyBatisBlockParser.mergeLine(block, params);
        assertEquals("1(Long)", block.parameters);
        assertFalse(block.complete);

        MyBatisBlockParser.finalizeBlock(block, 9999);
        assertNull(block.rowCount);
        assertFalse(block.complete);
        // Parameters 行までの 1ms は SQL の実行時間ではないので elapsed は持たせない
        assertNull(block.elapsedMs);
    }

    @Test
    void blockKeyDistinguishesThreadAndMapper() {
        assertEquals(
                MyBatisBlockParser.blockKey("exec-1", "com.example.mapper.A"),
                MyBatisBlockParser.blockKey("exec-1", "com.example.mapper.A"));
        assertNotEquals(
                MyBatisBlockParser.blockKey("exec-1", "com.example.mapper.A"),
                MyBatisBlockParser.blockKey("exec-2", "com.example.mapper.A"));
        assertNotEquals(
                MyBatisBlockParser.blockKey("exec-1", "com.example.mapper.A"),
                MyBatisBlockParser.blockKey("exec-1", "com.example.mapper.B"));
    }

    @Test
    void finalizeBlockIncompleteRespectsRawEndCap() {
        MyBatisBlockParser.SqlBlock block = MyBatisBlockParser.startBlock(1, 1, 100, line(
                "2026-06-15 00:19:16.100[exec-6][DEBUG][com.example.mapper.A] - ==>  Preparing: SELECT 1"));
        MyBatisBlockParser.noteRawLineEnd(block, 350);
        block.rawEndCap = 400;

        MyBatisBlockParser.finalizeBlock(block, 9999);
        assertEquals(350, block.endByteOffset);

        block.rawEndCap = 300;
        MyBatisBlockParser.finalizeBlock(block, 9999);
        assertEquals(300, block.endByteOffset);
    }

    @Test
    void finalizeBlockCompleteIgnoresRawEndCap() {
        MyBatisBlockParser.SqlBlock block = MyBatisBlockParser.startBlock(1, 1, 100, line(
                "2026-06-15 00:19:11.705[exec-1][DEBUG][com.example.mapper.A] - ==>  Preparing: SELECT 1"));
        MyBatisBlockParser.mergeLine(block, line(
                "2026-06-15 00:19:11.708[exec-1][DEBUG][com.example.mapper.A] - <==      Total: 1"));
        block.rawEndCap = 200;

        MyBatisBlockParser.finalizeBlock(block, 500);
        assertTrue(block.complete);
        assertEquals(500, block.endByteOffset);
    }

    @Test
    void capIncompleteBlocksAtNextPreparing() {
        MyBatisBlockParser.SqlBlock open = MyBatisBlockParser.startBlock(1, 1, 100, line(
                "2026-06-15 00:19:16.100[exec-6][DEBUG][com.example.mapper.A] - ==>  Preparing: SELECT 1"));
        MyBatisBlockParser.SqlBlock done = MyBatisBlockParser.startBlock(1, 2, 500, line(
                "2026-06-15 00:19:17.600[exec-7][DEBUG][com.example.mapper.B] - ==>  Preparing: SELECT 2"));
        MyBatisBlockParser.mergeLine(done, line(
                "2026-06-15 00:19:17.650[exec-7][DEBUG][com.example.mapper.B] - <==      Total: 1"));

        Map<String, MyBatisBlockParser.SqlBlock> pending = new LinkedHashMap<>();
        pending.put(MyBatisBlockParser.blockKey(open.thread, open.mapper), open);
        pending.put(MyBatisBlockParser.blockKey(done.thread, done.mapper), done);

        MyBatisBlockParser.capIncompleteBlocksAt(pending.values(), 800);

        assertEquals(800, open.rawEndCap);
        assertEquals(Long.MAX_VALUE, done.rawEndCap);
    }

    @Test
    void detectSqlType() {
        assertEquals("SELECT", MyBatisBlockParser.detectSqlType("SELECT id FROM t"));
        assertEquals("INSERT", MyBatisBlockParser.detectSqlType("INSERT INTO t VALUES (1)"));
        assertEquals("OTHER", MyBatisBlockParser.detectSqlType(""));
    }
}
