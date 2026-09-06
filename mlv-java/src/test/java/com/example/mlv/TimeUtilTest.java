package com.example.mlv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeUtilTest {

    private static String parseAndFormat(String value) {
        return TimeUtil.formatIso(TimeUtil.parseUiDatetime(value));
    }

    private static void assertRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseUiDatetime(value),
                "受け付けてはいけない日時: " + value);
    }

    @Test
    void acceptsAllUiInputFormats() {
        assertEquals("2026-06-15T00:00:00.000", parseAndFormat("2026-06-15"));
        assertEquals("2026-06-15T10:30:00.000", parseAndFormat("2026-06-15 10:30"));
        assertEquals("2026-06-15T10:30:45.000", parseAndFormat("2026-06-15 10:30:45"));
        assertEquals("2026-06-15T10:30:45.123", parseAndFormat("2026-06-15 10:30:45.123"));
        // ISO の T 区切りも同じ結果になる（/api/sql/detail が渡す形式）
        assertEquals("2026-06-15T10:30:45.123", parseAndFormat("2026-06-15T10:30:45.123"));
        assertEquals("2026-06-15T10:30:45.123", parseAndFormat("  2026-06-15 10:30:45.123  "));
    }

    @Test
    void rejectsNonExistentDatesInsteadOfRollingOver() {
        assertRejected("2025-13-45 10:00");
        assertRejected("2026-02-30");
        assertRejected("2026-00-00");
        assertRejected("2026-06-15 25:99");
        assertRejected("2026-99-99 99:99:99.999");
        assertRejected("2026-06-00");
        assertRejected("2026-06-31");
        assertRejected("2026-06-15 24:00");
        assertRejected("2026-06-15 10:60");
        assertRejected("2026-06-15 10:30:60");
    }

    @Test
    void handlesLeapYears() {
        assertEquals("2024-02-29T00:00:00.000", parseAndFormat("2024-02-29"));
        assertEquals("2000-02-29T00:00:00.000", parseAndFormat("2000-02-29"));
        assertRejected("2026-02-29");
        assertRejected("1900-02-29");
    }

    @Test
    void rejectsMalformedInput() {
        assertRejected(null);
        assertRejected("");
        assertRejected("2026-06-15 10:30:45.123XYZ");
        assertRejected("2026-06-15 10");
        assertRejected("2026/06/15");
        assertRejected("2026-06-15T10:30:45,123");
        assertRejected("20260615");
        assertRejected("abcd-ef-gh");
        // 全角数字や符号付きは受け付けない
        assertRejected("２０２６-06-15");
        assertRejected("2026-+6-15");
    }

    @Test
    void daysInMonthCoversEveryMonth() {
        int[] expected = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        for (int m = 1; m <= 12; m++) {
            assertEquals(expected[m - 1], TimeUtil.daysInMonth(2026, m), "月: " + m);
        }
        assertEquals(29, TimeUtil.daysInMonth(2024, 2));
    }
}
