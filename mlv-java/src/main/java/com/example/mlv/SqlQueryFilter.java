package com.example.mlv;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class SqlQueryFilter {

    public Set<String> sqlTypes;
    public Pattern mapperRe;
    public Pattern sqlRe;
    public Pattern parametersRe;
    public Pattern threadRe;
    public Pattern sourceRe;
    public Pattern grepRe;
    /** grep の元文字列。正規表現メタ文字を含まなければ、バイト列のまま探せる。 */
    public String grepText;
    public Long sinceMillis;
    public Long untilMillis;
    public Integer minElapsed;
    public Integer maxElapsed;
    public Integer minRowCount;
    public Integer maxRowCount;
    /** null=すべて, true=Total/Updates まで到達, false=未到達（失敗・中断など） */
    public Boolean complete;

    public boolean needsRaw() {
        return grepRe != null;
    }

    /** SQL 側で表現できない条件があるか（ある場合のみ全件走査が必要）。 */
    public boolean needsJavaFilter() {
        return mapperRe != null || sqlRe != null || parametersRe != null
                || threadRe != null || sourceRe != null || grepRe != null;
    }

    public static Set<String> parseSqlTypeFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String part : value.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            if (!p.isEmpty()) {
                result.add(p);
            }
        }
        return result.isEmpty() ? null : result;
    }

    /** 正規表現のメタ文字。これらを含まない文字列は「部分一致」そのもの。 */
    private static final String REGEX_META = ".^$*+?()[]{}|\\";

    /** grep の元文字列がメタ文字を含まないか（含まなければバイト列照合に回せる）。 */
    public static boolean hasNoRegexMeta(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (REGEX_META.indexOf(text.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    public static Pattern compileRegex(String pat) {
        if (pat == null || pat.isEmpty()) {
            return null;
        }
        return Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
    }

    public static Integer parseIntOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        return Integer.valueOf(s.trim());
    }

    /** {@code 1}/{@code true}=完了のみ, {@code 0}/{@code false}=未完了のみ, それ以外/null=すべて。 */
    public static Boolean parseCompleteFilter(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "complete".equals(v)) {
            return Boolean.TRUE;
        }
        if ("0".equals(v) || "false".equals(v) || "no".equals(v) || "incomplete".equals(v)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
