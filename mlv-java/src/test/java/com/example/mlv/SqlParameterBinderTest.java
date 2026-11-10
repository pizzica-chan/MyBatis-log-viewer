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

    /** MyBatis は null を型なしの {@code null} と書く。後ろの割り当てがずれないこと。 */
    @Test
    void bindBareNullParameters() {
        SqlParameterBinder.BindResult first = SqlParameterBinder.bind(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                "null, 1(Integer)");
        assertEquals("SELECT * FROM t WHERE a = NULL AND b = 1", first.sql);
        assertNull(first.warning);

        SqlParameterBinder.BindResult middle = SqlParameterBinder.bind(
                "UPDATE t SET a = ?, b = ?, c = ? WHERE id = ?",
                "x(String), null, null, 5(Long)");
        assertEquals("UPDATE t SET a = 'x', b = NULL, c = NULL WHERE id = 5", middle.sql);
        assertNull(middle.warning);

        SqlParameterBinder.BindResult last = SqlParameterBinder.bind(
                "UPDATE t SET a = ? WHERE id = ?",
                "1(Integer), null");
        assertEquals("UPDATE t SET a = 1 WHERE id = NULL", last.sql);
        assertNull(last.warning);

        SqlParameterBinder.BindResult only = SqlParameterBinder.bind(
                "SELECT * FROM t WHERE a = ?", "null");
        assertEquals("SELECT * FROM t WHERE a = NULL", only.sql);
    }

    /** null で始まるだけの値（nullable など）は null として切り出さないこと。 */
    @Test
    void bindValueStartingWithNullIsNotNull() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT * FROM t WHERE a = ? AND b = ?",
                "nullable(String), a, b(String)");
        assertEquals("SELECT * FROM t WHERE a = 'nullable' AND b = 'a, b'", r.sql);
        assertNull(r.warning);
    }

    /** 文字列リテラルの中の ? はプレースホルダではない。 */
    @Test
    void bindSkipsQuestionMarkInsideLiteral() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT * FROM t WHERE name LIKE '%?%' AND note = 'it''s ?' AND id = ?",
                "7(Integer)");
        assertEquals(
                "SELECT * FROM t WHERE name LIKE '%?%' AND note = 'it''s ?' AND id = 7", r.sql);
        assertNull(r.warning);
    }

    /**
     * 引用符の開閉が合わない SQL（コメントの中のアポストロフィなど）では、リテラルを
     * 見分けようとせず、今までどおりすべての ? に割り当てること。
     */
    @Test
    void bindFallsBackWhenQuotesAreUnbalanced() {
        SqlParameterBinder.BindResult r = SqlParameterBinder.bind(
                "SELECT * FROM t /* user's rows */ WHERE a = ? AND b = ?",
                "1(Integer), x(String)");
        assertEquals("SELECT * FROM t /* user's rows */ WHERE a = 1 AND b = 'x'", r.sql);
        assertNull(r.warning);
    }
}
