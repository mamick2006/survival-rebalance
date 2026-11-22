package survivalrebalance;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** verify: read-only check of one patch file against a mods folder. One PASS or FAIL per op. */
final class Verify {
    private Verify() {}

    static Path modsDir(Main.Opts o) throws Failure {
        if (o.get("--mods") != null) return Main.path(o.get("--mods"));
        if (o.get("--game") != null) return Main.path(o.get("--game")).resolve("mods");
        throw new Failure("give --mods <dir> or --game <dir>");
    }

    static Path savesDir(Main.Opts o) {
        if (o.get("--saves") != null) return Main.path(o.get("--saves"));
        if (o.get("--game") != null) return Main.path(o.get("--game")).resolve("saves").resolve("common");
        return null;
    }

    static int run(Main.Opts o, PrintStream out) throws Exception {
        String pf = o.get("--patch");
        if (pf == null) throw new Failure("verify needs --patch <file>");
        Patch file = Patch.load(Main.path(pf));
        Path mods = modsDir(o);
        Path saves = savesDir(o);
        List<ModInfo> found = ModInfo.byId(ModInfo.scan(mods), file.modId);
        if (found.isEmpty()) {
            out.println("FAIL  mod " + file.modId + " is not installed in " + mods);
            return 1;
        }
        int fails = 0, passes = 0;
        for (ModInfo m : found) {
            // the ops as they read at this version; at a version not on the list, as at the last tested one
            Patch p = file.readings(m.version).get(0);
            boolean tested = p.tested.contains(m.version);
            out.println("mod " + m.id + " version " + m.version + (tested ? " (tested)" : " (NOT in tested_versions " + p.tested + ")") + "  folder " + m.dir.getFileName());
            Workspace ws = new Workspace(m.dir);
            for (Patch.Change c : p.changes) {
                ws.owner = "\"" + c.title + "\" (" + c.id + ")";
                for (int i = 0; i < c.ops.size(); i++) {
                    Json.Obj op = c.ops.get(i);
                    if (op == null) continue; // not at this version
                    String label = c.id + "#" + i + "  " + Ops.describe(op);
                    try {
                        Ops.run(op, ws, true);
                        String note = "";
                        String k = op.str("op");
                        if ((k.equals("class_const") || k.equals("class_string")) && op.str("method_fp") == null) note = "  (no method_fp yet; the build adds it)";
                        out.println("PASS  " + label + note);
                        passes++;
                    } catch (Failure f) {
                        out.println("FAIL  " + label + "  -- " + f.getMessage());
                        fails++;
                    }
                }
            }
            try {
                ws.results();
            } catch (Failure f) {
                out.println("FAIL  writing the edited files: " + f.getMessage());
                fails++;
            }
            if (!ws.luna.isEmpty() && saves != null) {
                try { Saves.planApply(saves, m.id, ws.luna); } catch (Failure f) { out.println("FAIL  saved settings: " + f.getMessage()); fails++; }
            }
            if (!ws.luna.isEmpty()) {
                Path sf = saves == null ? null : Saves.lunaFile(saves, m.id);
                for (var e : ws.luna.entrySet()) {
                    String cur = sf == null ? null : Saves.peek(sf, e.getKey());
                    if (saves == null) out.println("INFO  luna " + e.getKey() + ": no --saves given, saved value not checked");
                    else if (cur == null) out.println("INFO  luna " + e.getKey() + ": no saved value, only the default changes");
                    else out.println("INFO  luna " + e.getKey() + ": saved value " + cur + " would become " + e.getValue());
                }
            }
        }
        out.println((fails == 0 ? "OK" : "FAILED") + ": " + passes + " passed, " + fails + " failed");
        return fails == 0 ? 0 : 1;
    }

    static boolean exists(Path p) { return p != null && Files.exists(p); }
}
