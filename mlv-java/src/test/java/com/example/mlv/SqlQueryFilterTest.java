package com.example.mlv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlQueryFilterTest {

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
