package com.example.mlv;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlParameterBinderTest {

    @Test
    void bindSingleLong() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT * FROM users WHERE id = ?",
                "1(Long)");
        assertEquals("SELECT * FROM users WHERE id = 1", r.sql);
        assertNull(r.warning);
    }

    @Test
    void bindMultipleTypes() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "INSERT INTO users (name, email) VALUES (?, ?)",
                "Alice(String), alice@example.com(String)");
        assertEquals("INSERT INTO users (name, email) VALUES ('Alice', 'alice@example.com')", r.sql);
    }

    @Test
    void bindUpdateMixed() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "UPDATE users SET email = ? WHERE id = ?",
                "bob@example.com(String), 2(Long)");
        assertEquals("UPDATE users SET email = 'bob@example.com' WHERE id = 2", r.sql);
    }

    @Test
    void bindNullParameter() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "UPDATE t SET col = ? WHERE id = ?",
                "null(String), 1(Integer)");
        assertEquals("UPDATE t SET col = NULL WHERE id = 1", r.sql);
    }

    @Test
    void bindEmptyParameters() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT 1 FROM dual",
                "");
        assertEquals("SELECT 1 FROM dual", r.sql);
    }

    @Test
    void bindMismatchWarning() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                "1(Long)");
        assertEquals("SELECT * FROM t WHERE a = 1 AND b = ?", r.sql);
        assertNotNull(r.warning);
    }

    @Test
    void parseParameters() {
        List<String> literals = SqlParameterBinder.parseParameterLiterals("Alice(String), 2(Long)");
        assertEquals(Arrays.asList("'Alice'", "2"), literals);
    }

    @Test
    void quoteEscape() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT ?",
                "O'Brien(String)");
        assertEquals("SELECT 'O''Brien'", r.sql);
    }
}
