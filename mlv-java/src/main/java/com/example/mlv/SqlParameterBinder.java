package com.example.mlv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MyBatis の Parameters ログ（{@code 1(Long), Alice(String)} 形式）を解析し、
 * PreparedStatement の {@code ?} に当てはめた SQL 文字列を生成する。
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
        int placeholderCount = countPlaceholders(sql);
        String bound = replacePlaceholders(sql, literals);

        String warning = null;
        if (literals.size() != placeholderCount) {
            warning = "プレースホルダ " + placeholderCount + " 個に対しパラメータ "
                    + literals.size() + " 個（先頭から順に当てはめ）";
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

    /** MyBatis の {@code value(Type), value(Type)} を {@code )} 終端で分割する。 */
    private static List<String> splitParameterSegments(String parameters) {
        List<String> segments = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < parameters.length(); i++) {
            char c = parameters.charAt(i);
            cur.append(c);
            if (c == ')' && i + 1 < parameters.length()) {
                int j = i + 1;
                while (j < parameters.length() && parameters.charAt(j) == ' ') {
                    j++;
                }
                if (j < parameters.length() && parameters.charAt(j) == ',') {
                    segments.add(cur.toString());
                    cur.setLength(0);
                    i = j;
                }
            }
        }
        if (cur.length() > 0) {
            segments.add(cur.toString());
        }
        return segments;
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

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }

    private static String replacePlaceholders(String sql, List<String> literals) {
        StringBuilder out = new StringBuilder(sql.length() + literals.size() * 8);
        int pi = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?' && pi < literals.size()) {
                out.append(literals.get(pi++));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
