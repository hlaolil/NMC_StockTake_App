package com.nmc.stocktake.core;

/**
 * Works out a count typed as a sum: "34", "3x10+4", "2 x 12 + 7", "(4+1)*20 - 3".
 *
 * Counting a shelf usually means boxes times units plus loose ones, so the
 * count box accepts + - and x (also *, X and the times sign), with brackets.
 * Only whole numbers: a count is never a fraction of a unit.
 *
 * Where the sheet gives the item's pack size, "p" after a number counts
 * packs: with packs of 28, "12p+5" is 12 x 28 + 5 = 341. Without a pack
 * size, "p" is refused rather than guessed.
 *
 * evaluate() returns null for an empty box - "not counted yet", which is
 * different from a count of 0 - and throws IllegalArgumentException with a
 * plain message for anything it cannot work out, so the app can say what is
 * wrong instead of saving a wrong number.
 */
public final class CountExpression {
    private final String s;
    private final Integer packSize;
    private int pos;

    private CountExpression(String s, Integer packSize) {
        this.s = s;
        this.packSize = packSize;
    }

    public static Integer evaluate(String text) {
        return evaluate(text, null);
    }

    /** As evaluate(text), with "p" meaning a pack of packSize units. */
    public static Integer evaluate(String text, Integer packSize) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        t = t.replace('×', '*').replace('x', '*').replace('X', '*');
        CountExpression p = new CountExpression(t, packSize);
        long value = p.sum();
        p.skipSpaces();
        if (p.pos != p.s.length()) {
            throw new IllegalArgumentException("Cannot read \"" + text.trim() + "\" - use numbers with + - x");
        }
        if (value < 0) throw new IllegalArgumentException("The count comes to " + value + " - it cannot be below zero");
        if (value > 10_000_000L) throw new IllegalArgumentException("The count comes to " + value + " - too large");
        return (int) value;
    }

    /** The sum as typed, or "" if it is just a number (for showing "= 34"). */
    public static boolean isSum(String text) {
        return text != null && text.trim().matches(".*\\d.*[-+*xX×(pP].*|.*[(].*");
    }

    private long sum() {
        long v = product();
        while (true) {
            skipSpaces();
            if (peek('+')) { pos++; v += product(); }
            else if (peek('-')) { pos++; v -= product(); }
            else return v;
            check(v);
        }
    }

    private long product() {
        long v = factor();
        while (true) {
            skipSpaces();
            if (peek('*')) { pos++; v *= factor(); check(v); }
            else return v;
        }
    }

    private long factor() {
        skipSpaces();
        if (peek('(')) {
            pos++;
            long v = sum();
            skipSpaces();
            if (!peek(')')) throw new IllegalArgumentException("A bracket is not closed");
            pos++;
            return v;
        }
        int start = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        if (start == pos) {
            String at = pos < s.length() ? "\"" + s.charAt(pos) + "\"" : "the end";
            throw new IllegalArgumentException("Expected a number at " + at);
        }
        if (pos - start > 9) throw new IllegalArgumentException("A number is too long");
        long n = Long.parseLong(s.substring(start, pos));
        skipSpaces();
        if (peek('p') || peek('P')) {
            pos++;
            if (peek('k') || peek('K')) pos++;          // "12pk" too
            if (packSize == null || packSize < 1) {
                throw new IllegalArgumentException("No pack size on the sheet for this item - count in units");
            }
            n *= packSize;
        }
        return n;
    }

    private boolean peek(char c) {
        return pos < s.length() && s.charAt(pos) == c;
    }

    private void skipSpaces() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private static void check(long v) {
        if (Math.abs(v) > 10_000_000_000L) throw new IllegalArgumentException("The count is too large");
    }
}
