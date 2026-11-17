package com.nmc.stocktake.core;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an expiry as the sheet and the label reader write it, to warn about
 * short-dated stock while the pack is in your hand.
 *
 * Accepts dd/mm/yyyy, mm/yyyy (meaning the end of that month), yyyy-mm-dd
 * and dd-mm-yyyy. Anything else is "not known" (null) and no warning shows.
 */
public final class Expiry {
    /** Warn when an expiry is this many months away or less. */
    public static final int SHORT_MONTHS = 3;

    private static final Pattern DMY = Pattern.compile("(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})");
    private static final Pattern MY = Pattern.compile("(\\d{1,2})[/.\\-](\\d{4})");
    private static final Pattern YMD = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    private Expiry() {}

    public static LocalDate parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        try {
            Matcher m = YMD.matcher(t);
            if (m.matches()) return LocalDate.of(i(m, 1), i(m, 2), i(m, 3));
            m = DMY.matcher(t);
            if (m.matches()) return LocalDate.of(i(m, 3), i(m, 2), i(m, 1));
            m = MY.matcher(t);
            if (m.matches()) return YearMonth.of(i(m, 2), i(m, 1)).atEndOfMonth();
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }

    private static int i(Matcher m, int g) {
        return Integer.parseInt(m.group(g));
    }

    /**
     * A warning to show, or null: "EXPIRED" wording for a date gone by, and
     * "expires in N days" within SHORT_MONTHS.
     */
    public static String warning(String text, LocalDate today) {
        LocalDate d = parse(text);
        if (d == null) return null;
        long days = ChronoUnit.DAYS.between(today, d);
        if (days < 0) return "EXPIRED on " + d + " - take it off the shelf";
        if (!d.isAfter(today.plusMonths(SHORT_MONTHS))) {
            return days == 0 ? "Expires today" : "Short-dated: expires in " + days + " day" + (days == 1 ? "" : "s");
        }
        return null;
    }
}
