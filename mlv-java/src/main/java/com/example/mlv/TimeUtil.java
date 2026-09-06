package com.example.mlv;

public final class TimeUtil {

    private TimeUtil() {
    }

    private static final long MILLIS_PER_DAY = 86_400_000L;

    public static long parseLogTimestamp(byte[] buf, int off) {
        int year = digit4(buf, off);
        int month = digit2(buf, off + 5);
        int day = digit2(buf, off + 8);
        int hour = digit2(buf, off + 11);
        int min = digit2(buf, off + 14);
        int sec = digit2(buf, off + 17);
        int milli = digit3(buf, off + 20);
        if (year < 0 || month < 0 || day < 0 || hour < 0 || min < 0 || sec < 0 || milli < 0) {
            return Long.MIN_VALUE;
        }
        return toMillis(year, month, day, hour, min, sec, milli);
    }

    /**
     * UI / API の日時文字列を解釈する。
     *
     * <p>受け付ける形式は {@code yyyy-MM-dd}, {@code yyyy-MM-dd HH:mm},
     * {@code yyyy-MM-dd HH:mm:ss}, {@code yyyy-MM-dd HH:mm:ss.SSS} の 4 通りのみ
     * （{@code T} 区切りも可）。区切り文字・桁・暦としての妥当性をすべて検証し、
     * 存在しない日時は繰り上げずに例外にする。
     *
     * @throws IllegalArgumentException 形式不正、または存在しない日時の場合
     */
    public static long parseUiDatetime(String value) {
        if (value == null) {
            throw invalidDatetime(null);
        }
        String v = value.trim().replace('T', ' ');
        int len = v.length();
        if (len != 10 && len != 16 && len != 19 && len != 23) {
            throw invalidDatetime(value);
        }
        if (v.charAt(4) != '-' || v.charAt(7) != '-') {
            throw invalidDatetime(value);
        }
        if (len >= 16 && (v.charAt(10) != ' ' || v.charAt(13) != ':')) {
            throw invalidDatetime(value);
        }
        if (len >= 19 && v.charAt(16) != ':') {
            throw invalidDatetime(value);
        }
        if (len == 23 && v.charAt(19) != '.') {
            throw invalidDatetime(value);
        }

        int year = digitsAt(v, 0, 4);
        int month = digitsAt(v, 5, 2);
        int day = digitsAt(v, 8, 2);
        int hour = len >= 16 ? digitsAt(v, 11, 2) : 0;
        int min = len >= 16 ? digitsAt(v, 14, 2) : 0;
        int sec = len >= 19 ? digitsAt(v, 17, 2) : 0;
        int milli = len == 23 ? digitsAt(v, 20, 3) : 0;
        if (year < 0 || month < 0 || day < 0 || hour < 0 || min < 0 || sec < 0 || milli < 0) {
            throw invalidDatetime(value);
        }
        if (!isValidDateTime(year, month, day, hour, min, sec, milli)) {
            throw invalidDatetime(value);
        }
        return toMillis(year, month, day, hour, min, sec, milli);
    }

    static boolean isValidDateTime(int year, int month, int day, int hour, int min, int sec, int milli) {
        if (month < 1 || month > 12) {
            return false;
        }
        if (day < 1 || day > daysInMonth(year, month)) {
            return false;
        }
        return hour <= 23 && min <= 59 && sec <= 59 && milli <= 999;
    }

    static int daysInMonth(int year, int month) {
        switch (month) {
            case 2:
                return isLeapYear(year) ? 29 : 28;
            case 4:
            case 6:
            case 9:
            case 11:
                return 30;
            default:
                return 31;
        }
    }

    private static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private static IllegalArgumentException invalidDatetime(String value) {
        return new IllegalArgumentException("日時形式を解釈できません: " + value);
    }

    /** ASCII 数字のみを受け付ける。1 文字でも数字以外なら -1。 */
    private static int digitsAt(String s, int off, int count) {
        int result = 0;
        for (int i = off; i < off + count; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            result = result * 10 + (c - '0');
        }
        return result;
    }

    public static String formatIso(long millis) {
        long days = Math.floorDiv(millis, MILLIS_PER_DAY);
        int msOfDay = (int) Math.floorMod(millis, MILLIS_PER_DAY);
        int[] ymd = civilFromDays(days);
        int hour = msOfDay / 3_600_000;
        int rem = msOfDay % 3_600_000;
        int min = rem / 60_000;
        rem %= 60_000;
        int sec = rem / 1000;
        int milli = rem % 1000;
        StringBuilder sb = new StringBuilder(23);
        pad(sb, ymd[0], 4);
        sb.append('-');
        pad(sb, ymd[1], 2);
        sb.append('-');
        pad(sb, ymd[2], 2);
        sb.append('T');
        pad(sb, hour, 2);
        sb.append(':');
        pad(sb, min, 2);
        sb.append(':');
        pad(sb, sec, 2);
        sb.append('.');
        pad(sb, milli, 3);
        return sb.toString();
    }

    static long toMillis(int year, int month, int day, int hour, int min, int sec, int milli) {
        long days = daysFromCivil(year, month, day);
        return days * MILLIS_PER_DAY + (hour * 3600L + min * 60L + sec) * 1000L + milli;
    }

    static long daysFromCivil(int y, int m, int d) {
        int yy = m <= 2 ? y - 1 : y;
        int era = (yy >= 0 ? yy : yy - 399) / 400;
        int yoe = yy - era * 400;
        int doy = (153 * (m > 2 ? m - 3 : m + 9) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146_097 + doe - 719_468;
    }

    static int[] civilFromDays(long z) {
        z += 719_468;
        long era = (z >= 0 ? z : z - 146_096) / 146_097;
        long doe = z - era * 146_097;
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        return new int[] {(int) (m <= 2 ? y + 1 : y), (int) m, (int) d};
    }

    private static void pad(StringBuilder sb, int value, int width) {
        String s = Integer.toString(value);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }

    private static int digit(byte[] b, int i) {
        int c = b[i] & 0xFF;
        return (c >= '0' && c <= '9') ? c - '0' : -1;
    }

    private static int digit2(byte[] b, int i) {
        int a = digit(b, i);
        int c = digit(b, i + 1);
        return (a < 0 || c < 0) ? -1 : a * 10 + c;
    }

    private static int digit3(byte[] b, int i) {
        int a = digit(b, i);
        int c = digit(b, i + 1);
        int d = digit(b, i + 2);
        return (a < 0 || c < 0 || d < 0) ? -1 : a * 100 + c * 10 + d;
    }

    private static int digit4(byte[] b, int i) {
        int a = digit(b, i);
        int c = digit(b, i + 1);
        int d = digit(b, i + 2);
        int e = digit(b, i + 3);
        return (a < 0 || c < 0 || d < 0 || e < 0) ? -1 : a * 1000 + c * 100 + d * 10 + e;
    }
}
