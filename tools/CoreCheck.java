import com.nmc.stocktake.core.*;
import java.util.*;

/**
 * Checks the counting core without Android: the sums, the CSV, the column
 * finder, the label reader and the export rules. Run from the project folder:
 *
 *   javac -d build/corecheck app/src/main/java/com/nmc/stocktake/core/*.java tools/CoreCheck.java
 *   java -cp build/corecheck CoreCheck
 */
public class CoreCheck {
    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, Object detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + label + (ok ? "" : "  ->  " + detail));
    }

    static String err(String text) {
        try { CountExpression.evaluate(text); return ""; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    public static void main(String[] a) {
        System.out.println("\n--- Counts and sums ---");
        Object[][] sums = {{"34", 34}, {"3x10+4", 34}, {"3 x 10 + 4", 34}, {"3X10+4", 34}, {"3×10+4", 34},
                {"3*10+4", 34}, {"(4+1)*20-3", 97}, {"2x12 + 3x6 + 1", 43}, {"0", 0}, {" 007 ", 7},
                {"10-10", 0}, {"5+", null}, {"3x", null}, {"abc", null}, {"3..4", null}, {"(3+4", null},
                {"3-10", null}, {"1.5", null}};
        for (Object[] s : sums) {
            Integer want = (Integer) s[1];
            Integer got;
            try { got = CountExpression.evaluate((String) s[0]); } catch (IllegalArgumentException e) { got = null; }
            check("\"" + s[0] + "\" -> " + want, Objects.equals(got, want), got);
        }
        check("an empty box is NOT counted (null), not 0", CountExpression.evaluate("  ") == null, "");
        check("a sum below zero says so", err("3-10").contains("below zero"), err("3-10"));
        check("a bad character says where", err("3x10+a").contains("\"a\""), err("3x10+a"));
        check("\"3x10+4\" is a sum, \"34\" is not", CountExpression.isSum("3x10+4") && !CountExpression.isSum("34"), "");

        System.out.println("\n--- CSV ---");
        List<List<String>> rows = Csv.parse("﻿Code,Medication,On system\r\nMED-0001,\"Amlodipine tablets, 10 mg\",1998\r\n"
                + "MED-0002,\"Say \"\"hi\"\"\",3\n\n,,\nMED-3,Plain,4");
        check("a byte-order mark is dropped", rows.get(0).get(0).equals("Code"), rows.get(0));
        check("a name with a comma stays whole", rows.get(1).get(1).equals("Amlodipine tablets, 10 mg"), rows.get(1));
        check("doubled quotes become one", rows.get(2).get(1).equals("Say \"hi\""), rows.get(2));
        check("empty lines are skipped", rows.size() == 4, rows.size());
        String line = Csv.line(Arrays.asList("MED-1", "Amoxicillin capsules, 250 mg", "", "Say \"x\""));
        check("writing quotes what needs it", line.equals("MED-1,\"Amoxicillin capsules, 250 mg\",,\"Say \"\"x\"\"\"\r\n"), line);
        check("what is written reads back the same", Csv.parse(line).get(0).get(3).equals("Say \"x\""), "");

        System.out.println("\n--- Finding columns ---");
        List<List<String>> sheet = Csv.parse("Count sheet ST-1\n\nCODE,Medication,ON SYSTEM,Physical Count,Expiry Date,Batch,Note,Group\n"
                + "MED-1,X,5,,31/03/2027,B1,,Pharmaceuticals");
        SheetColumns cols = SheetColumns.find(sheet);
        check("the heading row is found below a title line (blank lines dropped)", cols != null && cols.headerRow == 1, cols == null ? null : cols.headerRow);
        check("columns are found whatever their case", cols.get(sheet.get(2), SheetColumns.EXPIRY).equals("31/03/2027")
                && cols.get(sheet.get(2), SheetColumns.ON_SYSTEM).equals("5") && cols.get(sheet.get(2), SheetColumns.GROUP).equals("Pharmaceuticals"), "");
        check("a sheet with no medication column is not taken", SheetColumns.find(Csv.parse("A,B\n1,2")) == null, "");

        System.out.println("\n--- Reading labels ---");
        String[][] labels = {
                {"PARACETAMOL 500MG\nBATCH NO: 250306\nMFG: 03/2025\nEXP: 02/2028", "250306", "02/2028"},
                {"Lot 4610 Exp. 2028-06", "4610", "06/2028"},
                {"B.NO. AB2A3062A\nMFD 11/2024\nEXP 10/2026", "AB2A3062A", "10/2026"},
                {"Mfg. Date: 15.04.2024\nExp. Date: 14.04.2027\nBatch: L24294", "L24294", "14/04/2027"},
                {"EXP MAR 2027 LOT KE24425", "KE24425", "03/2027"},
                {"USE BY 31 JAN 2029", "", "31/01/2029"},
                {"MFG 2024-01-10 EXP 2026-12-31", "", "31/12/2026"},
                {"EXPIRY DATE\n06/27\nBATCH\nH0203", "H0203", "06/2027"},
                {"BN: 5T001 03/2024 02/2029", "5T001", "02/2029"},            // no words: the later date
                {"MFG 03/2025", "", ""},                                    // only a manufacture date
                {"EXP: O3/2O27", "", "03/2027"},                            // the camera read 0 as O
                {"BATCH NO. EXP 05/2028", "", "05/2028"},                   // no batch printed after the word
                {"Lot: 24J048  Exp: 09/2027  Mfd: 09/2024", "24J048", "09/2027"},
                {"Ch.-B.: 85414 Verw. bis 01/2027", "85414", "01/2027"},
        };
        for (String[] l : labels) {
            String b = LabelReader.batch(l[0]), e = LabelReader.expiry(l[0]);
            check("label \"" + l[0].replace("\n", " | ") + "\" -> batch " + (l[1].isEmpty() ? "none" : l[1])
                    + ", expiry " + (l[2].isEmpty() ? "none" : l[2]), b.equals(l[1]) && e.equals(l[2]), "batch=" + b + " expiry=" + e);
        }

        System.out.println("\n--- The sheet: import and export ---");
        String countSheet = "Code,Medication,On system,Physical count,Expiry date,Batch,Note,Group\r\n"
                + "MED-0001,\"Amlodipine tablets, 10 mg\",1998,,30/11/2028,1128,,Pharmaceuticals\r\n"
                + "MED-0002,\"Crepe bandage, 150 mm\",24,,31/12/2027,000,,Medical Supplies\r\n"
                + "MED-0003,\"Morphine tablets, 10 mg\",59,,31/01/2027,2577,,Controlled Drugs\r\n"
                + "MED-0004,\"Zinc tablets, 20 mg\",1061,,29/02/2028,KE25138,,Pharmaceuticals\r\n";
        StockSheet st = StockSheet.fromCsv(countSheet, StockSheet.Mode.STOCK_TAKE);
        check("all items read, names whole", st.items.size() == 4 && st.items.get(0).name.equals("Amlodipine tablets, 10 mg"), st.items.size());
        check("the system balance is read", st.items.get(0).onSystem == 1998, st.items.get(0).onSystem);
        check("nothing counted yet", st.countDone() == 0, st.countDone());
        StockSheet.Item am = st.items.get(0), crepe = st.items.get(1), mor = st.items.get(2);
        am.countText = "19x100+98"; am.counted = CountExpression.evaluate(am.countText);
        crepe.countText = "0"; crepe.counted = 0;
        mor.batch = "2601"; mor.expiry = "06/2028";            // label read, count not yet entered
        String out = st.toCsv();
        List<List<String>> back = Csv.parse(out);
        check("the export has the columns the web upload reads",
                back.get(0).equals(Arrays.asList("Code", "Medication", "On system", "Physical count", "Expiry date", "Batch", "Note", "Location", "Barcode")), back.get(0));
        check("only touched items are exported", back.size() == 4, back.size() - 1);
        check("a sum is exported as its result", back.get(1).get(3).equals("1998"), back.get(1));
        check("a count of 0 is exported as 0", back.get(2).get(3).equals("0"), back.get(2));
        check("an item with a new label but no count exports its count BLANK, not 0",
                back.get(3).get(3).isEmpty() && back.get(3).get(5).equals("2601"), back.get(3));
        check("an untouched item is left out entirely", !out.contains("Zinc"), "");
        check("the code goes with each row, so the web app matches by code", back.get(1).get(0).equals("MED-0001"), back.get(1));
        check("next open item skips what is counted", st.nextOpen(0) == 2, st.nextOpen(0));

        StockSheet bad = null; String why = "";
        try { bad = StockSheet.fromCsv("A,B\n1,2", StockSheet.Mode.STOCK_TAKE); } catch (IllegalArgumentException e) { why = e.getMessage(); }
        check("a file that is not a count sheet is refused, with a reason", bad == null && why.contains("Medication"), why);

        System.out.println("\n--- Receiving ---");
        String invoice = "Medication,Quantity,Pack size,Price per pack,Batch,Expiry date\n"
                + "\"Amoxicillin capsules, 250 mg\",20,100,42.37,,\n\"Cetirizine tablets, 10 mg\",10,30,8.20,,\n";
        StockSheet rc = StockSheet.fromCsv(invoice, StockSheet.Mode.RECEIVING);
        rc.items.get(0).batch = "0003"; rc.items.get(0).expiry = "08/2027"; rc.items.get(0).done = true;
        List<List<String>> rb = Csv.parse(rc.toCsv());
        check("receiving keeps every line, quantity and price as imported",
                rb.size() == 3 && rb.get(1).get(3).equals("20") && rb.get(1).get(5).equals("42.37"), rb);
        check("with the batch and expiry read off the box", rb.get(1).get(6).equals("0003") && rb.get(1).get(7).equals("08/2027"), rb.get(1));
        String why2 = "";
        try { StockSheet.fromCsv("Medication,Note\nX,y", StockSheet.Mode.RECEIVING); } catch (IllegalArgumentException e) { why2 = e.getMessage(); }
        check("a receiving list without quantities is refused", why2.contains("Quantity"), why2);


        System.out.println("\n--- Packs, shelf, barcode, expiry ---");
        check("12p+5 with packs of 28 is 341", Objects.equals(CountExpression.evaluate("12p+5", 28), 341), "");
        check("12 p + 5, 12pk+5 and 12P+5 too", Objects.equals(CountExpression.evaluate("12 p + 5", 28), 341)
                && Objects.equals(CountExpression.evaluate("12pk+5", 28), 341)
                && Objects.equals(CountExpression.evaluate("12P+5", 28), 341), "");
        check("2p x 3 is six packs", Objects.equals(CountExpression.evaluate("2p x 3", 10), 60), "");
        String noPack = "";
        try { CountExpression.evaluate("3p", null); } catch (IllegalArgumentException e) { noPack = e.getMessage(); }
        check("p without a pack size is refused, not guessed", noPack.contains("pack size"), noPack);
        check("\"12p\" shows its sum", CountExpression.isSum("12p"), "");
        String sheetCsv = "Code,Medication,On system,Physical count,Expiry date,Batch,Note,Group,Location,Pack size,Barcode\n"
                + "MED-0001,\"Amoxicillin caps, 500 mg\",100,,31/01/2027,A0,,Pharmaceuticals,B10,28,\n"
                + "MED-0002,\"Paracetamol tabs, 500 mg\",500,,,,,Pharmaceuticals,B2,1,6001234567890\n";
        StockSheet ps = StockSheet.fromCsv(sheetCsv, StockSheet.Mode.STOCK_TAKE);
        StockSheet.Item amx = ps.items.get(0);
        check("location, pack size and barcode are read", amx.location.equals("B10") && Objects.equals(amx.packUnits(), 28)
                && ps.items.get(1).barcode.equals("6001234567890"), amx.location);
        check("a pack of 1 is no pack size", ps.items.get(1).packUnits() == null, "");
        check("an item is found by its barcode", ps.findBarcode(" 600 1234567890") == 1 && ps.findBarcode("123") == -1, "");
        check("untouched items with a location on file are not exported", Csv.parse(ps.toCsv()).size() == 1, ps.toCsv());
        amx.location = "B11"; ps.items.get(1).barcode = "6009999999999";
        List<List<String>> pe = Csv.parse(ps.toCsv());
        check("a changed location or barcode is exported, the count left blank",
                pe.size() == 3 && pe.get(0).get(7).equals("Location") && pe.get(0).get(8).equals("Barcode")
                && pe.get(1).get(7).equals("B11") && pe.get(1).get(3).isEmpty()
                && pe.get(2).get(8).equals("6009999999999"), pe);
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 17);
        check("expiry dd/mm/yyyy, mm/yyyy (month end), yyyy-mm-dd",
                java.time.LocalDate.of(2027, 1, 31).equals(Expiry.parse("31/01/2027"))
                && java.time.LocalDate.of(2027, 2, 28).equals(Expiry.parse("02/2027"))
                && java.time.LocalDate.of(2027, 3, 1).equals(Expiry.parse("2027-03-01"))
                && Expiry.parse("rubbish") == null && Expiry.parse("31/02/2027") == null, "");
        check("expired is said plainly", String.valueOf(Expiry.warning("10/2026", today)).startsWith("EXPIRED"), Expiry.warning("10/2026", today));
        check("within 3 months is short-dated", String.valueOf(Expiry.warning("15/01/2027", today)).contains("59 days"),
                Expiry.warning("15/01/2027", today));
        check("further off, no warning", Expiry.warning("03/2027", today) == null && Expiry.warning("", today) == null, "");

        System.out.println("\n--- Receiving from the web app ---");
        String lines = "﻿Line,Code,Medication,Quantity,Pack size,Price per pack,Batch,Expiry date,Barcode,Note,Checked\n"
                + "1,MED-0001,\"Amoxicillin caps, 500 mg\",56,28,40.0,,,,,\n"
                + "2,MED-0002,\"Paracetamol tabs, 500 mg\",100,1,0.5,P1,30/06/2028,6001111111111,,\n";
        StockSheet rw = StockSheet.fromCsv(lines, StockSheet.Mode.RECEIVING);
        StockSheet.Item r1 = rw.items.get(0), r2 = rw.items.get(1);
        check("the web's lines are read with their line numbers and invoiced quantity",
                r1.line.equals("1") && r2.line.equals("2") && r1.invoiceQty.equals("56") && Objects.equals(r1.packUnits(), 28), r1.line);
        check("a box scanned finds its line", rw.findBarcode("6001111111111") == 1, "");
        r1.qty = String.valueOf(CountExpression.evaluate("2p", r1.packUnits())); r1.batch = "AX9"; r1.expiry = "04/2028"; r1.done = true;
        r2.qty = "90"; r2.note = "one box crushed"; r2.done = true;
        check("2p of 28 is the 56 invoiced: no difference", StockSheet.invoiceDifference(r1) == null, StockSheet.invoiceDifference(r1));
        check("90 against 100 is short by 10", "Short by 10 (invoice 100)".equals(StockSheet.invoiceDifference(r2)), StockSheet.invoiceDifference(r2));
        r2.qty = "105";
        check("and 105 is 5 more", "5 more than the invoice (100)".equals(StockSheet.invoiceDifference(r2)), "");
        r2.qty = "90";
        List<List<String>> rx = Csv.parse(rw.toCsv());
        check("the export carries line, quantity, batch, expiry, note and Checked",
                rx.get(0).get(0).equals("Line") && rx.get(0).get(10).equals("Checked")
                && rx.get(1).get(0).equals("1") && rx.get(1).get(3).equals("56") && rx.get(1).get(6).equals("AX9")
                && rx.get(2).get(3).equals("90") && rx.get(2).get(9).equals("one box crushed") && rx.get(2).get(10).equals("yes"), rx);

        String again = "Line,Code,Medication,Quantity,Pack size,Price per pack,Batch,Expiry date,Barcode,Note,Checked,Received\n"
                + "1,,\"Amoxicillin caps, 500 mg\",56,28,40,AX9,30/04/2028,,,yes,56\n"
                + "4,,\"Paracetamol tabs, 500 mg\",100,1,0.5,P1,30/06/2028,,one box crushed,yes,90\n"
                + "5,,\"Cetirizine tabs, 10 mg\",30,30,6,,,,,,\n";
        StockSheet ag = StockSheet.fromCsv(again, StockSheet.Mode.RECEIVING);
        check("lines checked before come back checked, with what was counted and the note",
                ag.items.get(1).done && ag.items.get(1).qty.equals("90") && ag.items.get(1).invoiceQty.equals("100")
                && ag.items.get(1).note.equals("one box crushed") && !ag.items.get(2).done
                && ag.items.get(2).qty.equals("30"), ag.items.get(1).qty);
        check("so the difference still shows", "Short by 10 (invoice 100)".equals(StockSheet.invoiceDifference(ag.items.get(1))), "");
        check("and the line numbers are the web's own, not positions", ag.items.get(1).line.equals("4"), ag.items.get(1).line);
        check("Next not checked goes to the line still to do", ag.nextOpen(0) == 2, ag.nextOpen(0));

        System.out.println("\n--- Several phones ---");
        String shared = "Code,Medication,On system,Physical count,Expiry date,Batch,Note,Group,Location,Pack size,Barcode,Counted by\n"
                + "M1,Item A,10,,,,,,A1,1,,\n"
                + "M2,Item B,10,,,,,,A2,1,,Samsung\n"
                + "M3,Item C,10,,,,,,A3,1,,Samsung\n"
                + "M4,Item D,10,7,,,,,A4,1,,\n";
        StockSheet sh = StockSheet.fromCsv(shared, StockSheet.Mode.STOCK_TAKE);
        check("items another phone counted are read as done here", sh.isDone(sh.items.get(1)) && !sh.isDone(sh.items.get(0))
                && sh.items.get(1).countedBy.equals("Samsung"), "");
        check("this phone's own earlier count comes back", Objects.equals(sh.items.get(3).counted, 7), sh.items.get(3).counted);
        check("moving on from A passes over B and C to D", sh.nextFree(0) == 3, sh.nextFree(0));
        check("and Next not counted does too", sh.nextOpen(0) == -1 || sh.nextOpen(0) == 0, sh.nextOpen(0));
        check("progress counts the other phone's items", sh.countDone() == 3, sh.countDone());
        List<List<String>> se = Csv.parse(sh.toCsv());
        check("only this phone's counts are sent", se.size() == 2 && se.get(1).get(1).equals("Item D"), se);
        String lines2 = "Line,Medication,Quantity,Pack size,Checked,Received,Checked by\n"
                + "1,Item A,10,1,yes,10,Samsung\n2,Item B,5,1,,,\n";
        StockSheet rv = StockSheet.fromCsv(lines2, StockSheet.Mode.RECEIVING);
        check("a line another phone checked arrives checked, but is not sent as this phone's check",
                rv.items.get(0).done && !StockSheet.mine(rv.items.get(0))
                && Csv.parse(rv.toCsv()).get(1).get(10).isEmpty(), Csv.parse(rv.toCsv()).get(1));
        rv.items.get(0).countedBy = ""; // checked again here
        check("checked again here, it is sent", Csv.parse(rv.toCsv()).get(1).get(10).equals("yes"), "");
        System.out.println("\n=========  " + pass + " passed, " + fail + " failed  =========");
        if (fail > 0) System.exit(1);
    }
}
