package com.example.mlv;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis 3 標準 DEBUG ログ（Preparing / Parameters / Total / Updates）を
 * 1 SQL 実行ブロックとして解析する。
 */
public final class MyBatisBlockParser {

    private static final Pattern PREPARING = Pattern.compile("(?:==>\\s*)?Preparing:\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAMETERS = Pattern.compile("(?:==>\\s*)?Parameters:\\s*(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL = Pattern.compile("<==\\s*Total:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATES = Pattern.compile("<==\\s*Updates:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private MyBatisBlockParser() {
    }

    /** 解析済み MyBatis SQL ブロック。 */
    public static final class SqlBlock {
        public long fileId;
        public long lineNo;
        public long byteOffset;
        public long endByteOffset;
        public long tsMillis;
        public long tsEndMillis;
        public String mapper;
        public String level;
        public String thread;
        public String sqlText;
        public String parameters;
        public Integer rowCount;
        public String sqlType;
        public Integer elapsedMs;
        public StringBuilder bodyBuf;
    }

    public static boolean isPreparingLine(LogParser.ParsedLine line) {
        return line != null && PREPARING.matcher(line.message).find();
    }

    public static boolean isBlockContinuation(LogParser.ParsedLine line, String mapper, String thread) {
        if (line == null || mapper == null || thread == null) {
            return false;
        }
        if (!mapper.equals(line.logger) || !thread.equals(line.thread)) {
            return false;
        }
        String msg = line.message;
        return msg.contains("Parameters:")
                || msg.contains("Total:")
                || msg.contains("Updates:")
                || msg.contains("Columns:")
                || msg.contains("Row:");
    }

    public static boolean isBlockEnd(LogParser.ParsedLine line) {
        if (line == null) {
            return false;
        }
        String msg = line.message;
        return TOTAL.matcher(msg).find() || UPDATES.matcher(msg).find();
    }

    /** Preparing 行から新規ブロックを開始する。 */
    public static SqlBlock startBlock(long fileId, long lineNo, long byteOffset, LogParser.ParsedLine line) {
        SqlBlock block = new SqlBlock();
        block.fileId = fileId;
        block.lineNo = lineNo;
        block.byteOffset = byteOffset;
        block.tsMillis = line.tsMillis;
        block.tsEndMillis = line.tsMillis;
        block.mapper = line.logger;
        block.level = line.level;
        block.thread = line.thread;
        block.sqlText = extractPreparingSql(line.message);
        block.sqlType = detectSqlType(block.sqlText);
        return block;
    }

    /** 継続行の情報をブロックにマージする。 */
    public static void mergeLine(SqlBlock block, LogParser.ParsedLine line) {
        block.tsEndMillis = line.tsMillis;
        Matcher pm = PARAMETERS.matcher(line.message);
        if (pm.find()) {
            block.parameters = pm.group(1).trim();
        }
        Matcher tm = TOTAL.matcher(line.message);
        if (tm.find()) {
            block.rowCount = Integer.valueOf(tm.group(1));
        }
        Matcher um = UPDATES.matcher(line.message);
        if (um.find()) {
            block.rowCount = Integer.valueOf(um.group(1));
        }
    }

    /** ブロック終了時に elapsed_ms を確定する。 */
    public static void finalizeBlock(SqlBlock block, long endByteOffset) {
        block.endByteOffset = endByteOffset;
        if (block.tsEndMillis >= block.tsMillis) {
            long elapsed = block.tsEndMillis - block.tsMillis;
            block.elapsedMs = elapsed > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) elapsed;
        }
        if (block.sqlType == null || block.sqlType.isEmpty()) {
            block.sqlType = detectSqlType(block.sqlText);
        }
    }

    static String extractPreparingSql(String message) {
        Matcher m = PREPARING.matcher(message);
        if (m.find()) {
            return m.group(1).trim();
        }
        return message.trim();
    }

    static String detectSqlType(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "OTHER";
        }
        String trimmed = sql.trim();
        int space = trimmed.indexOf(' ');
        String first = (space > 0 ? trimmed.substring(0, space) : trimmed).toUpperCase(Locale.ROOT);
        if ("SELECT".equals(first) || "WITH".equals(first)) {
            return "SELECT";
        }
        if ("INSERT".equals(first)) {
            return "INSERT";
        }
        if ("UPDATE".equals(first)) {
            return "UPDATE";
        }
        if ("DELETE".equals(first)) {
            return "DELETE";
        }
        if ("MERGE".equals(first)) {
            return "MERGE";
        }
        if ("CALL".equals(first)) {
            return "CALL";
        }
        return "OTHER";
    }
}
