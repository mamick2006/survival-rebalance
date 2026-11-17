package survivalrebalance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * customize.txt: what the user switched off, how strong each change is, and direct values.
 * Every line is optional. The tool reads this file and never writes over what the user wrote.
 *
 *   all:strength = 0.5                       every change, halfway
 *   some_mod = off                           skip a mod
 *   some_mod:strength = 0.5                  one mod
 *   some_mod:some-change = off | on | 0.5    one change
 *   some_mod:some-change#2 = 60000           op 2 of that change gets this new value
 *   some_mod:some-change#2.1 = 15%           op 2, text pair 1 (when the op has several)
 *
 * Strength runs from 0 (off) to 1 (as designed). The most specific setting wins.
 */
final class Customize {
    static final String FILE = "customize.txt";
    static final BigDecimal ONE = BigDecimal.ONE;

    static final class Setting {
        Boolean off;          // null: not said
        BigDecimal strength;  // null: not said
        int line;
    }

    static final class Direct {
        int op, pair = -1, line;
        String value, key;
    }

    BigDecimal all;
    /** all:check_updates; null when not said (on). */
    Boolean checkUpdates;
    final Map<String, Setting> mods = new LinkedHashMap<>();
    final Map<String, Map<String, Setting>> changes = new LinkedHashMap<>();
    /** mod -> change -> directs */
    final Map<String, Map<String, List<Direct>>> directs = new LinkedHashMap<>();
    /** Lines that could not be read, as "line N: ..." texts. */
    final List<String> problems = new ArrayList<>();
    final List<String> keys = new ArrayList<>(); // every key read, for validation messages

    // ---------------------------------------------------------------- reading

    static String stripComment(String line) {
        String t = line.trim();
        if (t.startsWith("#")) return "";
        for (int i = 1; i < line.length(); i++)
            if (line.charAt(i) == '#' && Character.isWhitespace(line.charAt(i - 1))) return line.substring(0, i);
        return line;
    }

    static Customize parse(String text) {
        Customize c = new Customize();
        if (text == null) return c;
        String[] lines = Text.lf(text).split("\n", -1);
        for (int n = 0; n < lines.length; n++) c.line(n + 1, stripComment(lines[n]).trim());
        return c;
    }

    private void problem(int n, String raw, String why) {
        problems.add("line " + n + " \"" + Text.clip(raw) + "\" " + why);
    }

    /** "on", "off", or a strength from 0 to 1; null (and a problem) otherwise. */
    private Setting value(int n, String raw, String val, boolean allowOnOff) {
        Setting s = new Setting();
        s.line = n;
        String v = val.toLowerCase(java.util.Locale.ROOT);
        if (allowOnOff && v.equals("off")) { s.off = true; return s; }
        if (allowOnOff && v.equals("on")) { s.off = false; return s; }
        BigDecimal d = Json.number(val);
        if (d == null) {
            problem(n, raw, allowOnOff ? "needs on, off, or a strength from 0 to 1" : "needs a strength from 0 to 1");
            return null;
        }
        if (d.signum() < 0 || d.compareTo(ONE) > 0) {
            problem(n, raw, "has strength " + val + ", but a strength must be from 0 to 1 (1 is as designed, 0 is off)");
            return null;
        }
        s.strength = d;
        return s;
    }

    static final Pattern DIRECT = Pattern.compile("^(.*)#(\\d+)(?:\\.(\\d+))?$");

    private void line(int n, String raw) {
        if (raw.isEmpty()) return;
        int eq = raw.indexOf('=');
        if (eq < 0) { problem(n, raw, "has no \"=\", so it was ignored"); return; }
        String key = raw.substring(0, eq).trim(), val = raw.substring(eq + 1).trim();
        if (key.isEmpty()) { problem(n, raw, "has nothing before \"=\""); return; }
        if (val.isEmpty()) { problem(n, raw, "has nothing after \"=\""); return; }
        keys.add(key);
        int colon = key.indexOf(':');
        if (colon < 0) {
            Setting s = value(n, raw, val, true);
            if (s != null) merge(mods, key, s);
            return;
        }
        String mod = key.substring(0, colon).trim(), rest = key.substring(colon + 1).trim();
        if (mod.isEmpty() || rest.isEmpty()) { problem(n, raw, "needs a mod id and a change id around the \":\""); return; }
        if (rest.equals("strength")) {
            Setting s = value(n, raw, val, false);
            if (s == null) return;
            if (mod.equals("all")) all = s.strength;
            else merge(mods, mod, s);
            return;
        }
        if (mod.equals("all") && rest.equals("check_updates")) {
            String v = val.toLowerCase(java.util.Locale.ROOT);
            if (v.equals("off")) checkUpdates = false;
            else if (v.equals("on")) checkUpdates = true;
            else problem(n, raw, "needs on or off");
            return;
        }
        if (mod.equals("all")) { problem(n, raw, "\"all\" only takes all:strength and all:check_updates"); return; }
        Matcher m = DIRECT.matcher(rest);
        if (m.matches()) {
            Direct d = new Direct();
            d.op = Integer.parseInt(m.group(2));
            d.pair = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
            d.value = val;
            d.line = n;
            d.key = key;
            directs.computeIfAbsent(mod, k -> new LinkedHashMap<>()).computeIfAbsent(m.group(1).trim(), k -> new ArrayList<>()).add(d);
            return;
        }
        Setting s = value(n, raw, val, true);
        if (s != null) merge(changes.computeIfAbsent(mod, k -> new LinkedHashMap<>()), rest, s);
    }

    private static void merge(Map<String, Setting> map, String k, Setting s) {
        Setting have = map.get(k);
        if (have == null) { map.put(k, s); return; }
        if (s.off != null) have.off = s.off;
        if (s.strength != null) have.strength = s.strength;
        have.line = s.line;
    }

    // ---------------------------------------------------------------- effective settings

    boolean modOff(String mod) {
        Setting s = mods.get(mod);
        return s != null && Boolean.TRUE.equals(s.off);
    }

    /** The strength a change runs at: 0 means off. */
    BigDecimal strength(String mod, String change) {
        if (modOff(mod)) return BigDecimal.ZERO;
        Setting cs = changes.getOrDefault(mod, Map.of()).get(change);
        if (cs != null && Boolean.TRUE.equals(cs.off)) return BigDecimal.ZERO;
        if (cs != null && cs.strength != null) return cs.strength;
        Setting ms = mods.get(mod);
        if (ms != null && ms.strength != null) return ms.strength;
        return all != null ? all : ONE;
    }

    boolean changeOff(String mod, String change) { return strength(mod, change).signum() == 0; }

    List<Direct> directsFor(String mod, String change) {
        return directs.getOrDefault(mod, Map.of()).getOrDefault(change, List.of());
    }

    /** Canonical lines of what this customization changes in this patch (for the patch hash). */
    List<String> lines(Patch p, java.util.Set<String> off) {
        List<String> out = new ArrayList<>();
        for (Patch.Change c : p.changes) {
            if (off.contains(c.id)) { out.add(c.id); continue; }
            BigDecimal s = strength(p.modId, c.id);
            if (s.compareTo(ONE) != 0) out.add(c.id + " strength " + plain(s));
            for (Direct d : directsFor(p.modId, c.id)) out.add(c.id + "#" + d.op + (d.pair >= 0 ? "." + d.pair : "") + " = " + d.value);
        }
        return out;
    }

    static String plain(BigDecimal d) {
        String s = d.stripTrailingZeros().toPlainString();
        return s.equals("0.0") ? "0" : s;
    }

    /** Warnings for ids that no patch knows. patches: every patch file, unfiltered. */
    void validate(List<Patch> patches, Warnings w) {
        Map<String, Patch> byId = new LinkedHashMap<>();
        for (Patch p : patches) byId.put(p.modId, p);
        for (String pr : problems) w.warn("customize.txt", pr + ".", "Fix or remove that line. The rest of the file is used.");
        for (Map.Entry<String, Setting> e : mods.entrySet())
            if (!byId.containsKey(e.getKey())) unknownMod(w, e.getKey(), e.getValue().line, byId);
        for (Map.Entry<String, Map<String, Setting>> e : changes.entrySet()) {
            Patch p = byId.get(e.getKey());
            for (Map.Entry<String, Setting> c : e.getValue().entrySet()) {
                if (p == null) { unknownMod(w, e.getKey(), c.getValue().line, byId); continue; }
                if (change(p, c.getKey()) == null) unknownChange(w, p, c.getKey(), c.getValue().line);
            }
        }
        for (Map.Entry<String, Map<String, List<Direct>>> e : directs.entrySet()) {
            Patch p = byId.get(e.getKey());
            for (Map.Entry<String, List<Direct>> c : e.getValue().entrySet()) {
                for (Direct d : c.getValue()) {
                    if (p == null) { unknownMod(w, e.getKey(), d.line, byId); continue; }
                    Patch.Change ch = change(p, c.getKey());
                    if (ch == null) { unknownChange(w, p, c.getKey(), d.line); continue; }
                    if (d.op >= ch.ops.size()) {
                        w.warn("customize.txt", "line " + d.line + ": change " + ch.id + " of " + p.modId + " has ops #0 to #" + (ch.ops.size() - 1) + ", so #" + d.op + " was ignored.",
                                "Run app\\status.bat to see the op numbers of each change.");
                        continue;
                    }
                    String why = directProblem(ch.ops.get(d.op), d);
                    if (why != null) w.warn("customize.txt", "line " + d.line + " (" + d.key + "): " + why + ". The line was ignored.", "Run app\\status.bat to see what each op of that change writes.");
                }
            }
        }
    }

    private static Patch.Change change(Patch p, String id) {
        for (Patch.Change c : p.changes) if (c.id.equals(id)) return c;
        return null;
    }

    private static void unknownMod(Warnings w, String mod, int line, Map<String, Patch> byId) {
        // a line carried over from a version that changed this mod: kept, and not a mistake
        if (History.get().mods.contains(mod)) {
            w.note("customize.txt line " + line + " is for " + mod + ", which this version of the tool does not change, so the line does nothing now. It is kept for a version that has it.");
            return;
        }
        String near = nearest(mod, byId.keySet());
        w.warn("customize.txt", "line " + line + ": no patch is for a mod with the id \"" + mod + "\", so the line does nothing.",
                near != null ? "Did you mean " + near + "? Mod ids are case-sensitive." : "Check the spelling against customize.txt's list of mods. Mod ids are case-sensitive.");
    }

    private static void unknownChange(Warnings w, Patch p, String id, int line) {
        if (History.get().changes.contains(p.modId + ":" + id)) {
            w.note("customize.txt line " + line + " is for the change " + p.modId + ":" + id + ", which this version of the tool does not have, so the line does nothing now. It is kept for a version that has it.");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Patch.Change c : p.changes) ids.add(c.id);
        String near = nearest(id, ids);
        w.warn("customize.txt", "line " + line + ": " + p.modId + " has no change \"" + id + "\", so the line does nothing.",
                near != null ? "Did you mean " + p.modId + ":" + near + "?" : "Check the change id against the list in customize.txt.");
    }

    static String nearest(String s, Iterable<String> options) {
        String best = null;
        int bd = Integer.MAX_VALUE;
        for (String o : options) {
            int d = distance(s.toLowerCase(java.util.Locale.ROOT), o.toLowerCase(java.util.Locale.ROOT));
            if (d < bd) { bd = d; best = o; }
        }
        return best != null && bd <= Math.max(2, s.length() / 3) ? best : null;
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** Why a direct value cannot be used on this op, or null when it can. */
    static String directProblem(Json.Obj op, Direct d) {
        String k = op.str("op");
        if (k == null) return "the op has no kind";
        switch (k) {
            case "csv_row_off": return "op #" + d.op + " switches a row off and has no value to set (use \"= off\" on the change instead)";
            case "class_const": {
                if (d.pair >= 0) return "op #" + d.op + " is a number, it has no text pairs";
                BigDecimal v = Json.number(d.value);
                if (v == null) return "op #" + d.op + " is a " + op.str("type") + " in the code, and " + d.value + " is not a number";
                try { ClassOps.numValue(String.valueOf(op.str("type")), v); } catch (Failure f) { return "op #" + d.op + ": " + f.getMessage(); }
                return null;
            }
            case "csv_cell": case "json_value": case "luna":
                return d.pair >= 0 ? "op #" + d.op + " sets one value, it has no text pairs" : null;
            case "csv_cell_sub": case "class_string": case "line_sub": {
                Json.Arr subs = op.arr("subs");
                int n = subs == null ? 0 : subs.items.size();
                if (d.pair < 0 && n > 1) return "op #" + d.op + " has " + n + " text pairs; write #" + d.op + ".0 to #" + d.op + "." + (n - 1) + " for one of them";
                if (d.pair >= n) return "op #" + d.op + " has text pairs ." + 0 + " to ." + (n - 1);
                if (d.value.indexOf('\n') >= 0) return "the value has a line break";
                return null;
            }
            default: return "unknown op " + k;
        }
    }

    // ---------------------------------------------------------------- the effective op

    /** One op as it will run, plus what to tell the user about it. */
    static final class Eff {
        Json.Obj op;
        /** A text edit (or a value) that could not follow the strength, or null. */
        String cannotFollow;
        boolean direct;
    }

    static final Pattern NUM = Pattern.compile("(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");

    /**
     * The op at strength s (0 < s <= 1) with the user's direct values. Strength 1 with no direct
     * value returns the op itself, untouched.
     */
    static Eff effective(Json.Obj op, BigDecimal s, List<Direct> ds, int index) { return effective(op, s, ds, index, false); }

    /** decimal: the value is a decimal number even when old and new are written as integers (a LunaLib Double). */
    static Eff effective(Json.Obj op, BigDecimal s, List<Direct> ds, int index, boolean decimal) {
        Eff e = new Eff();
        e.op = op;
        List<Direct> mine = new ArrayList<>();
        for (Direct d : ds) if (d.op == index && directProblem(op, d) == null) mine.add(d);
        boolean partial = s.compareTo(ONE) < 0;
        if (!partial && mine.isEmpty()) return e;
        String k = String.valueOf(op.str("op"));
        Direct whole = null;
        for (Direct d : mine) if (d.pair < 0) whole = d;
        e.direct = !mine.isEmpty();
        switch (k) {
            case "class_const": {
                String from = raw(op.get("from")), to = raw(op.get("to"));
                String nv;
                if (whole != null) nv = whole.value;
                else if (from != null && to != null) {
                    String t = String.valueOf(op.str("type"));
                    nv = interp(from, to, s, t.equals("int") || t.equals("long"));
                } else return e;
                e.op = with(op, "to", new Json.Num(nv, new BigDecimal(nv)));
                return e;
            }
            case "csv_cell": case "json_value": case "luna": {
                Json.Node set = op.get("set");
                String expectKey = k.equals("luna") ? "expect_default" : "expect";
                if (whole != null) { e.op = with(op, "set", directNode(k, set, whole.value)); return e; }
                String from = numText(op.get(expectKey)), to = numText(set);
                if (from == null || to == null) {
                    // a word, an id or a setting with no known old value: a switch
                    if (k.equals("luna") && op.get(expectKey) == null && to != null)
                        e.cannotFollow = "the saved setting " + op.str("key") + " is set to its full new value " + to + ", because the tool does not know its old value";
                    return e;
                }
                String nv = interp(from, to, s, !decimal && isInt(from) && isInt(to));
                Json.Node nn = set instanceof Json.Num ? new Json.Num(nv, new BigDecimal(nv)) : new Json.Str(nv, true);
                e.op = with(op, "set", nn);
                return e;
            }
            case "csv_cell_sub": case "class_string": case "line_sub": {
                Json.Arr subs = op.arr("subs");
                if (subs == null) return e;
                Json.Arr ns = new Json.Arr();
                List<String> stuck = new ArrayList<>();
                for (int i = 0; i < subs.items.size(); i++) {
                    Json.Node pn = subs.items.get(i);
                    if (!(pn instanceof Json.Arr) || ((Json.Arr) pn).items.size() < 2) { ns.items.add(pn); continue; }
                    Json.Arr pair = (Json.Arr) pn;
                    String find = Json.display(pair.items.get(0)), rep = Json.display(pair.items.get(1));
                    String nr = null;
                    for (Direct d : mine) if (d.pair == i || (d.pair < 0 && subs.items.size() == 1)) nr = d.value;
                    if (nr == null && partial) {
                        nr = follow(find, rep, s);
                        if (nr == null) { nr = rep; stuck.add("\"" + find + "\" to \"" + rep + "\""); }
                    }
                    if (nr == null) nr = rep;
                    Json.Arr np = new Json.Arr();
                    np.items.add(pair.items.get(0));
                    np.items.add(new Json.Str(nr, true));
                    for (int j = 2; j < pair.items.size(); j++) np.items.add(pair.items.get(j));
                    ns.items.add(np);
                }
                if (!stuck.isEmpty()) e.cannotFollow = "the text edit " + String.join(", ", stuck) + " is applied in full, so this text may not match its number";
                e.op = with(op, "subs", ns);
                return e;
            }
            default:
                return e; // csv_row_off and anything else: a switch
        }
    }

    static Json.Node directNode(String kind, Json.Node set, String v) {
        if (kind.equals("json_value")) {
            BigDecimal d = Json.number(v);
            if (d != null) return new Json.Num(v, d);
            if (v.equals("true") || v.equals("false")) return new Json.Bool(v.equals("true"));
            return new Json.Str(v, true);
        }
        if (set instanceof Json.Num && Json.number(v) != null) return new Json.Num(v, Json.number(v));
        return new Json.Str(v, true);
    }

    static Json.Obj with(Json.Obj op, String key, Json.Node v) {
        Json.Obj n = new Json.Obj();
        boolean put = false;
        for (int i = 0; i < op.keys.size(); i++) {
            n.keys.add(op.keys.get(i));
            if (op.keys.get(i).equals(key)) { n.values.add(v); put = true; }
            else n.values.add(op.values.get(i));
        }
        if (!put) { n.keys.add(key); n.values.add(v); }
        return n;
    }

    static String raw(Json.Node n) { return n instanceof Json.Num ? ((Json.Num) n).raw : null; }

    /** The node's text when it reads as a plain number, else null. */
    static String numText(Json.Node n) {
        if (n instanceof Json.Num) return ((Json.Num) n).raw;
        if (n instanceof Json.Str && Json.number(((Json.Str) n).value.trim()) != null) return ((Json.Str) n).value.trim();
        return null;
    }

    static boolean isInt(String raw) { return raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0; }

    static int decimals(String raw) {
        int dot = raw.indexOf('.');
        if (dot < 0) return 0;
        int e = dot + 1;
        while (e < raw.length() && Character.isDigit(raw.charAt(e))) e++;
        return e - dot - 1;
    }

    /**
     * old + s x (new - old). Integers round half up and stay between old and new. Other numbers
     * keep at most the designed value's decimals plus one, and at least the decimals both had.
     */
    static String interp(String oldRaw, String newRaw, BigDecimal s, boolean integer) {
        BigDecimal o = new BigDecimal(oldRaw.startsWith("+") ? oldRaw.substring(1) : oldRaw);
        BigDecimal n = new BigDecimal(newRaw.startsWith("+") ? newRaw.substring(1) : newRaw);
        if (s.compareTo(ONE) >= 0) return newRaw;
        BigDecimal v = o.add(s.multiply(n.subtract(o)));
        BigDecimal lo = o.min(n), hi = o.max(n);
        if (integer) {
            v = v.setScale(0, RoundingMode.HALF_UP);
            if (v.compareTo(lo) < 0) v = lo.setScale(0, RoundingMode.HALF_UP);
            if (v.compareTo(hi) > 0) v = hi.setScale(0, RoundingMode.HALF_UP);
            return v.toPlainString();
        }
        int max = decimals(newRaw) + 1;
        int keep = Math.min(max, Math.max(decimals(oldRaw), decimals(newRaw)));
        v = v.setScale(max, RoundingMode.HALF_UP);
        if (v.compareTo(lo) < 0) v = lo;
        if (v.compareTo(hi) > 0) v = hi;
        v = v.stripTrailingZeros();
        if (v.scale() < keep) v = v.setScale(keep);
        if (v.scale() < 0) v = v.setScale(0);
        return v.toPlainString();
    }

    /**
     * A text edit at strength s: when find and replace are the same text around the same count of
     * numbers, each number is interpolated and written in the token's own style. Null otherwise.
     */
    static String follow(String find, String rep, BigDecimal s) {
        List<String> fa = new ArrayList<>(), fn = new ArrayList<>(), ra = new ArrayList<>(), rn = new ArrayList<>();
        split(find, fa, fn);
        split(rep, ra, rn);
        if (fn.isEmpty() || fn.size() != rn.size() || !fa.equals(ra)) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < fn.size(); i++) {
            b.append(fa.get(i));
            String a = fn.get(i), z = rn.get(i);
            boolean group = a.indexOf(',') >= 0 || z.indexOf(',') >= 0;
            String ac = a.replace(",", ""), zc = z.replace(",", "");
            String v = interp(ac, zc, s, isInt(ac) && isInt(zc));
            b.append(group ? group(v) : v);
        }
        b.append(fa.get(fn.size()));
        return b.toString();
    }

    private static void split(String s, List<String> text, List<String> nums) {
        Matcher m = NUM.matcher(s);
        int at = 0;
        while (m.find()) {
            text.add(s.substring(at, m.start()));
            nums.add(m.group());
            at = m.end();
        }
        text.add(s.substring(at));
    }

    /** 1234567.5 -> 1,234,567.5 */
    static String group(String plain) {
        int dot = plain.indexOf('.');
        String ip = dot < 0 ? plain : plain.substring(0, dot), fp = dot < 0 ? "" : plain.substring(dot);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ip.length(); i++) {
            if (i > 0 && (ip.length() - i) % 3 == 0) b.append(',');
            b.append(ip.charAt(i));
        }
        return b + fp;
    }

    /** What an op writes, in one short line: "300 -> 225", or the text pairs. */
    static String shows(Json.Obj original, Json.Obj eff) {
        String k = String.valueOf(original.str("op"));
        switch (k) {
            case "class_const": return original.str("type") + " " + original.str("from") + " -> " + eff.str("to");
            case "csv_cell": case "json_value": {
                String from = original.get("expect") != null ? Json.display(original.get("expect")) : "(sealed)";
                return Text.clip(from) + " -> " + Text.clip(Json.display(eff.get("set")));
            }
            case "luna": {
                String from = original.get("expect_default") != null ? Json.display(original.get("expect_default")) : "saved value";
                return "setting " + original.str("key") + ": " + from + " -> " + Json.display(eff.get("set"));
            }
            case "csv_cell_sub": case "class_string": case "line_sub": {
                Json.Arr subs = eff.arr("subs");
                List<String> out = new ArrayList<>();
                if (subs != null) for (Json.Node pn : subs.items)
                    if (pn instanceof Json.Arr && ((Json.Arr) pn).items.size() >= 2)
                        out.add("\"" + Json.display(((Json.Arr) pn).items.get(0)) + "\" -> \"" + Json.display(((Json.Arr) pn).items.get(1)) + "\"");
                return "text " + String.join(", ", out);
            }
            case "csv_row_off": return "row switched off";
            default: return k;
        }
    }

    // ---------------------------------------------------------------- the generated file

    static final String HEADER =
            "# Survival Rebalance: your settings. Every line is optional.\n" +
            "#\n" +
            "# A line that starts with # is ignored. To use a setting, remove the # in\n" +
            "# front of it and change the value. Then press Apply in the window.\n" +
            "#\n" +
            "#   some_mod = off                  skip a whole mod\n" +
            "#   some_mod:some-change = off      skip one change\n" +
            "#   all:strength = 0.5              every change goes halfway\n" +
            "#   some_mod:strength = 0.5         every change of one mod goes halfway\n" +
            "#   some_mod:some-change = 0.5      one change goes halfway\n" +
            "#   some_mod:some-change#2 = 60000  op #2 of that change writes 60000\n" +
            "#\n" +
            "# Strength is a number from 0 to 1. At 1 a change is as designed, at 0 it is\n" +
            "# off, and at 0.5 every number goes halfway from the mod's own value to the\n" +
            "# rebalanced one. The most specific line wins. A direct value (#2) is used\n" +
            "# exactly as you write it; making the matching tooltip text agree is up to\n" +
            "# you. app\\status.bat lists what every change will write.\n" +
            "#\n" +
            "# The tool never changes what you write here. When a new version of the tool\n" +
            "# brings new changes, app\\customize.bat adds them at the end, as comments.\n" +
            "\n" +
            "# all:strength = 1\n" +
            "\n" +
            "# Apply, status and check look on GitHub for a newer version of the tool,\n" +
            "# and say so in the warnings. To stop that, remove the # in front of this line:\n" +
            "# all:check_updates = off\n";

    /** The commented-out section of one mod, or of only the listed changes of it. */
    static String section(Patch p, List<Patch.Change> only, boolean wholeMod) {
        StringBuilder b = new StringBuilder();
        b.append("\n# ==== ").append(p.modName).append("  (").append(p.modId).append(", ").append(p.changes.size()).append(p.changes.size() == 1 ? " change)\n" : " changes)\n");
        if (wholeMod) {
            b.append("# ").append(p.modId).append(" = off\n");
            b.append("# ").append(p.modId).append(":strength = 1\n");
        }
        for (Patch.Change c : only) {
            b.append("#\n");
            b.append("# ").append(oneLine(c.title)).append('\n');
            if (c.before != null) b.append("#   before: ").append(oneLine(c.before)).append('\n');
            if (c.after != null) b.append("#   after:  ").append(oneLine(c.after)).append('\n');
            for (int i = 0; i < c.ops.size(); i++)
                b.append("#   #").append(i).append("  ").append(oneLine(shows(c.ops.get(i), c.ops.get(i)))).append('\n');
            b.append("# ").append(p.modId).append(':').append(c.id).append(" = 1\n");
        }
        return b.toString();
    }

    static String oneLine(String s) { return s.replace("\r", " ").replace("\n", " "); }

    static final Pattern MENTION = Pattern.compile("^\\s*#?\\s*([^\\s#=:]+)(?::([^\\s#=]+?))?(?:#\\d+(?:\\.\\d+)?)?\\s*=");

    /**
     * The text to add to an existing customize.txt: sections for the mods and changes that the file
     * does not mention yet. Empty when the file already covers everything.
     */
    static String additions(String existing, List<Patch> patches, String when) {
        java.util.Set<String> mentioned = new java.util.HashSet<>();
        for (String line : Text.lf(existing).split("\n")) {
            Matcher m = MENTION.matcher(line);
            if (!m.find()) continue;
            mentioned.add(m.group(1));
            if (m.group(2) != null) mentioned.add(m.group(1) + ":" + m.group(2));
        }
        StringBuilder b = new StringBuilder();
        for (Patch p : patches) {
            boolean modNew = !mentioned.contains(p.modId);
            List<Patch.Change> fresh = new ArrayList<>();
            for (Patch.Change c : p.changes) if (!mentioned.contains(p.modId + ":" + c.id)) fresh.add(c);
            if (!modNew && fresh.isEmpty()) continue;
            b.append(section(p, fresh, modNew));
        }
        if (b.length() == 0) return "";
        return "\n# ---- Added by customize.bat on " + when + ": mods and changes that are new since this file was written.\n" + b;
    }

    static String fresh(List<Patch> patches) {
        StringBuilder b = new StringBuilder(HEADER);
        for (Patch p : patches) b.append(section(p, p.changes, true));
        return b.toString();
    }
}
