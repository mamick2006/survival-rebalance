package survivalrebalance;

import java.util.List;

/**
 * Guards a CSV file's shape: a patched CSV must keep the number of fields in every row. A comma
 * or a quote written into an unquoted cell splits it, the row's later columns shift, and the game
 * fails to load (a sprite path read from a description column). Called on every changed CSV
 * before it is written, so verify, the build and apply all refuse such an edit.
 */
final class CsvWidth {
    private CsvWidth() {}

    static boolean isCsv(String rel) {
        return rel.toLowerCase(java.util.Locale.ROOT).endsWith(".csv");
    }

    /** Throws when a row's field count differs between the original and the patched text. */
    static void check(String rel, String before, String after) throws Failure {
        if (!isCsv(rel)) return;
        Csv a = new Csv(before), b = new Csv(after);
        if (a.rows.size() != b.rows.size())
            throw new Failure("the edit changes the row count of " + rel + " from " + a.rows.size() + " to " + b.rows.size()
                    + " (a comma, quote or line break inside a cell). The file was left as it was.");
        for (int r = 0; r < a.rows.size(); r++) {
            List<Csv.Cell> ca = a.rows.get(r).cells, cb = b.rows.get(r).cells;
            if (ca.size() != cb.size())
                throw new Failure("the edit changes row \"" + Text.clip(ca.isEmpty() ? "" : ca.get(0).value) + "\" of " + rel
                        + " from " + ca.size() + " to " + cb.size() + " fields (a comma or quote inside a cell). The file was left as it was.");
        }
    }

    /**
     * Seal-time rule for raw text edits on a CSV: a line_sub whose replace token adds a comma or
     * a double quote that its find token did not have can split a cell. csv_cell and
     * csv_cell_sub re-encode their cell and are not raw.
     */
    static String rawSplitRisk(String op, String file, String find, String repl) {
        if (!"line_sub".equals(op) || file == null || !isCsv(file)) return null;
        if (count(repl, ',') > count(find, ',') || count(repl, '"') > count(find, '"'))
            return "line_sub on " + file + " writes a comma or quote into the line: " + Text.clip(repl);
        return null;
    }

    /**
     * The build-time form of the rule, with the mod's original text in hand: a risky raw edit is
     * allowed only where every place it writes sits inside a quoted cell. Returns the refusal, or null.
     */
    static String unquotedRawSplit(String op, String file, String text, String line, String find, String repl) {
        String risk = rawSplitRisk(op, file, find, repl);
        if (risk == null || line == null) return null;
        Csv csv = new Csv(text);
        String want = line.trim();
        boolean seen = false;
        for (Csv.Row r : csv.rows) {
            int ls = r.start;
            while (ls <= r.end) {
                int le = text.indexOf('\n', ls);
                if (le < 0 || le > r.end) le = r.end;
                String raw = text.substring(ls, le);
                if (raw.trim().equals(want)) {
                    seen = true;
                    int lead = raw.indexOf(want);
                    for (int at = raw.indexOf(find, lead); at >= 0; at = raw.indexOf(find, at + 1)) {
                        int off = ls + at;
                        boolean quoted = false;
                        for (Csv.Cell c : r.cells) if (off >= c.start && off < c.end) { quoted = c.quoted; break; }
                        if (!quoted) return risk + " (the cell is not quoted)";
                    }
                }
                if (le >= r.end) break;
                ls = le + 1;
            }
        }
        return seen ? null : risk;
    }

    static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
