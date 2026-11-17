package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static survivalrebalance.SwitchTests.fx;
import static survivalrebalance.SwitchTests.game;
import static survivalrebalance.SwitchTests.hasState;
import static survivalrebalance.SwitchTests.history;
import static survivalrebalance.SwitchTests.json;
import static survivalrebalance.SwitchTests.out;
import static survivalrebalance.SwitchTests.pristine;
import static survivalrebalance.SwitchTests.release;
import static survivalrebalance.SwitchTests.restoreHistory;
import static survivalrebalance.SwitchTests.run;
import static survivalrebalance.SwitchTests.sol;
import static survivalrebalance.SwitchTests.unzip;
import static survivalrebalance.SwitchTests.withHistory;
import static survivalrebalance.SwitchTests.write;
import static survivalrebalance.Tests.check;

/**
 * The tool folder and what the tool says about it: read-only commands, a missing Monitor, a
 * dropped mod updated in place, refused runs in the window, the files of a newer version beside
 * the jar, the zip's contents put loose into another folder, folders Java cannot run from, and
 * the check for a running game.
 */
final class FolderTests {
    private FolderTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("folder: status, check and a dry run write nothing in the tool folder", FolderTests::readOnlyRuns);
        t.put("folder: a missing Monitor is work for Apply while a mod is rebalanced", FolderTests::monitorMissing);
        t.put("folder: a dropped mod updated in place is not offered to Apply or Undo", FolderTests::droppedModified);
        t.put("folder: an up-to-date mod with a damaged backup is one status line, not a failed plan", FolderTests::upToDateBadBackup);
        t.put("folder: undo with the Monitor's jar held leaves it whole and ends normally", FolderTests::undoMonitorHeld);
        t.put("folder: a held file names another program unless the game is seen; a read-only file is said at once", FolderTests::heldWords);
        t.put("folder: the window shows a refused run as refused", FolderTests::refusedInWindow);
        t.put("folder: a newer version's files beside the jar: said first, no mod changed", FolderTests::newerFiles);
        t.put("folder: the zip's contents loose in the game folder or Downloads: nothing written there", FolderTests::looseLayout);
        t.put("folder: the bats and the launcher end in one plain line where Java cannot run", FolderTests::badPaths);
        t.put("folder: the game-running check on stand-in processes", FolderTests::gameRunningCheck);
        t.put("folder: the window's Apply tidies the folder when every mod is up to date", FolderTests::windowTidy);
        t.put("folder: status on a dropped mod with a tracked file it cannot read ends with its summary", FolderTests::droppedUnreadable);
        t.put("folder: with shipped.txt deleted the install root is still the folder above app", FolderTests::rootWithoutShipped);
    }

    static void windowTidy() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) { System.out.println("  (window test skipped: no display)"); return; }
        Path root = game(), tool = root.resolve("tool");
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_OLD);
        write(old, "app/old_only.txt", "a file only OLD shipped");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        History h = history(old, neu);
        withHistory(h);
        try {
            Path jar = SwitchTests.realJar(neu, h);
            if (jar == null) { System.out.println("  (window test skipped: the suite runs from a jar)"); return; }
            unzip(neu, tool, false);
            Path tjar = tool.resolve("app/SurvivalRebalance.jar");
            check(SwitchTests.child(tjar, SwitchTests.engineArgs(root, "apply")) == 0 && hasState(fx(root)), "apply: " + out);
            // every mod up to date, and a file another version left in the folder
            write(tool, "app/old_only.txt", "a file only OLD shipped");
            String said = survivalrebalance.gui.WindowCheck.tidyOnly(tool, tjar, root, Tests.tmp.resolve("shots"));
            check(said == null, said);
            check(!Files.exists(tool.resolve("app/old_only.txt")), "the window's Apply removed the leftover file");
            check(SwitchTests.child(tjar, SwitchTests.engineArgs(root, "undo")) == 0 && !hasState(fx(root)), "undo: " + out);
        } finally { restoreHistory(); }
    }

    static void droppedUnreadable() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SwitchTests.SOL), SwitchTests.BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", SwitchTests.fxWithoutLine()), SwitchTests.BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0 && hasState(sol(root)), "OLD patched sol_mod: " + out);
            unzip(neu, tool, false);
            // a tracked file the tool cannot read: here a folder stands where the file was
            String rel = SwitchTests.lastTracked(sol(root));
            Path f = sol(root).resolve(rel);
            byte[] was = Files.readAllBytes(f);
            Files.delete(f);
            Files.createDirectories(f);
            try {
                int rc;
                try { rc = run(root, tool, neu, "status"); } catch (Failure e) { rc = -1; out = "ERROR: " + e.getMessage(); }
                check(rc == 1 && out.contains("Summary") && !out.contains("ERROR"), "status ends with its summary: " + rc + " " + out);
                check(out.contains("Apply and undo leave it alone."), "and says the mod is left alone: " + out);
                run(root, tool, neu, "status", "--json");
                check("dropped_modified".equals(GuiTests.mod(json(), "sol_mod").get("state")), "the window agrees: " + out);
            } finally {
                Files.delete(f);
                Files.write(f, was);
            }
            check(run(root, tool, neu, "apply") == 0 && !hasState(sol(root)), "readable again, apply puts it back: " + out);
        } finally { restoreHistory(); }
    }

    static void rootWithoutShipped() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            Files.delete(tool.resolve(Updater.SHIPPED));
            check(Updater.installRoot(tool.resolve("app")).equals(tool), "the root is the folder above app: " + Updater.installRoot(tool.resolve("app")));
            write(tool, "customize.txt", "fx_mod = off\r\n");
            run(root, tool, neu, "apply");
            check(out.contains("Switched off") && !hasState(fx(root)), "customize.txt is read from the folder above app: " + out);
            check(Files.isDirectory(tool.resolve("logs")) && !Files.exists(tool.resolve("app/logs")), "and the log goes there too");
        } finally { restoreHistory(); }
    }

    /** Every file under the tool folder with its bytes, the logs left out (every run writes one). */
    static Map<String, String> all(Path tool) throws Exception {
        Map<String, String> m = Build.treeHashes(tool);
        m.keySet().removeIf(k -> k.startsWith("logs/"));
        return m;
    }

    static void readOnlyRuns() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SwitchTests.SOL), SwitchTests.BAT_OLD);
        write(old, "app/old_only.txt", "a file only OLD shipped");
        Path neu = release("2.0", Map.of("fx_mod.json", SwitchTests.fxWithoutLine()), SwitchTests.BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0, "OLD apply: " + out);
            unzip(neu, tool, false);
            Files.delete(tool.resolve("README.txt"));
            Map<String, String> files = all(tool);
            Map<String, java.nio.file.attribute.FileTime> times = SwitchTests.folderTimes(root);
            Thread.sleep(20);
            for (String[] cmd : new String[][]{{"status"}, {"status", "--json"}, {"check"}, {"apply", "--dry-run"}, {"undo", "--dry-run"}}) {
                run(root, tool, neu, cmd[0], cmd.length > 1 ? new String[]{cmd[1]} : new String[0]);
                String what = String.join(" ", cmd);
                check(all(tool).equals(files), what + " changed no file in the tool folder: " + out);
                check(SwitchTests.changedFolders(times, SwitchTests.folderTimes(root)).isEmpty(), what + " changed no folder: " + SwitchTests.changedFolders(times, SwitchTests.folderTimes(root)));
            }
            run(root, tool, neu, "status");
            check(out.contains("Apply will remove the files another version of the tool left in its folder (app\\old_only.txt, app\\patches\\sol_mod.json) and put back the files of this version that are missing or changed (README.txt)."),
                    "status says what apply would do, in one line: " + out);
            check(run(root, tool, neu, "apply") == 0 && !Files.exists(tool.resolve("app/old_only.txt")) && Files.exists(tool.resolve("README.txt")), "apply tidies the folder: " + out);
        } finally { restoreHistory(); }
    }

    static void monitorMissing() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            run(root, tool, neu, "status", "--json");
            check(Boolean.FALSE.equals(json().get("monitor_missing")), "nothing rebalanced: a missing Monitor is no work of its own: " + out);
            check(run(root, tool, neu, "apply") == 0 && hasState(fx(root)), "apply: " + out);
            Path mon = root.resolve("mods").resolve(Checks.MONITOR_DIR);
            check(Files.isDirectory(mon), "apply installed the Monitor");
            run(root, tool, neu, "status", "--json");
            check(Boolean.FALSE.equals(json().get("apply_has_work")), "all done: " + out);
            Files2.deleteTree(mon);
            run(root, tool, neu, "status", "--json");
            Map<String, Object> d = json();
            check(Boolean.TRUE.equals(d.get("monitor_missing")) && Boolean.TRUE.equals(d.get("apply_has_work")) && ((List<?>) d.get("apply_todo")).isEmpty(), "the Monitor gone: Apply has work: " + d);
            check(run(root, tool, neu, "apply") == 0 && Files.isRegularFile(mon.resolve("mod_info.json")) && out.contains("Installed"), "apply puts it back: " + out);
            run(root, tool, neu, "status", "--json");
            check(Boolean.FALSE.equals(json().get("apply_has_work")), "then nothing is left to do: " + out);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: pristine: " + out);
        } finally { restoreHistory(); }
    }

    static void droppedModified() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path old = release("1.0", Map.of("fx_mod.json", Tests.patchJson(false), "sol_mod.json", SwitchTests.SOL), SwitchTests.BAT_OLD);
        Path neu = release("2.0", Map.of("fx_mod.json", SwitchTests.fxWithoutLine()), SwitchTests.BAT_NEW);
        withHistory(history(old, neu));
        try {
            unzip(old, tool, false);
            check(run(root, tool, old, "apply") == 0 && hasState(sol(root)), "OLD patched sol_mod: " + out);
            SwitchTests.recordVersion(sol(root), "1.3.1");
            unzip(neu, tool, false);
            // the mod updated in place: a tracked file at neither the before nor the after hash
            String rel = SwitchTests.lastTracked(sol(root));
            Files.write(sol(root).resolve(rel), "an update of the mod".getBytes(StandardCharsets.UTF_8));
            Map<String, String> mod = Build.treeHashes(sol(root));
            run(root, tool, neu, "status");
            check(out.contains("Apply and undo leave it alone.") && out.contains("Sol Mod 1.0: rebalanced by another version of the tool and updated in place since, so apply and undo leave it alone (changed: " + rel + ")."),
                    "status says what is true: " + out);
            check(!out.contains("Run apply; it puts this mod's original files back") && !out.contains("Apply or undo puts its original files back"), "and does not offer what will be refused: " + out);
            run(root, tool, neu, "status", "--json");
            Map<String, Object> d = json(), s = GuiTests.mod(d, "sol_mod");
            check("dropped_modified".equals(s.get("state")) && String.valueOf(s.get("state_text")).contains("Apply and Undo leave it alone") && !String.valueOf(s.get("state_text")).contains("Click Apply"), "the window row: " + s);
            check(!String.valueOf(d.get("apply_todo")).contains("Sol Mod") && !String.valueOf(d.get("undo_todo")).contains("Sol Mod"), "neither button counts it: " + d);
            run(root, tool, neu, "apply");
            check(Build.treeHashes(sol(root)).equals(mod), "apply leaves it as it was: " + out);
            check(run(root, tool, neu, "undo", "--force") == 0 && !hasState(sol(root)), "undo --force puts the backups back: " + out);
        } finally { restoreHistory(); }
    }

    static void upToDateBadBackup() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            check(run(root, tool, neu, "apply") == 0, "apply: " + out);
            String rel = "data/config/settings.json";
            Path orig = Engine.stateDir(fx(root)).resolve("backup").resolve(rel + ".orig");
            for (String how : new String[]{"damaged", "missing"}) {
                if (how.equals("damaged")) Files.writeString(orig, Files.readString(orig) + "\n# edited\n"); else Files.delete(orig);
                int rc = run(root, tool, neu, "status");
                check(rc == 0, how + ": status exits as apply does: " + rc + " " + out);
                check(out.contains("Apply would do nothing: up to date.") && !out.contains("Apply would fail"), how + ": one status line: " + out);
                check(out.contains("  Already applied") && !out.contains("  Failed "), how + ": one Summary row: " + out);
                check(out.contains("so undo cannot put that file back") && !out.contains("It is left alone"), how + ": the true warning only: " + out);
                run(root, tool, neu, "status", "--json");
                check("patched".equals(GuiTests.mod(json(), "fx_mod").get("state")), how + ": the window row: " + out);
                check(run(root, tool, neu, "apply") == 0 && out.contains("Already applied."), how + ": apply agrees: " + out);
            }
        } finally { restoreHistory(); }
    }

    /** A release whose Monitor carries a jar a class loader can hold. */
    static Path releaseWithMonitorJar() throws Exception {
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        Path j = neu.resolve("app/" + Checks.MONITOR_DIR + "/jars/m.jar");
        Files.createDirectories(j.getParent());
        Files.write(j, Tests.fixtureJar);
        return neu;
    }

    static void undoMonitorHeld() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = releaseWithMonitorJar();
        withHistory(history(neu));
        try {
            unzip(neu, tool, false);
            check(run(root, tool, neu, "apply") == 0, "apply: " + out);
            Path mon = root.resolve("mods").resolve(Checks.MONITOR_DIR);
            Map<String, String> was = Build.treeHashes(mon);
            int rc;
            try (java.net.URLClassLoader c = SwitchTests.gameHolds(mon.resolve("jars/m.jar"))) {
                rc = run(root, tool, neu, "undo");
            }
            check(rc == 0 && out.contains("Summary") && !out.contains("ERROR"), "the undo ends with its summary and a normal exit: " + rc + " " + out);
            check(Build.treeHashes(mon).keySet().containsAll(was.keySet()) && Files.isRegularFile(mon.resolve("mod_info.json")), "the Monitor is left whole: " + Build.treeHashes(mon).keySet());
            check(out.contains("Survival Rebalance Monitor: could not be removed: another program has jars/m.jar open, most likely Starsector."), "one line says so: " + out);
            check(!hasState(fx(root)), "the mods were undone");
            check(run(root, tool, neu, "undo") == 0 && !Files.exists(mon) && pristine(root, before), "free again, undo removes it: " + out);
        } finally { restoreHistory(); }
    }

    static void heldWords() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Files.createDirectories(root.resolve("starsector-core"));
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        java.util.function.Predicate<Path> real = GameRunning.check;
        try {
            unzip(neu, tool, false);
            Path jar = fx(root).resolve("jars/fx.jar");
            try (java.net.URLClassLoader c = SwitchTests.gameHolds(jar)) {
                GameRunning.check = g -> false;
                run(root, tool, neu, "status");
                check(out.contains("Fx Mod 1.0: another program has jars/fx.jar open, so the tool cannot change it. Most likely that program is Starsector.") && !out.contains("Starsector is running"), "the game not seen: another program: " + out);
                GameRunning.check = g -> true;
                run(root, tool, neu, "status");
                check(out.contains("Starsector is running: Fx Mod's jars/fx.jar is open in the game"), "the game seen: the game: " + out);
            }
            GameRunning.check = g -> false;
            // a read-only file is said at once, with no wait for it to come free
            Path f = fx(root).resolve("data/config/settings.json");
            Files.setAttribute(f, "dos:readonly", true);
            try {
                Files2.sawBusy = false;
                int w0 = Files2.waits;
                long s = System.nanoTime();
                check(!Files2.writable(f) && Files2.denied(f), "a read-only file is not writable, and denied");
                check(Files2.waits == w0 && (System.nanoTime() - s) / 1_000_000 < 300 && !Files2.sawBusy, "and no wait was spent on it");
                check(run(root, tool, neu, "apply") == 1 && out.contains("FAILED: data/config/settings.json is marked read-only, or Windows does not let you change it.") && !out.contains("another program"), "apply names the read-only mark: " + out);
            } finally { Files.setAttribute(f, "dos:readonly", false); }
            Files2.sawBusy = false;
            check(Files2.writable(f) && !Files2.denied(f), "a free file is writable");
        } finally { GameRunning.check = real; restoreHistory(); }
    }

    static void refusedInWindow() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Files.createDirectories(root.resolve("starsector-core"));
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        java.util.function.Predicate<Path> real = GameRunning.check;
        try {
            unzip(neu, tool, false);
            GameRunning.check = g -> true;
            for (String cmd : new String[]{"apply", "undo"}) {
                String title = cmd.equals("apply") ? "Apply" : "Undo";
                int rc = run(root, tool, neu, cmd);
                String h = survivalrebalance.gui.RunReport.headline(title, out, rc), s = survivalrebalance.gui.RunReport.summary(title, out, rc);
                check(h.equals(title + " did not run. Nothing was changed."), cmd + ": the headline says refused: " + h);
                check(s.startsWith("Starsector is running. Close the game first, then click " + title + " again.") && !s.contains("Done."), cmd + ": the body names the reason: " + s);
            }
        } finally { GameRunning.check = real; restoreHistory(); }
    }

    static void newerFiles() throws Exception {
        Path root = game(), tool = root.resolve("tool");
        Map<String, String> before = Tests.hashes(root);
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        try {
            // the build's own step writes this version's file into a release folder
            Path stage = Files.createTempDirectory(Tests.tmp, "stage");
            Files.createDirectories(stage.resolve("app"));
            check(Main.run(new String[]{"shiplist", "--out", stage.toString()}, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) == 0, "shiplist");
            Path own = stage.resolve("app").resolve(ToolFolder.versionFile(Main.VERSION));
            check(Files.readString(own).equals(ToolFolder.versionText(Main.VERSION)) && Files.readString(stage.resolve(Updater.SHIPPED)).contains("app/" + ToolFolder.versionFile(Main.VERSION)), "the release ships its version file, and lists it");
            unzip(neu, tool, false);
            check(run(root, tool, neu, "apply") == 0, "apply: " + out);
            Map<String, String> mods = Tests.hashes(root);
            // a later version unzipped over this one, keeping the files that were there: only its new files came in.
            // The words are written out here, not through versionText(): every release has to read them.
            String line = "The files in this folder are from Survival Rebalance 9.9.9.";
            check(ToolFolder.versionText("9.9.9").equals(line + "\r\n"), "the release writes the pinned line: " + ToolFolder.versionText("9.9.9"));
            Path vf = tool.resolve("app/" + ToolFolder.versionFile("9.9.9"));
            for (String body : new String[]{line + "\n", "\uFEFF" + line + "\r\n", line, line + "\r\n\r\n  \r\n", line + "  \n", line + "\r\n"}) {
                Files.writeString(vf, body);
                check("9.9.9".equals(ToolFolder.newerFolder(tool.resolve("app"), Main.VERSION)), "the version file is read with this line end, BOM or blanks: " + body.replace("\r", "\\r").replace("\n", "\\n"));
            }
            for (String body : new String[]{"The files in this folder are from Survival Rebalance 9.9.8.\r\n", line + "\r\nand more words\r\n", "my own notes"}) {
                Files.writeString(vf, body);
                check(ToolFolder.newerFolder(tool.resolve("app"), Main.VERSION) == null, "other words are not a version file: " + body);
            }
            Files.writeString(vf, line + "\r\n");
            String says = "This folder holds the files of Survival Rebalance 9.9.9, but the program that runs is version " + Main.VERSION + "."
                    + " If you unzipped 9.9.9 over " + Main.VERSION + " and kept the old files, unzip 9.9.9 again and choose to replace the files."
                    + " If you unzipped " + Main.VERSION + " over 9.9.9 to go back, copy customize.txt out, delete the SurvivalRebalance folder, unzip " + Main.VERSION + " again and put customize.txt back.";
            for (String cmd : new String[]{"status", "apply", "check"}) {
                int rc = run(root, tool, neu, cmd);
                String[] parts = out.replace("\r", "").split("\n\n", 3);
                if (!cmd.equals("check")) check(parts.length > 1 && parts[1].startsWith(says), cmd + ": said first: " + out);
                else check(out.contains("1. Survival Rebalance: " + says.substring(0, 60)), "check: the first warning: " + out);
                if (cmd.equals("apply")) check(rc == 1 && out.contains("Nothing was changed.") && !out.contains("Summary"), cmd + " refuses: " + out);
                check(Tests.hashes(root).equals(mods), cmd + ": no mod changed");
            }
            run(root, tool, neu, "apply");
            check(survivalrebalance.gui.RunReport.headline("Apply", out, 1).equals("Apply did not run. Nothing was changed.") && survivalrebalance.gui.RunReport.summary("Apply", out, 1).startsWith(says), "the window shows the refusal: " + out);
            run(root, tool, neu, "status", "--json");
            Map<String, Object> d = json();
            check("9.9.9".equals(d.get("newer_folder")) && Boolean.FALSE.equals(d.get("apply_has_work")) && Boolean.TRUE.equals(d.get("undo_has_work")), "the window: Apply off, Undo on: " + d);
            check(String.valueOf(d.get("window_warnings")).contains("This folder holds the files of Survival Rebalance 9.9.9"), "and the warning: " + d);
            String head = survivalrebalance.gui.Window.newerHeadline("9.9.9", Main.VERSION);
            check(head.contains("If you unzipped 9.9.9 and kept the old files, unzip 9.9.9 again") && head.contains("If you unzipped " + Main.VERSION + " over 9.9.9 to go back") && head.contains("Undo still works."), "the window's headline gives both causes: " + head);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            Main.run(new String[]{"customize", "--tool-dir", tool.resolve("app").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
            check(b.toString(StandardCharsets.UTF_8).startsWith(says), "customize says it first too: " + b);
            // undo runs: it only puts original files back, which every version can do for every other
            int rc = run(root, tool, neu, "undo");
            check(rc == 0 && out.contains("Summary") && !out.contains("Nothing was changed.") && out.contains(says), "undo runs, after the same line: " + out);
            check(pristine(root, before) && Files.exists(vf), "undo put the mods back and left the newer version's file: " + out);
            check(survivalrebalance.gui.RunReport.headline("Undo", out, rc).startsWith("Done."), "the window shows the undo as done: " + out);
            check(run(root, tool, neu, "apply") == 1 && pristine(root, before), "apply still refuses: " + out);
            // a file that only looks like one, and an older version's file: neither stops anything
            Files.delete(tool.resolve("app/" + ToolFolder.versionFile("9.9.9")));
            write(tool, "app/" + ToolFolder.versionFile("9.9.8"), "my own notes");
            write(tool, "app/" + ToolFolder.versionFile("1.0.0"), ToolFolder.versionText("1.0.0"));
            run(root, tool, neu, "status");
            check(!out.contains("This folder holds the files") && out.contains("app\\" + ToolFolder.versionFile("1.0.0")), "an older version's file is apply's to remove: " + out);
            check(run(root, tool, neu, "undo") == 0 && pristine(root, before), "undo: " + out);
            check(!Files.exists(tool.resolve("app/" + ToolFolder.versionFile("1.0.0"))) && Files.exists(tool.resolve("app/" + ToolFolder.versionFile("9.9.8"))), "undo removed the older version's file and kept the player's");
        } finally { restoreHistory(); }
    }

    static void looseLayout() throws Exception {
        Path neu = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        withHistory(history(neu));
        try {
            // loose in the Starsector folder: the game's own README.txt beside the tool's files
            Path root = game();
            Files.createDirectories(root.resolve("starsector-core"));
            write(root, "README.txt", "the game's own readme");
            unzip(neu, root, true);
            looseRelease = neu;
            check(Files.readString(root.resolve("README.txt")).equals("the game's own readme"), "fixture: the game's README is there");
            Map<String, String> before = outsideMods(root);
            int rc = runLoose(root, "apply");
            check(rc == 0 && out.contains("Applied: Fuel halved"), "apply still rebalances: " + out);
            check(outsideMods(root).equals(before), "nothing written outside mods and saves: " + outsideMods(root).keySet());
            check(out.contains("Survival Rebalance: its files are loose in " + root + ", not in a folder of their own, so the tool writes nothing there.")
                    && out.contains("Unzip SurvivalRebalance.zip into the Starsector folder as it is, so it makes a SurvivalRebalance folder"), "one plain line says how to install it: " + out);
            check(!out.contains("another copy of the tool"), "it does not call itself another copy: " + out);
            check(runLoose(root, "undo") == 0 && outsideMods(root).equals(before), "undo writes nothing there either: " + out);
            // loose in Downloads: more than three things that are not the tool's
            Path dl = Files.createTempDirectory(Tests.tmp, "Downloads"), game = game();
            for (String n : new String[]{"photo.jpg", "setup.exe", "notes.txt", "README.txt"}) write(dl, n, "the player's " + n);
            write(dl, "other/a.txt", "a folder of the player's");
            unzip(neu, dl, true);
            Map<String, String> d0 = Build.treeHashes(dl);
            Bundle.override = Bundle.of(neu);
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                Main.run(new String[]{"status", "--mods", game.resolve("mods").toString(), "--saves", game.resolve("saves/common").toString(), "--tool-dir", dl.resolve("app").toString(), "--dev", "--offline"}, new PrintStream(b, true, StandardCharsets.UTF_8));
                SwitchTests.out = b.toString(StandardCharsets.UTF_8);
            } finally { Bundle.override = null; }
            check(out.contains("its files are loose in " + dl), "Downloads is loose too: " + out);
            check(Build.treeHashes(dl).equals(d0) && !Files.exists(dl.resolve("logs")), "and nothing is written there, not even a log: " + Build.treeHashes(dl).keySet());
            check(!Files.exists(root.resolve("logs")), "nor a log in the game folder");
            // a tool folder of its own with a file or two of the player's is not loose
            Path root2 = game(), tool2 = root2.resolve("tool");
            unzip(neu, tool2, false);
            write(tool2, "my_notes.txt", "mine");
            run(root2, tool2, neu, "status");
            check(!out.contains("loose"), "a folder of its own is not loose: " + out);
            // the SurvivalRebalance folder, with four files of the player's and the tool's own .edited copies: not loose, and apply tidies it
            Path root3 = game(), tool3 = root3.resolve("SurvivalRebalance");
            unzip(neu, tool3, false);
            for (String n : new String[]{"customize - Copy.txt", "notes.txt", "Survival Rebalance.lnk", "desktop.ini", "README.txt.edited", "README.txt.edited2", "shipped.txt.edited"}) write(tool3, n, "the player's " + n);
            Files.delete(tool3.resolve("README.txt"));
            check(run(root3, tool3, neu, "apply") == 0 && !out.contains("loose") && Files.exists(tool3.resolve("README.txt")) && out.contains("Put back the files of this version"), "the SurvivalRebalance folder is not loose, and apply tidies it: " + out);
            check(Files.isDirectory(tool3.resolve("logs")), "it keeps its log");
            // a tool folder of another name, with three files of the player's and three .edited copies: not loose
            Path root4 = game(), tool4 = root4.resolve("SR (new) & old");
            unzip(neu, tool4, false);
            for (String n : new String[]{"notes.txt", "desktop.ini", "backup.zip", "README.txt.edited", "README.txt.edited2", "shipped.txt.edited"}) write(tool4, n, "the player's " + n);
            run(root4, tool4, neu, "status");
            check(!out.contains("loose"), "a renamed tool folder with a few files of the player's is not loose: " + out);
        } finally { restoreHistory(); }
    }

    /** Everything under a game folder but its mods and saves. */
    static Map<String, String> outsideMods(Path root) throws Exception {
        Map<String, String> m = Build.treeHashes(root);
        m.keySet().removeIf(k -> k.startsWith("mods/") || k.startsWith("saves/"));
        return m;
    }

    static int runLoose(Path root, String cmd) throws Exception {
        Bundle.override = Bundle.of(looseRelease);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int rc = Main.run(new String[]{cmd, "--game", root.toString(), "--tool-dir", root.resolve("app").toString(), "--dev", "--offline"}, new PrintStream(b, true, StandardCharsets.UTF_8));
            SwitchTests.out = b.toString(StandardCharsets.UTF_8);
            return rc;
        } finally { Bundle.override = null; }
    }

    static Path looseRelease;

    // ---------------------------------------------------------------- folders Java cannot run from

    /** A fixture install of the real bats and jar under base\<folder>\Starsector; status.bat through cmd. Null when there is no jar to use. */
    static String statusBat(Path base, String folder, byte[] jar) throws Exception { return statusBat(base, folder, "SurvivalRebalance", jar, true); }

    /**
     * withJava false: the game has no jre, game_dir.txt names it, and PATH holds no Java, so the
     * bat finds no Java at all.
     */
    static String statusBat(Path base, String folder, String toolName, byte[] jar, boolean withJava) throws Exception {
        Path game = base.resolve(folder).resolve("Starsector");
        Tests.game(game, "1.0");
        Path tool = game.resolve(toolName), app = tool.resolve("app");
        Files.createDirectories(app);
        Path bats = Path.of("dist-src").toAbsolutePath();
        Files.copy(bats.resolve("status.bat"), app.resolve("status.bat"));
        Files.write(app.resolve("SurvivalRebalance.jar"), jar);
        Files.writeString(tool.resolve(Updater.SHIPPED), "app/status.bat\r\n");
        if (!withJava) Files.writeString(tool.resolve("game_dir.txt"), game + "\r\n");
        // the game's own Java, as the bats find it: a junction to the Java that runs this suite
        Path jre = game.resolve("jre");
        if (withJava) {
            Process mk = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", jre.toString(), System.getProperty("java.home")).redirectErrorStream(true).start();
            mk.getInputStream().readAllBytes();
            mk.waitFor(30, TimeUnit.SECONDS);
        }
        try {
            if (withJava) check(Files.isRegularFile(jre.resolve("bin/java.exe")), "fixture: the game's jre");
            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "\"\"" + app.resolve("status.bat") + "\" --dev --offline\"");
            pb.directory(app.toFile());
            String sys = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32").toString();
            pb.environment().put("PATH", withJava ? Path.of(System.getProperty("java.home"), "bin") + File.pathSeparator + System.getenv("PATH") : sys);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] o = p.getInputStream().readAllBytes();
            check(p.waitFor(120, TimeUnit.SECONDS), "status.bat finished");
            return "rc=" + p.exitValue() + "\n" + new String(o, StandardCharsets.UTF_8);
        } finally {
            // the junction goes first, so no later clean-up of the suite's folder can reach the Java it points to
            if (!Files.exists(jre, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { /* no junction was made */ }
            else try { Files.delete(jre); } catch (java.io.IOException e) { new ProcessBuilder("cmd.exe", "/c", "rmdir", jre.toString()).start().waitFor(30, TimeUnit.SECONDS); }
            if (Files.exists(jre, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { System.out.println("FATAL: the fixture junction " + jre + " could not be removed. Remove it by hand (rmdir) before deleting " + Tests.tmp); Runtime.getRuntime().halt(3); }
        }    }

    static final String SEMI_LINE = "Survival Rebalance cannot run from this folder, because its path has a ; in it.";
    /** Every line a bat ends with when Java cannot start the jar; none may show for a jar that runs. */
    static final String[] JAVA_LINES = {"Java could not start Survival Rebalance.", "Survival Rebalance cannot run from the Starsector folder", "because the path of that folder has letters", "Cannot find Java.", "The Java in the Starsector folder cannot start"};

    static boolean anyJavaLine(String t) {
        for (String l : JAVA_LINES) if (t.contains(l)) return true;
        return false;
    }

    static void badPaths() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows") || !Files.isRegularFile(Path.of("dist-src", "status.bat"))) { System.out.println("  (bat paths skipped: run from the tool folder on Windows)"); return; }
        Path base = Files.createTempDirectory(Tests.tmp, "paths");
        // a ; in the path: said before Java is started
        String t = statusBat(base, "Games; Mods", new byte[]{1, 2, 3});
        check(t.startsWith("rc=2") && t.contains(SEMI_LINE) && !t.contains("Error") && !t.contains("jimage"), "a ; in the path: one plain line: " + t);
        // a jar Java cannot start (here a damaged one) from a plain folder: the jar or the Java, never the folder
        t = statusBat(base, "Plain", new byte[]{1, 2, 3});
        check(t.startsWith("rc=2") && t.contains(JAVA_LINES[0]) && t.contains("older than Java 17") && !t.contains("Move the Starsector folder") && !t.contains("Error:") && !t.contains("Invalid or corrupt"), "Java cannot start the jar: one plain line: " + t);
        // no Java at all: said so, with the folder it looked in
        t = statusBat(base, "NoJava", "SurvivalRebalance", new byte[]{1, 2, 3}, false);
        check(t.startsWith("rc=2") && t.contains(JAVA_LINES[3]) && t.contains(base.resolve("NoJava").resolve("Starsector").toString()) && !t.contains("is not recognized"), "no Java: one plain line: " + t);
        // letters outside the code page, with a jar that works elsewhere
        Path rel = release("2.0", Map.of("fx_mod.json", Tests.patchJson(false)), SwitchTests.BAT_NEW);
        History h = history(rel);
        Path jar = SwitchTests.realJar(rel, h);
        if (jar == null) { System.out.println("  (bat paths: the real-jar half skipped, the suite runs from a jar)"); }
        else {
            byte[] jb = Files.readAllBytes(jar);
            t = statusBat(base, "Fine", jb);
            check(!anyJavaLine(t) && t.contains("Survival Rebalance " + Main.VERSION), "the control: the same jar runs from a plain folder: " + t);
            String odd = "ゲーム";
            boolean outside = !java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", "Cp1252")).newEncoder().canEncode(odd);
            if (outside) {
                // the letters in the Starsector folder's path: move the Starsector folder (the game's own Java may not start there at all)
                t = statusBat(base, "Games " + odd, jb);
                check(t.startsWith("rc=2") && (t.contains(JAVA_LINES[1]) || t.contains(JAVA_LINES[4])) && !t.contains(JAVA_LINES[0]) && t.contains("Move the Starsector folder") && !t.contains("Unable to access jarfile"), "letters Java cannot read in the game's path: one plain line: " + t);
                // the letters only in the tool folder's own name: that folder is named, the game is not blamed
                t = statusBat(base, "Plain2", "SR " + odd + " & tool", jb, true);
                check(t.startsWith("rc=2") && t.contains(JAVA_LINES[2]) && t.contains(" & tool\"") && !t.contains("Move the Starsector folder") && !t.contains("Unable to access jarfile"), "letters only in the tool folder's name: that folder is named: " + t);
            } else System.out.println("  (bat paths: this Windows' code page holds the test letters, so that case is skipped)");
        }
        // the launcher says the same before it starts Java
        Path exe = Path.of("launcher", "build", "SurvivalRebalance.exe").toAbsolutePath();
        if (!Files.isRegularFile(exe)) { System.out.println("  (launcher path check skipped: no launcher\\build\\SurvivalRebalance.exe)"); return; }
        for (String[] c : new String[][]{{"C:\\Games; Mods\\Starsector", "a ; in it"}, {"C:\\Games\\Starsector", null}, {"C:\\Games ゲーム\\Starsector", "letters that Java cannot read"}}) {
            Process p = new ProcessBuilder(exe.toString(), "--check-path", c[0]).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(p.waitFor(30, TimeUnit.SECONDS), "the launcher answered");
            if (c[1] == null) check(p.exitValue() == 0 && o.isBlank(), "the launcher: a plain path is fine: " + o);
            else if (c[1].startsWith("letters") && !java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", "Cp1252")).newEncoder().canEncode("ゲーム")) check(p.exitValue() == 1 && o.contains(c[1]) && o.contains("Move the Starsector folder"), "the launcher: " + c[0] + ": " + o);
            else if (!c[1].startsWith("letters")) check(p.exitValue() == 1 && o.contains(c[1]) && o.contains("Move the Starsector folder"), "the launcher: " + c[0] + ": " + o);
        }
        // the tool folder's own path at fault (the Starsector folder checked first and plain): that folder is to be renamed
        if (!java.nio.charset.Charset.forName(System.getProperty("sun.jnu.encoding", "Cp1252")).newEncoder().canEncode("ゲーム")) {
            Process p = new ProcessBuilder(exe.toString(), "--check-path", "C:\\Games\\Starsector\\SR ゲーム", "--tool").redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(p.waitFor(30, TimeUnit.SECONDS), "the launcher answered");
            check(p.exitValue() == 1 && o.contains("Rename that folder") && !o.contains("Move the Starsector folder"), "the launcher: the tool folder's name: " + o);
        }
    }

    // ---------------------------------------------------------------- the game-running check

    static GameRunning.Proc proc(long pid, String cmd, String args, Long parent, String parentCmd) { return new GameRunning.Proc(pid, cmd, args, parent, parentCmd); }

    static void gameRunningCheck() throws Exception {
        Path game = Path.of("C:\\Games\\Starsector"), other = Path.of("C:\\Other\\Starsector");
        String exe = game + "\\starsector.exe", gameJava = game + "\\jre\\bin\\java.exe";
        long me = 1;
        Set<Long> mine = Set.of(1L, 2L);
        // the cases checked with stand-in processes, as records
        check(!GameRunning.decide(game, List.of(), mine, me), "nothing running");
        check(GameRunning.decide(game, List.of(proc(10, exe, "", null, "")), mine, me), "starsector.exe in this game folder");
        check(!GameRunning.decide(game, List.of(proc(10, other + "\\starsector.exe", "", null, "")), mine, me), "starsector.exe in another folder");
        check(GameRunning.decide(game, List.of(proc(11, gameJava, "-cp starfarer.api.jar com.fs.starfarer.StarfarerLauncher", 10L, exe)), mine, me), "the game's java with its arguments");
        check(GameRunning.decide(game, List.of(proc(11, gameJava, "", 10L, exe)), mine, me), "the game's java, arguments unreadable, started by starsector.exe");
        check(!GameRunning.decide(game, List.of(proc(11, gameJava, "-jar some.jar", 20L, "C:\\Windows\\System32\\cmd.exe")), mine, me), "another Java program on this game's jre");
        check(!GameRunning.decide(game, List.of(proc(12, gameJava, "-cp app\\SurvivalRebalance.jar survivalrebalance.gui.Window", 20L, "")), mine, me), "the tool's own window");
        check(!GameRunning.decide(game, List.of(proc(2, exe, "", null, "")), mine, me), "the tool's own processes never count");
        check(!GameRunning.decide(game, List.of(proc(13, gameJava, "", 1L, "")), mine, me), "a java this process started");
        // the live path: a stand-in program named starsector.exe in a game folder
        if (!System.getProperty("os.name").startsWith("Windows")) return;
        Path ping = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "PING.EXE");
        if (!Files.isRegularFile(ping)) { System.out.println("  (live game-running check skipped: no ping.exe)"); return; }
        Path g = Files.createTempDirectory(Tests.tmp, "running"), g2 = Files.createTempDirectory(Tests.tmp, "notrunning");
        Files.copy(ping, g.resolve("starsector.exe"));
        check(!GameRunning.test(g), "the stand-in is not started yet");
        // started through "start", so it is not a child of this process (the check never counts its own children)
        Process s = new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/min", g.resolve("starsector.exe").toString(), "-n", "30", "127.0.0.1").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        s.waitFor(10, TimeUnit.SECONDS);
        String prefix = g.toAbsolutePath().toString().toLowerCase(java.util.Locale.ROOT);
        try {
            boolean seen = false;
            for (int i = 0; i < 50 && !seen; i++) { seen = GameRunning.test(g); if (!seen) Thread.sleep(100); }
            check(seen, "a running starsector.exe in the game folder is seen");
            check(!GameRunning.test(g2), "and only for its own folder");
        } finally {
            ProcessHandle.allProcesses().filter(h -> h.info().command().orElse("").toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).forEach(ProcessHandle::destroyForcibly);
        }
        for (int i = 0; i < 50 && GameRunning.test(g); i++) Thread.sleep(100);
        check(!GameRunning.test(g), "once it ends, the game is not running");
    }
}
