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

    private static final Pattern EXP_WORD = Pattern.compile(
            "(EXP(IRY)?(\\s*DATE)?|EXPIRES?|EXPIRATION|USE\\s*BY|USE\\s*BEFORE|BEST\\s*BEFORE|E\\.?D\\.?|VERV(AL)?(\\s*DATUM)?)\\W*$");
    private static final Pattern MFG_WORD = Pattern.compile(
            "(MFG|MFD|MNF|MANUF(ACTURED)?(\\s*(ON|DATE))?|MANUFACTURING(\\s*DATE)?|M\\.?D\\.?|DOM|PROD(UCTION)?(\\s*DATE)?|PACKED(\\s*ON)?|VERV\\s*DAT)\\W*$");

    // 31/03/2027  31-03-27  31.03.2027
    private static final Pattern DMY = Pattern.compile("(?<![0-9])([0-3]?[0-9])[./-]([01]?[0-9])[./-]((?:20)?[0-9]{2})(?![0-9])");
    // 2027-03-31
    private static final Pattern YMD = Pattern.compile("(?<![0-9])(20[0-9]{2})[./-]([01]?[0-9])[./-]([0-3]?[0-9])(?![0-9])");
    // 03/2027  03-27  3.2027
    private static final Pattern MY = Pattern.compile("(?<![0-9./-])([01]?[0-9])[./-]((?:20)?[0-9]{2})(?![0-9./-])");
    // 2027-03  2027/03
    private static final Pattern YM = Pattern.compile("(?<![0-9./-])(20[0-9]{2})[./-]([01]?[0-9])(?![0-9./-])");
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
        String t = clean(rawText);
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
            return v.replaceAll("[-/]+$", "");
        }
        return "";
    }
}
