package com.nmc.stocktake.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the batch number and the expiry date out of the text the camera read
 * from a label.
 *
 * Labels carry two dates, manufacture and expiry, in many formats:
 * "EXP 03/2027", "Exp. Date: 31.03.2027", "EXP 2027-03", "EXP MAR 2027",
 * "MFG 04/2024". So every date on the label is found, each is matched to the
 * word just before it, a date after MFG / MFD / MANUFACTURED is never taken,
 * and a date after EXP / EXPIRY / USE BY is preferred. With no word to go on,
 * the latest date is taken: an expiry always comes after the manufacture.
 *
 * Results are written the way the web app reads them: "dd/mm/yyyy" when the
 * day is printed, "mm/yyyy" when only the month is (the end of that month).
 */
public final class LabelReader {
    private LabelReader() {}

    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN",
            "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    private static final String EXP_CORE =
            "(?:EXP(?:IRY)?(?:\\W{0,2}DATE)?|EXPIRES?|EXPIRATION|USE\\s*BY|USE\\s*BEFORE|BEST\\s*BEFORE|E\\.?D\\.?|VERV(?:AL)?(?:\\s*DATUM)?)";
    private static final Pattern EXP_WORD = Pattern.compile(EXP_CORE + "\\W*$");
    // "EXP 31 03 2027" / "EXP 03 2027": digits split by spaces only, straight after the word
    private static final Pattern EXP_SPACED_DMY = Pattern.compile(
            "((?<![A-Z])" + EXP_CORE + "\\W*?)([0-3]?[0-9]) +([01]?[0-9]) +((?:20)?[0-9]{2})(?![0-9])");
    private static final Pattern EXP_SPACED_MY = Pattern.compile(
            "((?<![A-Z])" + EXP_CORE + "\\W*?)([01]?[0-9]) +((?:20)?[0-9]{2})(?![0-9])");
    private static final Pattern MFG_WORD = Pattern.compile(
            "(MFG|MFD|MNF|MANUF(ACTURED)?(\\s*(ON|DATE))?|MANUFACTURING(\\s*DATE)?|M\\.?D\\.?|DOM|PROD(UCTION)?(\\s*DATE)?|PACKED(\\s*ON)?|VERV\\s*DAT)\\W*$");

    // 31/03/2027  31-03-27  31.03.2027
    private static final Pattern DMY = Pattern.compile("(?<![0-9])([0-3]?[0-9])[./-]([01]?[0-9])[./-]((?:20)?[0-9]{2})(?![0-9])");
    // 2027-03-31
    private static final Pattern YMD = Pattern.compile("(?<![0-9])(20[0-9]{2})[./-]([01]?[0-9])[./-]([0-3]?[0-9])(?![0-9])");
    // 03/2027  03-27  3.2027
    private static final Pattern MY = Pattern.compile("(?<![0-9])(?<![0-9][./-])([01]?[0-9])[./-]((?:20)?[0-9]{2})(?![0-9])(?![./-][0-9])");
    // 2027-03  2027/03
    private static final Pattern YM = Pattern.compile("(?<![0-9])(?<![0-9][./-])(20[0-9]{2})[./-]([01]?[0-9])(?![0-9])(?![./-][0-9])");
    // MAR 2027  MAR-27  MARCH 2027  31 MAR 2027
    private static final Pattern MON_Y = Pattern.compile(
            "(?<![A-Z0-9])(?:([0-3]?[0-9])\\s*[ ./-]?\\s*)?(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\\.?\\s*[ ./-]?\\s*((?:20)?[0-9]{2})(?![0-9])");

    private static final Pattern BATCH = Pattern.compile(
            "(?:BATCH\\s*(?:NO\\.?|NUMBER|#)?|LOT\\s*(?:NO\\.?|NUMBER|#)?|B\\s*[./]\\s*NO\\.?|BN|B\\.N\\.?|CH\\.?\\s*-?\\s*B\\.?)\\s*[:.#-]?\\s*([A-Z0-9][A-Z0-9/-]{1,19})");

    /** A date found on the label: where it starts, and how it is written for the web app. */
    static final class Found {
        final int start;
        final int sortKey;      // yyyymmdd, for "the latest"
        final String text;
        Found(int start, int y, int m, int d, boolean dayKnown) {
            this.start = start;
            this.sortKey = y * 10000 + m * 100 + (dayKnown ? d : 31);
            this.text = dayKnown ? String.format(Locale.ROOT, "%02d/%02d/%04d", d, m, y)
                                 : String.format(Locale.ROOT, "%02d/%04d", m, y);
        }
    }

    /** Fix the letters a camera most often mistakes for digits, inside digit runs. */
    static String clean(String raw) {
        String t = raw.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            boolean digitBefore = i > 0 && Character.isDigit(sb.charAt(i - 1));
            boolean digitAfter = i + 1 < sb.length() && Character.isDigit(sb.charAt(i + 1));
            if ((c == 'O') && digitBefore && digitAfter) sb.setCharAt(i, '0');
        }
        return sb.toString();
    }

    /**
     * Tidy the text for finding dates: letters the camera reads in place of
     * digits inside a date (O D Q -> 0, I L | -> 1, S -> 5, Z -> 2, B -> 8),
     * stray spaces around a / or . ("03 / 2027"), other strokes used as a
     * separator ("03|2027", "03\\2027", "03,2027"), and dates printed with
     * spaces only, straight after the expiry word ("EXP 03 2027").
     * Only for dates: the batch is read from the plain cleaned text.
     */
    static String forDates(String raw) {
        // strokes used as a separator first, so "03|2027" is not read as 0312027
        StringBuilder sb = new StringBuilder(
                clean(raw).replaceAll("(?<=[0-9]) *[|\\\\,] *(?=[0-9])", "/"));
        for (int pass = 0; pass < 2; pass++) {          // twice, so "OI/2O27" settles
            for (int i = 0; i < sb.length(); i++) {
                char d = digitFor(sb.charAt(i));
                if (d == 0) continue;
                char p = i > 0 ? sb.charAt(i - 1) : ' ', n = i + 1 < sb.length() ? sb.charAt(i + 1) : ' ';
                boolean pd = Character.isDigit(p), nd = Character.isDigit(n);
                boolean ps = pd || isSep(p) || p == ' ' || p == ':', ns = nd || isSep(n) || n == ' ' || n == '\n';
                // a digit on one side, and a digit or separator on the other
                if ((pd && ns && (nd || isSep(n))) || (nd && ps && (pd || isSep(p)))
                        || (pd && nd)) sb.setCharAt(i, d);
            }
        }
        String t = sb.toString();
        t = t.replaceAll("(?<=[0-9]) *([/.]) +(?=[0-9])", "$1");
        t = t.replaceAll("(?<=[0-9]) +([/.]) *(?=[0-9])", "$1");
        t = EXP_SPACED_DMY.matcher(t).replaceAll("$1$2/$3/$4");
        t = EXP_SPACED_MY.matcher(t).replaceAll("$1$2/$3");
        return t;
    }

    private static boolean isSep(char c) {
        return c == '/' || c == '.' || c == '-';
    }

    private static char digitFor(char c) {
        switch (c) {
            case 'O': case 'D': case 'Q': return '0';
            case 'I': case 'L': case '|': return '1';
            case 'S': return '5';
            case 'Z': return '2';
            case 'B': return '8';
            default: return 0;
        }
    }

    static int year(String y) {
        int v = Integer.parseInt(y);
        return v < 100 ? 2000 + v : v;
    }

    static boolean ok(int y, int m, int d) {
        return y >= 2015 && y <= 2050 && m >= 1 && m <= 12 && d >= 1 && d <= 31;
    }

    static List<Found> dates(String t) {
        List<Found> out = new ArrayList<>();
        boolean[] used = new boolean[t.length() + 1];
        Matcher m = YMD.matcher(t);
        while (m.find()) {
            int y = year(m.group(1)), mo = Integer.parseInt(m.group(2)), d = Integer.parseInt(m.group(3));
            if (ok(y, mo, d)) { out.add(new Found(m.start(), y, mo, d, true)); mark(used, m); }
        }
        m = DMY.matcher(t);
        while (m.find()) {
            if (used[m.start()]) continue;
            int d = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), y = year(m.group(3));
            if (ok(y, mo, d)) { out.add(new Found(m.start(), y, mo, d, true)); mark(used, m); }
        }
        m = MON_Y.matcher(t);
        while (m.find()) {
            if (used[m.start()]) continue;
            int mo = 1 + indexOfMonth(m.group(2));
            int y = year(m.group(3));
            boolean dayKnown = m.group(1) != null;
            int d = dayKnown ? Integer.parseInt(m.group(1)) : 1;
            if (ok(y, mo, d)) { out.add(new Found(m.start(), y, mo, d, dayKnown)); mark(used, m); }
        }
        m = YM.matcher(t);
        while (m.find()) {
            if (used[m.start()]) continue;
            int y = year(m.group(1)), mo = Integer.parseInt(m.group(2));
            if (ok(y, mo, 1)) { out.add(new Found(m.start(), y, mo, 1, false)); mark(used, m); }
        }
        m = MY.matcher(t);
        while (m.find()) {
            if (used[m.start()]) continue;
            int mo = Integer.parseInt(m.group(1)), y = year(m.group(2));
            if (ok(y, mo, 1)) { out.add(new Found(m.start(), y, mo, 1, false)); mark(used, m); }
        }
        return out;
    }

    private static void mark(boolean[] used, Matcher m) {
        for (int i = m.start(); i < m.end() && i < used.length; i++) used[i] = true;
    }

    private static int indexOfMonth(String mon) {
        for (int i = 0; i < 12; i++) if (MONTHS[i].equals(mon)) return i;
        return 0;
    }

    /** The text just before a date, on its own line or the line above. */
    private static String before(String t, int start) {
        int from = Math.max(0, start - 40);
        String s = t.substring(from, start);
        int nl = s.lastIndexOf('\n');
        String sameLine = nl >= 0 ? s.substring(nl + 1) : s;
        if (sameLine.trim().isEmpty() && nl >= 0) {
            // the word is often on the line above the date
            return s.substring(0, nl).trim();
        }
        return sameLine.trim();
    }

    /** The expiry printed on the label, or "" if there is none to be sure of. */
    public static String expiry(String rawText) {
        if (rawText == null) return "";
        String t = forDates(rawText);
        List<Found> all = dates(t);
        Found best = null;
        List<Found> unlabelled = new ArrayList<>();
        for (Found f : all) {
            String w = before(t, f.start);
            if (MFG_WORD.matcher(w).find()) continue;
            if (EXP_WORD.matcher(w).find()) {
                if (best == null || f.start < best.start) best = f;
            } else {
                unlabelled.add(f);
            }
        }
        if (best != null) return best.text;
        Found latest = null;
        for (Found f : unlabelled) if (latest == null || f.sortKey > latest.sortKey) latest = f;
        // A lone unlabelled date might be the manufacture date: only trust it
        // when there were two dates to compare, or it is in the future-ish range.
        if (latest == null) return "";
        if (all.size() >= 2 || latest.sortKey >= 20250000) return latest.text;
        return "";
    }

    /** The batch / lot number printed on the label, or "". */
    public static String batch(String rawText) {
        if (rawText == null) return "";
        String t = clean(rawText);
        Matcher m = BATCH.matcher(t);
        while (m.find()) {
            String v = m.group(1);
            if (v.matches("(NO|NUMBER|EXP|MFG|MFD|DATE)")) continue;
            if (!v.matches(".*[0-9].*")) continue;      // a batch number has a digit in it
            // "LOT.123EXP.03/2027": the camera ran the batch into the next word
            v = v.replaceAll("(EXP|MFG|MFD|MNF).*$", "");
            v = v.replaceAll("[-/]+$", "");
            if (v.length() >= 2 && v.matches(".*[0-9].*")) return v;
        }
        return "";
    }
}
