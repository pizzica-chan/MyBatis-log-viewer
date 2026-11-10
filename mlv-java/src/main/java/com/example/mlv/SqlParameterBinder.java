package com.example.mlv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MyBatis の Parameters ログ（{@code 1(Long), Alice(String)} 形式）を解析し、
 * PreparedStatement の {@code ?} にパラメータを割り当てた SQL 文字列を生成する。
 */
public final class SqlParameterBinder {

    private SqlParameterBinder() {
    }

    /** バインド結果。 */
    public static final class BindResult {
        public final String sql;
        /** パラメータ不足・余剰などがあればメッセージ（なければ null）。 */
        public final String warning;

        BindResult(String sql, String warning) {
            this.sql = sql;
            this.warning = warning;
        }
    }

    /**
     * {@code sql} の {@code ?} を {@code parameters} の値で順に置換する。
     *
     * @param sql         Preparing 行の SQL（{@code ?} プレースホルダ含む）
     * @param parameters  Parameters 行の本文（空/null 可）
     */
    public static BindResult bind(String sql, String parameters) {
        if (sql == null || sql.isEmpty()) {
            return new BindResult(sql, null);
        }
        if (parameters == null || parameters.trim().isEmpty()) {
            return new BindResult(sql, null);
        }

        List<String> literals = parseParameterLiterals(parameters.trim());
        List<Integer> positions = choosePlaceholders(sql, literals.size());
        int placeholderCount = positions.size();
        String bound = replacePlaceholders(sql, positions, literals);

        String warning = null;
        if (literals.size() != placeholderCount) {
            warning = "プレースホルダ " + placeholderCount + " 個に対しパラメータ "
                    + literals.size() + " 個（先頭から順に割り当て）";
        }
        return new BindResult(bound, warning);
    }

    /** 一覧表示用の短いプレビュー。 */
    public static String bindPreview(String sql, String parameters, int maxLen) {
        BindResult r = bind(sql, parameters);
        if (r.sql == null) {
            return null;
        }
        if (r.sql.length() <= maxLen) {
            return r.sql;
        }
        return r.sql.substring(0, maxLen - 3) + "...";
    }

    static List<String> parseParameterLiterals(String parameters) {
        List<String> out = new ArrayList<>();
        for (String segment : splitParameterSegments(parameters)) {
            int close = segment.lastIndexOf(')');
            int open = segment.lastIndexOf('(');
            if (open >= 0 && close > open) {
                out.add(formatValue(segment.substring(0, open).trim(), segment.substring(open + 1, close).trim()));
            } else {
                out.add(formatRawValue(segment.trim()));
            }
        }
        return out;
    }

    /**
     * MyBatis の {@code value(Type), value(Type)} を {@code )} 終端で分割する。
     *
     * <p>値にカンマを含む文字列（{@code a, b(String)}）があるので、カンマだけでは区切れない。
     * ただし null は型を付けずに {@code null} とだけ書かれる（MyBatis の
     * {@code BaseJdbcLogger}）。{@code )} で終わらないので、セグメントの先頭に来た
     * {@code null,} と末尾の {@code null} は、それだけで 1 つのパラメータとして切り出す。
     * 切り出さないと {@code null, 1(Integer)} が 1 つの値 {@code null, 1} になり、
     * 以降の {@code ?} への割り当てがすべてずれる。
     *
     * <p>文字列の値が {@code null, } で始まる場合（{@code null, x(String)} が 1 つの値）とは
     * ログの上で見分けられない。null のパラメータのほうがずっと多いので、そちらに倒している。
     */
    private static List<String> splitParameterSegments(String parameters) {
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        int n = parameters.length();
        while (i < n) {
            if (cur.length() == 0) {
                // セグメントの先頭。空白を飛ばし、型なしの null ならそれだけで区切る
                while (i < n && parameters.charAt(i) == ' ') {
                    i++;
                }
                int afterNull = bareNullEnd(parameters, i);
                if (afterNull >= 0) {
                    segments.add("null");
                    i = afterNull;
                    continue;
                }
                if (i >= n) {
                    break;
                }
            }
            char c = parameters.charAt(i);
            cur.append(c);
            if (c == ')' && i + 1 < n) {
                int j = i + 1;
                while (j < n && parameters.charAt(j) == ' ') {
                    j++;
                }
                if (j < n && parameters.charAt(j) == ',') {
                    segments.add(cur.toString());
                    cur.setLength(0);
                    i = j;
                }
            }
            i++;
        }
        if (cur.length() > 0) {
            segments.add(cur.toString());
        }
        return segments;
    }

    /**
     * {@code from} から型なしの {@code null} が 1 つのパラメータとして書かれていれば、
     * その次のパラメータの開始位置（区切りのカンマの次。末尾なら文字列の長さ）を返す。
     * そうでなければ {@code -1}。
     */
    private static int bareNullEnd(String parameters, int from) {
        if (!parameters.startsWith("null", from)) {
            return -1;
        }
        int j = from + 4;
        while (j < parameters.length() && parameters.charAt(j) == ' ') {
            j++;
        }
        if (j >= parameters.length()) {
            return j;
        }
        return parameters.charAt(j) == ',' ? j + 1 : -1;
    }

    private static String formatRawValue(String value) {
        if (value.isEmpty()) {
            return "NULL";
        }
        if ("null".equalsIgnoreCase(value)) {
            return "NULL";
        }
        return quote(value);
    }

    private static String formatValue(String value, String type) {
        if (value == null || value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return "NULL";
        }
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (needsQuotes(t)) {
            return quote(value);
        }
        if ("boolean".equals(t) || t.endsWith(".boolean")) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value;
    }

    private static boolean needsQuotes(String typeLower) {
        if (typeLower.isEmpty()) {
            return false;
        }
        if (typeLower.contains("string")
                || typeLower.contains("date")
                || typeLower.contains("time")
                || typeLower.contains("char")
                || typeLower.contains("uuid")
                || typeLower.contains("enum")
                || typeLower.contains("json")
                || typeLower.contains("clob")
                || typeLower.contains("blob")
                || typeLower.contains("binary")) {
            return true;
        }
        return false;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * どの {@code ?} がプレースホルダかを決める。パラメータの個数を手がかりにする。
     *
     * <p>まず、すべての {@code ?} をプレースホルダとして数える。個数がパラメータと合えば
     * それを採る（ほとんどの SQL はここで決まり、文字列リテラルやコメントの書き方に
     * 左右されない）。合わないときだけ、文字列リテラル・引用識別子・ブロックコメントの
     * 中の {@code ?} を外して数え直し（{@code LIKE '%?%'} など）、パラメータと個数が
     * 合う読み方があればそれを採る。{@code \'} をエスケープとみるかどうかは DB によって
     * 違うので、両方を試す。引用やコメントが閉じないまま終わる読み方は、個数が合っても
     * 採らない（{@code 'it\'s ?' AND id = ?} を {@code \'} で閉じたと読むと、個数は 1 で
     * 合うのに、置き換える相手が引用の中の {@code ?} になる）。どれも合わなければ、
     * すべての {@code ?} に先頭から割り当てる（呼び出し側が個数の食い違いを警告する）。
     *
     * <p>行コメント（{@code --}）は見ない。MyBatis はログに出す SQL の改行を空白に
     * 畳むので、コメントの終わりがどこかはログから分からない。
     */
    private static List<Integer> choosePlaceholders(String sql, int parameterCount) {
        List<Integer> all = placeholderPositions(sql, false, false);
        if (all.size() == parameterCount) {
            return all;
        }
        List<Integer> standard = placeholderPositions(sql, true, false);
        if (standard != null && standard.size() == parameterCount) {
            return standard;
        }
        List<Integer> backslash = placeholderPositions(sql, true, true);
        if (backslash != null && backslash.size() == parameterCount) {
            return backslash;
        }
        return all;
    }

    /**
     * {@code ?} の位置を集める。引用かコメントが閉じないまま終わったら {@code null}
     * （その読み方は SQL に合っていない）。
     *
     * @param lexical          引用（一重・二重・バッククォート）とブロックコメントの中を外すか
     * @param backslashEscapes 引用の中で {@code \} が次の 1 文字を打ち消すとみるか
     */
    private static List<Integer> placeholderPositions(String sql, boolean lexical,
            boolean backslashEscapes) {
        List<Integer> positions = new ArrayList<>();
        char quote = 0;
        boolean inComment = false;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (inComment) {
                if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                    inComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (backslashEscapes && c == '\\') {
                    i++;
                } else if (c == quote) {
                    // '' （引用符のエスケープ）は閉じてすぐ開くので、特別扱いしなくてよい
                    quote = 0;
                }
                continue;
            }
            if (lexical) {
                if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    continue;
                }
                if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                    inComment = true;
                    i++;
                    continue;
                }
            }
            if (c == '?') {
                positions.add(i);
            }
        }
        return (quote != 0 || inComment) ? null : positions;
    }

    /** {@code positions} の {@code ?} を、先頭から順に {@code literals} で置き換える。 */
    private static String replacePlaceholders(String sql, List<Integer> positions,
            List<String> literals) {
        StringBuilder out = new StringBuilder(sql.length() + literals.size() * 8);
        int from = 0;
        int count = Math.min(positions.size(), literals.size());
        for (int k = 0; k < count; k++) {
            int at = positions.get(k);
            out.append(sql, from, at).append(literals.get(k));
            from = at + 1;
        }
        return out.append(sql, from, sql.length()).toString();
    }
}
