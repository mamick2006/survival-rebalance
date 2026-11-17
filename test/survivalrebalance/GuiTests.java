package survivalrebalance;

import survivalrebalance.gui.RunReport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static survivalrebalance.Tests.check;
import static survivalrebalance.Tests.game;
import static survivalrebalance.Tests.run;

/** What the window relies on: status --json, the window's customize.txt lines, the run report, the launcher's update install. */
final class GuiTests {
    private GuiTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("status --json: states, changes, checked and unsupported mods", GuiTests::statusJson);
        t.put("status --json writes no log and changes nothing", GuiTests::statusJsonQuiet);
        t.put("window edits keep the player's own customize.txt lines", GuiTests::customizeRoundTrip);
        t.put("window edits drop lines that change nothing", GuiTests::customizePrune);
        t.put("run report: summary, failures, saved settings", GuiTests::runReport);
        t.put("launcher installs a staged update: flat 1.2.0 to the app layout", GuiTests::launcherInstall);
        t.put("launcher version matches the tool", GuiTests::launcherVersion);
        t.put("app layout: jar, patches, manifest, monitor in app; player files at the root", GuiTests::appLayout);
        t.put("supported list: manifest, command, generated README", GuiTests::supportedList);
        t.put("one-job bats run from app", GuiTests::batsLayout);
        t.put("window warnings: window words, not supported marked, strength texts one line per mod", GuiTests::windowWarnings);
        t.put("not patched yet counts installed mods that are not enabled", GuiTests::notEnabledCounts);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json() throws Exception {
        int at = Tests.lastOut.indexOf('{');
        check(at >= 0, "no JSON: " + Tests.lastOut);
        return (Map<String, Object>) Api.parseJson(Tests.lastOut.substring(at));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mod(Map<String, Object> doc, String id) {
        for (Object o : (List<Object>) doc.get("mods")) if (id.equals(((Map<String, Object>) o).get("id"))) return (Map<String, Object>) o;
        return null;
    }

    @SuppressWarnings("unchecked")
    static void statusJson() throws Exception {
        Path root = game("1.0");
        Path other = root.resolve("mods").resolve("Other");
        Files.createDirectories(other);
        Files.writeString(other.resolve("mod_info.json"), "{\"id\": \"other_mod\", \"name\": \"Other Mod\", \"version\": \"2.0\"}");
        Path plain = root.resolve("mods").resolve("Plain");
        Files.createDirectories(plain);
        Files.writeString(plain.resolve("mod_info.json"), "{\"id\": \"plain_mod\", \"name\": \"Plain Mod\", \"version\": \"3.1\"}");
        Files.writeString(root.resolve("mods").resolve("enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\", \"other_mod\", \"plain_mod\"]}");
        Files.writeString(root.resolve("tool").resolve("manifest.json"), "{\"game_version\": \"0.98.5a-RC7\", \"checked_mods\": {\"plain_mod\": \"3.1\", \"gone_mod\": \"1\"}}");

        check(run(root, "status", "--json", "--dev") == 0, "status --json: " + Tests.lastOut);
        Map<String, Object> d = json();
        Map<String, Object> fx = mod(d, "fx_mod");
        check(fx != null && "patch".equals(fx.get("kind")) && "not_patched".equals(fx.get("state")), "fx not patched: " + fx);
        check("1.0".equals(fx.get("installed_version")) && List.of("1.0").equals(fx.get("tested_versions")), "versions: " + fx);
        List<Map<String, Object>> ch = (List<Map<String, Object>>) fx.get("changes");
        check(ch.size() == 6 && "Fuel halved".equals(ch.get(0).get("title")) && Boolean.TRUE.equals(ch.get(0).get("on")), "changes: " + ch);
        check(String.valueOf(ch.get(0).get("ops")).contains("300 -> 150"), "ops show the values: " + ch.get(0));
        Map<String, Object> pl = mod(d, "plain_mod"), gone = mod(d, "gone_mod");
        check(pl != null && "checked".equals(pl.get("kind")) && "checked".equals(pl.get("state")) && "Plain Mod".equals(pl.get("name")), "checked mod: " + pl);
        check(gone != null && "not_installed".equals(gone.get("state")) && Boolean.FALSE.equals(gone.get("installed")), "checked, not installed: " + gone);
        List<Map<String, Object>> un = (List<Map<String, Object>>) d.get("unsupported");
        check(un.size() == 1 && "other_mod".equals(un.get(0).get("id")) && Boolean.TRUE.equals(un.get(0).get("enabled")), "unsupported: " + un);
        check(String.valueOf(d.get("warnings")).contains("Other Mod 2.0"), "the not-analysed warning is in the list: " + d.get("warnings"));
        check("0.98.5a-RC7".equals(d.get("tested_game_version")), "tested game version");

        check(run(root, "apply", "--dev") == 0, "apply: " + Tests.lastOut);
        check(run(root, "status", "--json", "--dev") == 0, "status after apply");
        check("patched".equals(mod(json(), "fx_mod").get("state")), "patched: " + mod(json(), "fx_mod"));

        Api.setCustomize(root.resolve("tool"), root.resolve("patches"), Map.of("fx_mod:c-json", "off"));
        check(run(root, "status", "--json", "--dev") == 0, "status after edit");
        fx = mod(json(), "fx_mod");
        check("customized".equals(fx.get("state")), "other settings: " + fx);
        ch = (List<Map<String, Object>>) fx.get("changes");
        check(Boolean.FALSE.equals(ch.get(1).get("on")) && Boolean.TRUE.equals(ch.get(0).get("on")), "the change is off: " + ch.get(1));

        Api.setCustomize(root.resolve("tool"), root.resolve("patches"), Map.of("fx_mod", "off"));
        check(run(root, "status", "--json", "--dev") == 0, "status after mod off");
        check("off_but_patched".equals(mod(json(), "fx_mod").get("state")), "switched off, still patched: " + mod(json(), "fx_mod"));
        check(run(root, "apply", "--dev") == 0 && run(root, "status", "--json", "--dev") == 0, "apply puts it back");
        check("off".equals(mod(json(), "fx_mod").get("state")), "off: " + mod(json(), "fx_mod"));
    }

    static void statusJsonQuiet() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = Tests.hashes(root);
        check(run(root, "status", "--json", "--dev") == 0, "status --json");
        check(!Files.exists(root.resolve("tool").resolve("logs")), "no log written");
        check(Tests.hashes(root).equals(before), "nothing changed");
        check(Tests.lastOut.trim().startsWith("{") && Tests.lastOut.trim().endsWith("}"), "the output is only the JSON");
    }

    static final String HAND = "# my notes\r\nfx_mod:c-csv = 0.5   # half fuel\r\nall:strength = 0.9\r\n";

    static void customizeRoundTrip() throws Exception {
        Path root = game("1.0");
        Path f = root.resolve("tool").resolve(Customize.FILE);
        Files.writeString(f, HAND);
        Api.setCustomize(root.resolve("tool"), root.resolve("patches"), Map.of("fx_mod:c-row", "off"));
        String t = Files.readString(f);
        check(t.startsWith(HAND), "the player's lines are kept byte for byte: " + t);
        check(t.contains(Api.BEGIN + "\r\nfx_mod:c-row = off\r\n" + Api.END + "\r\n"), "the window's block, CRLF kept: " + t);
        Customize c = Customize.parse(t);
        check(c.changeOff("fx_mod", "c-row") && c.strength("fx_mod", "c-csv").compareTo(new java.math.BigDecimal("0.5")) == 0, "both apply");

        Map<String, String> e = new LinkedHashMap<>();
        e.put("fx_mod:strength", "0.75");
        Api.setCustomize(root.resolve("tool"), root.resolve("patches"), e);
        t = Files.readString(f);
        check(t.startsWith(HAND) && t.indexOf(Api.BEGIN) == t.lastIndexOf(Api.BEGIN), "one block, rewritten in place: " + t);
        check(t.contains("fx_mod:c-row = off") && t.contains("fx_mod:strength = 0.75"), "earlier window lines stay: " + t);

        // a line the player adds above the block later is kept too
        Files.writeString(f, t.replace(Api.BEGIN, "fx_mod:c-line = off\r\n\r\n" + Api.BEGIN));
        e.clear();
        e.put("fx_mod:c-row", null);
        Api.setCustomize(root.resolve("tool"), root.resolve("patches"), e);
        t = Files.readString(f);
        check(t.startsWith(HAND) && t.contains("fx_mod:c-line = off") && !t.contains("fx_mod:c-row"), "null drops a window line: " + t);
        // the window's line wins over the player's for the same key, because it comes last
        Api.setCustomize(root.resolve("tool"), root.resolve("patches"), Map.of("fx_mod:c-line", "on"));
        c = Customize.parse(Files.readString(f));
        check(!c.changeOff("fx_mod", "c-line"), "the window switched it back on: " + Files.readString(f));
        check(Files.readString(f).contains("fx_mod:c-line = off"), "the player's line itself is not touched");
        check(run(root, "status", "--dev") >= 0 && !Tests.lastOut.contains("customize.txt:"), "the file reads with no warnings: " + Tests.lastOut);
    }

    static void customizePrune() throws Exception {
        Patch p = Patch.load(game("1.0").resolve("patches").resolve("fx_mod.json"));
        String r = Api.editText("", Map.of("fx_mod:c-csv", "on", "fx_mod:strength", "1"), List.of(p));
        check(r.isEmpty(), "on and 1 change nothing by default, so no block: [" + r + "]");
        r = Api.editText("all:strength = 0.5\n", Map.of("fx_mod:strength", "1"), List.of(p));
        check(r.contains("fx_mod:strength = 1"), "1 matters when all:strength says otherwise: " + r);
        check(!r.contains("\r\n"), "LF files stay LF");
        r = Api.editText(r, Map.of("fx_mod:strength", "0.5"), List.of(p));
        check(!r.contains(Api.BEGIN), "back to what the player's line says: the block goes: " + r);
        r = Api.editText("", Map.of("fx_mod", "off", "fx_mod:c-csv", "off"), List.of(p));
        check(r.contains("fx_mod = off") && !r.contains("fx_mod:c-csv"), "a change under a switched-off mod adds nothing: " + r);
    }

    static void runReport() {
        String out = "Survival Rebalance 1.1.0: apply\n\n== Fx Mod 1.0  (FxMod)\n  Applied: Fuel halved\n  Saved setting changed: fx_mod fx_mult: 0.75 -> 0.5\n\n"
                + "== Bad Mod 2.0  (Bad)\n  FAILED: Bad Mod 2.0 (tested: 1.0).\n  Change \"x\" did not match: gone\n  Nothing in this mod was changed. Check this mod by hand, or stay on the tested version.\n\n"
                + "Summary\n  Applied            1   Fx Mod\n  Failed             1   Bad Mod\nSome mods failed. Their files were not changed. Read the FAILED lines above.\n\nWarnings (1)\n   1. Bad Mod 2.0: cannot be patched.\n      What to do: Check it.\n";
        String r = RunReport.summary("Apply", out, 1);
        check(r.contains("Rebalanced: Fx Mod"), "applied: " + r);
        check(RunReport.headline("Apply", out, 1).equals("Done. 1 mod rebalanced. 1 mod failed."), "headline: " + RunReport.headline("Apply", out, 1));
        check(RunReport.summary("Apply", out, 1, Map.of("fx_mod", "Fx Mod")).contains("   Fx Mod\n      fx_mult: 0.75 to 0.5"), "settings by mod name, old to new: " + RunReport.summary("Apply", out, 1, Map.of("fx_mod", "Fx Mod")));
        check(r.contains("FAILED, their files were not changed: Bad Mod"), "failed: " + r);
        check(r.contains("did not match: gone"), "why it failed: " + r);
        check(r.contains("fx_mult: 0.75 to 0.5"), "saved setting old and new: " + r);
        check(r.contains("Warnings (1)") && r.contains("What to do: Check it."), "warnings: " + r);
        check(RunReport.summary("Undo", "Summary\n  Restored           2   A, B\n\nNo warnings\n", 0).contains("Put back: A, B"), "undo");
        check(RunReport.headline("Undo", "Summary\n  Restored           2   A, B\n\nNo warnings\n", 0).equals("Done. 2 mods put back."), "undo headline");
    }

    static boolean windows() { return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"); }

    static Path exe() {
        Path exe = Path.of("launcher", "build", "SurvivalRebalance.exe").toAbsolutePath();
        check(Files.isRegularFile(exe), "build the launcher first: " + exe);
        return exe;
    }

    /**
     * A 1.2.0 install with the flat layout updates itself to a release with the app\ layout: the
     * real exe installs a staged update that the updater's own code laid out, and the real jar's
     * "update --finish" checks it. build.bat builds the exe and the jar before the tests.
     */
    static void launcherInstall() throws Exception {
        if (!windows()) return; // the exe is Windows only
        Path exe = exe(), realJar = Path.of("build", "SurvivalRebalance.jar").toAbsolutePath();
        check(Files.isRegularFile(realJar), "the build's jar: " + realJar);
        Path tool = Files.createTempDirectory(Tests.tmp, "tool");
        write(tool, "shipped.txt", "apply.bat\r\npatches/a.json\r\npatches/old.json\r\nREADME.txt\r\nshipped.txt\r\nSurvivalRebalance.jar\r\nupdate.bat\r\n");
        for (String f : new String[]{"apply.bat", "patches/a.json", "patches/old.json", "README.txt", "SurvivalRebalance.jar", "update.bat"}) write(tool, f, "old " + f);
        write(tool, "customize.txt", "mine");
        write(tool, "game_dir.txt", "C:\\Games\\Starsector");
        write(tool, "logs/x.log", "log");
        write(tool, "my_notes.txt", "a file no version shipped");

        Map<String, byte[]> rel = new java.util.TreeMap<>();
        rel.put("SurvivalRebalance.exe", "new exe".getBytes(StandardCharsets.UTF_8));
        rel.put("README.txt", "new readme".getBytes(StandardCharsets.UTF_8));
        rel.put("app/SurvivalRebalance.jar", Files.readAllBytes(realJar));
        rel.put("app/apply.bat", "new apply".getBytes(StandardCharsets.UTF_8));
        rel.put("app/patches/a.json", "new a".getBytes(StandardCharsets.UTF_8));
        rel.put("shipped.txt", "app/apply.bat\r\napp/patches/a.json\r\napp/SurvivalRebalance.jar\r\nREADME.txt\r\nshipped.txt\r\nSurvivalRebalance.exe\r\n".getBytes(StandardCharsets.UTF_8));
        Path ud = tool.resolve(Updater.DIR), st = ud.resolve(Updater.STAGED);

        // a download cut short: staged files and no marker are thrown away, nothing installed
        write(st, "README.txt", "half");
        check(launch(exe, tool, null) == 0 && !Files.exists(st) && read(tool, "README.txt").equals("old README.txt"), "a partial download is deleted");

        Updater.Marker m = new Updater.Marker();
        m.version = "9.0.0";
        m.from = "1.2.0";
        for (Map.Entry<String, byte[]> e : rel.entrySet()) {
            Path p = st.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
            m.files.put(e.getKey(), Text.sha256(e.getValue()));
        }
        write(st, "logs/evil.log", "a staged file under logs is never copied");
        m.remove.addAll(Updater.removals(tool, rel.keySet()));
        check(m.remove.containsAll(List.of("SurvivalRebalance.jar", "apply.bat", "update.bat", "patches/a.json", "patches/old.json")) && !m.remove.contains("README.txt"),
                "the updater lists the flat files for removal: " + m.remove);
        Files.writeString(ud.resolve(Updater.REMOVE), String.join("\r\n", m.remove) + "\r\n");
        Updater.writeMarker(ud, m);

        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        check(launch(exe, tool, java) == 0, "install with the new jar's update --finish: exit 0: " + lastLaunch);
        check(Arrays.equals(Files.readAllBytes(tool.resolve("app/SurvivalRebalance.jar")), rel.get("app/SurvivalRebalance.jar")) && read(tool, "README.txt").equals("new readme")
                && read(tool, "app/patches/a.json").equals("new a"), "the new files are in place");
        check(!Files.exists(tool.resolve("SurvivalRebalance.jar")) && !Files.exists(tool.resolve("apply.bat")) && !Files.exists(tool.resolve("update.bat"))
                && !Files.exists(tool.resolve("patches")), "the flat files and the emptied patches folder are gone");
        check(read(tool, "customize.txt").equals("mine") && read(tool, "game_dir.txt").startsWith("C:") && read(tool, "logs/x.log").equals("log")
                && !Files.exists(tool.resolve("logs/evil.log")), "the player's files are untouched");
        check(read(tool, "my_notes.txt").startsWith("a file"), "a file no version shipped is left alone");
        check(!Files.exists(ud.resolve(Updater.MARKER)) && !Files.exists(st), "update --finish cleared the marker and the staged files");
        check(Updater.installRoot(tool.resolve("app")).equals(tool), "the new jar finds the install root above app");
    }

    static String lastLaunch;

    static int launch(Path exe, Path tool, Path java) throws Exception {
        List<String> c = new java.util.ArrayList<>(List.of(exe.toString(), "--install-update", tool.toString()));
        if (java != null) c.add(java.toString());
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        lastLaunch = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return p.waitFor();
    }

    /** The exe's version info is the tool's version. */
    static void launcherVersion() throws Exception {
        String cs = Files.readString(Path.of("launcher", "SurvivalRebalance.cs"));
        check(cs.contains("AssemblyFileVersion(\"" + Main.VERSION + ".0\")") && cs.contains("AssemblyVersion(\"" + Main.VERSION + ".0\")"),
                "launcher\\SurvivalRebalance.cs names version " + Main.VERSION + ".0");
    }

    /**
     * The release layout: the jar, patches, manifest and monitor in app\, the player's files
     * (customize.txt, logs) at the install root, which holds shipped.txt.
     */
    @SuppressWarnings("unchecked")
    static void appLayout() throws Exception {
        Path root = game("1.0");
        Path tool = root.resolve("tool"), app = tool.resolve("app");
        Files.createDirectories(app.resolve("patches"));
        Files.copy(root.resolve("patches/fx_mod.json"), app.resolve("patches/fx_mod.json"));
        Files.writeString(tool.resolve("shipped.txt"), "app/SurvivalRebalance.jar\r\nshipped.txt\r\n");
        Files.writeString(app.resolve("manifest.json"), "{\"game_version\": \"0.98.5a-RC7\", \"checked_mods\": {}, \"supported\": [{\"id\": \"fx_mod\", \"name\": \"Fx Mod\", \"tested_versions\": [\"1.0\"], \"changes\": 6}]}");
        Path mon = app.resolve(Checks.MONITOR_DIR);
        Files.createDirectories(mon);
        Files.writeString(mon.resolve("mod_info.json"), "{\"id\": \"" + Checks.MONITOR_ID + "\", \"name\": \"Survival Rebalance Monitor\", \"version\": \"1.0.0\"}");
        Files.writeString(tool.resolve(Customize.FILE), "fx_mod:c-row = off\r\n");
        String[] base = {"--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString(), "--tool-dir", app.toString(), "--dev"};

        check(runArgs("status", base, "--json") == 0, "status --json: " + Tests.lastOut);
        Map<String, Object> d = json();
        check("0.98.5a-RC7".equals(d.get("tested_game_version")), "the manifest is read from app");
        List<Map<String, Object>> ch = (List<Map<String, Object>>) mod(d, "fx_mod").get("changes");
        check(ch.stream().anyMatch(c -> "c-row".equals(c.get("id")) && Boolean.FALSE.equals(c.get("on"))), "customize.txt is read from the install root: " + ch);
        check(tool.toString().equals(d.get("tool_dir")), "tool_dir is the install root: " + d.get("tool_dir"));

        check(runArgs("apply", base) == 0, "apply: " + Tests.lastOut);
        check(Files.isDirectory(tool.resolve("logs")) && !Files.exists(app.resolve("logs")), "logs go to the install root");
        check(!ModInfo.byId(ModInfo.scan(root.resolve("mods")), Checks.MONITOR_ID).isEmpty(), "the monitor is installed from app");
        Files.delete(tool.resolve(Customize.FILE));
        check(runArgs("customize", new String[]{"--tool-dir", app.toString()}) == 0 && Files.isRegularFile(tool.resolve(Customize.FILE)) && !Files.exists(app.resolve(Customize.FILE)),
                "customize writes customize.txt at the install root: " + Tests.lastOut);
        check(runArgs("undo", base) == 0, "undo: " + Tests.lastOut);
    }

    static int runArgs(String cmd, String[] base, String... extra) throws Exception {
        List<String> a = new java.util.ArrayList<>();
        a.add(cmd);
        a.addAll(List.of(base));
        a.addAll(List.of(extra));
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int rc = Main.run(a.toArray(new String[0]), new java.io.PrintStream(b, true, StandardCharsets.UTF_8));
        Tests.lastOut = b.toString(StandardCharsets.UTF_8);
        return rc;
    }

    /** The manifest lists every supported mod by name; supported prints it; the README gets it from the build, not by hand. */
    static void supportedList() throws Exception {
        Path root = game("1.0");
        Path changes = root.resolve("changes.json");
        Files.writeString(changes, "{\"mods\": [{\"mod_id\": \"fx_mod\", \"mod_name\": \"Fx Mod\", \"version\": \"1.0\", \"changes\": 6},"
                + " {\"mod_id\": \"lib_mod\", \"mod_name\": \"A Library\", \"version\": \"2.1\", \"changes\": 0},"
                + " {\"mod_id\": \"old_form\", \"mod_name\": \"Old Form\", \"version\": \"3\", \"changes\": []}]}");
        Path app = root.resolve("tool");
        String[] man = {"--patches", root.resolve("patches").toString(), "--game-version", "0.98.5a-RC7",
                "--changes", changes.toString(), "--out", app.resolve("manifest.json").toString()};
        try {
            runArgs("manifest", man);
            throw new AssertionError("a mod with no changes that no --checked file lists must fail the manifest");
        } catch (Failure f) {
            check(f.getMessage().contains("lib_mod") && f.getMessage().contains("old_form"), "both forms of an empty change list count as needing no change: " + f.getMessage());
        }
        Path checked = root.resolve("checked.json");
        Files.writeString(checked, "{\"mods\": [{\"id\": \"lib_mod\", \"name\": \"A Library\"}, {\"id\": \"old_form\", \"name\": \"Old Form\"}]}");
        check(runArgs("manifest", Tests.concat(man, "--checked", checked.toString())) == 0, "manifest: " + Tests.lastOut);
        Checks.Manifest m = Checks.manifest(app);
        check(m.supported.size() == 3, "three supported mods: " + m.supported.size());
        check(m.supported.get(0).name.equals("A Library") && m.supported.get(0).changes == 0 && m.supported.get(0).tested.isEmpty() && m.supported.get(0).anyVersion, "checked mod with its name, at any version");
        check(m.supported.get(1).name.equals("Fx Mod") && m.supported.get(1).changes == 6 && !m.supported.get(1).anyVersion, "patched mod with its change count");
        check(m.checked.contains("lib_mod") && m.checked.contains("old_form"), "both are listed as checked");

        check(runArgs("supported", new String[]{"--tool-dir", app.toString()}) == 0, "supported: " + Tests.lastOut);
        check(Tests.lastOut.contains("supports 3 mods") && Tests.lastOut.matches("(?s).*A Library\\s+any version\\s+checked, needs no change.*")
                && Tests.lastOut.matches("(?s).*Fx Mod\\s+1\\.0\\s+6 changes.*"), "one line per mod: " + Tests.lastOut);

        Path tpl = root.resolve("README.txt"), out = root.resolve("README.out.txt");
        Files.writeString(tpl, "TOP\n\nSUPPORTED MODS\n\n" + SupportedCmd.MARK + "\n\nEND\n");
        check(runArgs("supported", new String[]{"--manifest", app.resolve("manifest.json").toString(), "--readme", tpl.toString(), "--out", out.toString()}) == 0, "readme: " + Tests.lastOut);
        String r = Files.readString(out);
        check(r.contains("SUPPORTED MODS\r\n\r\n   A Library") && r.contains("needs no change\r\n\r\nEND") && !r.contains(SupportedCmd.MARK), "the list replaces the marker: " + r);
        check(Files.readString(Path.of("dist-src", "README.txt")).contains(SupportedCmd.MARK), "the shipped README template has the marker");
    }

    /** The one-job bats live in app\ and find the install root and the game above it. */
    static void batsLayout() throws Exception {
        for (String b : new String[]{"apply", "undo", "status", "check", "customize", "update"}) {
            String t = Files.readString(Path.of("dist-src", b + ".bat"));
            check(t.contains("\"%APP%\\SurvivalRebalance.jar\"") && t.contains("if /i \"%%~nxI\"==\"app\" for %%J in (\"%APP%\\..\") do set \"TOOL=%%~fJ\"") && t.contains("\"%TOOL%\\game_dir.txt\""), b + ".bat runs the jar beside it and reads game_dir.txt at the install root");
            check(t.contains("\r\n"), b + ".bat has CRLF line ends");
        }
    }

    @SuppressWarnings("unchecked")
    static void windowWarnings() {
        List<Warnings.W> ws = List.of(
                new Warnings.W("Bounty 0.3", "not supported. It has not been checked for Survival Mode, so it stays as it is.", null),
                new Warnings.W("2 mods", "not patched yet: A, B.", "Run apply."),
                new Warnings.W("customize.txt", "Sic: Order (sic:order-cr#2): the text edit \"x\" to \"y\" is applied in full, so this text may not match its number.", "Set this change to 0 or 1."),
                new Warnings.W("customize.txt", "Sic: Order (sic:order-cr#3): the text edit \"a\" to \"b\" is applied in full, so this text may not match its number.", "Set this change to 0 or 1."),
                new Warnings.W("customize.txt", "Sic: Armor (sic:armor#0): the text edit \"c\" to \"d\" is applied in full, so this text may not match its number.", "Set this change to 0 or 1."));
        List<Object> w = StatusJson.windowWarnings(ws);
        check(w.size() == 3, "five warnings become three: " + w);
        Map<String, Object> a = (Map<String, Object>) w.get(0), b = (Map<String, Object>) w.get(1), c = (Map<String, Object>) w.get(2);
        check("unsupported".equals(a.get("kind")), "not supported is marked");
        check("not rebalanced yet: A, B.".equals(b.get("text")) && "Click Apply.".equals(b.get("advice")), "window words: " + b);
        check("strength".equals(c.get("kind")) && "Sic".equals(c.get("subject")) && String.valueOf(c.get("text")).startsWith("texts in 2 changes still show"), "one line per mod, changes counted: " + c);
    }

    static void notEnabledCounts() throws Exception {
        Path root = game("1.0");
        Files.writeString(root.resolve("mods").resolve("enabled_mods.json"), "{\"enabledMods\": []}");
        check(run(root, "check", "--dev") == 2 && Tests.lastOut.contains("not patched yet: Fx Mod (not enabled)."), "check counts the disabled mod apply would patch: " + Tests.lastOut);
    }

    static void write(Path dir, String rel, String text) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, text.getBytes(StandardCharsets.UTF_8));
    }

    static String read(Path dir, String rel) throws Exception { return Files.readString(dir.resolve(rel)); }
}
