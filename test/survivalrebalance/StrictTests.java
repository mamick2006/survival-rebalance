package survivalrebalance;

import survivalrebalance.gui.RunReport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static survivalrebalance.Tests.check;
import static survivalrebalance.Tests.game;
import static survivalrebalance.Tests.hashes;
import static survivalrebalance.Tests.mod;
import static survivalrebalance.Tests.read;
import static survivalrebalance.Tests.run;

/**
 * The version rule: a supported mod whose installed version is not one its patch was tested
 * with is skipped, everywhere and with no way to switch the rule off. A mod that needs no
 * change is supported at any version.
 */
final class StrictTests {
    private StrictTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("strict: another version is skipped, the tested one beside it is patched", StrictTests::skipBeside);
        t.put("strict: no customize line, flag or selection forces the patch", StrictTests::cannotForce);
        t.put("strict: a patched mod that becomes another version is put back", StrictTests::becameOther);
        t.put("strict: a folder replaced by another version is skipped, its saved settings go back", StrictTests::replacedByOther);
        t.put("strict: a mod that needs no change is supported at any version", StrictTests::checkedAnyVersion);
        t.put("strict: the manifest lists checked mods from their file and refuses a gap", StrictTests::manifestChecked);
        t.put("strict: the result dialog has its own Skipped block", StrictTests::resultDialog);
        t.put("strict: the released build has the rule on and updates on", StrictTests::releasedBuild);
    }

    /** A second supported mod at its tested version: one CSV cell. */
    static void addOkMod(Path root) throws Exception {
        Path d = root.resolve("mods").resolve("OkMod");
        Files.createDirectories(d.resolve("data"));
        Files.writeString(d.resolve("mod_info.json"), "{\"id\": \"ok_mod\", \"name\": \"Ok Mod\", \"version\": \"2.0\"}");
        Files.writeString(d.resolve("data/x.csv"), "id,v\nr,5\n");
        Files.writeString(root.resolve("patches/ok_mod.json"), "{\"format\":1,\"mod_id\":\"ok_mod\",\"mod_name\":\"Ok Mod\",\"tested_versions\":[\"2.0\"],\"changes\":["
                + "{\"id\":\"v\",\"title\":\"V lower\",\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/x.csv\",\"key\":{\"id\":\"r\"},\"column\":\"v\",\"expect\":\"5\",\"set\":\"1\"}]}]}");
    }

    /** The bytes of the skipped mod's folder and of the saved settings. */
    static Map<String, String> fxAndSaves(Path root) throws Exception {
        Map<String, String> m = new TreeMap<>(hashes(root));
        m.keySet().removeIf(k -> !(k.startsWith("mods/FxMod-") || k.startsWith("saves/common/LunaSettings/fx_mod")));
        check(!m.isEmpty(), "the fixture has files");
        return m;
    }

    static String json(Path root) throws Exception {
        check(run(root, "status", "--json", "--dev") == 0, "status --json: " + Tests.lastOut);
        return Tests.lastOut;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> modOf(String statusJson, String id) throws Exception {
        Map<String, Object> d = (Map<String, Object>) Api.parseJson(statusJson);
        for (Object o : (List<Object>) d.get("mods")) if (id.equals(((Map<String, Object>) o).get("id"))) return (Map<String, Object>) o;
        return null;
    }

    static final String FACT = "Survival Rebalance was tested with version 1.0 of this mod and does not change version 1.1.";

    static void skipBeside() throws Exception {
        Path root = game("1.1");
        addOkMod(root);
        Path mon = root.resolve("mods").resolve("Survival Rebalance Monitor");
        Files.createDirectories(mon.resolve("data/config"));
        Files.writeString(mon.resolve("mod_info.json"), "{\"id\": \"survival_rebalance_monitor\", \"name\": \"Survival Rebalance Monitor\", \"version\": \"9.0.0\"}");
        Map<String, String> before = fxAndSaves(root), all = hashes(root);

        check(run(root, "apply", "--dev") == 0, "apply succeeds when nothing failed: " + Tests.lastOut);
        String o = Tests.lastOut.replace("\r", "");
        check(o.contains("  Skipped: " + FACT + " Nothing in this mod was changed."), "the skip line: " + o);
        check(o.contains("Fx Mod 1.1: skipped. " + FACT + " This is not a problem in the mod, and its author cannot help with it."), "the warning: " + o);
        check(o.contains("What to do: Install version 1.0, or wait for a Survival Rebalance update. app\\update.bat checks for one."), "the advice: " + o);
        check(o.matches("(?s).*\\n  Skipped\\s+1   Fx Mod 1\\.1 \\(tested with 1\\.0\\)\\n.*") && !o.matches("(?s).*\\n  Failed .*"), "summary: " + o);
        check(fxAndSaves(root).equals(before), "the skipped mod's files and the saved settings are byte-identical");
        check(!Files.exists(mod(root).resolve("survival_rebalance")), "no state folder in the skipped mod");
        Path rec = root.resolve("saves/common/survival_rebalance.json.data");
        check(!Files.exists(rec) || !read(rec).contains("fx_mod"), "the tool's saved record does not name the skipped mod");
        check(read(root.resolve("mods/OkMod/data/x.csv")).contains("r,1"), "the mod of the tested version beside it is patched");

        String idx = read(mon.resolve("data/config/survival_rebalance_index.json"));
        check(idx.contains("\"skips_other_versions\": true") && idx.matches("(?s).*\"id\": \"fx_mod\".*?\"state\": \"skipped\".*"), "monitor index: " + idx);
        check(idx.matches("(?s).*\"id\": \"ok_mod\".*?\"state\": \"patched\".*"), "monitor index, the patched one: " + idx);

        Map<String, Object> fx = modOf(json(root), "fx_mod");
        check("other_version".equals(fx.get("state")) && String.valueOf(fx.get("state_text")).startsWith("Skipped. " + FACT + " This is not a problem in the mod"), "status --json state: " + fx);
        check(Tests.lastOut.contains("The window shows an Update button when there is one."), "the window's advice: " + Tests.lastOut);
        check(run(root, "status", "--dev") == 0 && Tests.lastOut.contains("== Fx Mod 1.1  (FxMod-1.1)  another version") && Tests.lastOut.contains("  Apply skips it. " + FACT), "status: " + Tests.lastOut);
        check(run(root, "check", "--dev") == 2 && Tests.lastOut.contains("Fx Mod 1.1: skipped."), "check warns, exit 2: " + Tests.lastOut);

        check(run(root, "apply", "--dev", "--dry-run") == 0 && Tests.lastOut.contains("  Skipped: " + FACT), "dry run: " + Tests.lastOut);
        check(run(root, "undo") == 0, "undo: " + Tests.lastOut);
        Map<String, String> after = hashes(root);
        after.keySet().removeIf(k -> k.startsWith("mods/Survival Rebalance Monitor"));
        Map<String, String> was = new TreeMap<>(all);
        was.keySet().removeIf(k -> k.startsWith("mods/Survival Rebalance Monitor"));
        check(after.equals(was), "undo after a skip is clean: every file as before apply");
    }

    static void cannotForce() throws Exception {
        Path root = game("1.1");
        Files.writeString(root.resolve("tool").resolve(Customize.FILE),
                "fx_mod = on\nfx_mod:strength = 1\nall:strength = 1\nfx_mod:c-csv = 1\nfx_mod:c-csv#0 = 150\nfx_mod:version = 1.1\nall:versions = any\nall:check_versions = off\n");
        Map<String, String> before = fxAndSaves(root);
        check(run(root, "apply", "--dev", "--force", "--only", "fx_mod") == 0 && Tests.lastOut.contains("  Skipped: " + FACT), "still skipped: " + Tests.lastOut);
        check(!Tests.lastOut.contains("Applied: "), "nothing applied: " + Tests.lastOut);
        check(fxAndSaves(root).equals(before), "nothing written");
    }

    static void becameOther() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0, "apply at the tested version");
        Path mi = mod(root).resolve("mod_info.json");
        String orig = read(mi);
        Files.writeString(mi, orig.replace("{major:1, minor:0}", "\"1.1\""));
        Map<String, Object> fx = modOf(json(root), "fx_mod");
        check("other_version_patched".equals(fx.get("state")) && String.valueOf(fx.get("state_text")).contains("Click Apply to put its original files back."), "state: " + fx);
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Putting its original files back.") && Tests.lastOut.contains("  Skipped: " + FACT + " Its original files were put back."), "apply puts it back: " + Tests.lastOut);
        Files.writeString(mi, orig);
        check(hashes(root).equals(before), "every file and saved setting as before the first apply");
    }

    static void replacedByOther() throws Exception {
        Path root = game("1.0");
        check(run(root, "apply", "--dev") == 0, "apply at the tested version");
        Path sf = root.resolve("saves/common/LunaSettings/fx_mod.json.data");
        check(read(sf).contains("\"fx_mult\": 0.5"), "saved setting changed by the first apply");
        Path m = mod(root);
        Files2.deleteTree(m);
        Tests.writeMod(m, "1.1");
        check("other_version".equals(modOf(json(root), "fx_mod").get("state")), "replaced by another version shows the same state");
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("  Skipped: " + FACT) && !Tests.lastOut.contains("Patching it again"), "skipped: " + Tests.lastOut);
        check(read(sf).contains("\"fx_mult\": 0.75"), "the saved setting the first apply changed is back: " + read(sf));
        check(!Files.exists(m.resolve("survival_rebalance")), "the new folder is untouched");
    }

    static void checkedAnyVersion() throws Exception {
        Path root = game("1.0");
        Path d = root.resolve("mods").resolve("Checked");
        Files.createDirectories(d);
        Files.writeString(d.resolve("mod_info.json"), "{\"id\": \"checked_mod\", \"name\": \"Checked Mod\", \"version\": \"7.7\"}");
        Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\", \"checked_mod\"]}");
        Files.writeString(root.resolve("tool/manifest.json"), "{\"game_version\": \"0.98.5a-RC7\", \"checked_mods\": [\"checked_mod\"],"
                + " \"supported\": [{\"id\": \"checked_mod\", \"name\": \"Checked Mod\", \"any_version\": true, \"changes\": 0}]}");
        Map<String, Object> c = modOf(json(root), "checked_mod");
        check("checked".equals(c.get("state")) && Boolean.TRUE.equals(c.get("any_version")) && List.of().equals(c.get("tested_versions")), "checked at any version: " + c);
        check(String.valueOf(c.get("state_text")).contains("needs no change at any version"), "its text: " + c);
        check(!Tests.lastOut.contains("Checked Mod 7.7"), "no warning names it: " + Tests.lastOut);
        check(run(root, "apply", "--dev") == 0 && !Tests.lastOut.contains("Checked Mod") && !Tests.lastOut.contains("Skipped "), "apply neither warns about it nor skips it: " + Tests.lastOut);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        check(Main.run(new String[]{"supported", "--tool-dir", root.resolve("tool").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8)) == 0, "supported");
        check(b.toString(StandardCharsets.UTF_8).matches("(?s).*Checked Mod\\s+any version\\s+checked, needs no change.*"), "supported says any version: " + b);
    }

    static void manifestChecked() throws Exception {
        Path root = game("1.0");
        Path checked = root.resolve("checked.json"), changes = root.resolve("changes.json"), out = root.resolve("manifest.json");
        Files.writeString(checked, "{\"mods\": [{\"id\": \"own_mod\", \"name\": \"Own Mod\"}, {\"id\": \"lib_mod\", \"name\": \"Lib Mod\"}]}");
        Files.writeString(changes, "{\"mods\": [{\"mod_id\": \"lib_mod\", \"mod_name\": \"Lib Mod\", \"version\": \"3\", \"changes\": []},"
                + " {\"mod_id\": \"fx_mod\", \"mod_name\": \"Fx Mod\", \"version\": \"1.0\", \"changes\": [1]}]}");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        String[] base = {"manifest", "--patches", root.resolve("patches").toString(), "--game-version", "0.98.5a-RC7", "--out", out.toString(), "--changes", changes.toString()};
        check(Main.run(Tests.concat(base, "--checked", checked.toString()), ps) == 0, "manifest: " + b);
        String m = read(out);
        check(m.contains("\"checked_mods\": [\"lib_mod\", \"own_mod\"]") || m.matches("(?s).*\"checked_mods\": \\[\\s*\"lib_mod\",\\s*\"own_mod\"\\s*\\].*"), "checked list: " + m);
        check(m.matches("(?s).*\"id\": \"own_mod\",\\s*\"name\": \"Own Mod\",\\s*\"any_version\": true,\\s*\"changes\": 0.*"), "a checked mod carries no version: " + m);
        check(m.matches("(?s).*\"id\": \"fx_mod\",\\s*\"name\": \"Fx Mod\",\\s*\"tested_versions\": \\[\\s*\"1.0\"\\s*\\].*"), "a patched mod keeps its tested version: " + m);
        Files.writeString(checked, "{\"mods\": [{\"id\": \"own_mod\", \"name\": \"Own Mod\"}]}");
        try {
            Main.run(Tests.concat(base, "--checked", checked.toString()), ps);
            throw new AssertionError("a mod with no changes missing from the checked file must fail the build");
        } catch (Failure f) {
            check(f.getMessage().contains("lib_mod") && f.getMessage().contains("checked_mods.json"), "names the gap: " + f.getMessage());
        }
    }

    static void resultDialog() throws Exception {
        Path root = game("1.1");
        addOkMod(root);
        run(root, "apply", "--dev");
        String o = Tests.lastOut;
        String h = RunReport.headline("Apply", o, 0), s = RunReport.summary("Apply", o, 0);
        check(h.equals("Done. 1 mod rebalanced. 1 mod skipped: another version."), "headline: " + h);
        check(s.contains("Skipped, another version: Fx Mod 1.1 (tested with 1.0)\nSurvival Rebalance does not change a version it was not tested with. This is not a problem in the mod, and its author cannot help with it."), "the Skipped block: " + s);
        check(!s.contains("What failed") && !s.contains("FAILED"), "not under What failed: " + s);
        check(s.contains("The window shows an Update button when there is one."), "the warnings use the window's words: " + s);
    }

    static void releasedBuild() throws Exception {
        check(VersionPolicy.skipsOtherVersions() && VersionPolicy.buildNote() == null && VersionPolicy.updateOff() == null, "the public VersionPolicy is strict and updates");
        check(Main.versionText().equals(Main.VERSION), "no note beside the version");
    }
}
