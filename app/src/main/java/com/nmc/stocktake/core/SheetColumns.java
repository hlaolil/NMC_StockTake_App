package com.nmc.stocktake.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the columns of an imported sheet by their headings - the same words
 * the web app's upload looks for, so a sheet that works there works here.
 *
 * The heading row need not be the first: a few title lines above it are
 * skipped. Headings are compared without case, spaces or punctuation, so
 * "Physical Count", "physical count" and "PHYSICAL-COUNT" are the same.
 */
public final class SheetColumns {
    public static final String CODE = "code", MED = "med", ON_SYSTEM = "onsys", COUNTED = "counted",
            EXPIRY = "expiry", BATCH = "batch", NOTE = "note", GROUP = "group",
            QTY = "qty", PACK_SIZE = "pack_size", PACK_PRICE = "pack_price",
            LOCATION = "location", BARCODE = "barcode", LINE = "line",
            RECEIVED = "received", CHECKED = "checked", COUNTED_BY = "counted_by",
            INVOICE_NAME = "invoice_name", ON_LIST = "on_list";

    private static final Map<String, List<String>> WORDS = new HashMap<>();
    static {
        WORDS.put(CODE, Arrays.asList("code", "product code", "med code", "nmc code"));
        WORDS.put(MED, Arrays.asList("medication", "medicine", "item", "item description", "description",
                "product", "product description", "drug", "name", "item name", "medication name", "stock item"));
        WORDS.put(ON_SYSTEM, Arrays.asList("on system", "system", "balance", "system balance",
                "on hand", "stock on hand", "book stock"));
        WORDS.put(COUNTED, Arrays.asList("physical count", "counted", "count", "qty counted",
                "quantity counted"));
        WORDS.put(EXPIRY, Arrays.asList("expiry date", "expiry", "exp", "exp date", "expiration date",
                "use by", "expires"));
        WORDS.put(BATCH, Arrays.asList("batch", "batch no", "batch number", "lot", "lot no", "lot number"));
        WORDS.put(NOTE, Arrays.asList("note", "notes", "remarks", "comment", "comments"));
        WORDS.put(GROUP, Arrays.asList("group", "category", "classification"));
        WORDS.put(QTY, Arrays.asList("quantity", "qty", "qty supplied", "qty delivered", "quantity supplied",
                "quantity delivered", "quantity single units", "units"));
        WORDS.put(PACK_SIZE, Arrays.asList("pack size", "units per pack", "pack qty", "pack"));
        WORDS.put(LOCATION, Arrays.asList("location", "shelf", "shelf location", "bin", "bin location", "place"));
        WORDS.put(BARCODE, Arrays.asList("barcode", "bar code", "gtin", "ean", "ean 13", "scan code"));
        WORDS.put(LINE, Arrays.asList("line", "line no", "line number"));
        WORDS.put(RECEIVED, Arrays.asList("received", "units received", "counted units"));
        WORDS.put(CHECKED, Arrays.asList("checked", "checked on phone", "done"));
        WORDS.put(COUNTED_BY, Arrays.asList("counted by", "checked by"));
        WORDS.put(INVOICE_NAME, Arrays.asList("invoice name", "name on invoice", "supplier name"));
        WORDS.put(ON_LIST, Arrays.asList("on list", "on our list"));
        WORDS.put(PACK_PRICE, Arrays.asList("price per pack", "pack price", "price per pack r"));
    }

    public final int headerRow;
    private final Map<String, Integer> index = new HashMap<>();

    private SheetColumns(int headerRow) {
        this.headerRow = headerRow;
    }

    static String key(String heading) {
        return heading == null ? "" : heading.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The heading row and its columns, or null if no row names a medication column. */
    public static SheetColumns find(List<List<String>> rows) {
        int limit = Math.min(rows.size(), 15);
        for (int r = 0; r < limit; r++) {
            SheetColumns cols = new SheetColumns(r);
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String k = key(row.get(c));
                for (Map.Entry<String, List<String>> e : WORDS.entrySet()) {
                    if (!cols.index.containsKey(e.getKey()) && e.getValue().contains(k)) {
                        cols.index.put(e.getKey(), c);
                    }
                }
            }
            if (cols.has(MED)) return cols;
        }
        return null;
    }

    public boolean has(String field) {
        return index.containsKey(field);
    }

    /** The cell for a field in a row, trimmed; "" when the column or cell is missing. */
    public String get(List<String> row, String field) {
        Integer c = index.get(field);
        if (c == null || c >= row.size()) return "";
        String v = row.get(c);
        return v == null ? "" : v.trim();
    }
}
