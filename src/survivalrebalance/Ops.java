package survivalrebalance;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The op implementations. Each op checks its anchor and edits the workspace, or throws. */
final class Ops {
    private Ops() {}

    static final String LUNA_CSV = "data/config/LunaSettings.csv";

    /** dev: accept class ops that carry no method_fp. */
    static void run(Json.Obj op, Workspace ws, boolean dev) throws Failure {
        String kind = op.str("op");
        if (kind == null) throw new Failure("op has no \"op\"");
        switch (kind) {
            case "csv_cell": csvCell(op, ws, op.str("file"), keyOf(op), op.str("column"), op.get("expect"), op.get("expect_sha256"), op.get("set")); break;
            case "csv_cell_sub": csvCellSub(op, ws); break;
            case "json_value": jsonValue(op, ws); break;
            case "line_sub": lineSub(op, ws); break;
            case "class_const": classConst(op, ws, dev); break;
            case "class_string": classString(op, ws, dev); break;
            case "luna": luna(op, ws); break;
            case "csv_row_off": csvRowOff(op, ws); break;
            default: throw new Failure("unknown op \"" + kind + "\"");
        }
    }

    /** One line naming what an op addresses, for PASS/FAIL lines. */
    static String describe(Json.Obj op) {
        String k = op.str("op");
        if (k == null) return "?";
        switch (k) {
            case "csv_cell": case "csv_cell_sub": return k + " " + op.str("file") + " " + keyText(op) + " ." + op.str("column");
            case "csv_row_off": return k + " " + op.str("file") + " " + keyText(op);
            case "json_value": return k + " " + op.str("file") + " " + pathText(op.arr("path"));
            case "line_sub": return k + " " + op.str("file");
            case "class_const": return k + " " + op.str("class") + "." + op.str("method") + " " + op.str("type") + " " + op.str("from") + " -> " + op.str("to");
            case "class_string": return k + " " + op.str("class") + "." + op.str("method");
            case "luna": return k + " " + op.str("key");
            default: return k;
        }
    }

    static String keyText(Json.Obj op) {
        Json.Obj k = op.obj("key");
        if (k == null) return "{}";
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < k.keys.size(); i++) { if (i > 0) b.append(", "); b.append(k.keys.get(i)).append('=').append(Json.display(k.values.get(i))); }
        return b.append('}').toString();
    }

    static String pathText(Json.Arr a) {
        if (a == null) return "[]";
        StringBuilder b = new StringBuilder();
        for (Json.Node n : a.items) b.append(n instanceof Json.Num ? "[" + Json.display(n) + "]" : "." + Json.display(n));
        return b.toString();
    }

    static Map<String, String> keyOf(Json.Obj op) throws Failure {
        Json.Obj k = op.obj("key");
        if (k == null) throw new Failure("missing \"key\"");
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < k.keys.size(); i++) m.put(k.keys.get(i), Json.display(k.values.get(i)));
        return m;
    }

    /** Compare a text against a literal "expect" or a sealed "expect_sha256". */
    static void anchor(String what, String actual, Json.Node expect, Json.Node sha) throws Failure {
        if (expect != null) {
            if (!Text.lf(actual).equals(Text.lf(Json.display(expect))))
                throw new Failure(what + " is \"" + Text.clip(actual) + "\", expected \"" + Text.clip(Json.display(expect)) + "\"");
        } else if (sha != null) {
            if (!Text.anchorHash(actual).equals(Json.display(sha)))
                throw new Failure(what + " does not match its sealed anchor (the text changed)");
        } else throw new Failure("op has no anchor (" + what + ")");
    }

    static String requireStr(Json.Obj op, String k) throws Failure {
        String v = op.str(k);
        if (v == null) throw new Failure("missing \"" + k + "\"");
        return v;
    }

    // ---------------------------------------------------------------- CSV

    static void csvCell(Json.Obj op, Workspace ws, String file, Map<String, String> key, String column,
                        Json.Node expect, Json.Node sha, Json.Node set) throws Failure {
        if (file == null) throw new Failure("missing \"file\"");
        if (column == null) throw new Failure("missing \"column\"");
        if (set == null) throw new Failure("missing \"set\"");
        Text t = ws.text(file);
        Csv csv = new Csv(t.body);
        int col = csv.column(column);
        int r = csv.findRow(key);
        Csv.Row row = csv.rows.get(r);
        if (col >= row.cells.size()) throw new Failure("row " + key + " has no cell for column " + column);
        Csv.Cell cell = row.cells.get(col);
        String cur = cell.value;
        if (expect instanceof Json.Num) {
            if (!Csv.numericEquals(cur, ((Json.Num) expect).value))
                throw new Failure("cell " + column + " is \"" + Text.clip(cur) + "\", expected " + ((Json.Num) expect).raw);
        } else anchor("cell " + column, cur, expect, sha);
        String nv = set instanceof Json.Num ? ((Json.Num) set).raw : Json.display(set);
        writeCell(ws, file, t, csv, r, col, nv);
    }

    static void writeCell(Workspace ws, String file, Text t, Csv csv, int r, int col, String nv) throws Failure {
        Csv.Cell cell = csv.rows.get(r).cells.get(col);
        String raw = t.body.substring(cell.start, cell.end);
        if (cell.quoted && !raw.equals(Csv.encode(cell.value, true)))
            throw new Failure("cell has unusual quoting, edit it with line_sub instead");
        String tok = Csv.encode(nv, cell.quoted);
        String nb = t.body.substring(0, cell.start) + tok + t.body.substring(cell.end);
        Csv after = new Csv(nb);
        checkCsv(csv, after, r, col, nv, false);
        ws.edit(file, cell.start, cell.end, tok);
    }

    /** After an edit: same rows, same cells, and only the target changed (to the new value). */
    static void checkCsv(Csv before, Csv after, int r, int col, String nv, boolean rowOff) throws Failure {
        if (before.rows.size() != after.rows.size()) throw new Failure("self-check: the edit changed the number of CSV rows");
        for (int i = 0; i < before.rows.size(); i++) {
            Csv.Row a = before.rows.get(i), b = after.rows.get(i);
            if (a.cells.size() != b.cells.size()) throw new Failure("self-check: the edit changed the cells of row " + (i + 1));
            for (int j = 0; j < a.cells.size(); j++) {
                String va = a.cells.get(j).value, vb = b.cells.get(j).value;
                if (i == r && j == col) {
                    if (!vb.equals(nv)) throw new Failure("self-check: the cell reads \"" + Text.clip(vb) + "\" after the edit");
                } else if (!va.equals(vb)) throw new Failure("self-check: the edit changed another cell (row " + (i + 1) + ")");
            }
        }
        if (rowOff && !after.rows.get(r).comment()) throw new Failure("self-check: the row is not a comment after the edit");
    }

    static void csvCellSub(Json.Obj op, Workspace ws) throws Failure {
        String file = requireStr(op, "file");
        String column = requireStr(op, "column");
        Text t = ws.text(file);
        Csv csv = new Csv(t.body);
        int col = csv.column(column);
        Map<String, String> key = keyOf(op);
        int r = csv.findRow(key);
        Csv.Row row = csv.rows.get(r);
        if (col >= row.cells.size()) throw new Failure("row " + key + " has no cell for column " + column);
        String cur = row.cells.get(col).value;
        anchor("cell " + column, cur, op.get("expect"), op.get("expect_sha256"));
        writeCell(ws, file, t, csv, r, col, subs(cur, op.arr("subs")));
    }

    static String subs(String s, Json.Arr subs) throws Failure {
        if (subs == null || subs.items.isEmpty()) throw new Failure("missing \"subs\"");
        for (Json.Node pn : subs.items) {
            if (!(pn instanceof Json.Arr) || ((Json.Arr) pn).items.size() < 2) throw new Failure("each sub must be [find, replace] or [find, replace, count]");
            List<Json.Node> it = ((Json.Arr) pn).items;
            String find = Json.display(it.get(0)), rep = Json.display(it.get(1));
            int want = it.size() > 2 ? ((Json.Num) it.get(2)).value.intValueExact() : 1;
            int have = Text.countOf(s, find);
            if (have != want) throw new Failure("\"" + find + "\" occurs " + have + " times, expected " + want);
            s = s.replace(find, rep);
        }
        return s;
    }

    static void csvRowOff(Json.Obj op, Workspace ws) throws Failure {
        String file = requireStr(op, "file");
        Text t = ws.text(file);
        Csv csv = new Csv(t.body);
        int r = csv.findRow(keyOf(op));
        anchor("row", csv.rowText(r), op.get("expect"), op.get("expect_sha256"));
        Csv.Cell first = csv.rows.get(r).cells.get(0);
        int at = first.quoted ? first.start + 1 : first.start;
        String nb = t.body.substring(0, at) + "#" + t.body.substring(at);
        Csv after = new Csv(nb);
        checkCsv(csv, after, r, 0, "#" + first.value, true);
        ws.edit(file, at, at, "#");
    }

    // ---------------------------------------------------------------- JSON

    static void jsonValue(Json.Obj op, Workspace ws) throws Failure {
        String file = requireStr(op, "file");
        Json.Arr path = op.arr("path");
        if (path == null) throw new Failure("missing \"path\"");
        Json.Node set = op.get("set");
        if (set == null) throw new Failure("missing \"set\"");
        Text t = ws.text(file);
        int[] span = new int[2];
        String nb = setJson(t.body, path.items, op.get("expect"), op.get("expect_sha256"), set, span);
        ws.edit(file, span[0], span[1], nb.substring(span[0], nb.length() - (t.body.length() - span[1])));
    }

    static String setJson(String text, List<Json.Node> path, Json.Node expect, Json.Node sha, Json.Node set) throws Failure {
        return setJson(text, path, expect, sha, set, new int[2]);
    }

    /** Rewrite one scalar of a loose JSON text; returns the new text and puts the old span in span. */
    static String setJson(String text, List<Json.Node> path, Json.Node expect, Json.Node sha, Json.Node set, int[] span) throws Failure {
        Json.Node root;
        try { root = Json.parse(text); } catch (Json.ParseException e) { throw new Failure("cannot parse the file: " + e.getMessage()); }
        Json.Node target;
        try { target = Json.resolve(root, path); } catch (Json.ParseException e) { throw new Failure(e.getMessage()); }
        if (target instanceof Json.Obj || target instanceof Json.Arr) throw new Failure("path names an object or array, not a value");
        if (expect != null) {
            if (!Json.scalarEquals(expect, target)) throw new Failure("value is " + Text.clip(Json.display(target)) + ", expected " + Text.clip(Json.display(expect)));
        } else anchor("value", Json.display(target), null, sha);
        String tok;
        if (set instanceof Json.Num) tok = ((Json.Num) set).raw;
        else if (set instanceof Json.Bool) tok = String.valueOf(((Json.Bool) set).value);
        else if (set instanceof Json.Str) {
            String v = ((Json.Str) set).value;
            boolean wasBare = target instanceof Json.Str && !((Json.Str) target).quoted;
            tok = wasBare && v.matches("[A-Za-z0-9_.+-]+") ? v : Json.quote(v);
        } else throw new Failure("\"set\" must be a number, string or boolean");
        if (target instanceof Json.Str && ((Json.Str) target).quoted && set instanceof Json.Num) tok = Json.quote(tok);
        String nb = text.substring(0, target.start) + tok + text.substring(target.end);
        span[0] = target.start;
        span[1] = target.end;
        // self-check
        Json.Node nroot;
        try { nroot = Json.parse(nb); } catch (Json.ParseException e) { throw new Failure("self-check: the file no longer parses: " + e.getMessage()); }
        Json.Node nt;
        try { nt = Json.resolve(nroot, path); } catch (Json.ParseException e) { throw new Failure("self-check: " + e.getMessage()); }
        if (!Json.scalarEquals(set, nt)) throw new Failure("self-check: the value reads " + Json.display(nt) + " after the edit");
        List<String> a = Json.flatten(text, root), b = Json.flatten(nb, nroot);
        if (a.size() != b.size()) throw new Failure("self-check: the edit changed the file's structure");
        int diff = 0;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) diff++;
        if (diff > 1) throw new Failure("self-check: the edit changed another value");
        return nb;
    }

    // ---------------------------------------------------------------- lines

    static void lineSub(Json.Obj op, Workspace ws) throws Failure {
        String file = requireStr(op, "file");
        Text t = ws.text(file);
        Json.Node want = op.get("line"), sha = op.get("line_sha256");
        int count = op.get("count") == null ? 1 : ((Json.Num) op.get("count")).value.intValueExact();
        String s = t.body;
        List<int[]> spans = new ArrayList<>(); // content start, content end
        int i = 0;
        while (i <= s.length()) {
            int e = i;
            while (e < s.length() && s.charAt(e) != '\n' && s.charAt(e) != '\r') e++;
            spans.add(new int[]{i, e});
            if (e >= s.length()) break;
            i = e + (s.charAt(e) == '\r' && e + 1 < s.length() && s.charAt(e + 1) == '\n' ? 2 : 1);
        }
        List<int[]> hits = new ArrayList<>();
        String wantText = want == null ? null : Json.display(want).trim();
        String wantSha = sha == null ? null : Json.display(sha);
        if (wantText == null && wantSha == null) throw new Failure("op has no anchor (line)");
        for (int[] sp : spans) {
            String line = s.substring(sp[0], sp[1]).trim();
            if (wantText != null ? line.equals(wantText) : Text.anchorHash(line).equals(wantSha)) hits.add(sp);
        }
        if (hits.size() != count) throw new Failure("the line occurs " + hits.size() + " times, expected " + count);
        List<int[]> chosen = new ArrayList<>();
        for (int k : which(op, hits.size())) chosen.add(hits.get(k));
        List<String> repl = new ArrayList<>();
        for (int[] sp : chosen) {
            String nl = subs(s.substring(sp[0], sp[1]), op.arr("subs"));
            if (nl.indexOf('\n') >= 0 || nl.indexOf('\r') >= 0) throw new Failure("a sub would split the line");
            repl.add(nl);
        }
        for (int k = 0; k < chosen.size(); k++) ws.edit(file, chosen.get(k)[0], chosen.get(k)[1], repl.get(k));
    }

    // ---------------------------------------------------------------- classes

    static void checkFp(Json.Obj op, Workspace ws, String jar, String cls, String method, String desc, boolean dev) throws Failure {
        String fp = op.str("method_fp");
        if (fp == null) {
            if (!dev) throw new Failure("op has no method_fp (an authoring file): run it with verify or apply --dev");
            return;
        }
        String have = ClassOps.fingerprintOf(ws.originalClass(jar, cls), method, desc);
        if (!have.equals(fp)) throw new Failure("method " + cls + "." + method + " has changed in this version (fingerprint " + have + ", tested " + fp + ")");
    }

    /** The method an op names, read from the original class. */
    static org.objectweb.asm.tree.MethodNode originalMethod(Workspace ws, String jar, String cls, String method, String desc) throws Failure {
        org.objectweb.asm.tree.ClassNode cn = ClassOps.read(ws.originalClass(jar, cls), org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return ClassOps.findMethod(cn, method, desc);
    }

    static void classConst(Json.Obj op, Workspace ws, boolean dev) throws Failure {
        String jar = requireStr(op, "jar"), cls = requireStr(op, "class").replace('.', '/'), method = requireStr(op, "method");
        String desc = op.str("desc"), type = requireStr(op, "type");
        if (!(op.get("from") instanceof Json.Num) || !(op.get("to") instanceof Json.Num)) throw new Failure("\"from\" and \"to\" must be numbers");
        double from = ((Json.Num) op.get("from")).value.doubleValue();
        Object to = ClassOps.numValue(type, ((Json.Num) op.get("to")).value);
        if (!(op.get("count") instanceof Json.Num)) throw new Failure("missing \"count\"");
        int count = ((Json.Num) op.get("count")).value.intValueExact();
        checkFp(op, ws, jar, cls, method, desc, dev);
        org.objectweb.asm.tree.MethodNode m = originalMethod(ws, jar, cls, method, desc);
        List<ClassOps.Const> cs = ClassOps.constants(m);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) if (cs.get(i).type.equals(type) && ClassOps.numEquals(type, cs.get(i).value, from)) hits.add(i);
        if (hits.size() != count) throw new Failure("the " + type + " " + Json.display(op.get("from")) + " occurs " + hits.size() + " times in " + cls + "." + method + ", expected " + count);
        for (int k : which(op, hits.size())) ws.classEdit(jar, cls, m.name + m.desc, hits.get(k), type, to);
    }

    static void classString(Json.Obj op, Workspace ws, boolean dev) throws Failure {
        String jar = requireStr(op, "jar"), cls = requireStr(op, "class").replace('.', '/'), method = requireStr(op, "method");
        String desc = op.str("desc");
        Json.Node expect = op.get("expect"), sha = op.get("expect_sha256");
        if (expect == null && sha == null) throw new Failure("op has no anchor (expect)");
        int count = op.get("count") == null ? 1 : ((Json.Num) op.get("count")).value.intValueExact();
        checkFp(op, ws, jar, cls, method, desc, dev);
        org.objectweb.asm.tree.MethodNode m = originalMethod(ws, jar, cls, method, desc);
        List<ClassOps.Const> cs = ClassOps.constants(m);
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < cs.size(); i++) {
            if (!(cs.get(i).value instanceof String)) continue;
            String v = (String) cs.get(i).value;
            if (expect != null ? v.equals(Json.display(expect)) : Text.anchorHash(v).equals(Json.display(sha))) hits.add(i);
        }
        if (hits.size() != count) throw new Failure("the string occurs " + hits.size() + " times in " + cls + "." + method + ", expected " + count);
        String nv = subs((String) cs.get(hits.get(0)).value, op.arr("subs"));
        for (int k : which(op, hits.size())) ws.classEdit(jar, cls, m.name + m.desc, hits.get(k), "string", nv);
    }

    // ---------------------------------------------------------------- LunaLib

    static void luna(Json.Obj op, Workspace ws) throws Failure {
        String key = requireStr(op, "key");
        Json.Node set = op.get("set");
        if (set == null) throw new Failure("missing \"set\"");
        if (op.get("csv") instanceof Json.Bool && !((Json.Bool) op.get("csv")).value) {
            // settings registered in code: only the saved value changes
            ws.luna.put(key, Json.display(set));
            return;
        }
        Map<String, String> k = new LinkedHashMap<>();
        k.put("fieldID", key);
        Json.Node expect = op.get("expect_default");
        Json.Node sha = op.get("expect_default_sha256");
        if (expect instanceof Json.Str && Json.number(((Json.Str) expect).value) != null) {
            // numeric defaults compare by value ("0.50" matches "0.5")
            Json.Num n = new Json.Num(((Json.Str) expect).value, Json.number(((Json.Str) expect).value));
            expect = n;
        }
        csvCell(op, ws, LUNA_CSV, k, "defaultValue", expect, sha, set);
        ws.luna.put(key, Json.display(set));
    }

    /** The "which" indexes of an op, or all of 0..total-1 when it has none. */
    static List<Integer> which(Json.Obj op, int total) throws Failure {
        List<Integer> out = new ArrayList<>();
        Json.Arr w = op.arr("which");
        if (w == null) { for (int i = 0; i < total; i++) out.add(i); return out; }
        for (Json.Node n : w.items) {
            if (!(n instanceof Json.Num)) throw new Failure("\"which\" must hold integers");
            int k = ((Json.Num) n).value.intValueExact();
            if (k < 0 || k >= total) throw new Failure("\"which\" index " + k + " is out of range (0 to " + (total - 1) + ")");
            if (!out.contains(k)) out.add(k);
        }
        return out;
    }

    static BigDecimal num(Json.Node n) { return n instanceof Json.Num ? ((Json.Num) n).value : null; }
}