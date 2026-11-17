package survivalrebalance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A CSV parse that keeps every cell's character span, so one cell can be rewritten alone. */
final class Csv {
    static final class Cell {
        int start, end;   // raw span, including quotes
        boolean quoted;
        String value;     // unquoted, unescaped
    }

    static final class Row {
        int start, end;   // span of the row text, without its line terminator
        final List<Cell> cells = new ArrayList<>();
        boolean comment() { return !cells.isEmpty() && cells.get(0).value.startsWith("#"); }
        boolean blank() {
            for (Cell c : cells) if (!c.value.trim().isEmpty()) return false;
            return true;
        }
    }

    final String text;
    final List<Row> rows = new ArrayList<>();

    Csv(String text) {
        this.text = text;
        int i = 0, n = text.length();
        while (i < n) {
            Row r = new Row();
            r.start = i;
            while (true) {
                Cell c = new Cell();
                c.start = i;
                StringBuilder v = new StringBuilder();
                if (i < n && text.charAt(i) == '"') {
                    c.quoted = true;
                    i++;
                    while (i < n) {
                        char ch = text.charAt(i);
                        if (ch == '"') {
                            if (i + 1 < n && text.charAt(i + 1) == '"') { v.append('"'); i += 2; continue; }
                            i++;
                            break;
                        }
                        v.append(ch);
                        i++;
                    }
                    // characters after the closing quote, up to the separator, belong to the cell
                    while (i < n && text.charAt(i) != ',' && text.charAt(i) != '\n' && text.charAt(i) != '\r') v.append(text.charAt(i++));
                } else {
                    while (i < n && text.charAt(i) != ',' && text.charAt(i) != '\n' && text.charAt(i) != '\r') v.append(text.charAt(i++));
                }
                c.end = i;
                c.value = v.toString();
                r.cells.add(c);
                if (i < n && text.charAt(i) == ',') { i++; continue; }
                break;
            }
            r.end = i;
            if (i < n && text.charAt(i) == '\r') i++;
            if (i < n && text.charAt(i) == '\n') i++;
            rows.add(r);
        }
    }

    List<String> header() {
        List<String> h = new ArrayList<>();
        if (rows.isEmpty()) return h;
        for (Cell c : rows.get(0).cells) h.add(c.value.trim());
        return h;
    }

    int column(String name) throws Failure {
        List<String> h = header();
        int idx = -1, count = 0;
        for (int i = 0; i < h.size(); i++) if (h.get(i).equals(name.trim())) { if (idx < 0) idx = i; count++; }
        if (count == 0) throw new Failure("column '" + name + "' not in the header");
        if (count > 1) throw new Failure("column '" + name + "' appears " + count + " times in the header");
        return idx;
    }

    /** Index of the one data row whose key columns hold the key values. Comment rows are skipped. */
    int findRow(Map<String, String> key) throws Failure {
        if (key.isEmpty()) throw new Failure("empty key");
        int[] cols = new int[key.size()];
        String[] vals = new String[key.size()];
        int k = 0;
        for (Map.Entry<String, String> e : key.entrySet()) { cols[k] = column(e.getKey()); vals[k] = e.getValue(); k++; }
        int found = -1, count = 0;
        for (int r = 1; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (row.comment()) continue;
            boolean ok = true;
            for (int j = 0; j < cols.length && ok; j++) {
                if (cols[j] >= row.cells.size()) { ok = false; break; }
                ok = Text.lf(row.cells.get(cols[j]).value).equals(Text.lf(vals[j]));
            }
            if (ok) { if (found < 0) found = r; count++; }
        }
        if (count == 0) throw new Failure("no row with " + key);
        if (count > 1) throw new Failure(count + " rows match " + key + ", the key must select one");
        return found;
    }

    String rowText(int r) { return text.substring(rows.get(r).start, rows.get(r).end); }

    /** The cell's new raw text, quoted the way the original was (or when the value needs it). */
    static String encode(String value, boolean quoted) {
        boolean need = quoted || value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!need) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static boolean numericEquals(String cell, BigDecimal v) {
        BigDecimal d = Json.number(cell.trim());
        return d != null && d.compareTo(v) == 0;
    }
}
