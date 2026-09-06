package com.example.mlv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryFilterTest {

    /** トルコ語ロケールでは "insert" の i が İ になり、既定ロケールの変換では一致しなくなる。 */
    @org.junit.jupiter.api.Test
    void filterParsingIsLocaleIndependent() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertTrue(SqlQueryFilter.parseSqlTypeFilter("insert").contains("INSERT"));
            assertTrue(SqlQueryFilter.parseSqlTypeFilter("select, Update").contains("SELECT"));
            assertEquals(Boolean.FALSE, SqlQueryFilter.parseCompleteFilter("INCOMPLETE"));
            assertEquals(Boolean.TRUE, SqlQueryFilter.parseCompleteFilter("Complete"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void parseCompleteFilter() {
        assertNull(SqlQueryFilter.parseCompleteFilter(null));
        assertNull(SqlQueryFilter.parseCompleteFilter(""));
        assertNull(SqlQueryFilter.parseCompleteFilter("all"));

        assertEquals(Boolean.TRUE, SqlQueryFilter.parseCompleteFilter("1"));
        assertEquals(Boolean.TRUE, SqlQueryFilter.parseCompleteFilter("true"));
        assertEquals(Boolean.TRUE, SqlQueryFilter.parseCompleteFilter("complete"));

        assertEquals(Boolean.FALSE, SqlQueryFilter.parseCompleteFilter("0"));
        assertEquals(Boolean.FALSE, SqlQueryFilter.parseCompleteFilter("false"));
        assertEquals(Boolean.FALSE, SqlQueryFilter.parseCompleteFilter("incomplete"));
    }
}
