package com.nmc.stocktake.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading and writing CSV the way Excel and the web app do.
 *
 * Almost every NMC item name has a comma in it ("Amlodipine tablets, 10 mg"),
 * so a name is written inside quotes and a reader must not split inside
 * them. A quote inside a quoted value is doubled (""). Lines may end in
 * \n or \r\n, and a file saved by Excel may start with a byte-order mark.
 */
public final class Csv {
    private Csv() {}

    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') { cell.append('"'); i += 2; continue; }
                    quoted = false;
                } else {
                    cell.append(c);
                }
                i++;
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString()); cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                row.add(cell.toString()); cell.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
            } else {
                cell.append(c);
            }
            i++;
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            addRow(rows, row);
        }
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        for (String v : row) {
            if (!v.trim().isEmpty()) { rows.add(row); return; }
        }
        // a completely empty line is skipped
    }

    /** One line of CSV, values quoted where they need to be, ending in \r\n. */
    public static String line(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            String v = values.get(i) == null ? "" : values.get(i);
            if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                    || (!v.isEmpty() && (v.charAt(0) == ' ' || v.charAt(v.length() - 1) == ' '))) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.append("\r\n").toString();
    }
}
