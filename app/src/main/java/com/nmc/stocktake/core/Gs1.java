package com.nmc.stocktake.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The square 2D code (GS1 DataMatrix) printed on many medicine boxes. It
 * carries the product number (GTIN), the batch and the expiry exactly, so
 * one scan finds the item and fills both, with no reading of print.
 *
 * As a scanner gives it: "0106001234567890" "17" "280331" "10" "AB123",
 * the variable fields ended by the separator character (GS, 0x1D), or
 * written out as "(01)06001234567890(17)280331(10)AB123". A plain EAN-13
 * or anything else is not GS1 and parse() gives null.
 *
 * The product number is the item's barcode: the same for every batch,
 * unlike the whole 2D code, which changes with each batch. Written as the
 * 13-digit EAN when it starts with 0, so it is the same number the box's
 * ordinary barcode gives.
 */
public final class Gs1 {
    public final String gtin;     // as the item's barcode: 13 digits when it starts with 0
    public final String batch;    // "" when not in the code
    public final String expiry;   // dd/mm/yyyy, or mm/yyyy when the day is 00; "" when not in the code

    private Gs1(String gtin, String batch, String expiry) {
        this.gtin = gtin; this.batch = batch; this.expiry = expiry;
    }

    private static final char GS = '\u001D';
    private static final Pattern BRACKETED = Pattern.compile("\\((\\d{2,4})\\)([^(]*)");

    /** The fields of a GS1 code, or null when it is not one. */
    public static Gs1 parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("]d2") || s.startsWith("]C1") || s.startsWith("]Q3") || s.startsWith("]e0")) s = s.substring(3);
        while (!s.isEmpty() && s.charAt(0) == GS) s = s.substring(1);
        String gtin = "", batch = "", exp = "";
        if (s.startsWith("(")) {
            Matcher m = BRACKETED.matcher(s);
            boolean any = false;
            while (m.find()) {
                any = true;
                String ai = m.group(1), v = m.group(2).trim();
                if (ai.equals("01") || ai.equals("02")) gtin = v;
                else if (ai.equals("10")) batch = v;
                else if (ai.equals("17")) exp = v;
            }
            if (!any) return null;
        } else {
            if (!s.startsWith("01") && !s.startsWith("02")) return null;   // GS1 codes on packs start with the product
            int i = 0;
            while (i + 2 <= s.length()) {
                String ai = s.substring(i, i + 2);
                i += 2;
                int fixed = fixedLength(ai);
                if (fixed > 0) {
                    if (i + fixed > s.length()) return null;
                    String v = s.substring(i, i + fixed);
                    i += fixed;
                    if (ai.equals("01") || ai.equals("02")) gtin = v;
                    else if (ai.equals("17")) exp = v;
                    if (i < s.length() && s.charAt(i) == GS) i++;
                } else if (ai.equals("10") || ai.equals("21") || ai.equals("22") || ai.equals("24")
                        || ai.equals("30") || ai.equals("37") || ai.equals("90") || ai.equals("91")
                        || ai.equals("92") || ai.equals("93") || ai.equals("94") || ai.equals("95")
                        || ai.equals("96") || ai.equals("97") || ai.equals("98") || ai.equals("99")) {
                    // Variable length: up to the separator or the end. AI 24 and 71x are longer
                    // codes ("240", "710"): take their third digit as part of the code.
                    if (ai.equals("24") || ai.equals("71")) i++;
                    int end = s.indexOf(GS, i);
                    if (end < 0) end = s.length();
                    String v = s.substring(i, end);
                    if (ai.equals("10")) batch = v;
                    i = end < s.length() ? end + 1 : end;
                } else if (ai.equals("71")) {
                    i++;                                     // "710".."714": national number, variable
                    int end = s.indexOf(GS, i);
                    i = end < 0 ? s.length() : end + 1;
                } else {
                    break;                                   // something we do not know: keep what we have
                }
            }
        }
        if (!gtin.matches("\\d{14}")) return null;
        return new Gs1(gtin.startsWith("0") ? gtin.substring(1) : gtin, batch.trim(), expiryText(exp));
    }

    private static int fixedLength(String ai) {
        switch (ai) {
            case "00": return 18;
            case "01": case "02": return 14;
            case "11": case "12": case "13": case "15": case "16": case "17": return 6;
            case "20": return 2;
            default: return 0;
        }
    }

    /** YYMMDD as the app writes an expiry; DD 00 means the end of that month. */
    static String expiryText(String yymmdd) {
        if (yymmdd == null || !yymmdd.matches("\\d{6}")) return "";
        int y = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
        int m = Integer.parseInt(yymmdd.substring(2, 4));
        int d = Integer.parseInt(yymmdd.substring(4, 6));
        if (m < 1 || m > 12 || d > 31) return "";
        return d == 0 ? String.format(Locale.ROOT, "%02d/%04d", m, y)
                      : String.format(Locale.ROOT, "%02d/%02d/%04d", d, m, y);
    }

    /**
     * A scanned code as it is kept and compared: the product number when it
     * is a GS1 code, a 14-digit number starting with 0 as its 13-digit
     * EAN, otherwise digits and letters only.
     */
    public static String barcodeKey(String code) {
        Gs1 g = parse(code);
        if (g != null) return g.gtin;
        String c = code == null ? "" : code.replaceAll("[^0-9A-Za-z]", "");
        if (c.matches("0\\d{13}")) c = c.substring(1);
        return c;
    }
}
