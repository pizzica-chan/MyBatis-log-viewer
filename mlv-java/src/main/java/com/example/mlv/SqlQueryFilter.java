package com.example.mlv;

import java.util.HashSet;
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
    public String grepText;
    public Long sinceMillis;
    public Long untilMillis;
    public Integer minElapsed;
    public Integer maxElapsed;

    public boolean needsRaw() {
        return grepRe != null;
    }

    public static Set<String> parseSqlTypeFilter(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String part : value.split(",")) {
            String p = part.trim().toUpperCase();
            if (!p.isEmpty()) {
                result.add(p);
            }
        }
        return result.isEmpty() ? null : result;
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
}
