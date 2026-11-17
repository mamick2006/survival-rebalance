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
import java.util.TreeMap;

/**
 * manifest --patches <sealed dir> --game-version <v> --out <manifest.json> [--checked <file>[;<file>]]
 *          [--changes <changes.json>[;<changes.json>]] [--index <file>]
 *
 * Writes the tool's manifest (the game version the patch set was tested on, and the mods that
 * were checked and need no change) and, with --index, the companion mod's index (every mod the
 * patch set changes, with its tested versions and its number of changes).
 *
 * The checked mods come from --checked: JSON files of the form {"mods": [{"id": .., "name": ..}]}.
 * A mod that needs no change is supported at any version, so it carries no tested version. With
 * --changes, every mod there with no changes must also be in a --checked file, so the list
 * cannot drift from the analysis.
 */
final class Manifests {
    private Manifests() {}

    /** Every mod the tool supports, by name: the ones it patches and the ones checked that need no change. */
    static List<Object> supported(List<Patch> ps, Map<String, String> checked) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Patch p : ps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.modId);
            m.put("name", p.modName);
            m.put("tested_versions", new ArrayList<Object>(p.tested));
            m.put("changes", new Json.RawNum(String.valueOf(p.changes.size())));
            l.add(m);
        }
        for (Map.Entry<String, String> e : checked.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey());
            m.put("name", e.getValue());
            m.put("any_version", true);
            m.put("changes", new Json.RawNum("0"));
            l.add(m);
        }
        l.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return new ArrayList<Object>(l);
    }

    @SuppressWarnings("unchecked")
    static int run(Main.Opts o, PrintStream out) throws Exception {
        Path pdir = Main.path(Build.req(o, "--patches")), dst = Main.path(Build.req(o, "--out"));
        String gv = Build.req(o, "--game-version");
        List<Patch> ps = new ArrayList<>();
        for (Path f : Build.jsonFiles(pdir)) ps.add(Patch.load(f));
        Map<String, String> checked = new TreeMap<>();
        if (o.get("--checked") != null) for (String f : o.get("--checked").split(";")) {
            if (f.isBlank()) continue;
            Path cf = Main.path(f.trim());
            Object doc;
            try { doc = Json.toJava(Json.parse(Text.decode(Files.readAllBytes(cf)).body)); }
            catch (IOException | Json.ParseException e) { throw new Failure("cannot read " + cf + ": " + e.getMessage()); }
            Object mods = doc instanceof Map ? ((Map<String, Object>) doc).get("mods") : null;
            if (!(mods instanceof List)) throw new Failure(cf + " holds no \"mods\" list");
            for (Object x : (List<Object>) mods) {
                if (!(x instanceof Map) || ((Map<String, Object>) x).get("id") == null) throw new Failure(cf + ": every entry needs an id");
                Map<String, Object> m = (Map<String, Object>) x;
                String id = String.valueOf(m.get("id"));
                checked.put(id, m.get("name") == null ? id : String.valueOf(m.get("name")));
            }
        }
        List<String> missing = new ArrayList<>();
        if (o.get("--changes") != null) for (String cs : o.get("--changes").split(";")) {
            if (cs.isBlank()) continue;
            Path cf = Main.path(cs.trim());
            if (Files.isRegularFile(cf)) {
                Object doc;
                try { doc = Json.toJava(Json.parse(Text.decode(Files.readAllBytes(cf)).body)); }
                catch (IOException | Json.ParseException e) { throw new Failure("cannot read " + cf + ": " + e.getMessage()); }
                Object mods = doc instanceof Map ? ((Map<String, Object>) doc).get("mods") : null;
                if (mods instanceof List) for (Object x : (List<Object>) mods) {
                    if (!(x instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) x;
                    Object ch = m.get("changes");
                    boolean none = ch instanceof List ? ((List<Object>) ch).isEmpty() : ch != null && String.valueOf(ch).equals("0");
                    if (m.get("mod_id") != null && none && !checked.containsKey(String.valueOf(m.get("mod_id")))) missing.add(String.valueOf(m.get("mod_id")));
                }
            } else out.println("note: " + cf + " not found, so the checked list is not compared with it");
        }
        for (Patch p : ps) { checked.remove(p.modId); missing.remove(p.modId); }
        if (!missing.isEmpty()) throw new Failure("these mods need no change in changes.json but are not in a --checked file: " + String.join(", ", missing) + ". Add them to checked_mods.json.");
        Map<String, Object> man = new LinkedHashMap<>();
        man.put("about", "Written by the Survival Rebalance build. The tool reads it to warn about an untested game version and about enabled mods it did not analyse.");
        man.put("tool_version", Main.VERSION);
        man.put("game_version", gv);
        man.put("checked_mods", new ArrayList<Object>(checked.keySet()));
        man.put("supported", supported(ps, checked));
        Files2.atomicWrite(dst, Json.write(man).getBytes(StandardCharsets.UTF_8));
        out.println("manifest: game " + gv + ", " + ps.size() + " patch files, " + checked.size() + " mods checked with no change");
        if (o.get("--index") != null) {
            List<Object> mods = new ArrayList<>();
            List<Patch> sorted = new ArrayList<>(ps);
            sorted.sort((a, b) -> a.modId.compareTo(b.modId));
            for (Patch p : sorted) {
                    Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.modId);
                m.put("name", p.modName);
                m.put("tested_versions", new ArrayList<Object>(p.tested));
                m.put("changes", new Json.RawNum(String.valueOf(p.changes.size())));
                mods.add(m);
            }
            Map<String, Object> idx = new LinkedHashMap<>();
            idx.put("tool_version", Main.VERSION);
            idx.put("game_version", gv);
            idx.put("mods", mods);
            Files2.atomicWrite(Main.path(o.get("--index")), Json.write(idx).getBytes(StandardCharsets.UTF_8));
            out.println("monitor index: " + mods.size() + " mods");
        }
        return 0;
    }
}
