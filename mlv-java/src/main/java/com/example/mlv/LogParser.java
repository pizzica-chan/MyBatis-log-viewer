package com.example.mlv;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public final class LogParser {

    private LogParser() {
    }

    public static final int TS_LEN = 23;

    private static final Set<String> KNOWN_LEVELS = new HashSet<>(Arrays.asList(
            "TRACE", "DEBUG", "INFO", "WARN", "WARNING", "ERROR", "FATAL", "SEVERE"));

    private static final String FIELD3_END = "] - ";

    private static final Pattern THREAD_HINT = Pattern.compile(
            "(?:^main(?:$|:)|exec-\\d+|pool-\\d+-thread-\\d+|scheduler-\\d+"
                    + "|ajp-|http-nio-|https-nio-|catalina-|-exec-\\d+$)",
            Pattern.CASE_INSENSITIVE);

    public static final class ParsedLine {
        public final long tsMillis;
        public final String logger;
        public final String level;
        public final String thread;
        public final String message;

        ParsedLine(long tsMillis, String logger, String level, String thread, String message) {
            this.tsMillis = tsMillis;
            this.logger = logger;
            this.level = level;
            this.thread = thread;
            this.message = message;
        }
    }

    public static boolean looksLikeHeader(byte[] b, int len) {
        if (len < TS_LEN + 1) {
            return false;
        }
        return isDigit(b[0]) && isDigit(b[1]) && isDigit(b[2]) && isDigit(b[3])
                && b[4] == '-' && isDigit(b[5]) && isDigit(b[6])
                && b[7] == '-' && isDigit(b[8]) && isDigit(b[9])
                && b[10] == ' ' && isDigit(b[11]) && isDigit(b[12])
                && b[13] == ':' && isDigit(b[14]) && isDigit(b[15])
                && b[16] == ':' && isDigit(b[17]) && isDigit(b[18])
                && b[19] == '.' && isDigit(b[20]) && isDigit(b[21]) && isDigit(b[22])
                && b[23] == '[';
    }

    public static ParsedLine parse(byte[] b, int len) {
        if (!looksLikeHeader(b, len)) {
            return null;
        }
        long ts = TimeUtil.parseLogTimestamp(b, 0);
        if (ts == Long.MIN_VALUE) {
            return null;
        }
        int end = len;
        while (end > TS_LEN && (b[end - 1] == '\n' || b[end - 1] == '\r')) {
            end--;
        }
        String rest = new String(b, TS_LEN, end - TS_LEN, StandardCharsets.UTF_8);
        return parseRest(ts, rest);
    }

    private static ParsedLine parseRest(long ts, String rest) {
        if (rest.isEmpty() || rest.charAt(0) != '[') {
            return null;
        }
        int e1 = rest.indexOf(']', 1);
        if (e1 < 0 || e1 + 1 >= rest.length() || rest.charAt(e1 + 1) != '[') {
            return null;
        }
        int s2 = e1 + 2;
        int e2 = rest.indexOf(']', s2);
        if (e2 < 0 || e2 + 1 >= rest.length() || rest.charAt(e2 + 1) != '[') {
            return null;
        }
        int s3 = e2 + 2;
        int e3 = rest.indexOf(FIELD3_END, s3);
        if (e3 < 0) {
            return null;
        }
        String field1 = rest.substring(1, e1);
        String field2 = rest.substring(s2, e2);
        String field3 = rest.substring(s3, e3);
        String message = rest.substring(e3 + FIELD3_END.length());

        String level = field2.toUpperCase();
        if (!KNOWN_LEVELS.contains(level)) {
            return null;
        }
        String logger;
        String thread;
        boolean field1LooksLikeThread = THREAD_HINT.matcher(field1).find();
        boolean field3LooksLikeThread = THREAD_HINT.matcher(field3).find();
        if (field1LooksLikeThread != field3LooksLikeThread) {
            if (field1LooksLikeThread) {
                thread = field1;
                logger = field3;
            } else {
                thread = field3;
                logger = field1;
            }
        } else if (field3.indexOf('.') >= 0 && field1.indexOf('.') < 0) {
            logger = field3;
            thread = field1;
        } else if (field1.indexOf('.') >= 0 && field3.indexOf('.') < 0) {
            logger = field1;
            thread = field3;
        } else {
            // 標準 Tomcat 形式 [thread][LEVEL][logger] を優先
            logger = field3;
            thread = field1;
        }
        return new ParsedLine(ts, logger, level, thread, message);
    }

    public static ParsedLine parseLine(String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return parse(b, b.length);
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }
}
