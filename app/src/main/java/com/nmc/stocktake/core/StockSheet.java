package com.nmc.stocktake.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The list being worked through, read from a sheet and written back as one.
 *
 * Stock take: import the web app's count sheet (Stock Take page, the CSV
 * link). Export gives Code, Medication, On system, Physical count, Expiry
 * date, Batch, Note - the columns the web app's "Read Spreadsheet" reads.
 *
 * Receiving: import a list of what arrived - a delivery's invoice lines from
 * the web app, the invoice template, or any sheet with Medication and
 * Quantity. Each box is checked: the quantity confirmed (in packs if
 * easier), batch and expiry read off it. Export gives every line back with
 * its Line number, so the web app fills in the very line it sent.
 *
 * The one rule that matters most: an item not counted is exported with its
 * count BLANK, never 0. The web app reads a blank as "not counted" and a 0
 * as "none on the shelf" - exporting 0 for every skipped item would zero
 * their stock.
 */
public final class StockSheet {
    public enum Mode { STOCK_TAKE, RECEIVING }

    public static final class Item {
        public String code = "", name = "", group = "", note = "";
        public Integer onSystem;            // stock take: the system balance, if the sheet gives it
        public String countText = "";       // what was typed, e.g. "3x10+4"
        public Integer counted;             // null = not counted yet; 0 = counted, none there
        public String expiry = "", batch = "";
        public String fileExpiry = "", fileBatch = "";   // as imported, to tell what changed
        public String qty = "", packSize = "", packPrice = "";  // packSize: both modes
        public String line = "", invoiceQty = "";        // receiving: the web's line number, the invoiced units
        public String countedBy = "";                    // other phones that already did this one
        public String location = "", fileLocation = "";  // shelf, e.g. "B3"
        public String barcode = "", fileBarcode = "";    // scanned off the box
        public boolean done;                // receiving: this line checked
        public String invoiceName = "";     // receiving: what the invoice calls it, when not our name
        public String onList = "";          // receiving: "yes" our item, "no" not ours yet, "check" a guess

        /** Receiving: the invoice's name is not one of ours, so the line must be put on our item. */
        public boolean needsOurItem() {
            return onList.equalsIgnoreCase("no");
        }

        /** Receiving: the web app took the invoice's name to be this item, and wants it checked. */
        public boolean guessed() {
            return onList.equalsIgnoreCase("check");
        }

        /** Anything entered that should go back to the web app. */
        public boolean touched() {
            return counted != null || done || !note.isEmpty()
                    || !expiry.equals(fileExpiry) || !batch.equals(fileBatch)
                    || !location.equals(fileLocation) || !barcode.equals(fileBarcode);
        }

        /** The pack size as a number, or null when the sheet gives none. */
        public Integer packUnits() {
            Integer n = wholeNumber(packSize);
            return n != null && n > 1 ? n : null;
        }
    }

    public final Mode mode;
    public final List<Item> items;

    public StockSheet(Mode mode, List<Item> items) {
        this.mode = mode;
        this.items = items;
    }

    /** Read a sheet. Throws IllegalArgumentException with a plain reason. */
    public static StockSheet fromCsv(String text, Mode mode) {
        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) throw new IllegalArgumentException("The file is empty");
        SheetColumns cols = SheetColumns.find(rows);
        if (cols == null) {
            throw new IllegalArgumentException("No \"Medication\" column was found - is this the count sheet CSV?");
        }
        if (mode == Mode.RECEIVING && !cols.has(SheetColumns.QTY)) {
            throw new IllegalArgumentException("A receiving list needs a \"Quantity\" column");
        }
        List<Item> items = new ArrayList<>();
        for (int r = cols.headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String name = cols.get(row, SheetColumns.MED);
            if (name.isEmpty()) continue;
            Item it = new Item();
            it.name = name;
            it.code = cols.get(row, SheetColumns.CODE);
            it.group = cols.get(row, SheetColumns.GROUP);
            it.onSystem = wholeNumber(cols.get(row, SheetColumns.ON_SYSTEM));
            it.expiry = it.fileExpiry = cols.get(row, SheetColumns.EXPIRY);
            it.batch = it.fileBatch = cols.get(row, SheetColumns.BATCH);
            String q = cols.get(row, SheetColumns.QTY);
            Integer qn = wholeNumber(q);                    // "56.0" from a spreadsheet is 56
            it.qty = it.invoiceQty = qn != null ? String.valueOf(qn) : q;
            it.line = cols.get(row, SheetColumns.LINE);
            it.countedBy = cols.get(row, SheetColumns.COUNTED_BY);
            it.invoiceName = cols.get(row, SheetColumns.INVOICE_NAME);
            it.onList = cols.get(row, SheetColumns.ON_LIST).toLowerCase();
            if (mode == Mode.RECEIVING && isYes(cols.get(row, SheetColumns.CHECKED))) {
                // Checked before (this phone or another): carry on from there.
                it.done = true;
                String got = cols.get(row, SheetColumns.RECEIVED);
                Integer gn = wholeNumber(got);
                if (gn != null) it.qty = String.valueOf(gn);
                it.note = cols.get(row, SheetColumns.NOTE);
            }
            it.packSize = cols.get(row, SheetColumns.PACK_SIZE);
            it.packPrice = cols.get(row, SheetColumns.PACK_PRICE);
            it.location = it.fileLocation = cols.get(row, SheetColumns.LOCATION);
            it.barcode = it.fileBarcode = cols.get(row, SheetColumns.BARCODE);
            String pre = cols.get(row, SheetColumns.COUNTED);
            if (mode == Mode.STOCK_TAKE && !pre.isEmpty()) {
                it.countText = pre;
                try { it.counted = CountExpression.evaluate(pre, it.packUnits()); } catch (IllegalArgumentException e) { it.counted = null; }
            }
            items.add(it);
        }
        if (items.isEmpty()) throw new IllegalArgumentException("The sheet has headings but no items");
        return new StockSheet(mode, items);
    }

    static boolean isYes(String s) {
        String t = s == null ? "" : s.trim().toLowerCase();
        return t.equals("yes") || t.equals("y") || t.equals("true") || t.equals("1") || t.equals("x") || t.equals("checked");
    }

    static Integer wholeNumber(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            double d = Double.parseDouble(s.trim());
            return d == Math.rint(d) ? (int) d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The sheet to upload to the web app: only items with something entered. */
    public String toCsv() {
        StringBuilder sb = new StringBuilder();
        if (mode == Mode.STOCK_TAKE) {
            sb.append(Csv.line(Arrays.asList("Code", "Medication", "On system", "Physical count",
                    "Expiry date", "Batch", "Note", "Location", "Barcode")));
            for (Item it : items) {
                if (!it.touched()) continue;
                sb.append(Csv.line(Arrays.asList(it.code, it.name,
                        it.onSystem == null ? "" : String.valueOf(it.onSystem),
                        it.counted == null ? "" : String.valueOf(it.counted),
                        it.expiry, it.batch, it.note, it.location, it.barcode)));
            }
        } else {
            sb.append(Csv.line(Arrays.asList("Line", "Code", "Medication", "Units", "Pack size",
                    "Price per pack", "Batch", "Expiry date", "Barcode", "Note", "Checked")));
            for (Item it : items) {
                sb.append(Csv.line(Arrays.asList(it.line, it.code, it.name, it.qty, it.packSize, it.packPrice,
                        it.batch, it.expiry, it.barcode, it.note, mine(it) ? "yes" : "")));
            }
        }
        return sb.toString();
    }

    /**
     * Receiving: a line this phone checked itself. A line another phone
     * checked comes in already checked, and is not sent back as this
     * phone's check - unless it is checked again here (which clears
     * countedBy), when the two are compared on the web.
     */
    public static boolean mine(Item it) {
        return it.done && it.countedBy.isEmpty();
    }

    /**
     * The next item after `from` that no other phone has done, for moving on
     * after saving: walking a shelf, items another phone counted are passed
     * over. -1 if there is none.
     */
    public int nextFree(int from) {
        for (int i = from + 1; i < items.size(); i++) {
            if (items.get(i).countedBy.isEmpty()) return i;
        }
        return -1;
    }

    public int countDone() {
        int n = 0;
        for (Item it : items) if (isDone(it)) n++;
        return n;
    }

    /** The next item after `from` not yet counted (or checked), or -1. */
    public int nextOpen(int from) {
        for (int i = from + 1; i < items.size(); i++) if (!isDone(items.get(i))) return i;
        for (int i = 0; i <= from && i < items.size(); i++) if (!isDone(items.get(i))) return i;
        return -1;
    }

    /**
     * Receiving: how the quantity entered compares with the invoice, e.g.
     * "Short by 10 (invoice 100)", or null when they agree or either is missing.
     */
    public static String invoiceDifference(Item it) {
        Integer inv = wholeNumber(it.invoiceQty), got = wholeNumber(it.qty);
        if (inv == null || got == null || inv.equals(got)) return null;
        return got < inv ? "Short by " + (inv - got) + " (invoice " + inv + ")"
                         : (got - inv) + " more than the invoice (" + inv + ")";
    }

    /**
     * Receiving: put a line the invoice names its own way on our item. What
     * the invoice called it is kept; the web app remembers it for the next
     * invoice once the line is added.
     */
    public static void putOnOurItem(Item it, String code, String name) {
        if (it.invoiceName.isEmpty()) it.invoiceName = it.name;
        it.code = code == null ? "" : code;
        it.name = name;
        it.onList = "yes";
    }

    /** One of our items, from the web app's medication list. */
    public static final class OurItem {
        public final String code, name, packSize, barcode;
        public OurItem(String code, String name, String packSize, String barcode) {
            this.code = code; this.name = name; this.packSize = packSize; this.barcode = barcode;
        }
    }

    /** Our medication list (Code, Medication, Pack size, Barcode) as the web app sends it. */
    public static List<OurItem> ourItems(String csv) {
        List<List<String>> rows = Csv.parse(csv);
        SheetColumns cols = SheetColumns.find(rows);
        List<OurItem> out = new ArrayList<>();
        if (cols == null) return out;
        for (int r = cols.headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String name = cols.get(row, SheetColumns.MED);
            if (name.isEmpty()) continue;
            out.add(new OurItem(cols.get(row, SheetColumns.CODE), name,
                    cols.get(row, SheetColumns.PACK_SIZE), cols.get(row, SheetColumns.BARCODE)));
        }
        return out;
    }

    /**
     * Our items most like an invoice's name, best first: shared words count,
     * and the same strength (numbers) counts most, so "AMOXIL 500MG CAPS"
     * puts "Amoxicillin caps, 500 mg" near the top. Only a suggestion.
     */
    public static List<OurItem> likely(List<OurItem> ours, String invoiceName, int limit) {
        String[] words = invoiceName.toLowerCase().split("[^a-z0-9]+");
        List<Object[]> scored = new ArrayList<>();
        for (OurItem o : ours) {
            String n = o.name.toLowerCase();
            String bare = n.replaceAll("[^a-z0-9]", "");
            int score = 0;
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (w.matches("[0-9]+.*")) {
                    String num = w.replaceAll("[^0-9].*$", "");
                    if (n.matches("(?s).*(?<![0-9])" + num + "(?![0-9]).*")) score += 3;
                } else if (w.length() >= 4 && bare.contains(w.substring(0, 4))) {
                    score += 2;                         // "amoxil" and "amoxicillin"
                } else if (w.length() >= 3 && bare.contains(w)) {
                    score += 1;
                }
            }
            if (score > 0) scored.add(new Object[]{score, o});
        }
        scored.sort((a, b) -> (Integer) b[0] - (Integer) a[0]);
        List<OurItem> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < limit; i++) out.add((OurItem) scored.get(i)[1]);
        return out;
    }

    /** The item carrying this barcode, or -1. */
    public int findBarcode(String code) {
        String c = cleanBarcode(code);
        if (c.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) if (c.equals(cleanBarcode(items.get(i).barcode))) return i;
        return -1;
    }

    /**
     * Digits and letters only, as the web app keeps them - and from a 2D
     * (GS1) code only its product number, the same for every batch. A code
     * stored whole before this still finds its item.
     */
    public static String cleanBarcode(String code) {
        return Gs1.barcodeKey(code);
    }

    /** One of our items carrying this barcode, or null. */
    public static OurItem findOurBarcode(List<OurItem> ours, String code) {
        String c = cleanBarcode(code);
        if (c.isEmpty()) return null;
        for (OurItem o : ours) if (c.equals(cleanBarcode(o.barcode))) return o;
        return null;
    }

    /** Receiving: the lines still to be put on one of our items (or only guessed), in order. */
    public List<Integer> linesNotOurs() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.needsOurItem() || it.guessed()) out.add(i);
        }
        return out;
    }

    /** The line already on this item of ours, or -1. */
    public int findName(String name) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).name.equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Done here, or by another phone. */
    public boolean isDone(Item it) {
        if (!it.countedBy.isEmpty()) return true;
        return mode == Mode.STOCK_TAKE ? it.counted != null : it.done;
    }
}
