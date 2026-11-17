package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static survivalrebalance.Tests.check;

/**
 * Changing from one version of the tool to another: unzipped over the old folder (replacing or
 * keeping files), into a fresh folder, beside it, back again, with another build, by the updater.
 * Each version is a release folder on disk and the bundle its jar would carry (Bundle.override).
 */
final class SwitchTests {
    private SwitchTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("switch: the jar's bundle reads back, and the build refuses a file history.txt lacks", SwitchTests::bundleBuild);
        t.put("switch: unzip over the old folder: stale patch files removed, the dropped mod put back", SwitchTests::unzipOver);
        t.put("switch: unzip keeping old files: the new jar puts the folder right, a mixed .bat is reported", SwitchTests::keepExisting);
        t.put("switch: a fresh folder on mods with another version's state, byte for byte like a fresh apply", SwitchTests::freshFolder);
        t.put("switch: two tool folders on one game, and going back", SwitchTests::twoFoldersAndBack);
        t.put("switch: a build that patched another mod version, then the release build", SwitchTests::otherBuild);
        t.put("switch: carried-over customize.txt lines are notes, typos stay warnings", SwitchTests::carriedCustomize);
        t.put("switch: the monitor is replaced whenever it differs, as a whole folder", SwitchTests::monitorSwap);
        t.put("switch: a record this version cannot read is refused and left alone", SwitchTests::unknownRecord);
        t.put("switch: a flat 1.2.0 folder under the new layout", SwitchTests::flatOld);
        t.put("switch: no shipped.txt: nothing above app is touched", SwitchTests::noShipped);
        t.put("switch: after the updater the folder equals a fresh unzip", SwitchTests::afterUpdate);
        t.put("switch: never applied, then a new version", SwitchTests::neverApplied);
        t.put("switch: a change another mod needs is dropped: the words say so, not customization", SwitchTests::droppedRequirement);
        t.put("switch: through a real built jar and its own bundle", SwitchTests::realJarSwitch);
        t.put("switch: a player's own patch file and a shipped name with other bytes are kept and named", SwitchTests::playerFiles);
        t.put("switch: a missing backup or a file in use leaves the mod exactly as it was", SwitchTests::restoreChecks);
        t.put("switch: apply reads an original from the disk, or from a backup only when the backup is sound", SwitchTests::backupChecked);
        t.put("switch: a monitor in use is left whole and the run still ends with its summary and log", SwitchTests::monitorInUse);
        t.put("switch: no check moves a file: status, a dry run, apply, undo and a refused run", SwitchTests::noRenames);
        t.put("switch: a file held for half a second is waited for, not called in use", SwitchTests::briefHold);
        t.put("switch: a monitor jar built again with other zip times is the same copy", SwitchTests::monitorSameContent);
        t.put("switch: apply and undo refuse while the game runs", SwitchTests::gameRunning);
        t.put("switch: status --json counts a dropped mod and a monitor to replace as Apply's work", SwitchTests::statusWork);
        t.put("switch: the window after a switch that leaves only a dropped mod", SwitchTests::windowAfterSwitch);
        t.put("switch: 1.3.4 patched a mod that 1.3.5 has no patch for: Apply puts it back and it is an unknown mod", SwitchTests::droppedNextVersion);
        t.put("switch: one version, built with and without one mod's patch, on the same game and back", SwitchTests::sameVersionOneMore);
    }

    /** Every file of a mod outside its state folder. */
    static Map<String, String> outsideState(Path mod) throws Exception {
        Map<String, String> m = Build.treeHashes(mod);
        m.keySet().removeIf(k -> k.startsWith(Engine.STATE_DIR + "/") || k.equals(Engine.STATE_DIR));
        return m;
    }

    /** A public 1.3.4 patched fx_mod and sol_mod; 1.3.5 has the same fx patch and none for sol_mod. */
    static void droppedNextVersion() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.3.4", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("1.3.5", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0 && hasState(sol(root)) && hasState(fx(root)), "1.3.4 patched both: " + out);
            recordVersion(sol(root), "1.3.4");
            recordVersion(fx(root), "1.3.4");
            Map<String, String> fxPatched = outsideState(fx(root));
            unzip(neu, tool, false);
            run(root, tool, neu, "status");
            check(out.contains("Sol Mod: rebalanced by another version of the tool (1.3.4), and this version has no changes for it."), "status says sol_mod is dropped: " + out);
            check(out.contains("Apply will remove the files another version of the tool left in its folder (app\\patches\\sol_mod.json)."), "and that the old patch file goes: " + out);
            run(root, tool, neu, "status", "--json");
            Map<String, Object> sol = GuiTests.mod(json(), "sol_mod");
            check(sol != null && "dropped".equals(sol.get("state")) && String.valueOf(sol.get("state_text")).contains("Click Apply to put its original files back"), "the window row says dropped: " + sol);
            check(run(root, tool, neu, "apply") == 0, "1.3.5 apply: " + out);
            check(!hasState(sol(root)) && Build.treeHashes(sol(root)).equals(Build.treeHashes(sol(game()))), "sol_mod byte for byte original, its record gone: " + out);
            check(!Files.exists(tool.resolve("app/patches/sol_mod.json")) && out.contains("app\\patches\\sol_mod.json"), "the old patch file left the tool folder, and apply said so: " + out);
            check(hasState(fx(root)) && outsideState(fx(root)).equals(fxPatched), "fx_mod, whose patch is the same, stays as 1.3.4 left it");
            run(root, tool, neu, "status", "--json");
            Map<String, Object> d = json();
            check(String.valueOf(d.get("unsupported")).contains("sol_mod") && Boolean.FALSE.equals(d.get("apply_has_work")), "sol_mod is now a mod the tool does not know, and Apply has nothing left: " + d);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo by 1.3.5: pristine: " + out);
        } finally { restoreHistory(); }
    }

    /** The same version built twice, with sol_mod's patch (A) and without it (B), unzipped over each other on one game. */
    static void sameVersionOneMore() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path a = release("1.3.5", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_NEW);
        Path b = release("1.3.5", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(a, b));
        try {
            unzip(a, tool, false);
            check(run(root, tool, a, "apply") == 0 && hasState(sol(root)) && hasState(fx(root)), "A patched both: " + out);
            Map<String, String> solPatched = outsideState(sol(root)), fxPatched = outsideState(fx(root));
            unzip(b, tool, false);
            run(root, tool, b, "status");
            check(out.contains("Sol Mod: ") && out.contains("this version has no changes for it"), "B's status says sol_mod is put back: " + out);
            check(run(root, tool, b, "apply") == 0 && !hasState(sol(root)) && Build.treeHashes(sol(root)).equals(Build.treeHashes(sol(game()))), "B puts sol_mod back byte for byte: " + out);
            check(!Files.exists(tool.resolve("app/patches/sol_mod.json")), "B removes A's sol patch file from the folder");
            check(outsideState(fx(root)).equals(fxPatched), "fx_mod stays patched");
            unzip(a, tool, false);
            check(run(root, tool, a, "apply") == 0 && hasState(sol(root)) && outsideState(sol(root)).equals(solPatched), "A patches sol_mod again, the same bytes: " + out);
            check(run(root, tool, a, "undo") == 0 && pristine(root, before), "undo by A: pristine: " + out);
        } finally { restoreHistory(); }
    }

    /** sol_mod's patch is the same in both versions, but one change needs fx_mod:c-line, which NEW dropped. */
    static void droppedRequirement() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        String sol = SOL.replace("{\"id\": \"c-csv\", \"title\": \"Fuel halved\",", "{\"id\": \"c-csv\", \"title\": \"Fuel halved\", \"requires\": [\"fx_mod:c-line\"],");
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", sol), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine(), "sol_mod.json", sol), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0 && out.contains("Applied: Fuel halved"), "OLD applied sol's change: " + out);
            unzip(neu, tool, false);
            run(root, tool, neu, "status");
            check(out.contains("Sol Mod: patched with a change that apply now skips, because a change it needs is not applied.") && !out.contains("Sol Mod: patched with a different customization"), "the true reason: " + out);
            run(root, tool, neu, "status", "--json");
            check(String.valueOf(GuiTests.mod(json(), "sol_mod").get("state_text")).startsWith("Rebalanced with a change that Apply now skips"), "the window row says it too: " + out);
            check(run(root, tool, neu, "apply") == 0, "NEW apply: " + out);
            // the change was already skipped when NEW patched it; a customize.txt edit is then the only cause
            Files.writeString(tool.resolve("customize.txt"), "sol_mod:c-json = off\r\n");
            run(root, tool, neu, "status");
            check(out.contains("Sol Mod: patched with a different customization") && !out.contains("apply now skips"), "an edit is not blamed on a requirement: " + out);
            run(root, tool, neu, "status", "--json");
            check(String.valueOf(GuiTests.mod(json(), "sol_mod").get("state_text")).startsWith("You changed your choices"), "nor in the window: " + out);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine");
        } finally { restoreHistory(); }
    }

    // ---------------------------------------------------------------- fixtures

    static final String SOL = Tests.patchJson(false).replace("fx_mod", "sol_mod").replace("Fx Mod", "Sol Mod");

    /** The fx patch without its c-line change: what a later version changed. */
    static String fxWithoutLine() {
        String p = Tests.patchJson(false);
        int a = p.indexOf("{\"id\": \"c-line\""), b = p.indexOf("{\"id\": \"c-row\"");
        return p.substring(0, a) + p.substring(b);
    }

    static final String BAT_OLD = "@echo off\r\nrem apply, old version\r\n", BAT_NEW = "@echo off\r\nrem apply, new version\r\n";

    /** A release folder: name -> text. Monitor, manifest, bats, README and shipped.txt are added. */
    static Path release(String version, Map<String, String> patches, String bat) throws Exception {
        Path s = Files.createTempDirectory(Tests.tmp, "rel" + version);
        Map<String, String> f = new TreeMap<>();
        for (Map.Entry<String, String> e : patches.entrySet()) f.put("app/patches/" + e.getKey(), e.getValue());
        f.put("app/manifest.json", "{\"tool_version\": \"" + version + "\", \"game_version\": \"0.98.5a-RC7\", \"checked_mods\": [], \"supported\": []}");
        f.put("app/apply.bat", bat);
        f.put("app/" + Checks.MONITOR_DIR + "/mod_info.json", "{\"id\": \"" + Checks.MONITOR_ID + "\", \"name\": \"Survival Rebalance Monitor\", \"version\": \"1.0.0\"}");
        f.put("app/" + Checks.MONITOR_DIR + "/" + Checks.MONITOR_INDEX, "{\"mods\": []}");
        f.put("README.txt", "Survival Rebalance " + version + "\r\n");
        List<String> names = new ArrayList<>(f.keySet());
        names.add("app/SurvivalRebalance.jar");
        names.add(Updater.SHIPPED);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        f.put(Updater.SHIPPED, String.join("\r\n", names) + "\r\n");
        for (Map.Entry<String, String> e : f.entrySet()) write(s, e.getKey(), e.getValue());
        write(s, "app/SurvivalRebalance.jar", "jar " + version);
        return s;
    }

    static void write(Path root, String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** Unzip a release into the tool folder: replacing files, or keeping the ones that exist. */
    static void unzip(Path rel, Path tool, boolean keep) throws Exception {
        try (Stream<Path> s = Files.walk(rel)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path t = tool.resolve(rel.relativize(p).toString());
                if (keep && Files.exists(t)) continue;
                Files.createDirectories(t.getParent());
                Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** A game with fx_mod and sol_mod, both pristine, and an empty tool folder. */
    static Path game() throws Exception {
        Path root = Tests.game("1.0");
        Path sol = root.resolve("mods").resolve("SolMod");
        Tests.writeMod(sol, "1.0");
        Files.writeString(sol.resolve("mod_info.json"), Files.readString(sol.resolve("mod_info.json")).replace("fx_mod", "sol_mod").replace("Fx Mod", "Sol Mod"));
        Files.writeString(root.resolve("saves/common/LunaSettings/sol_mod.json.data"), "{\n   \"fx_flag\": true,\n   \"fx_mult\": 0.75\n}");
        return root;
    }

    static History history(Path... releases) throws Exception {
        History h = new History();
        for (Path r : releases) try (Stream<Path> s = Files.walk(r)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) h.add(r.relativize(p).toString(), Files2.sha(p));
        }
        h.mods.add("sol_mod");
        h.changes.add("fx_mod:c-line");
        return h;
    }

    static String out;

    /** One run of the version whose release folder is rel, from the tool folder's app\. */
    static int run(Path root, Path tool, Path rel, String cmd, String... extra) throws Exception {
        Bundle.override = Bundle.of(rel);
        try {
            List<String> a = new ArrayList<>(List.of(cmd, "--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString(),
                    "--tool-dir", tool.resolve("app").toString(), "--dev"));
            a.addAll(List.of(extra));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int rc = Main.run(a.toArray(new String[0]), new PrintStream(b, true, StandardCharsets.UTF_8));
            out = b.toString(StandardCharsets.UTF_8);
            return rc;
        } finally {
            Bundle.override = null;
        }
    }

    /** The tool folder's files and hashes, without the player's files and the logs. */
    static Map<String, String> toolTree(Path tool) throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(tool)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String r = tool.relativize(p).toString().replace('\\', '/');
                if (Updater.userOwned(r)) continue;
                m.put(r, Files2.sha(p));
            }
        }
        return m;
    }

    static boolean pristine(Path root, Map<String, String> before) throws Exception {
        Map<String, String> now = Tests.hashes(root);
        if (now.equals(before)) return true;
        List<String> d = new ArrayList<>();
        for (String k : new java.util.TreeSet<>(now.keySet())) if (!java.util.Objects.equals(now.get(k), before.get(k))) d.add(k);
        for (String k : before.keySet()) if (!now.containsKey(k)) d.add("missing " + k);
        out = "differs from pristine: " + d + "\n" + out;
        return false;
    }

    static boolean hasState(Path mod) { return Files.exists(Engine.stateDir(mod)); }

    static Path fx(Path root) { return root.resolve("mods/FxMod-1.0"); }
    static Path sol(Path root) { return root.resolve("mods/SolMod"); }

    static History saved;
    static void withHistory(History h) { saved = History.loaded; History.loaded = h; }
    static void restoreHistory() { History.loaded = saved; }

    // ---------------------------------------------------------------- tests

    static void bundleBuild() throws Exception {
        Path rel = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        Path jar = rel.resolve("app/SurvivalRebalance.jar");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar))) {
            jo.putNextEntry(new JarEntry("survivalrebalance/Dummy.class"));
            jo.write(new byte[]{1, 2, 3});
            jo.closeEntry();
        }
        withHistory(history(rel));
        try {
            String[] a = {"bundle", "--stage", rel.toString(), "--jar", jar.toString()};
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            check(Main.run(a, new PrintStream(b, true, StandardCharsets.UTF_8)) == 0, "bundle: " + b);
            Bundle back = Bundle.read(jar);
            check(back != null && back.files.keySet().equals(Bundle.of(rel).files.keySet()), "the jar carries every file but itself: " + (back == null ? null : back.files.keySet()));
            check(back.patches().keySet().equals(java.util.Set.of("fx_mod.json")) && back.manifest() != null && back.monitor().containsKey("mod_info.json"), "patches, manifest and monitor read back");
            check(Main.run(a, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) == 0 && Bundle.read(jar).sha.equals(back.sha), "a second run replaces the bundle, not adds to it");
            write(rel, "app/new_file.txt", "a file no released version had");
            History.loaded = history(release("1.0", Map.of(), BAT_OLD));
            Tests.fails(() -> Main.run(a, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)), "does not name app/new_file.txt");
        } finally { restoreHistory(); }
    }

    /** OLD patched fx_mod and sol_mod; NEW drops sol_mod and changes fx_mod. Unzipped over, replacing. */
    static void unzipOver() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0 && hasState(sol(root)) && hasState(fx(root)), "OLD patched both: " + out);
            // the records say a released version wrote them, as a real switch has them
            recordVersion(sol(root), "1.3.1");
            recordVersion(fx(root), "1.3.1");
            Files.writeString(tool.resolve("customize.txt"), "# mine\r\n");
            write(tool, "logs/old.log", "log");
            write(tool, "my_notes.txt", "no version shipped this");
            unzip(neu, tool, false);
            check(Files.exists(tool.resolve("app/patches/sol_mod.json")), "fixture: the unzip left OLD's patch file");

            run(root, tool, neu, "status");
            check(out.contains("Apply will remove the files another version of the tool left in its folder (app\\patches\\sol_mod.json)."), "status names what apply would remove, in one line: " + out);
            check(out.contains("  Other patch set") && out.contains("1 mod: patched with an older patch set than the one in this tool (1.3.1): Fx Mod.")
                    && !out.contains("Fx Mod: patched with"), "a mod another version patched has its own heading and one shared warning: " + out);
            check(Files.exists(tool.resolve("app/patches/sol_mod.json")), "status removes nothing");
            check(out.contains("Sol Mod: rebalanced by another version of the tool (1.3.1), and this version has no changes for it."), "status says sol_mod will be put back: " + out);
            check(out.contains("  To put back        1   Sol Mod"), "and its Summary lists it: " + out);
            check(!out.contains("c-line]") && out.contains("Apply would put the files back"), "status shows NEW's changes for fx_mod, not OLD's: " + out);
            recordVersion(fx(root), "9.9.9");
            run(root, tool, neu, "status");
            check(out.contains("  Other patch set") && !out.contains("Out of date") && out.contains("patched with a newer patch set than the one in this tool (9.9.9): Fx Mod."), "a newer version: a heading that is true: " + out);
            recordVersion(fx(root), "1.3.1");
            check(!out.contains("ERROR") && !out.contains("Exception"), "no error: " + out);
            Map<String, String> folder = toolTree(tool);
            check(run(root, tool, neu, "check") != 1 && toolTree(tool).equals(folder), "check writes nothing in the tool folder: " + out);
            check(run(root, tool, neu, "apply", "--dry-run") == 0 && toolTree(tool).equals(folder) && out.contains("Apply will remove"), "a dry run writes nothing in the tool folder, and says what apply would remove: " + out);

            check(run(root, tool, neu, "apply") == 0, "NEW apply: " + out);
            check(out.contains("Removed the files another version of the tool left in its folder: app\\patches\\sol_mod.json.") && !Files.exists(tool.resolve("app/patches/sol_mod.json")), "apply removes the stale patch file and names it: " + out);
            check(!hasState(sol(root)) && out.contains("this version has no changes for this mod"), "sol_mod put back, its state folder gone: " + out);
            check(out.split("== Sol Mod", -1).length == 2 && out.contains("Put back 6 files."), "its heading once, the count in words: " + out);
            Map<String, String> solNow = Build.treeHashes(sol(root));
            Path ref = game();
            check(solNow.equals(Build.treeHashes(sol(ref))), "sol_mod byte for byte original");
            check(!out.contains("Applied: Cost lower") && out.contains("Applied: Fuel halved"), "fx_mod has exactly NEW's changes: " + out);
            check(Files.readString(tool.resolve("customize.txt")).equals("# mine\r\n") && Files.exists(tool.resolve("logs/old.log")) && Files.exists(tool.resolve("my_notes.txt")), "the player's files and a file no version shipped are kept");
            Files.delete(tool.resolve("my_notes.txt"));
            check(toolTree(tool).equals(toolTree(neu)), "the tool folder equals a fresh unzip of NEW: " + toolTree(tool).keySet());
            run(root, tool, neu, "status");
            check(!out.contains("Apply will") && !out.contains("Removed the files"), "then status has nothing to say about the folder: " + out);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo by NEW: every mod pristine: " + out);
        } finally { restoreHistory(); }
    }

    /** NEW unzipped over OLD keeping existing files, and NEW's jar runs (as after a flat old layout). */
    static void keepExisting() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply");
            unzip(neu, tool, true);
            check(Files.readString(tool.resolve("app/patches/fx_mod.json")).equals(Tests.patchJson(false)), "fixture: OLD's fx patch is kept on disk");
            check(run(root, tool, neu, "apply") == 0, "NEW apply: " + out);
            check(out.contains("Applied: Fuel halved") && !out.contains("Applied: Cost lower"), "NEW's own fx changes, whatever the folder holds: " + out);
            check(Files.readString(tool.resolve("app/patches/fx_mod.json")).equals(fxWithoutLine()) && Files.readString(tool.resolve("README.txt")).contains("2.0"), "the folder's copies are put right");
            check(out.contains("Put back the files of this version") && out.contains("README.txt"), "and it says so: " + out);
            check(out.contains("this folder mixes two versions (app\\apply.bat is from another version)") && out.contains("Delete the SurvivalRebalance folder and unzip the tool again"), "a .bat of another version is reported, not rewritten: " + out);
            check(Files.readString(tool.resolve("app/apply.bat")).equals(BAT_OLD), "the running .bat is left as it is");
            check(!hasState(sol(root)), "sol_mod put back");
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine");
        } finally { restoreHistory(); }
    }

    /** The OLD folder deleted, NEW in a fresh one; the mods carry OLD's state, and end byte for byte as a fresh apply of NEW leaves them. */
    static void freshFolder() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply");
            Files2.deleteTree(tool);
            unzip(neu, tool, false);
            check(run(root, tool, neu, "apply") == 0, "NEW apply: " + out);
            Path ref = game(), rtool = ref.resolve("tool");
            unzip(neu, rtool, false);
            check(run(ref, rtool, neu, "apply") == 0, "NEW on a pristine game");
            check(Build.treeHashes(fx(root)).equals(Build.treeHashes(fx(ref))) || sameButState(fx(root), fx(ref)), "fx_mod's patched files equal a fresh NEW apply");
            check(Build.treeHashes(sol(root)).equals(Build.treeHashes(sol(ref))), "sol_mod original");
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine");
        } finally { restoreHistory(); }
    }

    /** Every file outside the state folder is equal (the record's time differs). */
    static boolean sameButState(Path a, Path b) throws Exception {
        Map<String, String> x = Build.treeHashes(a), y = Build.treeHashes(b);
        x.keySet().removeIf(k -> k.startsWith(Engine.STATE_DIR + "/" + Engine.SENTINEL));
        y.keySet().removeIf(k -> k.startsWith(Engine.STATE_DIR + "/" + Engine.SENTINEL));
        return x.equals(y);
    }

    /** OLD and NEW in two folders on one game, then back to OLD by unzip-over. */
    static void twoFoldersAndBack() throws Exception {
        Path root = game(), toolA = root.resolve("tool"), toolB = root.resolve("tool-second");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, toolA, false);
            unzip(neu, toolB, false);
            check(run(root, toolA, old, "apply") == 0, "OLD apply");
            check(run(root, toolB, neu, "apply") == 0 && !hasState(sol(root)), "NEW from its own folder: sol_mod back: " + out);
            check(toolTree(toolA).equals(toolTree(old)), "NEW never touches the other tool folder");
            check(out.contains("another copy of the tool is in the folder tool. Each copy brings the mods to its own version"), "NEW names the other copy: " + out);
            check(run(root, toolA, old, "apply") == 0 && hasState(sol(root)) && out.contains("Applied: Cost lower"), "OLD again brings the mods back to OLD: " + out);
            // back to OLD by unzip-over: NEW's folder gets OLD's files
            unzip(old, toolB, false);
            check(run(root, toolB, old, "undo") == 0 && pristine(root, before), "undo by OLD: pristine: " + out);
            check(toolTree(toolB).equals(toolTree(old)), "the folder is OLD's again: " + toolTree(toolB).keySet());
        } finally { restoreHistory(); }
    }

    /** A build that patched a mod at a version it was not tested with, then the release build. */
    static void otherBuild() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Files.writeString(fx(root).resolve("mod_info.json"), Files.readString(fx(root).resolve("mod_info.json")).replace("{major:1, minor:0}", "\"1.1\""));
        Map<String, String> before = Tests.hashes(root);
        Path loose = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false).replace("[\"1.0\"]", "[\"1.0\", \"1.1\"]")), BAT_NEW);
        Path rel = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(loose, rel));
        try {
            unzip(loose, tool, false);
            check(run(root, tool, loose, "apply") == 0 && hasState(fx(root)), "the other build patched fx_mod 1.1: " + out);
            unzip(rel, tool, false);
            run(root, tool, rel, "status");
            check(out.contains("apply puts its original files back"), "status says apply puts it back: " + out);
            Path ref = game();
            Files.writeString(fx(ref).resolve("mod_info.json"), Files.readString(fx(ref).resolve("mod_info.json")).replace("{major:1, minor:0}", "\"1.1\""));
            check(run(root, tool, rel, "apply") == 0 && !hasState(fx(root)) && Build.treeHashes(fx(root)).equals(Build.treeHashes(fx(ref))), "the release build puts it back byte for byte: " + out);
            unzip(loose, tool, false);
            check(run(root, tool, loose, "apply") == 0 && hasState(fx(root)), "the other build again");
            check(run(root, tool, rel, "undo") == 0 && pristine(root, before), "undo by the release build: pristine");
        } finally { restoreHistory(); }
    }

    /** customize.txt lines for a dropped mod and a dropped change, at off and at a strength. */
    static void carriedCustomize() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            Files.writeString(tool.resolve("customize.txt"), "fx_mod:c-line = off\r\nfx_mod:c-line = 0.5\r\nsol_mod = off\r\nfx_mod:c-typo = off\r\n");
            check(run(root, tool, neu, "apply") == 0, "apply runs: " + out);
            check(out.contains("Note: customize.txt line 2 is for the change fx_mod:c-line, which this version of the tool does not have, so the line does nothing now. It is kept for a version that has it."), "a dropped change is a note: " + out);
            check(out.contains("Note: customize.txt line 3 is for sol_mod, which this version of the tool does not change"), "a dropped mod is a note: " + out);
            check(!out.contains("line 1: fx_mod has no change") && !out.contains("line 2: fx_mod has no change") && !out.contains("no patch is for a mod with the id \"sol_mod\""), "not warnings: " + out);
            check(out.contains("line 4: fx_mod has no change \"c-typo\""), "a typo is still a warning: " + out);
            check(Files.readString(tool.resolve("customize.txt")).startsWith("fx_mod:c-line = off"), "the lines are kept");
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine");
        } finally { restoreHistory(); }
    }

    static void monitorSwap() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\", \"" + Checks.MONITOR_ID + "\"]}");
            Path mon = root.resolve("mods").resolve(Checks.MONITOR_DIR);
            write(mon, "mod_info.json", "{\"id\": \"" + Checks.MONITOR_ID + "\", \"name\": \"Survival Rebalance Monitor\", \"version\": \"3.0.0\"}");
            write(mon, "jars/extra.jar", "a file of another monitor version");
            write(mon, Checks.MONITOR_MARK, "mark");
            run(root, tool, neu, "status");
            check(out.contains("Survival Rebalance Monitor 3.0.0: newer than the 1.0.0 this tool ships."), "status says the newer monitor differs: " + out);
            check(run(root, tool, neu, "apply") == 0 && out.contains("Replaced version 3.0.0 with the copy this version of the tool ships"), "apply replaces it: " + out);
            check(!Files.exists(mon.resolve("jars/extra.jar")) && Files.readString(mon.resolve("mod_info.json")).contains("1.0.0") && Files.exists(mon.resolve(Checks.MONITOR_MARK)), "the whole folder is the shipped copy");
            check(run(root, tool, neu, "apply") == 0 && !out.contains("== Survival Rebalance Monitor") && !out.contains("Replaced version") && !out.contains("Survival Rebalance Monitor 1.0.0:"), "the same copy is left alone: " + out);
            Files.delete(root.resolve("mods/enabled_mods.json"));
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo removes it: pristine");
        } finally { restoreHistory(); }
    }

    static void unknownRecord() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            write(fx(root), Engine.STATE_DIR + "/" + Engine.SENTINEL, "{\"layout\": 2, \"status\": \"applied\", \"files\": []}");
            Map<String, String> mod = Build.treeHashes(fx(root));
            run(root, tool, neu, "apply");
            check(out.contains("was written by a version of the tool that this one cannot read, so nothing in this mod was touched"), "refused in one plain line: " + out);
            check(!out.contains("Exception"), "no stack trace");
            check(Build.treeHashes(fx(root)).equals(mod), "nothing in the mod was touched");
            run(root, tool, neu, "undo");
            check(Build.treeHashes(fx(root)).equals(mod), "undo leaves it too");
            // a record with no files: put back without a "0 files" line
            Path sol = root.resolve("mods/SolMod");
            Tests.writeMod(sol, "1.0");
            Files.writeString(sol.resolve("mod_info.json"), Files.readString(sol.resolve("mod_info.json")).replace("fx_mod", "sol_mod").replace("Fx Mod", "Sol Mod"));
            write(sol, Engine.STATE_DIR + "/" + Engine.SENTINEL, "{\"status\": \"applied\", \"tool_version\": \"1.3.1\", \"files\": []}");
            run(root, tool, neu, "apply");
            check(!hasState(sol) && !out.contains("Put back 0") && out.split("== Sol Mod", -1).length == 2, "no zero count, one heading: " + out);
        } finally { restoreHistory(); }
    }

    /** A 1.2.0-style flat folder (jar, bats, patches at the top) with the new layout unzipped over it. */
    static void flatOld() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        Map<String, String> flat = new TreeMap<>();
        flat.put("SurvivalRebalance.jar", "old flat jar");
        flat.put("apply.bat", BAT_OLD);
        flat.put("manifest.json", "{}");
        flat.put("patches/sol_mod.json", SOL);
        flat.put(Checks.MONITOR_DIR + "/mod_info.json", "{}");
        flat.put("shipped.txt", "old list\r\n");
        flat.put("README.txt", "old readme");
        for (Map.Entry<String, String> e : flat.entrySet()) write(tool, e.getKey(), e.getValue());
        History h = history(neu);
        for (Map.Entry<String, String> e : flat.entrySet()) h.add(e.getKey(), Text.sha256(e.getValue().getBytes(StandardCharsets.UTF_8)));
        withHistory(h);
        try {
            unzip(neu, tool, true);
            run(root, tool, neu, "undo");
            check(toolTree(tool).equals(toolTree(neu)), "the flat files are gone and the rest is NEW's: " + toolTree(tool).keySet());
            check(!Files.exists(tool.resolve("patches")) && !Files.exists(tool.resolve(Checks.MONITOR_DIR)), "and their emptied folders");
            check(Files.exists(tool.resolve("app/SurvivalRebalance.jar")), "the running jar stays");
            List<String> many = new ArrayList<>(List.of("apply.bat"));
            for (int i = 0; i < 11; i++) many.add("patches/p" + i + ".json");
            many.add("app/patches/a.json");
            check(ToolFolder.list(many).equals("apply.bat, patches\\ (11 files), app\\patches\\a.json"), "a long list names a folder once: " + ToolFolder.list(many));
        } finally { restoreHistory(); }
    }

    /** app\ unzipped straight into a folder that is not a tool folder: only app\ is put right. */
    static void noShipped() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu.resolve("app"), tool.resolve("app"), false);
            write(tool, "README.txt", "the game's own readme");
            write(tool, "apply.bat", "someone else's bat");
            History.loaded.add("apply.bat", Text.sha256("someone else's bat".getBytes(StandardCharsets.UTF_8)));
            History.loaded.add("README.txt", Text.sha256("the game's own readme".getBytes(StandardCharsets.UTF_8)));
            check(run(root, tool, neu, "apply") == 0, "apply: " + out);
            check(Files.readString(tool.resolve("README.txt")).equals("the game's own readme") && Files.exists(tool.resolve("apply.bat")), "nothing above app was touched");
            check(!out.contains("another copy of the tool"), "its own folder is not another copy: " + out);
        } finally { restoreHistory(); }
    }

    /** The updater installs NEW over OLD; its first run leaves the folder equal to a fresh unzip. */
    static void afterUpdate() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply");
            Files.writeString(tool.resolve("customize.txt"), "# mine\r\n");
            // what update and install_update.bat do: stage, copy over, update --finish
            Map<String, byte[]> files = new TreeMap<>();
            try (Stream<Path> s = Files.walk(neu)) {
                for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) files.put(neu.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
            Updater.Marker m = new Updater.Marker();
            m.version = "2.0";
            m.from = "1.0";
            for (Map.Entry<String, byte[]> e : files.entrySet()) m.files.put(e.getKey(), Text.sha256(e.getValue()));
            m.remove.addAll(Updater.removals(tool, files.keySet()));
            Updater.writeMarker(tool.resolve(Updater.DIR), m);
            unzip(neu, tool, false);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            check(Updater.finish(tool, new PrintStream(b, true, StandardCharsets.UTF_8)) == 0, "finish: " + b);
            // the installer an older version's update leaves behind: kept while it may still run, removed later
            Path helper = tool.resolve(Updater.DIR).resolve(Updater.HELPER);
            java.nio.file.attribute.FileTime longAgo = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - ToolFolder.HELPER_AGE_MS - 60_000);
            // a file of that name that is not the tool's installer is never removed, however old
            write(tool, Updater.DIR + "/" + Updater.HELPER, "@echo off\r\nrem my own script\r\n");
            Files.setLastModifiedTime(helper, longAgo);
            run(root, tool, neu, "status");
            check(Files.readString(helper).contains("my own script") && !out.contains("install_update.bat"), "a file named install_update.bat that is not the tool's is kept: " + out);
            Files.write(helper, Updater.helperBat("app/SurvivalRebalance.jar").getBytes(StandardCharsets.US_ASCII));
            run(root, tool, neu, "status");
            check(Files.exists(helper) && !out.contains("install_update.bat"), "a fresh installer is left alone (it may be running the apply): " + out);
            Files.setLastModifiedTime(helper, longAgo);
            // while an update is under way (something staged), even an old installer stays
            write(tool, Updater.DIR + "/" + Updater.STAGED + "/x.txt", "staged");
            run(root, tool, neu, "status");
            check(Files.exists(helper) && !out.contains("install_update.bat"), "an installer is left alone while an update is staged: " + out);
            Files2.deleteTree(tool.resolve(Updater.DIR).resolve(Updater.STAGED));
            Files.write(helper, Updater.helperBat("app/SurvivalRebalance.jar").getBytes(StandardCharsets.US_ASCII));
            Files.setLastModifiedTime(helper, longAgo);
            run(root, tool, neu, "status");
            check(Files.exists(helper) && out.contains("Apply will remove") && out.contains("update\\install_update.bat"), "status names the old installer and leaves it: " + out);
            check(run(root, tool, neu, "apply") == 0 && !hasState(sol(root)), "the apply after the update: " + out);
            check(!Files.exists(tool.resolve(Updater.DIR)) && out.contains("update\\install_update.bat"), "the old installer and its emptied folder are removed, and named: " + out);
            check(toolTree(tool).equals(toolTree(neu)), "the folder equals a fresh unzip of NEW: " + toolTree(tool).keySet());
            check(Files.readString(tool.resolve("customize.txt")).equals("# mine\r\n"), "customize.txt kept");
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine");
        } finally { restoreHistory(); }
    }

    /** OLD unzipped and never applied, then NEW over it: only NEW's changes are shown. */
    static void neverApplied() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            run(root, tool, old, "status");
            check(out.contains("Sol Mod"), "fixture: OLD shows sol_mod");
            unzip(neu, tool, false);
            run(root, tool, neu, "status");
            check(!out.contains("== Sol Mod") && !out.contains("Cost lower"), "NEW shows only its own changes: " + out);
            check(run(root, tool, neu, "apply") == 0 && !hasState(sol(root)) && hasState(fx(root)), "apply: " + out);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine");
        } finally { restoreHistory(); }
    }

    /** Rewrite the tool version a mod's record names, as a record from that released version reads. */
    static void recordVersion(Path mod, String v) throws Exception {
        Path f = Engine.stateDir(mod).resolve(Engine.SENTINEL);
        Files.writeString(f, Files.readString(f).replaceFirst("\"tool_version\": \"[^\"]*\"", "\"tool_version\": \"" + v + "\""));
    }

    // ---------------------------------------------------------------- the real jar

    /** history.txt as the build ships it, from a History. */
    static String historyText(History h) {
        StringBuilder b = new StringBuilder("# test history\n");
        for (Map.Entry<String, java.util.Set<String>> e : h.files.entrySet()) for (String s : e.getValue()) b.append("file ").append(s).append(' ').append(h.spelling.get(e.getKey())).append('\n');
        for (String m : h.mods) b.append("mod ").append(m).append('\n');
        for (String c : h.changes) b.append("change ").append(c).append('\n');
        return b.toString();
    }

    /**
     * Make rel's jar a real one: the classes this suite runs on, this history.txt, and the bundle the
     * build step writes into it. Null when the suite runs from a jar (then there is no classes folder).
     */
    static Path realJar(Path rel, History h) throws Exception {
        Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isDirectory(classes)) return null;
        Path jar = rel.resolve("app/SurvivalRebalance.jar");
        java.util.jar.Manifest mf = new java.util.jar.Manifest();
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, "survivalrebalance.Main");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar), mf); Stream<Path> s = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String r = classes.relativize(p).toString().replace('\\', '/');
                if (r.equals("survivalrebalance/history.txt") || r.startsWith(Bundle.ROOT)) continue;
                jo.putNextEntry(new JarEntry(r));
                jo.write(Files.readAllBytes(p));
                jo.closeEntry();
            }
            jo.putNextEntry(new JarEntry("survivalrebalance/history.txt"));
            jo.write(historyText(h).getBytes(StandardCharsets.UTF_8));
            jo.closeEntry();
        }
        History was = History.loaded;
        History.loaded = h;
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            check(Main.run(new String[]{"bundle", "--stage", rel.toString(), "--jar", jar.toString()}, new PrintStream(b, true, StandardCharsets.UTF_8)) == 0, "bundle: " + b);
        } finally { History.loaded = was; }
        return jar;
    }

    /** Run the tool's jar in its own Java, as the bats do. */
    static int child(Path jar, String... args) throws Exception {
        List<String> c = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString()));
        c.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(c).redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")));
        Process pr = pb.start();
        out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(pr.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "the jar finished");
        return pr.exitValue();
    }

    static String[] engineArgs(Path root, String cmd) {
        return new String[]{cmd, "--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString(), "--dev", "--offline"};
    }

    /** An unzip over the old folder, run by the real jar: the bundle comes from the jar itself (Bundle.current, jarFile), no override. */
    static void realJarSwitch() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        History h = history(old, neu);
        withHistory(h);
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0 && hasState(sol(root)), "OLD patched both: " + out);
            Path jar = realJar(neu, h);
            if (jar == null) { System.out.println("  (real jar switch skipped: the suite runs from a jar)"); return; }
            unzip(neu, tool, false);
            Path tjar = tool.resolve("app/SurvivalRebalance.jar");
            check(child(tjar, engineArgs(root, "apply")) == 0, "NEW's jar applies: " + out);
            check(out.contains("Removed the files another version of the tool left in its folder: app\\patches\\sol_mod.json."), "it tidied its folder from its own bundle: " + out);
            check(!Files.exists(tool.resolve("app/patches/sol_mod.json")) && !hasState(sol(root)), "sol_mod.json gone, sol_mod put back: " + out);
            check(out.contains("Applied: Fuel halved") && !out.contains("Applied: Cost lower"), "fx_mod has exactly NEW's changes: " + out);
            // a loose patch file next to the jar cannot add a change: the jar's own set rules
            write(tool, "app/patches/fx_mod.json", Tests.patchJson(false));
            check(child(tjar, engineArgs(root, "status")) == 0 && out.contains("Apply would do nothing: up to date") && out.contains("Apply will put back the files of this version that are missing or changed (app\\patches\\fx_mod.json)."), "the loose copy changes nothing, and status says apply puts it right: " + out);
            check(child(tjar, engineArgs(root, "undo")) == 0 && pristine(root, before), "undo by the real jar: pristine: " + out);
        } finally { restoreHistory(); }
    }

    // ---------------------------------------------------------------- the player's own files in the tool folder

    static void playerFiles() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        write(old, "app/old_only.txt", "a file only OLD shipped");
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            unzip(neu, tool, false);
            String mine = Tests.patchJson(false).replace("fx_mod", "my_mod").replace("Fx Mod", "My Mod");
            write(tool, "app/patches/my_own_mod.json", mine);
            write(tool, "app/patches/sol_mod.json", SOL.replace("Fuel halved", "Fuel halved, my way"));
            run(root, tool, neu, "status");
            check(Files.readString(tool.resolve("app/patches/my_own_mod.json")).equals(mine), "a patch file no version shipped is kept");
            check(Files.readString(tool.resolve("app/patches/sol_mod.json")).contains("my way"), "a shipped name with other bytes is kept");
            check(Files.exists(tool.resolve("app/old_only.txt")) && out.contains("Apply will remove the files another version of the tool left in its folder (app\\old_only.txt)."), "status names a file OLD shipped, byte for byte, and leaves it: " + out);
            check(out.contains("These files are in the tool folder, but this version does not use them: ") && out.contains("app\\patches\\sol_mod.json") && out.contains("app\\patches\\my_own_mod.json") && out.contains("The tool leaves them where they are."), "both are named as not used: " + out);
            check(!out.contains("== My Mod") && !out.contains("== Sol Mod"), "neither is read as a patch: " + out);
            // a file of this version that the player edited: apply puts the shipped one back and keeps theirs beside it
            String edited = fxWithoutLine().replace("Fuel halved", "Fuel halved, edited by me");
            write(tool, "app/patches/fx_mod.json", edited);
            run(root, tool, neu, "status");
            check(out.contains("It keeps a copy of your own app\\patches\\fx_mod.json beside it.") && Files.readString(tool.resolve("app/patches/fx_mod.json")).equals(edited), "status says apply keeps the edited copy, and changes nothing: " + out);
            check(run(root, tool, neu, "apply") == 0, "apply: " + out);
            check(!Files.exists(tool.resolve("app/old_only.txt")) && out.contains("Removed the files another version of the tool left in its folder: app\\old_only.txt."), "apply removes the file OLD shipped: " + out);
            check(Files.readString(tool.resolve("app/patches/fx_mod.json")).equals(fxWithoutLine()) && Files.readString(tool.resolve("app/patches/fx_mod.json.edited")).equals(edited)
                    && out.contains("Your own copy is kept as app\\patches\\fx_mod.json.edited."), "the shipped file is back and the player's edit is kept and named: " + out);
            check(Files.readString(tool.resolve("app/patches/my_own_mod.json")).equals(mine) && Files.readString(tool.resolve("app/patches/sol_mod.json")).contains("my way"), "apply keeps the player's own files too");
        } finally { restoreHistory(); }
    }

    // ---------------------------------------------------------------- checks before a restore: backups and files in use

    /** A tracked file the patch changed, the last one in the record (the one an unchecked restore reaches last). */
    static String lastTracked(Path mod) throws Exception {
        List<Map<String, Object>> fs = Engine.files(Engine.readSentinel(mod));
        return Engine.str(fs.get(fs.size() - 1), "path");
    }

    /** Held open the way a running game holds a file: no other writer, no delete or rename. */
    static java.nio.channels.FileChannel hold(Path p) throws Exception {
        return java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE,
                com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
    }

    /** Held the way the running game holds a mod's jar: a class loader that has loaded a class from it. */
    static java.net.URLClassLoader gameHolds(Path jar) throws Exception {
        java.net.URLClassLoader c = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null);
        try { c.loadClass("fx.Skill"); } catch (ClassNotFoundException | LinkageError e) { c.close(); throw new AssertionError("the class loader loaded nothing from " + jar + ": " + e); }
        return c;
    }

    static void restoreChecks() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply: " + out);
            unzip(neu, tool, false);
            String last = lastTracked(fx(root));
            Path backup = Engine.stateDir(fx(root)).resolve("backup").resolve(last + ".orig");
            byte[] kept = Files.readAllBytes(backup);
            Files.delete(backup);
            Map<String, String> mod = Build.treeHashes(fx(root));
            check(run(root, tool, neu, "apply") == 1, "apply fails the mod: " + out);
            check(Build.treeHashes(fx(root)).equals(mod), "a missing backup: nothing in the mod changed: " + out);
            check(out.contains("the backup of " + last + " is missing, so it cannot be put back. Nothing in this mod was changed.") && out.contains("Some mods failed. Their files were not changed."), "and it says so, truly: " + out);
            check(run(root, tool, neu, "undo") == 1 && Build.treeHashes(fx(root)).equals(mod), "undo leaves it too: " + out);
            Files.write(backup, kept);

            Map<String, String> mod2 = Build.treeHashes(fx(root));
            // the game holds its jars through a class loader: read and write shared, no lock, no rename
            String jar = "jars/fx.jar";
            check(Engine.files(Engine.readSentinel(fx(root))).stream().anyMatch(f -> jar.equals(Engine.str(f, "path"))), "the record tracks the jar");
            try (java.net.URLClassLoader c = gameHolds(fx(root).resolve(jar))) {
                check(run(root, tool, neu, "apply") == 1, "apply with the jar in use: " + out);
                check(out.contains("FAILED: another program has " + jar + " open, most likely Starsector. Close it first.") && !out.contains("Delete this mod's folder") && !out.contains("Starsector is running"), "the jar in use: another program, most likely the game: " + out);
                check(Build.treeHashes(fx(root)).equals(mod2), "the jar in use: nothing in the mod changed, no file half restored");
                check(run(root, tool, neu, "undo") == 1 && out.contains("another program has " + jar + " open"), "undo with the jar in use: " + out);
                run(root, tool, neu, "status");
                check(out.contains("another program has " + jar + " open, so the tool cannot change it."), "status names the jar in use: " + out);
            }
            check(Build.treeHashes(fx(root)).equals(mod2), "a file in use: nothing in the mod changed");
            try (Stream<Path> s = Files.walk(fx(root))) { check(s.noneMatch(x -> x.toString().endsWith(".srtmp") || x.toString().contains(".srcheck")), "no temp or test file left in the mod"); }
            // a file held so no one else may write (a stricter program than the game): also in use
            try (java.nio.channels.FileChannel c = hold(fx(root).resolve(last))) {
                check(run(root, tool, neu, "apply") == 1 && out.contains("another program has " + last + " open"), "a file held without write sharing: " + out);
            }
            check(Build.treeHashes(fx(root)).equals(mod2), "held without write sharing: nothing in the mod changed");
            // the check leaves the file as it was, name, bytes and time, and its folder as it was
            Path pdir = Files.createTempDirectory(Tests.tmp, "probe"), probe = pdir.resolve("probe.txt");
            Files.writeString(probe, "probe");
            java.nio.file.attribute.FileTime pt = Files.getLastModifiedTime(probe), dt = Files.getLastModifiedTime(pdir);
            Thread.sleep(20);
            check(Files2.writable(probe) && Files.readString(probe).equals("probe") && Files.getLastModifiedTime(probe).equals(pt), "a free file: writable, and left as it was");
            try (Stream<Path> s = Files.list(pdir)) { check(s.count() == 1 && Files.getLastModifiedTime(pdir).equals(dt), "nothing made, moved or renamed in its folder"); }
            check(!Files2.writable(pdir.resolve("missing.txt")), "a missing file is not writable");
            check(run(root, tool, neu, "apply") == 0 && out.contains("Applied: Fuel halved"), "with the file free, apply completes: " + out);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine: " + out);
            // a write that fails leaves no temp file beside its target
            Path target = root.resolve("held.txt");
            Files.writeString(target, "held");
            try (java.nio.channels.FileChannel c = hold(target)) {
                Tests.fails(() -> Files2.atomicWrite(target, new byte[]{1}), "cannot write");
            }
            check(!Files.exists(root.resolve("held.txt.srtmp")), "the temp file is removed");
        } finally { restoreHistory(); }
    }

    /** The fx mod's own files, without its survival_rebalance folder. */
    static Map<String, String> modFiles(Path mod) throws Exception {
        Map<String, String> m = Build.treeHashes(mod);
        m.keySet().removeIf(k -> k.startsWith(Engine.STATE_DIR));
        return m;
    }

    /** Apply takes a tracked file's original from the disk when the disk holds it, and from a backup only when the backup is sound. */
    static void backupChecked() throws Exception {
        String rel = "data/config/settings.json";
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        Path bad = release("2.1", Map.of("fx_mod.json", Tests.patchJson(true)), BAT_NEW);
        withHistory(history(old, neu, bad));
        try {
            // what NEW writes on a pristine game
            Path fresh = game(), ftool = fresh.resolve("tool");
            unzip(neu, ftool, false);
            check(run(fresh, ftool, neu, "apply") == 0, "fresh NEW apply: " + out);
            Map<String, String> want = modFiles(fx(fresh));

            // the normal upgrade: restored from sound backups, patched again
            Path root = game(), tool = root.resolve("tool");
            Map<String, String> before = Tests.hashes(root);
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply: " + out);
            unzip(neu, tool, false);
            check(run(root, tool, neu, "apply") == 0 && out.contains("Putting the files back, then applying again."), "upgrade apply: " + out);
            check(modFiles(fx(root)).equals(want), "the upgrade writes what a fresh apply writes: " + modFiles(fx(root)) + " vs " + want);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo after the upgrade: pristine: " + out);

            // the original on disk, its backup edited: apply patches from the disk
            root = game(); tool = root.resolve("tool");
            before = Tests.hashes(root);
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply: " + out);
            check(Engine.files(Engine.readSentinel(fx(root))).stream().anyMatch(f -> rel.equals(Engine.str(f, "path"))), "the record tracks " + rel);
            Path orig = Engine.stateDir(fx(root)).resolve("backup").resolve(rel + ".orig");
            Files.copy(orig, fx(root).resolve(rel), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(orig, Files.readString(orig) + "\n# a line added by hand\n");
            unzip(neu, tool, false);
            check(run(root, tool, neu, "apply") == 0, "apply with the original on disk: " + out);
            check(modFiles(fx(root)).equals(want), "patched from the disk, as a fresh apply: " + modFiles(fx(root)) + " vs " + want);
            check(!Files.readString(Engine.stateDir(fx(root)).resolve("backup").resolve(rel + ".orig")).contains("by hand"), "the new backup is the disk's original");
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine: " + out);

            // the file patched, its backup edited: the mod is left exactly as it was, and named
            root = game(); tool = root.resolve("tool");
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply: " + out);
            orig = Engine.stateDir(fx(root)).resolve("backup").resolve(rel + ".orig");
            Files.writeString(orig, Files.readString(orig) + "\n# a line added by hand\n");
            unzip(neu, tool, false);
            Map<String, String> mod = Build.treeHashes(fx(root));
            check(run(root, tool, neu, "apply") == 1, "apply fails the mod: " + out);
            check(Build.treeHashes(fx(root)).equals(mod), "a damaged backup: nothing in the mod changed: " + out);
            check(out.contains("FAILED: the backup of " + rel + " is damaged, so it cannot be put back. Nothing in this mod was changed.")
                    && out.contains("Fx Mod 1.0: the backup of " + rel + " is damaged") && out.contains("install it again cleanly"), "it names the mod and says what to do: " + out);
            check(out.contains("Some mods failed. Their files were not changed."), "the engine says no file changed: " + out);
            String w = survivalrebalance.gui.RunReport.summary("Apply", out, 1);
            check(w.contains("FAILED, their files were not changed: Fx Mod"), "the window agrees: " + w);
            run(root, tool, neu, "status");
            check(out.contains("damaged"), "status names the damaged backup: " + out);

            // the window's line when a failed mod's files were changed: this version cannot patch it, so its originals go back
            root = game(); tool = root.resolve("tool");
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply: " + out);
            unzip(bad, tool, false);
            check(run(root, tool, bad, "apply") == 1 && out.contains("Its original files were put back.") && !out.contains("Their files were not changed"), "the engine: originals put back: " + out);
            w = survivalrebalance.gui.RunReport.summary("Apply", out, 1);
            check(w.contains("FAILED: Fx Mod.") && !w.contains("not changed"), "the window does not say their files were not changed: " + w);
        } finally { restoreHistory(); }
    }

    // ---------------------------------------------------------------- a Monitor in use, and how two Monitors are compared

    static void monitorInUse() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            Path mon = root.resolve("mods").resolve(Checks.MONITOR_DIR);
            write(mon, "mod_info.json", "{\"id\": \"" + Checks.MONITOR_ID + "\", \"name\": \"Survival Rebalance Monitor\", \"version\": \"0.9.0\"}");
            Files.createDirectories(mon.resolve("jars"));
            Files.write(mon.resolve("jars/old.jar"), Tests.fixtureJar);
            write(mon, Checks.MONITOR_MARK, "mark");
            Map<String, String> was = Build.treeHashes(mon);
            was.keySet().removeIf(k -> k.startsWith("data"));
            int rc;
            try (java.net.URLClassLoader c = gameHolds(mon.resolve("jars/old.jar"))) {
                rc = run(root, tool, neu, "apply");
            }
            check(rc == 0 && out.contains("Applied: Fuel halved"), "the run goes on: " + out);
            Map<String, String> now = Build.treeHashes(mon);
            now.keySet().removeIf(k -> k.startsWith("data")); // apply writes its index into every installed monitor
            check(now.equals(was), "the installed monitor is left whole: " + now.keySet());
            check(out.contains("Survival Rebalance Monitor: could not be replaced: another program has jars/old.jar open, most likely Starsector."), "one line says so: " + out);
            check(out.contains("Summary") && Files.list(tool.resolve("logs")).findAny().isPresent(), "the summary and the log: " + out);
            check(run(root, tool, neu, "apply") == 0 && out.contains("Updated from 0.9.0") && !Files.exists(mon.resolve("jars/old.jar")), "free again, apply replaces it: " + out);
            check(run(root, tool, neu, "undo") == 0, "undo");
        } finally { restoreHistory(); }
    }

    /**
     * The write time of every folder under the game's mods and the tool folder: a rename or a
     * new file in a folder changes it. The tool's logs folder is left out: every run writes its log there.
     */
    static Map<String, java.nio.file.attribute.FileTime> folderTimes(Path root) throws Exception {
        Map<String, java.nio.file.attribute.FileTime> m = new TreeMap<>();
        Path logs = root.resolve("tool/logs");
        for (Path top : List.of(root.resolve("mods"), root.resolve("tool"))) try (Stream<Path> s = Files.walk(top)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isDirectory(p) && !p.equals(logs)) m.put(root.relativize(p).toString(), Files.getLastModifiedTime(p));
        }
        return m;
    }

    /** The folders whose write time differs between two folderTimes() maps, with both times. */
    static List<String> changedFolders(Map<String, java.nio.file.attribute.FileTime> a, Map<String, java.nio.file.attribute.FileTime> b) {
        List<String> d = new ArrayList<>();
        java.util.Set<String> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) if (!java.util.Objects.equals(a.get(k), b.get(k))) d.add(k + " " + a.get(k) + " -> " + b.get(k));
        return d;
    }

    /** No file anywhere under the game (mods and tool folder) carries a test name, and every file the record tracks is there under its own name. */
    static void noTestNames(Path root, String when) throws Exception {
        try (Stream<Path> s = Files.walk(root)) {
            List<String> bad = s.map(p -> root.relativize(p).toString()).filter(x -> x.contains(".srcheck")).toList();
            check(bad.isEmpty(), when + ": no file carries a test name: " + bad);
        }
        if (hasState(fx(root)))
            for (Map<String, Object> f : Engine.files(Engine.readSentinel(fx(root)))) {
                String rel = Engine.str(f, "path");
                check(Files.isRegularFile(fx(root).resolve(rel)), when + ": " + rel + " is there under its own name");
            }
    }

    /** No check moves or renames a file, in status, a dry run, apply, undo or a run refused because a file is in use. */
    static void noRenames() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            check(run(root, tool, neu, "status") == 0, "first status: " + out);
            noTestNames(root, "status");
            // status alone, on a mod not rebalanced yet: every target is checked, no folder changes
            Map<String, java.nio.file.attribute.FileTime> t0 = folderTimes(root);
            Thread.sleep(20);
            check(run(root, tool, neu, "status") == 0 && out.contains("Not patched. Apply would make"), "status, not rebalanced: " + out);
            check(changedFolders(t0, folderTimes(root)).isEmpty(), "status changed no folder's write time (not rebalanced): " + changedFolders(t0, folderTimes(root)));
            check(run(root, tool, neu, "apply", "--dry-run") == 0 && out.contains("Would apply: Fuel halved"), "dry run: " + out);
            noTestNames(root, "dry run");
            check(Tests.hashes(root).entrySet().stream().filter(e -> e.getKey().startsWith("mods")).allMatch(e -> e.getValue().equals(before.get(e.getKey()))), "the dry run wrote nothing in the mods");
            check(run(root, tool, neu, "apply") == 0 && out.contains("Applied: Fuel halved"), "apply: " + out);
            noTestNames(root, "apply");
            // status alone, on a rebalanced mod: every tracked file is checked, no folder changes
            Map<String, java.nio.file.attribute.FileTime> t1 = folderTimes(root);
            Thread.sleep(20);
            check(run(root, tool, neu, "status") == 0 && out.contains("Apply would do nothing: up to date"), "status, rebalanced: " + out);
            check(changedFolders(t1, folderTimes(root)).isEmpty(), "status changed no folder's write time (rebalanced): " + changedFolders(t1, folderTimes(root)));
            // a run refused because the game holds the jar
            try (java.net.URLClassLoader c = gameHolds(fx(root).resolve("jars/fx.jar"))) {
                check(run(root, tool, neu, "undo") == 1 && out.contains("another program has jars/fx.jar open"), "undo refused, the jar in use: " + out);
                noTestNames(root, "undo refused");
                run(root, tool, neu, "status");
                check(out.contains("another program has jars/fx.jar open"), "status names the jar in use: " + out);
                noTestNames(root, "status with the jar in use");
            }
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine: " + out);
            noTestNames(root, "undo");
        } finally { restoreHistory(); }
    }

    /** Another program opens the file with no write or delete sharing, holds it for ms, then lets go. Returns once it holds it. */
    static Thread holdFor(Path p, long ms) throws Exception {
        java.util.concurrent.CountDownLatch open = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> {
            try (java.nio.channels.FileChannel c = hold(p)) { open.countDown(); Thread.sleep(ms); }
            catch (Exception e) { open.countDown(); }
        });
        t.start();
        open.await();
        return t;
    }

    /** A file held for half a second (a virus scanner reading it) is waited for, not called in use. */
    static void briefHold() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            Path f = fx(root).resolve("data/config/settings.json");
            Files2.sawBusy = false;
            Thread t = holdFor(f, 500);
            long s = System.nanoTime();
            boolean w = Files2.writable(f);
            long ms = (System.nanoTime() - s) / 1_000_000;
            t.join();
            check(w && ms >= 300, "held for half a second, then let go: writable, after waiting " + ms + " ms");
            // a whole run that meets the held file
            int w0 = Files2.waits;
            t = holdFor(f, 500);
            int rc = run(root, tool, neu, "apply");
            t.join();
            check(rc == 0 && out.contains("Applied: Fuel halved") && !out.contains("in use"), "the run waits and applies: " + out);
            check(Files2.waits > w0, "the run met the held file and waited for it");
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine: " + out);
        } finally { restoreHistory(); }
    }

    static byte[] jarOf(long time, String... nameText) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (JarOutputStream jo = new JarOutputStream(b)) {
            for (int i = 0; i < nameText.length; i += 2) {
                JarEntry e = new JarEntry(nameText[i]);
                e.setTime(time);
                jo.putNextEntry(e);
                jo.write(nameText[i + 1].getBytes(StandardCharsets.UTF_8));
                jo.closeEntry();
            }
        }
        return b.toByteArray();
    }

    static void monitorSameContent() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        byte[] shipJar = jarOf(1_700_000_000_000L, "a/A.class", "class A", "b/B.class", "class B");
        Files.createDirectories(neu.resolve("app/" + Checks.MONITOR_DIR + "/jars"));
        Files.write(neu.resolve("app/" + Checks.MONITOR_DIR + "/jars/m.jar"), shipJar);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\", \"" + Checks.MONITOR_ID + "\"]}");
            check(run(root, tool, neu, "apply") == 0, "apply installs the monitor: " + out);
            Path mj = root.resolve("mods").resolve(Checks.MONITOR_DIR).resolve("jars/m.jar");
            // the same classes, built at another time: other zip bytes
            byte[] rebuilt = jarOf(1_800_000_000_000L, "a/A.class", "class A", "b/B.class", "class B");
            check(!java.util.Arrays.equals(rebuilt, shipJar), "fixture: the two jars differ in their bytes");
            Files.write(mj, rebuilt);
            run(root, tool, neu, "status");
            check(!out.contains("Survival Rebalance Monitor 1.0.0:"), "no warning for the same copy: " + out);
            check(run(root, tool, neu, "apply") == 0 && !out.contains("== Survival Rebalance Monitor") && java.util.Arrays.equals(Files.readAllBytes(mj), rebuilt), "apply leaves it alone: " + out);
            Files.write(mj, jarOf(1_800_000_000_000L, "a/A.class", "class A", "b/B.class", "class B changed"));
            run(root, tool, neu, "status");
            check(out.contains("Survival Rebalance Monitor 1.0.0: its files differ from the copy this version of the tool ships.") && !out.contains("(1.0.0)"), "a different jar of the same version: one plain warning, no number twice: " + out);
            check(run(root, tool, neu, "apply") == 0 && out.contains("Replaced version 1.0.0") && java.util.Arrays.equals(Files.readAllBytes(mj), shipJar), "and apply replaces it: " + out);
            Files.delete(root.resolve("mods/enabled_mods.json"));
            check(run(root, tool, neu, "undo") == 0, "undo");
        } finally { restoreHistory(); }
    }

    // ---------------------------------------------------------------- apply and undo while the game runs

    static void gameRunning() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Files.createDirectories(root.resolve("starsector-core"));
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), BAT_NEW);
        withHistory(history(neu));
        java.util.function.Predicate<Path> real = GameRunning.check;
        try {
            unzip(neu, tool, false);
            GameRunning.check = g -> true;
            check(run(root, tool, neu, "apply") == 1 && out.contains("Starsector is running. Close the game first") && Tests.hashes(root).equals(before), "apply refuses and changes nothing: " + out);
            check(run(root, tool, neu, "apply", "--dry-run") == 0, "a dry run still runs: " + out);
            GameRunning.check = g -> false;
            check(run(root, tool, neu, "apply") == 0, "apply");
            GameRunning.check = g -> true;
            check(run(root, tool, neu, "undo") == 1 && out.contains("Starsector is running") && hasState(fx(root)), "undo refuses: " + out);
            GameRunning.check = g -> false;
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo");
        } finally { GameRunning.check = real; restoreHistory(); }
    }

    // ---------------------------------------------------------------- what status --json counts as work for Apply and Undo

    @SuppressWarnings("unchecked")
    static Map<String, Object> json() throws Exception {
        return (Map<String, Object>) Api.parseJson(out.substring(out.indexOf('{')));
    }

    /** OLD rebalanced sol_mod; NEW only checks it, and its fx patch has no mod installed. */
    static Path[] onlyDropped(Path root) throws Exception {
        Files2.deleteTree(fx(root));
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SOL), BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
        write(neu, "app/manifest.json", "{\"tool_version\": \"2.0\", \"game_version\": \"0.98.5a-RC7\", \"checked_mods\": [\"sol_mod\"], \"supported\": []}");
        return new Path[]{old, neu};
    }

    @SuppressWarnings("unchecked")
    static void statusWork() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path[] v = onlyDropped(root);
        withHistory(history(v[0], v[1]));
        try {
            unzip(v[0], tool, false);
            check(run(root, tool, v[0], "apply") == 0 && hasState(sol(root)), "OLD patched sol_mod: " + out);
            unzip(v[1], tool, false);
            run(root, tool, v[1], "status", "--json");
            Map<String, Object> d = json(), sol = GuiTests.mod(d, "sol_mod");
            check(sol != null && "dropped".equals(sol.get("state")) && "checked".equals(sol.get("kind")), "sol_mod is dropped, not checked: " + sol);
            check(String.valueOf(sol.get("state_text")).contains("Click Apply to put its original files back") && !String.valueOf(sol.get("state_text")).contains("needs no change"), "its row says the truth: " + sol);
            check(Boolean.TRUE.equals(d.get("apply_has_work")) && List.of("Sol Mod").equals(d.get("apply_todo")) && Boolean.TRUE.equals(d.get("undo_has_work")), "Apply and Undo have work: " + d);
            // the same with a mod that is not on the checked list: it is no "not supported" mod
            Path plain = release("2.0", Map.of("fx_mod.json", fxWithoutLine()), BAT_NEW);
            run(root, tool, plain, "status", "--json");
            d = json();
            check("dropped".equals(GuiTests.mod(d, "sol_mod").get("state")) && "dropped".equals(GuiTests.mod(d, "sol_mod").get("kind")) && !String.valueOf(d.get("unsupported")).contains("sol_mod"), "dropped either way: " + d);
            unzip(v[1], tool, false);
            check(run(root, tool, v[1], "apply") == 0 && !hasState(sol(root)), "apply puts it back: " + out);
            run(root, tool, v[1], "status", "--json");
            d = json();
            check(Boolean.FALSE.equals(d.get("apply_has_work")) && Boolean.FALSE.equals(d.get("undo_has_work")), "nothing left to do: " + d);
            // a monitor of another build with the same version is Apply's work too
            Path mon = root.resolve("mods").resolve(Checks.MONITOR_DIR);
            write(mon, "extra.txt", "another build's file");
            run(root, tool, v[1], "status", "--json");
            d = json();
            check(Boolean.TRUE.equals(d.get("apply_has_work")) && Boolean.TRUE.equals(d.get("monitor_to_replace")) && ((List<Object>) d.get("apply_todo")).isEmpty(), "a monitor to replace: " + d);
        } finally { restoreHistory(); }
    }

    static void windowAfterSwitch() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) { System.out.println("  (window test skipped: no display)"); return; }
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path[] v = onlyDropped(root);
        History h = history(v[0], v[1]);
        withHistory(h);
        try {
            unzip(v[0], tool, false);
            check(run(root, tool, v[0], "apply") == 0 && hasState(sol(root)), "OLD patched sol_mod: " + out);
            Path jar = realJar(v[1], h);
            if (jar == null) { System.out.println("  (window test skipped: the suite runs from a jar)"); return; }
            unzip(v[1], tool, false);
            String shots = System.getProperty("sr.shots");
            String said = survivalrebalance.gui.WindowCheck.afterSwitch(tool, tool.resolve("app/SurvivalRebalance.jar"), root, shots == null ? Tests.tmp.resolve("shots") : Path.of(shots));
            check(said == null, said);
            check(!hasState(sol(root)) && Build.treeHashes(sol(root)).equals(Build.treeHashes(sol(game()))), "the window's Apply put sol_mod back byte for byte");
            Files2.deleteTree(root.resolve("mods").resolve(Checks.MONITOR_DIR));
            Map<String, String> now = Tests.hashes(root);
            now.keySet().removeIf(k -> k.startsWith("saves"));
            Map<String, String> b = new TreeMap<>(before);
            b.keySet().removeIf(k -> k.startsWith("saves") || k.startsWith("mods/FxMod"));
            check(now.equals(b), "every mod as it was: " + now.keySet());
        } finally { restoreHistory(); }
    }
}