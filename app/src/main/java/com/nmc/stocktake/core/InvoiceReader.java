package com.nmc.stocktake.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the lines of a paper invoice from a photo's text.
 *
 * The camera gives pieces of text with where they sit on the page. Pieces
 * at the same height are one row of the invoice, read left to right. In a
 * row the numbers at the end are the quantity, the price and the line
 * total; the words before them are the item, with the supplier's item code
 * in front when there is one. The quantity and price are told apart by the
 * sum: quantity x price = total. A row with no such numbers (headings, the
 * address, VAT and totals) is not an invoice line.
 *
 * Everything read is shown to be checked and corrected before it is sent:
 * a camera can misread a figure.
 */
public final class InvoiceReader {
    private InvoiceReader() {}

    /** A piece of text from the photo and where it is. */
    public static final class Piece {
        final String text;
        final float left, top, right, bottom;
        public Piece(String text, float left, float top, float right, float bottom) {
            this.text = text; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        float cy() { return (top + bottom) / 2f; }
    }

    /** One invoice line as read: the quantity as printed (packs, usually). */
    public static final class Line {
        public final String code, name, qty, price, total;
        public Line(String code, String name, String qty, String price, String total) {
            this.code = code; this.name = name; this.qty = qty; this.price = price; this.total = total;
        }
        public Line with(String code, String name, String qty, String price) {
            return new Line(code, name, qty, price, total);
        }
        @Override public String toString() {
            return code + "|" + name + "|" + qty + "|" + price + "|" + total;
        }
    }

    private static final Pattern NUMBER = Pattern.compile("^(R|M|LSL|ZAR)?\\d[\\d,. ]*%?$");
    private static final List<String> NOT_ITEMS = Arrays.asList("TOTAL", "SUBTOTAL", "SUB TOTAL", "VAT", "TAX",
            "BALANCE", "AMOUNT DUE", "TOTAL DUE", "GRAND TOTAL", "DISCOUNT", "INVOICE", "PAGE", "TEL", "FAX",
            "DATE", "ORDER", "ACCOUNT", "DELIVERY", "CARRIED FORWARD", "BROUGHT FORWARD");

    /** The pieces in rows, top to bottom, each row left to right. */
    static List<List<Piece>> rows(List<Piece> pieces) {
        List<Piece> sorted = new ArrayList<>(pieces);
        sorted.sort((a, b) -> Float.compare(a.cy(), b.cy()));
        List<List<Piece>> rows = new ArrayList<>();
        List<float[]> bands = new ArrayList<>();
        for (Piece p : sorted) {
            if (p.text == null || p.text.trim().isEmpty()) continue;
            int at = -1;
            for (int i = rows.size() - 1; i >= 0 && i >= rows.size() - 3; i--) {
                float[] b = bands.get(i);
                if (p.cy() >= b[0] && p.cy() <= b[1]) { at = i; break; }
            }
            if (at < 0) {
                rows.add(new ArrayList<>());
                bands.add(new float[]{p.top, p.bottom});
                at = rows.size() - 1;
            } else {
                float[] b = bands.get(at);
                // keep the band to the row's own height, so a tall piece does not swallow the next row
                b[0] = Math.min(b[0], p.top + (p.bottom - p.top) * 0.25f);
                b[1] = Math.max(b[1], p.bottom - (p.bottom - p.top) * 0.25f);
            }
            rows.get(at).add(p);
        }
        for (List<Piece> r : rows) r.sort((a, b) -> Float.compare(a.left, b.left));
        return rows;
    }

    /** The invoice lines in the photo's text. */
    public static List<Line> read(List<Piece> pieces) {
        List<Line> out = new ArrayList<>();
        for (List<Piece> row : rows(pieces)) {
            StringBuilder sb = new StringBuilder();
            for (Piece p : row) sb.append(p.text).append("  ");
            Line l = line(sb.toString());
            if (l != null) out.add(l);
        }
        return out;
    }

    /** One row of text as an invoice line, or null when it is not one. */
    public static Line line(String rowText) {
        String t = fixDigits(rowText.toUpperCase(Locale.ROOT)).replace('|', ' ').trim();
        // Keep "1 234.50" whole: a space inside a number is a thousands gap only before 3 digits and a decimal.
        t = t.replaceAll("(?<=(?:^|\\s)(?:R|M)?\\d{1,3}) (?=\\d{3}[.,]\\d{2}(?![\\d,.]))", "");
        List<String> tok = new ArrayList<>(Arrays.asList(t.split("\\s+")));
        tok.removeIf(String::isEmpty);
        if (tok.size() < 2) return null;
        // the numbers at the end of the row
        int end = tok.size();
        int start = end;
        while (start > 0 && NUMBER.matcher(tok.get(start - 1)).matches()) start--;
        List<Double> nums = new ArrayList<>();
        List<String> numText = new ArrayList<>();
        for (int i = start; i < end; i++) {
            if (tok.get(i).endsWith("%")) continue;          // a VAT or discount rate, not an amount
            Double v = number(tok.get(i));
            if (v == null) continue;
            nums.add(v);
            numText.add(tok.get(i));
        }
        List<String> words = new ArrayList<>(tok.subList(0, start));
        // A quantity printed first ("2  CETIRIZINE ... 6.00 12.00"): a whole number before the words.
        Double leadQty = null;
        if (!words.isEmpty() && words.get(0).matches("\\d{1,4}") && words.size() > 1) {
            leadQty = Double.valueOf(words.get(0));
        }
        if (words.isEmpty()) return null;
        String joined = String.join(" ", words);
        for (String w : NOT_ITEMS) if (joined.startsWith(w) || joined.equals(w)) return null;
        if (!joined.matches(".*[A-Z]{3}.*")) return null;            // an item has a word in it

        // quantity x price = total, among the numbers at the end (and a quantity in front)
        double[] best = null;          // qty, price, total
        int n = nums.size();
        for (int ti = n - 1; ti >= 0 && best == null; ti--) {
            double total = nums.get(ti);
            for (int qi = -1; qi < ti && best == null; qi++) {
                double q = qi < 0 ? (leadQty == null ? -1 : leadQty) : nums.get(qi);
                if (q <= 0 || q != Math.rint(q)) continue;
                for (int pi = qi + 1; pi < ti; pi++) {
                    double p = nums.get(pi);
                    if (p <= 0) continue;
                    if (close(q * p, total)) { best = new double[]{q, p, total}; if (qi < 0) words.remove(0); break; }
                }
            }
        }
        if (best == null) {
            // No sum to go by: a whole number then the line total ("2 ... 12.00"), or a lone quantity.
            if (n >= 2 && nums.get(0) == Math.rint(nums.get(0)) && nums.get(0) > 0 && nums.get(0) < 10000) {
                double q = nums.get(0), total = nums.get(n - 1);
                best = new double[]{q, n >= 3 ? nums.get(n - 2) : total / q, total};
            } else if (n == 1 && nums.get(0) == Math.rint(nums.get(0)) && nums.get(0) > 0 && nums.get(0) < 10000) {
                best = new double[]{nums.get(0), -1, -1};
            } else if (leadQty != null && n >= 1) {
                words.remove(0);
                double total = nums.get(n - 1);
                best = new double[]{leadQty, total / leadQty, total};
            } else {
                return null;
            }
        }
        // the supplier's code in front: a token with a digit, before the words of the name
        String code = "";
        if (words.size() > 1 && words.get(0).matches("[A-Z0-9][A-Z0-9/-]{2,}") && words.get(0).matches(".*\\d.*")
                && words.get(1).matches(".*[A-Z]{2}.*")) {
            code = words.remove(0);
        }
        String name = String.join(" ", words).replaceAll("\\s+", " ").trim();
        if (name.isEmpty()) return null;
        return new Line(code, name, fmt(best[0], true), best[1] < 0 ? "" : fmt(best[1], false),
                best[2] < 0 ? "" : fmt(best[2], false));
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= Math.max(0.011, b * 0.005);
    }

    /** A printed amount as a number: "1,234.50", "1234,50", "R12.00", "12". */
    static Double number(String s) {
        String t = s.replaceAll("^(R|M|LSL|ZAR)", "").replace(" ", "");
        if (t.isEmpty()) return null;
        int dot = t.lastIndexOf('.'), comma = t.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            if (comma > dot) t = t.replace(".", "").replace(',', '.');
            else t = t.replace(",", "");
        } else if (comma >= 0) {
            t = t.length() - comma - 1 == 2 ? t.replace(',', '.') : t.replace(",", "");
        } else if (t.chars().filter(c -> c == '.').count() > 1) {
            int last = t.lastIndexOf('.');
            t = t.substring(0, last).replace(".", "") + t.substring(last);
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** O and l the camera reads for 0 and 1 inside figures. */
    static String fixDigits(String t) {
        StringBuilder sb = new StringBuilder(t);
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c != 'O' && c != 'L' && c != 'I') continue;
            char p = i > 0 ? sb.charAt(i - 1) : ' ';
            boolean before = Character.isDigit(p)
                    || ((p == '.' || p == ',') && i > 1 && Character.isDigit(sb.charAt(i - 2)));
            char nx = i + 1 < sb.length() ? sb.charAt(i + 1) : ' ';
            boolean after = Character.isDigit(nx) || nx == '.' || nx == ','
                    || (c == 'O' && (nx == ' ' || nx == 'O' || nx == '%'));   // "5L " stays litres
            boolean wordAfter = Character.isLetter(nx) && !(c == 'O' && nx == 'O');
            if (before && after && !wordAfter) sb.setCharAt(i, c == 'O' ? '0' : '1');
        }
        return sb.toString();
    }

    private static String fmt(double v, boolean whole) {
        if (whole && v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** The lines as the web app reads an invoice: quantity as printed, with the price for it. */
    public static String toCsv(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append(Csv.line(Arrays.asList("Medication", "Supplier code", "Quantity", "Unit price", "Total")));
        for (Line l : lines) {
            if (l.name.trim().isEmpty()) continue;
            sb.append(Csv.line(Arrays.asList(l.name.trim(), l.code.trim(), l.qty.trim(), l.price.trim(), l.total.trim())));
        }
        return sb.toString();
    }
}
