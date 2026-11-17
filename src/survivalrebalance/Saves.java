package survivalrebalance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved LunaLib settings in saves/common. A saved value shadows the mod's default, so a luna op
 * changes it too. The original value is kept in saves/common/survival_rebalance.json.data,
 * because the mod folder (and its state) may be replaced while the saved setting stays. The same
 * record lists the mods the tool patched, so a folder replaced by an update can be told apart
 * from one that was never patched.
 */
final class Saves {
    private Saves() {}

    static final String RECORD = "survival_rebalance.json.data";

    static Path lunaFile(Path saves, String modId) { return saves.resolve("LunaSettings").resolve(modId + ".json.data"); }

    /** The saved value's token text, or null when the file or key is missing. */
    static String peek(Path f, String key) {
        Json.Node n = node(f, key);
        return n == null ? null : textOf(f, n);
    }

    private static String textOf(Path f, Json.Node n) {
        try { return Text.decode(Files.readAllBytes(f)).body.substring(n.start, n.end); } catch (IOException e) { return null; }
    }

    private static Json.Node node(Path f, String key) {
        if (!Files.isRegularFile(f)) return null;
        try {
            Json.Node root = Json.parse(Text.decode(Files.readAllBytes(f)).body);
            if (!(root instanceof Json.Obj) || ((Json.Obj) root).count(key) != 1) return null;
            Json.Node v = ((Json.Obj) root).get(key);
            return v instanceof Json.Obj || v instanceof Json.Arr ? null : v;
        } catch (IOException | Json.ParseException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadRecord(Path saves) throws Failure {
        Path f = saves.resolve(RECORD);
        Map<String, Object> rec = new LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            try {
                Object o = Json.toJava(Json.parse(Text.decode(Files.readAllBytes(f)).body));
                if (o instanceof Map) rec = (Map<String, Object>) o;
            } catch (IOException | Json.ParseException e) { throw new Failure("cannot read " + f + ": " + e.getMessage()); }
        }
        rec.putIfAbsent("about", "Survival Rebalance keeps the original values of saved mod settings it changed here, so undo can put them back, and the mods it patched.");
        rec.putIfAbsent("settings", new LinkedHashMap<String, Object>());
        rec.putIfAbsent("patched", new LinkedHashMap<String, Object>());
        return rec;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> settings(Map<String, Object> rec) { return (Map<String, Object>) rec.get("settings"); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> patched(Map<String, Object> rec) {
        Object o = rec.get("patched");
        if (!(o instanceof Map)) { o = new LinkedHashMap<String, Object>(); rec.put("patched", o); }
        return (Map<String, Object>) o;
    }

    static void saveRecord(Path saves, Map<String, Object> rec) throws Failure {
        Path f = saves.resolve(RECORD);
        if (settings(rec).isEmpty() && patched(rec).isEmpty()) {
            try { Files.deleteIfExists(f); } catch (IOException e) { throw new Failure("cannot remove " + f + ": " + e.getMessage()); }
            return;
        }
        Files2.atomicWrite(f, Json.write(rec).getBytes(StandardCharsets.UTF_8));
    }

    /** Remember (version != null) or forget that the tool patched this mod. */
    static void markPatched(Path saves, String modId, String version) throws Failure {
        if (saves == null || !Files.isDirectory(saves)) return;
        Map<String, Object> rec = loadRecord(saves);
        Map<String, Object> p = patched(rec);
        if (version != null) {
            if (version.equals(p.get(modId))) return;
            p.put(modId, version);
        } else if (p.remove(modId) == null) return;
        saveRecord(saves, rec);
    }

    /** The version the tool last patched this mod at, or null. Never fails. */
    static String patchedVersion(Path saves, String modId) {
        if (saves == null || !Files.isRegularFile(saves.resolve(RECORD))) return null;
        try {
            Object v = patched(loadRecord(saves)).get(modId);
            return v == null ? null : String.valueOf(v);
        } catch (Failure f) { return null; }
    }

    /** Saved settings the tool set for this mod that now hold another value: key -> {now, written}. */
    @SuppressWarnings("unchecked")
    static Map<String, String[]> drift(Path saves, String modId) {
        Map<String, String[]> out = new LinkedHashMap<>();
        if (saves == null || !Files.isRegularFile(saves.resolve(RECORD))) return out;
        try {
            Map<String, Object> m = (Map<String, Object>) settings(loadRecord(saves)).get(modId);
            if (m == null) return out;
            Path f = lunaFile(saves, modId);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                String written = String.valueOf(((Map<String, Object>) e.getValue()).get("written"));
                String now = peek(f, e.getKey());
                if (now == null) out.put(e.getKey(), new String[]{null, written});
                else if (!now.equals(written)) out.put(e.getKey(), new String[]{now, written});
            }
        } catch (Failure | ClassCastException f) {
            // a damaged record is reported by apply and undo
        }
        return out;
    }

    static String tokenFor(Json.Node cur, String value) throws Failure {
        if (cur instanceof Json.Num) {
            if (Json.number(value) == null) throw new Failure("saved setting is a number but the new value " + value + " is not");
            return value;
        }
        if (cur instanceof Json.Bool) {
            // LunaSettings.csv writes booleans as TRUE or True; the saved file holds JSON true/false
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new Failure("saved setting is true/false but the new value " + value + " is not");
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        return Json.quote(value);
    }

    static Json.Node parseToken(String tok) throws Failure {
        try { return Json.parse(tok); } catch (Json.ParseException e) { throw new Failure("bad value " + tok); }
    }

    /** Lines that will be printed and the writes that will be done, computed before any write. */
    static final class Plan {
        final List<String> lines = new ArrayList<>();
        final Map<Path, byte[]> writes = new LinkedHashMap<>();
        Map<String, Object> record;
        boolean recordChanged;
    }

    /**
     * The saved settings this mod's ops want, and the ones the tool set earlier that no op wants
     * any more (a change switched off or customized away): those are put back.
     */
    @SuppressWarnings("unchecked")
    static Plan planApply(Path saves, String modId, Map<String, String> wanted) throws Failure {
        Plan p = new Plan();
        if (saves == null) return p;
        if (wanted.isEmpty() && !Files.isRegularFile(saves.resolve(RECORD))) return p;
        p.record = loadRecord(saves);
        Map<String, Object> mods = settings(p.record);
        if (wanted.isEmpty() && !mods.containsKey(modId)) return p;
        Path f = lunaFile(saves, modId);
        Text t = null;
        String text = null;
        if (Files.isRegularFile(f)) {
            try { t = Text.decode(Files.readAllBytes(f)); } catch (IOException ex) { throw new Failure("cannot read " + f); }
            text = t.body;
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            if (text == null) break;
            Json.Node root;
            try { root = Json.parse(text); } catch (Json.ParseException ex) { throw new Failure("cannot parse " + f + ": " + ex.getMessage()); }
            if (!(root instanceof Json.Obj) || ((Json.Obj) root).count(e.getKey()) != 1) continue;
            Json.Node cur = ((Json.Obj) root).get(e.getKey());
            String curTok = text.substring(cur.start, cur.end);
            String newTok = tokenFor(cur, e.getValue());
            Map<String, Object> m = (Map<String, Object>) mods.computeIfAbsent(modId, k -> new LinkedHashMap<String, Object>());
            Map<String, Object> entry = (Map<String, Object>) m.get(e.getKey());
            if (entry == null) {
                entry = new LinkedHashMap<>();
                entry.put("original", curTok);
                m.put(e.getKey(), entry);
                p.recordChanged = true;
            }
            if (!newTok.equals(entry.get("written"))) { entry.put("written", newTok); p.recordChanged = true; }
            if (Json.scalarEquals(cur, parseToken(newTok))) {
                p.lines.add("  Saved setting " + e.getKey() + " already holds " + newTok);
                continue;
            }
            text = Ops.setJson(text, List.of(new Json.Str(e.getKey(), true)), cur, null, parseToken(newTok));
            p.lines.add("  Saved setting changed: " + modId + " " + e.getKey() + ": " + curTok + " -> " + newTok);
        }
        Map<String, Object> mine = (Map<String, Object>) mods.get(modId);
        if (mine != null) {
            for (String key : new ArrayList<>(mine.keySet())) {
                if (wanted.containsKey(key)) continue;
                text = putBack(p, f, text, modId, key, (Map<String, Object>) mine.get(key));
                mine.remove(key);
                p.recordChanged = true;
            }
            if (mine.isEmpty()) mods.remove(modId);
        }
        if (t != null && !text.equals(t.body)) p.writes.put(f, t.withBody(text).encode());
        return p;
    }

    /** Put one saved setting back to its original when it still holds what the tool wrote. */
    private static String putBack(Plan p, Path f, String text, String modId, String key, Map<String, Object> entry) throws Failure {
        String orig = String.valueOf(entry.get("original")), written = String.valueOf(entry.get("written"));
        if (text == null) { p.lines.add("  Saved setting " + key + ": the settings file is gone, nothing to put back"); return null; }
        Json.Node root;
        try { root = Json.parse(text); } catch (Json.ParseException ex) { throw new Failure("cannot parse " + f + ": " + ex.getMessage()); }
        Json.Node cur = root instanceof Json.Obj && ((Json.Obj) root).count(key) == 1 ? ((Json.Obj) root).get(key) : null;
        if (cur == null) { p.lines.add("  Saved setting " + key + " is no longer saved, nothing to put back"); return text; }
        String curTok = text.substring(cur.start, cur.end);
        if (curTok.equals(orig)) { p.lines.add("  Saved setting " + key + " already holds its original value " + orig); return text; }
        if (!curTok.equals(written)) {
            p.lines.add("  Saved setting " + key + " left as is: it holds " + curTok + ", not the " + written + " this tool wrote, so you changed it since");
            return text;
        }
        p.lines.add("  Saved setting restored: " + modId + " " + key + ": " + curTok + " -> " + orig);
        return Ops.setJson(text, List.of(new Json.Str(key, true)), cur, null, parseToken(orig));
    }

    @SuppressWarnings("unchecked")
    static Plan planUndo(Path saves, String modId) throws Failure {
        Plan p = new Plan();
        if (saves == null || !Files.isRegularFile(saves.resolve(RECORD))) return p;
        p.record = loadRecord(saves);
        if (patched(p.record).remove(modId) != null) p.recordChanged = true;
        Map<String, Object> mods = settings(p.record);
        Map<String, Object> m = (Map<String, Object>) mods.get(modId);
        if (m == null) return p;
        Path f = lunaFile(saves, modId);
        Text t = null;
        String text = null;
        if (Files.isRegularFile(f)) {
            try { t = Text.decode(Files.readAllBytes(f)); } catch (IOException ex) { throw new Failure("cannot read " + f); }
            text = t.body;
        }
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String r = putBack(p, f, text, modId, e.getKey(), (Map<String, Object>) e.getValue());
            if (text != null) text = r;
        }
        mods.remove(modId);
        p.recordChanged = true;
        if (t != null && !text.equals(t.body)) p.writes.put(f, t.withBody(text).encode());
        return p;
    }

    static void commit(Path saves, Plan p) throws Failure {
        for (Map.Entry<Path, byte[]> w : p.writes.entrySet()) Files2.atomicWrite(w.getKey(), w.getValue());
        if (p.recordChanged) saveRecord(saves, p.record);
    }

    static void print(Plan p, PrintStream out) { for (String l : p.lines) out.println(l); }
}
