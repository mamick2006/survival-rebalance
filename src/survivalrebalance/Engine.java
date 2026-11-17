package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** apply, undo, status and check over every patch file. */
final class Engine {
    static final String STATE_DIR = "survival_rebalance";
    static final String SENTINEL = "applied.json";

    final Main.Opts o;
    final PrintStream out;
    final ByteArrayOutputStream logBuf = new ByteArrayOutputStream();
    /** toolDir: the install root (customize.txt, logs, game_dir.txt). appDir: the jar's folder (patches, manifest, the monitor). The same folder in a flat install. */
    final Path toolDir, appDir, mods, saves, patchesDir, game;
    final boolean dryRun, force, dev;
    final Set<String> only, skip;
    Customize cust = new Customize();
    final Warnings warn = new Warnings();
    final Checks.Manifest manifest;
    /** The release this jar carries, or null (no bundle, or --patches given): then the folder is read. */
    final Bundle bundle;
    /** A newer version whose files were unzipped beside this jar (the unzip kept the old files), or null. */
    final String newerFolder;
    List<Loaded> loadedAll;

    /** The tool folder put right: written only by apply and undo, never by status, check or a dry run. */
    void tidy(boolean write) {
        if (bundle != null && newerFolder == null) folder = ToolFolder.putRight(appDir, jarFile(), bundle, warn, write && !dryRun);
    }

    /** What the last tidy found in the tool folder (status: what apply would do there), or null. */
    ToolFolder.Result folder;

    static final String REFUSED_NEWER = "Nothing was changed.";

    /**
     * apply changes nothing while the folder holds a newer version's files beside this jar. Undo
     * still runs: it only puts original files back, and every version can undo every other's work.
     */
    boolean refuseNewerFolder() {
        if (newerFolder == null) return false;
        out.println(REFUSED_NEWER);
        return true;
    }

    // summary
    final Map<String, List<String>> summary = new LinkedHashMap<>();

    Engine(Main.Opts o, PrintStream console) throws Failure {
        this.o = o;
        this.out = new PrintStream(new Tee(console, logBuf), true, StandardCharsets.UTF_8);
        this.appDir = o.get("--tool-dir") != null ? Main.path(o.get("--tool-dir")) : jarDir();
        this.toolDir = Updater.installRoot(appDir);
        Path g = o.get("--game") != null ? Main.path(o.get("--game")) : null;
        if (g == null && o.get("--mods") == null && toolDir != null && toolDir.getParent() != null
                && Files.isDirectory(toolDir.getParent().resolve("mods"))) g = toolDir.getParent();
        if (o.get("--mods") != null) mods = Main.path(o.get("--mods"));
        else if (g != null) mods = g.resolve("mods");
        else throw new Failure("Cannot find the game folder. Put the SurvivalRebalance folder inside the Starsector folder, or write the game folder's path into game_dir.txt in the SurvivalRebalance folder.");
        if (g == null && mods.getParent() != null && Files.isDirectory(mods.getParent().resolve("starsector-core"))) g = mods.getParent();
        game = g;
        if (o.get("--saves") != null) saves = Main.path(o.get("--saves"));
        else if (game != null) saves = game.resolve("saves").resolve("common");
        else saves = mods.getParent() == null ? null : mods.getParent().resolve("saves").resolve("common");
        // the patch set, manifest and companion mod this jar carries rule; --patches (the build, the tests) wins
        bundle = o.get("--patches") == null ? Bundle.current() : null;
        newerFolder = ToolFolder.newerFolder(appDir, Main.VERSION);
        if (newerFolder != null) warn.warn("Survival Rebalance", ToolFolder.newerText(newerFolder, Main.VERSION), null);
        if (o.get("--patches") != null) patchesDir = Main.path(o.get("--patches"));
        else if (appDir != null) patchesDir = appDir.resolve("patches");
        else throw new Failure("give --patches <dir>");
        dryRun = o.has("--dry-run");
        force = o.has("--force");
        dev = o.has("--dev");
        only = csvSet(o.get("--only"));
        skip = csvSet(o.get("--skip"));
        readCustomize();
        manifest = bundle != null ? Checks.manifest(bundle.manifest()) : Checks.manifest(appDir);
        for (String k : new String[]{"Applied", "Already applied", "Restored", "Skipped", "Other patch set", "To put back", "Not patched", "Not installed", "Switched off", "Failed"})
            summary.put(k, new ArrayList<>());
    }

    static Set<String> csvSet(String s) {
        Set<String> r = new LinkedHashSet<>();
        if (s != null) for (String x : s.split(",")) if (!x.trim().isEmpty()) r.add(x.trim());
        return r;
    }

    static Path jarDir() {
        Path p = jarFile();
        return p == null ? null : p.getParent();
    }

    /** The jar this class runs from, or null (run from a classes folder). */
    static Path jarFile() {
        try {
            Path p = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) { return null; }
    }

    void readCustomize() throws Failure {
        if (toolDir == null) return;
        Path f = toolDir.resolve(Customize.FILE);
        if (Files.isRegularFile(f)) {
            try { cust = Customize.parse(Text.decode(Files.readAllBytes(f)).body); } catch (IOException e) { throw new Failure("cannot read " + f); }
        }
        if (Files.isRegularFile(toolDir.resolve("disabled_changes.txt")))
            warn.warn("disabled_changes.txt", "this file is no longer read, so the changes in it are not switched off.",
                    "Write each line into customize.txt as \"some_mod = off\" or \"some_mod:some-change = off\", then delete disabled_changes.txt.");
    }

    // ------------------------------------------------------------------ patches

    static final class Loaded {
        Path file;
        Patch patch;
        String error;
    }

    /** The selected patch files (every file, on first call, is read once and checks customize.txt). */
    List<Loaded> patches() throws Failure {
        if (loadedAll == null) {
            loadedAll = new ArrayList<>();
            List<Patch> good = new ArrayList<>();
            if (bundle != null) {
                for (Map.Entry<String, byte[]> e : bundle.patches().entrySet()) {
                    Loaded l = new Loaded();
                    l.file = patchesDir.resolve(e.getKey());
                    try { l.patch = Patch.load(l.file, e.getValue()); good.add(l.patch); } catch (Failure f) { l.error = f.getMessage(); }
                    loadedAll.add(l);
                }
            } else {
                if (!Files.isDirectory(patchesDir)) throw new Failure("patches folder not found: " + patchesDir);
                List<Path> files = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(patchesDir, "*.json")) {
                    for (Path p : ds) files.add(p);
                } catch (IOException e) { throw new Failure("cannot list " + patchesDir); }
                files.sort(null);
                for (Path f : files) {
                    Loaded l = new Loaded();
                    l.file = f;
                    try { l.patch = Patch.load(f); good.add(l.patch); } catch (Failure e) { l.error = e.getMessage(); }
                    loadedAll.add(l);
                }
            }
            cust.validate(good, warn);
        }
        List<Loaded> out = new ArrayList<>();
        for (Loaded l : loadedAll) if (l.patch == null || selected(l.patch.modId)) out.add(l);
        return out;
    }

    boolean selected(String id) { return (only.isEmpty() || only.contains(id)) && !skip.contains(id); }

    /** Changes switched off by customize.txt (strength 0 included). */
    Set<String> offFor(Patch p) {
        Set<String> off = new TreeSet<>();
        for (Patch.Change c : p.changes) if (cust.changeOff(p.modId, c.id)) off.add(c.id);
        return off;
    }

    // ------------------------------------------------------------------ state

    static Path stateDir(Path mod) { return mod.resolve(STATE_DIR); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readSentinel(Path mod) throws Failure {
        Path f = stateDir(mod).resolve(SENTINEL);
        if (!Files.isRegularFile(f)) return null;
        try {
            Object o = Json.toJava(Json.parse(Text.decode(Files.readAllBytes(f)).body));
            if (!(o instanceof Map)) throw new Failure("the tool's record " + f + " is damaged");
            Map<String, Object> s = (Map<String, Object>) o;
            // a later version that changes this record's layout writes "layout": 2 or more
            Object lay = s.get("layout");
            boolean newer = lay != null && !String.valueOf(lay).equals("1");
            boolean readable = !newer && s.get("files") instanceof List && s.get("status") != null;
            if (readable) for (Object x : (List<?>) s.get("files")) {
                if (!(x instanceof Map)) { readable = false; break; }
                Map<String, Object> e = (Map<String, Object>) x;
                if (str(e, "path") == null || (!Boolean.TRUE.equals(e.get("added")) && str(e, "sha256_before") == null)) { readable = false; break; }
            }
            if (!readable) throw new Failure("the tool's record " + f + " was written by a version of the tool that this one cannot read, so nothing in this mod was touched");
            return s;
        } catch (IOException | Json.ParseException e) {
            throw new Failure("the tool's record " + f + " is damaged: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> files(Map<String, Object> s) {
        Object f = s.get("files");
        return f instanceof List ? (List<Map<String, Object>>) f : List.of();
    }

    static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }

    /** Tracked files whose bytes are at neither their before nor their after hash. */
    static List<String> drifted(Path mod, Map<String, Object> s, List<String> atBefore, List<String> atAfter) throws Failure {
        List<String> bad = new ArrayList<>();
        for (Map<String, Object> f : files(s)) {
            String rel = str(f, "path");
            Path p = mod.resolve(rel);
            boolean added = Boolean.TRUE.equals(f.get("added"));
            if (!Files.exists(p)) { if (added) atBefore.add(rel); else bad.add(rel + " (missing)"); continue; }
            String h = Files2.sha(p);
            if (h.equals(str(f, "sha256_after"))) atAfter.add(rel);
            else if (!added && h.equals(str(f, "sha256_before"))) atBefore.add(rel);
            else bad.add(rel);
        }
        return bad;
    }

    /** Backups that are missing or do not hash to their recorded original. */
    static List<String> badBackups(Path mod, Map<String, Object> s) {
        List<String> bad = new ArrayList<>();
        for (Map<String, Object> f : files(s)) {
            if (Boolean.TRUE.equals(f.get("added"))) continue;
            String rel = str(f, "path");
            Path b = stateDir(mod).resolve("backup").resolve(rel + ".orig");
            if (!Files.isRegularFile(b)) { bad.add(rel + " (missing)"); continue; }
            try { if (!Files2.sha(b).equals(str(f, "sha256_before"))) bad.add(rel + " (damaged)"); } catch (Failure e) { bad.add(rel + " (unreadable)"); }
        }
        return bad;
    }

    /** Put every tracked file back from its backup and remove the state folder. */
    static void restore(Path mod, Map<String, Object> s) throws Failure {
        Path backup = stateDir(mod).resolve("backup");
        for (Map<String, Object> f : files(s)) {
            String rel = str(f, "path");
            Path p = mod.resolve(rel);
            if (Boolean.TRUE.equals(f.get("added"))) {
                try { Files.deleteIfExists(p); } catch (IOException e) { throw new Failure("cannot delete " + rel + ": " + e.getMessage()); }
                continue;
            }
            String before = str(f, "sha256_before");
            if (Files.exists(p) && Files2.sha(p).equals(before)) continue;
            Path b = backup.resolve(rel + ".orig");
            if (!Files.isRegularFile(b)) throw new Failure("the backup of " + rel + " is missing, so it cannot be put back. Reinstall this mod.");
            byte[] bytes;
            try { bytes = Files.readAllBytes(b); } catch (IOException e) { throw new Failure("cannot read the backup of " + rel); }
            if (!Text.sha256(bytes).equals(before)) throw new Failure("the backup of " + rel + " is damaged. Reinstall this mod.");
            Object mt = f.get("mtime");
            FileTime t = mt == null ? null : FileTime.fromMillis(Long.parseLong(String.valueOf(mt)));
            Files2.atomicWrite(p, bytes, t);
            if (!Files2.sha(p).equals(before)) throw new Failure("restoring " + rel + " did not give back the original bytes");
        }
        Files2.deleteTree(stateDir(mod));
    }

    // ------------------------------------------------------------------ compute

    static final class Planned {
        Workspace ws;
        Map<String, byte[]> results;
        List<Patch.Change> applied = new ArrayList<>();
        int anchors;
        /** Per applied change: the lines status prints (strength, then what each op writes). */
        final Map<String, List<String>> shows = new LinkedHashMap<>();
        final List<String> cannotFollow = new ArrayList<>();
        final List<String> directNotes = new ArrayList<>();
    }

    Planned compute(Patch p, ModInfo m, Set<String> off) throws Failure { return compute(p, m, off, Map.of()); }

    Planned compute(Patch p, ModInfo m, Set<String> off, Map<String, byte[]> preload) throws Failure {
        Planned pl = new Planned();
        pl.ws = new Workspace(m.dir);
        pl.ws.original.putAll(preload);
        for (Patch.Change c : p.changes) {
            if (off.contains(c.id)) continue;
            BigDecimal st = cust.strength(p.modId, c.id);
            List<Customize.Direct> ds = cust.directsFor(p.modId, c.id);
            pl.ws.owner = "\"" + c.title + "\" (" + c.id + ")";
            List<String> show = new ArrayList<>();
            boolean partial = st.compareTo(BigDecimal.ONE) < 0;
            boolean anyDirect = false;
            for (int i = 0; i < c.ops.size(); i++) {
                Json.Obj op = c.ops.get(i);
                Customize.Eff e = Customize.effective(op, st, ds, i, lunaDecimal(pl.ws, op));
                anyDirect |= e.direct;
                try {
                    Ops.run(e.op, pl.ws, dev);
                    pl.anchors++;
                } catch (Failure f) {
                    throw new Failure("Change \"" + c.title + "\" (" + c.id + ") did not match: " + f.getMessage());
                }
                show.add("#" + i + "  " + Customize.shows(op, e.op) + (e.direct ? "  (your value)" : ""));
                if (partial && e.cannotFollow != null) pl.cannotFollow.add(c.title + " (" + p.modId + ":" + c.id + "#" + i + "): " + e.cannotFollow);
            }
            if (anyDirect) {
                pl.directNotes.add("  Your own value is used in \"" + c.title + "\". Making its text match is up to you. Its ops:");
                for (String s : show) pl.directNotes.add("    " + s);
            }
            pl.shows.put(c.id, show);
            pl.applied.add(c);
        }
        pl.results = pl.ws.results();
        return pl;
    }

    /** True when a luna op names a setting whose LunaSettings.csv fieldType is Double or Float. */
    static boolean lunaDecimal(Workspace ws, Json.Obj op) {
        if (!"luna".equals(op.str("op")) || op.get("csv") instanceof Json.Bool) return false;
        try {
            Csv csv = new Csv(ws.text(Ops.LUNA_CSV).body);
            Map<String, String> k = new LinkedHashMap<>();
            k.put("fieldID", op.str("key"));
            Csv.Row row = csv.rows.get(csv.findRow(k));
            int col = csv.column("fieldType");
            String type = col < row.cells.size() ? row.cells.get(col).value.trim().toLowerCase(java.util.Locale.ROOT) : "";
            return type.equals("double") || type.equals("float");
        } catch (Failure | RuntimeException f) {
            return false; // the op itself reports a missing file or row
        }
    }

    String failText(Patch p, ModInfo m, String why) {
        return "  FAILED: " + p.modName + " " + m.version + " (tested: " + String.join(", ", p.tested) + ").\n"
                + "  " + why + "\n"
                + "  Nothing in this mod was changed. Reinstall the mod cleanly if its files were changed.";
    }

    /** The warning for a mod that cannot be patched. */
    void failWarning(Patch p, ModInfo m, String why) {
        if (why.contains(Workspace.HELD)) {
            if (gameSeen()) warn.warn("Starsector is running", p.modName + ": the game has a file open that the tool must read. " + why + ".", "Close Starsector, then run the tool again.");
            else warn.warn(p.modName + " " + m.version, "another program has a file open that the tool must read. " + why + ".", HELD_ADVICE);
            return;
        }
        boolean tested = p.tested.contains(m.version);
        warn.warn(p.modName + " " + m.version, (tested ? "" : "this version is not one the patch was tested with (" + String.join(", ", p.tested) + "), and it ")
                        + "cannot be patched. " + why + ". It is left alone.",
                tested ? "Read the FAILED lines above; reinstall the mod cleanly if you changed its files."
                        : "Check the change by hand, or go back to version " + String.join(" or ", p.tested) + " and run apply again.");
    }

    // ------------------------------------------------------------------ another version

    /** True when apply skips this mod: its installed version is not one the patch was tested on. */
    static boolean otherVersion(Patch p, ModInfo m) { return !p.tested.contains(m.version) && VersionPolicy.skipsOtherVersions(); }

    static String testedText(Patch p) { return String.join(" or ", p.tested); }

    /** The skip in one sentence, the same everywhere: what was tested, what the player has. */
    static String skipFact(Patch p, ModInfo m) {
        return "Survival Rebalance was tested with version " + testedText(p) + " of this mod and does not change version " + m.version + ".";
    }

    static final String NOT_THE_MOD = "This is not a problem in the mod, and its author cannot help with it.";

    static String skipAdvice(Patch p) {
        return "Install version " + testedText(p) + ", or wait for a Survival Rebalance update. app\\update.bat checks for one.";
    }

    /** The warning for a skipped mod. */
    void otherVersionWarning(Patch p, ModInfo m) {
        warn.warn(p.modName + " " + m.version, "skipped. " + skipFact(p, m) + " " + NOT_THE_MOD, skipAdvice(p));
    }

    /**
     * Apply on a mod of another version: nothing of the patch is written. When an earlier apply
     * patched this folder (restored), its files were put back before this; the saved settings an
     * earlier apply changed go back too, so no part of the rebalance stays.
     */
    void skipOne(Patch p, ModInfo m, boolean restored) throws Failure {
        out.println("  Skipped: " + skipFact(p, m) + (restored ? " Its original files were put back." : " Nothing in this mod was changed."));
        Saves.Plan sp = Saves.planUndo(saves, p.modId);
        if (!dryRun && (!sp.lines.isEmpty() || sp.recordChanged)) Saves.commit(saves, sp);
        Saves.print(sp, out);
        add("Skipped", p.modName + " " + m.version + " (tested with " + testedText(p) + ")");
        otherVersionWarning(p, m);
    }

    // ------------------------------------------------------------------ the install around the mods

    /** Warnings about the whole install, shared by every command. */
    void survey(List<ModInfo> all, List<Loaded> ps) {
        String have = Checks.gameVersion(game);
        if (manifest.gameVersion != null && have != null && !have.equals(manifest.gameVersion))
            warn.warn("Starsector " + have, "the patches were tested on Starsector " + manifest.gameVersion + ". A mod updated for the new game version may no longer match.",
                    "Apply as usual; any mod that no longer matches is reported and left alone.");
        else if (manifest.gameVersion != null && have == null && game != null)
            warn.note("the game's version could not be read, so it was not compared with the tested " + manifest.gameVersion + ".");

        Set<String> patchIds = new LinkedHashSet<>();
        int missing = 0;
        for (Loaded l : ps) {
            if (l.patch == null) continue;
            patchIds.add(l.patch.modId);
            List<ModInfo> found = ModInfo.byId(all, l.patch.modId);
            if (found.isEmpty()) missing++;
            if (found.size() > 1) {
                List<String> names = new ArrayList<>();
                for (ModInfo m : found) names.add(m.dir.getFileName() + " (" + m.version + ")");
                warn.warn(l.patch.modName, "two folders hold this mod: " + String.join(", ", names) + ". The game loads only one of them.",
                        "Delete the folder you do not use, then run apply.");
            }
        }
        if (missing > 0) warn.note(missing + (missing == 1 ? " patch is for a mod" : " patches are for mods") + " you do not have.");
        otherTools();

        Set<String> enabled = Checks.enabled(mods);
        if (enabled != null && loadedAll != null) {
            Set<String> known = new LinkedHashSet<>();
            for (Loaded l : loadedAll) if (l.patch != null) known.add(l.patch.modId);
            known.addAll(manifest.checked);
            known.add(Checks.MONITOR_ID);
            List<String> unknown = new ArrayList<>();
            for (String id : enabled) {
                if (known.contains(id)) continue;
                List<ModInfo> f = ModInfo.byId(all, id);
                if (f.isEmpty()) continue;
                unknown.add(f.get(0).name + " " + f.get(0).version);
            }
            // one line per mod, with its name: these mods were never analysed, so they are not rebalanced
            if (manifest.present) for (String u : unknown)
                warn.warn(u, "not supported. It has not been checked for Survival Mode, so it stays as it is.", null);
        }
    }

    /**
     * A second copy of the tool beside this one in the game folder: the two would take turns
     * bringing the same mods to their own versions, so the player hears of it.
     */
    void otherTools() {
        Path gameDir = game != null ? game : mods.getParent();
        Path here = toolDir == null ? null : toolDir.toAbsolutePath().normalize();
        if (gameDir == null || here == null) return;
        // with shipped.txt missing, toolDir is app\ itself: the folder above it is this copy too
        Path mine = appDir == null || appDir.getParent() == null ? here : appDir.toAbsolutePath().normalize().getParent();
        // the jar's own folder too: with the tool's files loose in the game folder, app\ sits right in it
        Path app = appDir == null ? null : appDir.toAbsolutePath().normalize();
        List<String> others = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(gameDir)) {
            for (Path d : ds) {
                Path n = d.toAbsolutePath().normalize();
                if (!Files.isDirectory(d) || n.equals(here) || n.equals(mine) || n.equals(app)) continue;
                boolean tool = Files.isRegularFile(d.resolve("app").resolve(Updater.JAR))
                        || (Files.isRegularFile(d.resolve(Updater.JAR)) && Files.isDirectory(d.resolve("patches")));
                if (tool) others.add(String.valueOf(d.getFileName()));
            }
        } catch (IOException e) {
            return;
        }
        others.sort(null);
        for (String n : others)
            warn.warn("Survival Rebalance", "another copy of the tool is in the folder " + n + ". Each copy brings the mods to its own version when you apply with it.",
                    "Delete the copy you do not use. Copy customize.txt across first if you changed it there.");
    }

    /** One line on the companion mod. Called after apply may have installed it. */
    void monitorNote() {
        Set<String> enabled = Checks.enabled(mods);
        if (enabled == null) return;
        List<ModInfo> mon;
        try { mon = ModInfo.byId(ModInfo.scan(mods), Checks.MONITOR_ID); } catch (Failure f) { return; }
        if (mon.isEmpty()) warn.note("the Survival Rebalance Monitor is not installed, so the game will not warn you when an update removes a patch. Run apply to install it.");
        else if (!enabled.contains(Checks.MONITOR_ID)) warn.note("the Survival Rebalance Monitor is installed but not enabled. Enable it in the Starsector launcher, so the game warns you when an update removes a patch.");
        String other = olderMonitor(mon);
        if (other == null || monitorFail) return;
        if (Checks.compareVersions(mon.get(0).version, other) < 0)
            warn.warn("Survival Rebalance Monitor " + mon.get(0).version, "older than the " + other + " this tool ships, so it may not report every unpatched mod.", "Run apply; it updates the monitor.");
        else if (Checks.compareVersions(mon.get(0).version, other) > 0)
            warn.warn("Survival Rebalance Monitor " + mon.get(0).version, "newer than the " + other + " this tool ships. It is the copy of another version of the tool.", "Run apply; it puts in the copy this version ships.");
        else
            warn.warn("Survival Rebalance Monitor " + mon.get(0).version, "its files differ from the copy this version of the tool ships.", "Run apply; it puts in the copy this version ships.");
    }

    /** The companion mod this tool carries, by path inside its folder, or null. */
    Map<String, byte[]> shipMonitor() { return Checks.monitorFiles(appDir, bundle); }

    /** The shipped monitor's version when the installed one is not the same copy, else null. */
    String olderMonitor(List<ModInfo> installed) {
        if (installed.isEmpty()) return null;
        Map<String, byte[]> src = shipMonitor();
        ModInfo ship = src == null ? null : ModInfo.parse(null, src.get("mod_info.json"));
        if (ship == null) return null;
        return Checks.sameMonitor(installed.get(0).dir, src) ? null : ship.version;
    }

    // ------------------------------------------------------------------ apply

    /** The look for a newer tool version (in the background) and the check for an unfinished update. */
    void startUpdateChecks() {
        notice = Updater.startNotice(o, cust);
        Updater.pendingWarning(toolDir, warn);
    }

    Updater.Notice notice;

    /** apply and undo change no mod while the game runs from this folder; the window refuses the same way. */
    boolean refuseWhileRunning() {
        if (dryRun || game == null || !GameRunning.check.test(game)) return false;
        out.println();
        out.println("Starsector is running. Close the game first, then run this again. The game holds the mods' files open, so the tool cannot change them. Nothing was changed.");
        return true;
    }

    int apply() throws Exception {
        header("apply");
        if (refuseNewerFolder() || refuseWhileRunning()) return 1;
        tidy(true);
        startUpdateChecks();
        List<Loaded> ps = dependencyOrder(patches());
        List<ModInfo> all = ModInfo.scan(mods);
        survey(all, ps);
        for (Loaded l : ps) {
            curId = null;
            if (l.patch == null) { out.println("== " + l.file.getFileName()); out.println("  FAILED: the patch file cannot be read: " + l.error); add("Failed", String.valueOf(l.file.getFileName())); warn.warn(String.valueOf(l.file.getFileName()), "the patch file cannot be read: " + l.error, "Get a fresh copy of the tool."); continue; }
            Patch p = l.patch;
            List<ModInfo> found = ModInfo.byId(all, p.modId);
            curId = p.modId;
            if (cust.modOff(p.modId)) {
                // a mod switched off after it was patched is put back
                for (ModInfo m : found) {
                    boolean state = Files.isRegularFile(stateDir(m.dir).resolve(SENTINEL));
                    Saves.Plan sp = Saves.planUndo(saves, m.id);
                    if (state || !sp.lines.isEmpty()) undoOne(m, p.modName, state, true);
                    else if (sp.recordChanged && !dryRun) Saves.commit(saves, sp);
                }
                add("Switched off", p.modName);
                continue;
            }
            if (found.isEmpty()) { add("Not installed", p.modName); continue; }
            for (ModInfo m : found) applyOne(p, m);
        }
        curId = null;
        restoreOrphans(all);
        if (only.isEmpty() && skip.isEmpty()) installMonitor(all);
        writeMonitorIndex(false);
        monitorNote();
        return finish();
    }

    /**
     * Mods patched by an earlier tool version whose patch set no longer has a file for them
     * (a new version dropped it) are put back to their original files, as undo would.
     */
    void restoreOrphans(List<ModInfo> all) throws Failure {
        Set<String> ids = new LinkedHashSet<>();
        for (Loaded l : loadedAll) {
            if (l.patch == null) return; // an unreadable patch file may be the one for this mod
            ids.add(l.patch.modId);
        }
        for (ModInfo m : all) {
            if (ids.contains(m.id) || !selected(m.id) || m.id.equals(Checks.MONITOR_ID)) continue;
            if (!Files.isRegularFile(stateDir(m.dir).resolve(SENTINEL))) continue;
            out.println();
            out.println("== " + m.name + " " + m.version + "  (" + m.dir.getFileName() + ")");
            out.println("  Rebalanced by " + byVersion(m.dir) + ", and this version has no changes for this mod. Putting its original files back.");
            undoOne(m, m.name, true, false);
        }
    }

    void applyOne(Patch p, ModInfo m) throws Failure {
        out.println();
        out.println("== " + p.modName + " " + m.version + "  (" + m.dir.getFileName() + ")");
        boolean other = otherVersion(p, m);
        Set<String> off = offFor(p);
        if (!other) for (String l : skipUnmet(p, off, appliedKeys::contains)) { out.println(l); skipWarning(p, l); }
        String hash = Patch.hashOf(p.raw, cust.lines(p, off));
        Map<String, Object> s;
        try { s = readSentinel(m.dir); } catch (Failure f) { fail(p, m, "  FAILED: " + f.getMessage(), f.getMessage() + ".", "Delete this mod's folder, install it again cleanly, then run apply again."); return; }
        // a record means the files go back from their backups before this version patches them;
        // that restore waits until every check below has passed, so a mod that fails one is untouched
        boolean needRestore = false;
        if (s != null) {
            if ("applying".equals(str(s, "status"))) {
                out.println("  The last run on this mod was interrupted. Putting its files back first.");
                needRestore = true;
            } else {
                List<String> atB = new ArrayList<>(), atA = new ArrayList<>();
                List<String> bad = drifted(m.dir, s, atB, atA);
                if (!bad.isEmpty()) {
                    out.println("  FAILED: these files changed after the tool patched them:");
                    for (String b : bad) out.println("    " + b);
                    out.println("  The mod was probably updated in place. Nothing was touched.");
                    out.println("  Delete this mod's folder, install it again cleanly, then run apply again.");
                    add("Failed", p.modName);
                    warn.warn(p.modName + " " + m.version, "updated in place since it was patched (changed: " + String.join(", ", bad) + "). Apply leaves it alone, so it may be half patched.",
                            "Delete this mod's folder, install it again cleanly, then run apply.");
                    return;
                }
                if (other) {
                    out.println("  It was rebalanced at version " + str(s, "mod_version") + ". Putting its original files back.");
                    if (!dryRun && !restoreChecked(p.modName, m, s)) return;
                    skipOne(p, m, true);
                    return;
                }
                if (hash.equals(str(s, "patch_hash")) && atB.isEmpty()) {
                    List<String> bb = badBackups(m.dir, s);
                    if (!bb.isEmpty()) backupWarning(p, m, bb);
                    out.println("  Already applied.");
                    Object ch = s.get("changes");
                    if (ch instanceof List) for (Object id : (List<?>) ch) appliedKeys.add(p.modId + ":" + id);
                    versionWarning(p, m, s);
                    savedWarnings(p, m, null);
                    add("Already applied", p.modName);
                    return;
                }
                out.println("  The changes differ from the last apply (a new tool version, or a different customization). Putting the files back, then applying again.");
                needRestore = true;
            }
        } else {
            String was = Saves.patchedVersion(saves, p.modId);
            if (was != null && !other) out.println("  This mod's folder was replaced since it was patched (at version " + was + "). Patching it again.");
            if (Files.exists(stateDir(m.dir)) && !dryRun && !other) Files2.deleteTree(stateDir(m.dir)); // leftovers with no record carry nothing to restore
        }
        if (other) { skipOne(p, m, s != null); return; }
        // every backup the restore needs is there and every file it writes can be written
        Blocker bl = needRestore ? blocker(m.dir, s) : null;
        if (bl != null) { blocked(p.modName, m, bl); return; }
        Planned pl;
        try {
            // nothing is restored yet, so the originals of the tracked files come from the backups
            pl = compute(p, m, off, needRestore ? backups(m.dir, s) : Map.of());
        } catch (Failure f) {
            failAfterChecks(p, m, s, needRestore, f.getMessage());
            return;
        }
        if (!p.tested.contains(m.version)) {
            out.println("  Untested version " + m.version + ": all " + pl.anchors + " anchors matched.");
            warn.warn(p.modName + " " + m.version, "this version is not one the patch was tested with (" + String.join(", ", p.tested) + "), but every anchor still matches, so it is patched.",
                    "Nothing to do unless the mod behaves oddly; then run undo and stay on version " + String.join(" or ", p.tested) + ".");
        }
        cannotFollowWarnings(p, pl);
        Saves.Plan sp;
        try { sp = Saves.planApply(saves, p.modId, pl.ws.luna); } catch (Failure f) { failAfterChecks(p, m, s, needRestore, f.getMessage()); return; }
        for (String rel : pl.results.keySet()) {
            if (!Files2.writable(m.dir.resolve(rel)) && Files.exists(m.dir.resolve(rel))) {
                blocked(p.modName, m, new Blocker(rel, m.dir.resolve(rel)));
                return;
            }
        }
        if (needRestore && !dryRun) {
            try { restore(m.dir, s); } catch (Failure f) { failChanged(p.modName, m, f.getMessage()); return; }
        }
        if (pl.applied.isEmpty()) {
            out.println("  Every change of this mod is switched off.");
            if (!dryRun) { Saves.commit(saves, sp); Saves.markPatched(saves, p.modId, null); }
            Saves.print(sp, out);
            add("Switched off", p.modName);
            return;
        }
        for (Patch.Change c : pl.applied) {
            BigDecimal st = cust.strength(p.modId, c.id);
            out.println("  " + (dryRun ? "Would apply: " : "Applied: ") + c.title + (st.compareTo(BigDecimal.ONE) < 0 ? "  (strength " + Customize.plain(st) + ")" : ""));
        }
        for (String l : pl.directNotes) out.println(l);
        for (Patch.Change c : pl.applied) appliedKeys.add(p.modId + ":" + c.id);
        savedWarnings(p, m, pl);
        if (dryRun) {
            for (String l : sp.lines) out.println(l.replace("Saved setting changed", "Saved setting would change"));
            add("Applied", p.modName + " (dry run)");
            return;
        }
        try { write(p, m, pl, hash, new ArrayList<>(off), cust.lines(p, off)); } catch (Failure f) {
            out.println("  FAILED: " + f.getMessage() + ". Run apply again; it puts this mod's files back first and patches it again.");
            add("Failed", p.modName);
            failedChanged.add(p.modName);
            warn.warn(p.modName + " " + m.version, "the tool could not finish patching it: " + f.getMessage() + ".", "Close Starsector, then run apply again.");
            return;
        }
        Saves.commit(saves, sp);
        Saves.markPatched(saves, p.modId, m.version);
        Saves.print(sp, out);
        add("Applied", p.modName);
    }

    /** What stops a restore before it writes anything: a backup it needs, or a file it cannot write. */
    static final class Blocker {
        final String rel, text;
        /** A file the tool cannot change now (held by another program, or read-only); text is null then. */
        final Path held;
        Blocker(String rel, String text) { this.rel = rel; this.text = text; this.held = null; }
        Blocker(String rel, Path held) { this.rel = rel; this.text = null; this.held = held; }
    }

    /** Null when restore() can put every tracked file back: each backup it needs is there and sound, each file it writes can be written. */
    static Blocker blocker(Path mod, Map<String, Object> s) {
        Path backup = stateDir(mod).resolve("backup");
        for (Map<String, Object> f : files(s)) {
            String rel = str(f, "path");
            Path p = mod.resolve(rel);
            boolean exists = Files.exists(p);
            if (Boolean.TRUE.equals(f.get("added"))) {
                if (exists && !Files2.writable(p)) return new Blocker(rel, p);
                continue;
            }
            String before = str(f, "sha256_before");
            try { if (exists && Files2.sha(p).equals(before)) continue; } catch (Failure e) { return new Blocker(rel, p); }
            Path b = backup.resolve(rel + ".orig");
            if (!Files.isRegularFile(b)) return new Blocker(rel, "the backup of " + rel + " is missing, so it cannot be put back");
            try { if (!Files2.sha(b).equals(before)) return new Blocker(rel, "the backup of " + rel + " is damaged, so it cannot be put back"); }
            catch (Failure e) { return new Blocker(rel, "the backup of " + rel + " cannot be read, so it cannot be put back"); }
            if (exists && !Files2.writable(p)) return new Blocker(rel, p);
        }
        return null;
    }

    /** Whether the game-running check sees the game from this folder now; asked once per run. */
    Boolean gameSeen;

    boolean gameSeen() {
        if (gameSeen == null) gameSeen = game != null && GameRunning.check.test(game);
        return gameSeen;
    }

    static final String HELD_ADVICE = "Close Starsector and any other program that may have the mod's files open, then run the tool again.";

    /** A file the tool cannot change now: the FAILED line's words, and its warning. */
    void heldFailure(String name, ModInfo m, Path p, String rel, String tail) {
        if (Files2.denied(p)) {
            out.println("  FAILED: " + rel + " is marked read-only, or Windows does not let you change it." + tail);
            warn.warn(name + " " + m.version, rel + " is marked read-only, or Windows does not let you change it, so the tool cannot change it.",
                    "Clear the read-only mark in the file's Properties, then run the tool again.");
        } else if (gameSeen()) {
            out.println("  FAILED: Starsector is running and has " + rel + " open. Close the game first." + tail);
            warn.warn("Starsector is running", name + "'s " + rel + " is open in the game, so the tool cannot change it.", "Close Starsector, then run the tool again.");
        } else {
            out.println("  FAILED: another program has " + rel + " open, most likely Starsector. Close it first." + tail);
            warn.warn(name + " " + m.version, "another program has " + rel + " open, so the tool cannot change it. Most likely that program is Starsector.", HELD_ADVICE);
        }
    }

    /** A mod left exactly as it was, because a check before the first write failed. */
    void blocked(String name, ModInfo m, Blocker b) {
        add("Failed", name);
        if (b.held != null) { heldFailure(name, m, b.held, b.rel, " Nothing in this mod was changed."); return; }
        out.println("  FAILED: " + b.text + ". Nothing in this mod was changed.");
        warn.warn(name + " " + m.version, b.text + ". Nothing in this mod was changed.", "Delete this mod's folder and install it again cleanly.");
    }

    /** Check, then restore. False (with the FAILED line) when the mod could not be put back. */
    boolean restoreChecked(String name, ModInfo m, Map<String, Object> s) {
        Blocker b = blocker(m.dir, s);
        if (b != null) { blocked(name, m, b); return false; }
        try { restore(m.dir, s); } catch (Failure f) { failChanged(name, m, f.getMessage()); return false; }
        return true;
    }

    /** Mods that failed after the tool had changed some of their files. */
    final Set<String> failedChanged = new LinkedHashSet<>();

    /** A restore that failed after its checks passed (a file taken between the check and the write). */
    void failChanged(String name, ModInfo m, String why) {
        out.println("  FAILED: " + why + ". Some of this mod's files may already be back. Close Starsector, then run apply again.");
        add("Failed", name);
        failedChanged.add(name);
        warn.warn(name + " " + m.version, "the tool could not put all its files back: " + why + ".", "Close Starsector, then run apply again.");
    }

    /** This version cannot patch the mod. An earlier apply's changes then go too, so the mod ends original. */
    void failAfterChecks(Patch p, ModInfo m, Map<String, Object> s, boolean needRestore, String why) {
        if (needRestore && !dryRun) {
            try { restore(m.dir, s); } catch (Failure r) { failChanged(p.modName, m, r.getMessage()); return; }
            out.println(failText(p, m, why).replace("Nothing in this mod was changed. Reinstall the mod cleanly if its files were changed.", "Its original files were put back."));
            failedChanged.add(p.modName);
        } else out.println(failText(p, m, why));
        add("Failed", p.modName);
        failWarning(p, m, why);
    }

    void fail(Patch p, ModInfo m, String line, String why, String advice) {
        out.println(line);
        add("Failed", p.modName);
        warn.warn(p.modName + " " + m.version, why, advice);
    }

    void skipWarning(Patch p, String line) {
        warn.warn(p.modName, line.trim().replace("Would skip: ", "").replace("Skipped: ", "the change \"").replaceFirst(" \\(it needs ", "\" is skipped: it needs ").replaceFirst("\\)$", "."),
                "Apply or install the change it needs, or leave it; the rest of the mod is patched.");
    }

    /** Status: a file apply would have to change that it cannot change now. */
    void lockedWarning(Patch p, ModInfo m, String rel) {
        Path f = m.dir.resolve(rel);
        if (Files2.denied(f))
            warn.warn(p.modName + " " + m.version, rel + " is marked read-only, or Windows does not let you change it, so apply cannot change it.", "Clear the read-only mark in the file's Properties, then run the tool again.");
        else if (gameSeen())
            warn.warn("Starsector is running", p.modName + "'s " + rel + " is open in the game, so the tool cannot change it.", "Close Starsector, then run the tool again.");
        else
            warn.warn(p.modName + " " + m.version, "another program has " + rel + " open, so the tool cannot change it. Most likely that program is Starsector.", HELD_ADVICE);
    }

    void backupWarning(Patch p, ModInfo m, List<String> bb) {
        warn.warn(p.modName + " " + m.version, "the backup of " + String.join(", ", bb) + " in its survival_rebalance folder is missing or does not match, so undo cannot put that file back.",
                "Reinstall this mod cleanly to get its original files, then run apply.");
    }

    void versionWarning(Patch p, ModInfo m, Map<String, Object> s) {
        String was = str(s, "mod_version");
        if (was != null && !was.equals(m.version))
            warn.warn(p.modName + " " + m.version, "patched at version " + was + "; the mod's version changed since, but every patched file is still as the tool left it.",
                    "Delete this mod's folder, install it again cleanly, then run apply, so the patch is checked against the new version.");
    }

    void cannotFollowWarnings(Patch p, Planned pl) {
        for (String c : pl.cannotFollow)
            warn.warn("customize.txt", p.modName + ": " + c + ".", "Set this change to 0 or 1, or give its text a value of your own with #N (app\\status.bat lists the op numbers).");
    }

    /** Saved settings the tool set that now hold another value, and settings the patch names that the saved file lacks. */
    void savedWarnings(Patch p, ModInfo m, Planned pl) {
        if (saves == null) return;
        for (Map.Entry<String, String[]> e : Saves.drift(saves, p.modId).entrySet()) {
            String[] v = e.getValue();
            if (v[0] == null)
                warn.warn(p.modName, "the saved setting " + e.getKey() + " the tool set to " + v[1] + " is no longer in the saved settings.",
                        "Nothing to do: the mod now uses its default, which the tool also changed.");
            else
                warn.warn(p.modName, "the saved setting " + e.getKey() + " holds " + v[0] + ", not the " + v[1] + " the tool set. It was changed in the game since.",
                        "If you want the rebalanced value, set it to " + v[1] + " in the mod's settings; otherwise nothing to do.");
        }
        if (pl == null) return;
        Path f = Saves.lunaFile(saves, p.modId);
        if (!Files.isRegularFile(f)) return;
        for (String k : pl.ws.luna.keySet())
            if (Saves.peek(f, k) == null)
                warn.warn(p.modName, "the patch names the setting " + k + ", which the saved settings file does not hold.",
                        "Nothing to do if the mod's settings screen shows the new value; if the mod renamed the setting, check it there by hand.");
    }

    final Set<String> appliedKeys = new java.util.HashSet<>();

    /** Patches ordered so a mod named in another's "requires" comes first (file order otherwise). */
    static List<Loaded> dependencyOrder(List<Loaded> in) {
        Map<String, Loaded> byId = new LinkedHashMap<>();
        for (Loaded l : in) if (l.patch != null) byId.putIfAbsent(l.patch.modId, l);
        List<Loaded> out = new ArrayList<>();
        for (Loaded l : in) if (l.patch == null) out.add(l);
        Set<String> done = new java.util.HashSet<>();
        List<Loaded> left = new ArrayList<>();
        for (Loaded l : in) if (l.patch != null) left.add(l);
        while (!left.isEmpty()) {
            Loaded pick = null;
            for (Loaded l : left) {
                boolean ready = true;
                for (Patch.Change c : l.patch.changes) for (String r : c.requires) {
                    int i = r.indexOf(':');
                    String mid = i < 0 ? l.patch.modId : r.substring(0, i);
                    if (!mid.equals(l.patch.modId) && byId.containsKey(mid) && !done.contains(mid)) ready = false;
                }
                if (ready) { pick = l; break; }
            }
            if (pick == null) pick = left.get(0); // a cycle: keep file order
            left.remove(pick);
            out.add(pick);
            done.add(pick.patch.modId);
        }
        return out;
    }

    /** Add to "off" every change whose "requires" is not met; returns the lines that say so. */
    static List<String> skipUnmet(Patch p, Set<String> off, java.util.function.Predicate<String> appliedElsewhere) {
        List<String> lines = new ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Patch.Change c : p.changes) {
                if (off.contains(c.id)) continue;
                for (String r : c.requires) {
                    String key = r.indexOf(':') < 0 ? p.modId + ":" + r : r;
                    boolean ok;
                    if (key.startsWith(p.modId + ":")) {
                        String id = key.substring(p.modId.length() + 1);
                        ok = false;
                        for (Patch.Change o : p.changes) if (o.id.equals(id) && !off.contains(id)) ok = true;
                    } else ok = appliedElsewhere.test(key);
                    if (!ok) {
                        off.add(c.id);
                        lines.add("  Skipped: " + c.title + " (it needs " + key + ", which is not applied)");
                        changed = true;
                        break;
                    }
                }
            }
        }
        return lines;
    }

    /** For status: a required change counts as available when its mod is installed, patched by a file here, and not switched off. */
    boolean available(String key, List<Loaded> ps, List<ModInfo> all) {
        int i = key.indexOf(':');
        String mid = key.substring(0, i), cid = key.substring(i + 1);
        if (cust.changeOff(mid, cid)) return false;
        if (ModInfo.byId(all, mid).isEmpty()) return false;
        for (Loaded l : ps) if (l.patch != null && l.patch.modId.equals(mid)) for (Patch.Change c : l.patch.changes) if (c.id.equals(cid)) return true;
        return false;
    }

    /** The original bytes of each tracked file, as restore() would put them back: the file on disk when it
     *  already hashes to the recorded original, else its backup, and only a backup that hashes to it. */
    static Map<String, byte[]> backups(Path mod, Map<String, Object> s) throws Failure {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (Map<String, Object> f : files(s)) {
            if (Boolean.TRUE.equals(f.get("added"))) continue;
            String rel = str(f, "path"), before = str(f, "sha256_before");
            Path p = mod.resolve(rel), b = stateDir(mod).resolve("backup").resolve(rel + ".orig");
            byte[] bytes = null;
            try {
                if (Files.isRegularFile(p)) { byte[] d = Files.readAllBytes(p); if (Text.sha256(d).equals(before)) bytes = d; }
            } catch (IOException e) { throw new Failure("cannot read " + rel + " (it may be in use)"); }
            if (bytes == null) {
                if (!Files.isRegularFile(b)) throw new Failure("the backup of " + rel + " is missing, so it cannot be put back");
                try { bytes = Files.readAllBytes(b); } catch (IOException e) { throw new Failure("cannot read the backup of " + rel); }
                if (!Text.sha256(bytes).equals(before)) throw new Failure("the backup of " + rel + " is damaged, so it cannot be put back");
            }
            m.put(rel, bytes);
        }
        return m;
    }

    void write(Patch p, ModInfo m, Planned pl, String hash, List<String> off, List<String> custLines) throws Failure {
        Path sd = stateDir(m.dir);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("tool", "Survival Rebalance");
        s.put("tool_version", Main.VERSION);
        s.put("about", "Written by Survival Rebalance. It changed the files listed here; the originals are in backup/. Run undo to put them back.");
        s.put("status", "applying");
        s.put("patch_hash", hash);
        s.put("patch_sha256", Text.sha256(p.raw));
        s.put("customization", new ArrayList<Object>(custLines));
        s.put("disabled_changes", off);
        s.put("mod_id", p.modId);
        s.put("mod_version", m.version);
        s.put("time", LocalDateTime.now().withNano(0).toString());
        List<Object> ids = new ArrayList<>();
        for (Patch.Change c : pl.applied) ids.add(c.id);
        s.put("changes", ids);
        List<Object> fl = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : pl.results.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", e.getKey());
            f.put("sha256_before", Text.sha256(pl.ws.original.get(e.getKey())));
            f.put("sha256_after", Text.sha256(e.getValue()));
            f.put("added", false);
            FileTime t = Files2.mtime(m.dir.resolve(e.getKey()));
            if (t != null) f.put("mtime", new Json.RawNum(String.valueOf(t.toMillis())));
            fl.add(f);
        }
        s.put("files", fl);
        Path sf = sd.resolve(SENTINEL);
        Files2.atomicWrite(sf, Json.write(s).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> e : pl.results.entrySet()) {
            Path src = m.dir.resolve(e.getKey());
            Files2.atomicWrite(sd.resolve("backup").resolve(e.getKey() + ".orig"), pl.ws.original.get(e.getKey()), Files2.mtime(src));
        }
        for (Map.Entry<String, byte[]> e : pl.results.entrySet()) Files2.atomicWrite(m.dir.resolve(e.getKey()), e.getValue());
        s.put("status", "applied");
        Files2.atomicWrite(sf, Json.write(s).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the companion mod

    /** Undo's line for a monitor it leaves in place because the tool did not install it. */
    static final String MONITOR_LEFT = "Left in the mods folder: the tool did not install this copy, so it does not delete it. Nothing is patched now, so it has nothing to check. Delete its folder, or switch it off in the Starsector launcher.";
    /** Undo's line for a monitor it keeps because only some mods were undone. */
    static final String MONITOR_KEPT_PARTIAL = "Kept: this undo covered only some mods, and the monitor still checks the others.";

    void installMonitor(List<ModInfo> all) throws Failure {
        Map<String, byte[]> src = shipMonitor();
        if (src == null) return;
        ModInfo ship = ModInfo.parse(null, src.get("mod_info.json"));
        if (ship == null) return;
        List<ModInfo> have = ModInfo.byId(all, Checks.MONITOR_ID);
        ModInfo cur = have.isEmpty() ? null : have.get(0);
        // the copy this version ships replaces any other, older or newer, as a whole folder
        if (cur != null && Checks.sameMonitor(cur.dir, src)) return;
        boolean older = cur != null && Checks.compareVersions(cur.version, ship.version) < 0;
        out.println();
        out.println("== " + ship.name + " " + ship.version);
        if (dryRun) { out.println("  Would " + (cur == null ? "install" : older ? "update" : "replace") + " it in the mods folder."); return; }
        boolean ours = cur == null || Files.isRegularFile(cur.dir.resolve(Checks.MONITOR_MARK));
        Path dst = cur != null ? cur.dir : mods.resolve(Checks.MONITOR_DIR);
        // check first that every installed file can go, so a copy in use is left whole
        String busy = cur == null ? null : inUse(cur.dir);
        if (busy != null) { monitorFailed(ship, cur, heldWhy(cur.dir.resolve(busy), busy.replace('\\', '/')), true); return; }
        try {
            if (cur != null) Files2.deleteTree(cur.dir);
            for (Map.Entry<String, byte[]> e : src.entrySet()) Files2.atomicWrite(dst.resolve(e.getKey()), e.getValue());
            if (ours) Checks.writeMark(dst);
        } catch (Failure f) {
            monitorFailed(ship, cur, f.getMessage(), false);
            return;
        }
        out.println("  " + (cur == null ? "Installed" : older ? "Updated from " + cur.version : "Replaced version " + cur.version + " with the copy this version of the tool ships")
                + ". It warns you in the game when an update removes a patch.");
        Set<String> enabled = Checks.enabled(mods);
        if (enabled == null || !enabled.contains(Checks.MONITOR_ID))
            out.println("  Enable \"" + ship.name + "\" in the Starsector launcher; the tool does not change which mods are enabled.");
    }

    /** The first file in this folder that cannot be changed now, or null. */
    static String inUse(Path dir) {
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p) && !Files2.writable(p)) return dir.relativize(p).toString();
        } catch (IOException | java.io.UncheckedIOException e) {
            return dir.getFileName().toString();
        }
        return null;
    }

    /** Set when this run could not put the monitor in; its own warning then says so. */
    boolean monitorFail;

    void monitorFailed(ModInfo ship, ModInfo cur, String why, boolean whole) {
        monitorFail = true;
        out.println("  FAILED: " + why + ". " + (cur == null ? "It was not installed." : whole ? "The installed copy was left as it is." : "Part of the installed copy may be missing; the next apply puts it in again."));
        warn.warn(ship.name, (cur == null ? "could not be installed: " : "could not be replaced: ") + why + ".", gameSeen() ? "Close Starsector, then run apply again." : HELD_ADVICE.replace("the mod's files", "its files").replace("run the tool again", "run apply again"));
    }

    /** Why a file cannot be changed now, as a clause: read-only, open in the game, or open in another program. */
    String heldWhy(Path p, String rel) {
        if (Files2.denied(p)) return rel + " is marked read-only, or Windows does not let you change it";
        return gameSeen() ? rel + " is open in the running game" : "another program has " + rel + " open, most likely Starsector";
    }

    /**
     * After a full undo: remove every monitor the tool installed (it has the mark file) and say so.
     * A monitor without the mark was put there by hand or by a mod manager, so it stays, and the
     * output says that too. After an undo of some mods only, the monitor stays to check the rest.
     */
    void removeMonitor(List<ModInfo> all, boolean partial) throws Failure {
        for (ModInfo m : ModInfo.byId(all, Checks.MONITOR_ID)) {
            out.println();
            out.println("== " + m.name + " " + m.version);
            if (partial) {
                out.println("  " + MONITOR_KEPT_PARTIAL);
                continue;
            }
            if (!Files.isRegularFile(m.dir.resolve(Checks.MONITOR_MARK))) {
                out.println("  " + MONITOR_LEFT);
                continue;
            }
            if (dryRun) { out.println("  Would remove it (the tool installed it)."); continue; }
            // check first that every file can go, so a copy in use is left whole (as apply does)
            String busy = inUse(m.dir);
            if (busy != null) {
                String why = heldWhy(m.dir.resolve(busy), busy.replace('\\', '/'));
                out.println("  FAILED: " + why + ". The Monitor was left as it is; the next undo removes it.");
                warn.warn(m.name, "could not be removed: " + why + ".", gameSeen() ? "Close Starsector, then run undo again." : "Close Starsector and any other program that may have its files open, then run undo again.");
                continue;
            }
            try { Files2.deleteTree(m.dir); } catch (Failure f) {
                out.println("  FAILED: " + f.getMessage() + ". Part of the Monitor may be left; the next undo removes the rest.");
                warn.warn(m.name, "could not be removed completely: " + f.getMessage() + ".", "Close Starsector, then run undo again.");
                continue;
            }
            out.println("  Removed it (the tool installed it). If it was enabled, the launcher forgets it.");
        }
    }

    // ------------------------------------------------------------------ undo

    int undo() throws Exception {
        header("undo");
        if (refuseWhileRunning()) return 1;
        tidy(true);
        Map<String, String> names = new LinkedHashMap<>();
        List<Loaded> ps = patches();
        for (Loaded l : ps) if (l.patch != null) names.put(l.patch.modId, l.patch.modName);
        Set<String> seen = new LinkedHashSet<>();
        List<ModInfo> all = ModInfo.scan(mods);
        survey(all, ps);
        for (ModInfo m : all) {
            if (!selected(m.id) || m.id.equals(Checks.MONITOR_ID)) continue;
            boolean state = Files.isRegularFile(stateDir(m.dir).resolve(SENTINEL));
            if (!state && !names.containsKey(m.id)) continue;
            seen.add(m.id);
            undoOne(m, names.getOrDefault(m.id, m.name), state, true);
        }
        // saved settings of mods that are no longer installed
        for (String id : names.keySet()) {
            if (seen.contains(id)) continue;
            Saves.Plan sp = Saves.planUndo(saves, id);
            if (sp.lines.isEmpty() && !sp.recordChanged) { add("Not installed", names.get(id)); continue; }
            if (sp.lines.isEmpty()) { if (!dryRun) Saves.commit(saves, sp); add("Not installed", names.get(id)); continue; }
            out.println();
            out.println("== " + names.get(id) + " (not installed)");
            if (!dryRun) Saves.commit(saves, sp);
            Saves.print(sp, out);
            add("Restored", names.get(id) + " (saved settings)");
        }
        writeMonitorIndex(true);
        removeMonitor(all, !(only.isEmpty() && skip.isEmpty()));
        return finish();
    }

    void undoOne(ModInfo m, String name, boolean state, boolean heading) throws Failure {
        Saves.Plan sp = Saves.planUndo(saves, m.id);
        if (!state && sp.lines.isEmpty()) {
            if (sp.recordChanged && !dryRun) Saves.commit(saves, sp); // forget a mod whose folder was replaced
            add("Not patched", name);
            return;
        }
        if (heading) {
            out.println();
            out.println("== " + name + " " + m.version + "  (" + m.dir.getFileName() + ")");
        }
        if (state) {
            Map<String, Object> s;
            try { s = readSentinel(m.dir); } catch (Failure f) { out.println("  FAILED: " + f.getMessage()); add("Failed", name); warn.warn(name, f.getMessage() + ".", "Delete this mod's folder and install it again cleanly."); return; }
            List<String> atB = new ArrayList<>(), atA = new ArrayList<>();
            List<String> bad = "applying".equals(str(s, "status")) ? List.of() : drifted(m.dir, s, atB, atA);
            if (!bad.isEmpty() && !force) {
                out.println("  FAILED: these files changed after the tool patched them:");
                for (String b : bad) out.println("    " + b);
                out.println("  Nothing was touched. Run undo with --force to put the backups back anyway, or reinstall the mod.");
                add("Failed", name);
                warn.warn(name + " " + m.version, "updated in place since it was patched (changed: " + String.join(", ", bad) + "), so undo left it alone.",
                        "Reinstall the mod cleanly, or run \"app\\undo.bat --force\" to put the backups back over the update.");
                return;
            }
            int n = files(s).size();
            String nf = count(n, "file");
            if (dryRun) { if (n > 0) out.println("  Would put back " + nf + "."); }
            else {
                if (!restoreChecked(name, m, s)) return;
                if (n > 0) out.println("  Put back " + nf + ".");
            }
        }
        if (!dryRun) Saves.commit(saves, sp);
        Saves.print(sp, out);
        for (String l : sp.lines) if (l.contains("left as is")) warn.note(name + ":" + l.trim().substring("Saved setting".length()) + ".");
        add("Restored", name);
    }

    // ------------------------------------------------------------------ status

    int status() throws Exception {
        header("status");
        tidy(false);
        startUpdateChecks();
        boolean anyBad = false;
        Set<String> withPatch = new LinkedHashSet<>();
        List<ModInfo> all = ModInfo.scan(mods);
        List<Loaded> ps = patches();
        survey(all, ps);
        Set<String> enabled = Checks.enabled(mods);
        List<String> unpatched = new ArrayList<>();
        for (Loaded l : ps) {
            if (l.patch == null) { out.println(); out.println("== " + l.file.getFileName()); out.println("  The patch file cannot be read: " + l.error); anyBad = true; warn.warn(String.valueOf(l.file.getFileName()), "the patch file cannot be read: " + l.error, "Get a fresh copy of the tool."); continue; }
            Patch p = l.patch;
            withPatch.add(p.modId);
            List<ModInfo> found = ModInfo.byId(all, p.modId);
            if (cust.modOff(p.modId)) {
                add("Switched off", p.modName);
                for (ModInfo m : found) if (Files.isRegularFile(stateDir(m.dir).resolve(SENTINEL)))
                    warn.warn(p.modName, "switched off in customize.txt, but still patched.", "Run apply to put it back.");
                continue;
            }
            if (found.isEmpty()) { add("Not installed", p.modName); continue; }
            for (ModInfo m : found) {
                out.println();
                boolean other = otherVersion(p, m);
                out.println("== " + p.modName + " " + m.version + "  (" + m.dir.getFileName() + ")" + (p.tested.contains(m.version) ? "" : other ? "  another version" : "  untested version"));
                Map<String, Object> s;
                try { s = readSentinel(m.dir); } catch (Failure f) { out.println("  " + f.getMessage()); anyBad = true; add("Failed", p.modName); warn.warn(p.modName, f.getMessage() + ".", "Delete this mod's folder and install it again cleanly."); continue; }
                if (other) {
                    if (s != null && !"applying".equals(str(s, "status"))) {
                        List<String> bad = drifted(m.dir, s, new ArrayList<>(), new ArrayList<>());
                        if (!bad.isEmpty()) {
                            out.println("  These files changed after the tool patched them: " + String.join(", ", bad));
                            out.println("  Apply will refuse. Reinstall the mod cleanly, then run apply.");
                            anyBad = true; add("Failed", p.modName);
                            warn.warn(p.modName + " " + m.version, "updated in place since it was patched (changed: " + String.join(", ", bad) + "). Apply will leave it alone, so it may be half patched.",
                                    "Delete this mod's folder, install it again cleanly, then run apply.");
                            continue;
                        }
                    }
                    out.println("  Apply skips it. " + skipFact(p, m) + (s != null ? " An earlier apply rebalanced this folder, so apply puts its original files back." : ""));
                    add("Skipped", p.modName + " " + m.version + " (tested with " + testedText(p) + ")");
                    otherVersionWarning(p, m);
                    continue;
                }
                Set<String> off = offFor(p);
                Set<String> offByChoice = new TreeSet<>(off);
                List<String> unmet = skipUnmet(p, off, k -> available(k, ps, all));
                Set<String> skippedNow = new TreeSet<>(off);
                skippedNow.removeAll(offByChoice);
                for (String sl : unmet) { out.println(sl.replace("Skipped:", "Would skip:")); skipWarning(p, sl); }
                String hash = Patch.hashOf(p.raw, cust.lines(p, off));
                Planned pl = null;
                String planFail = null;
                try {
                    pl = compute(p, m, off, s != null ? backups(m.dir, s) : Map.of());
                } catch (Failure f) { planFail = f.getMessage(); }
                if (s != null) {
                    out.println("  Patched " + str(s, "time") + " by tool " + str(s, "tool_version") + ", status " + str(s, "status") + ".");
                    List<String> atB = new ArrayList<>(), atA = new ArrayList<>();
                    List<String> bad = drifted(m.dir, s, atB, atA);
                    List<String> bb = badBackups(m.dir, s);
                    if (!bb.isEmpty()) backupWarning(p, m, bb);
                    if (!bad.isEmpty()) {
                        out.println("  These files changed after the tool patched them: " + String.join(", ", bad));
                        out.println("  Apply will refuse. Reinstall the mod cleanly, then run apply.");
                        anyBad = true; add("Failed", p.modName);
                        warn.warn(p.modName + " " + m.version, "updated in place since it was patched (changed: " + String.join(", ", bad) + "). Apply will leave it alone, so it may be half patched.",
                                "Delete this mod's folder, install it again cleanly, then run apply.");
                        continue;
                    } else if ("applying".equals(str(s, "status"))) {
                        out.println("  The last run was interrupted. Apply will put the files back and apply again.");
                        add("Not patched", p.modName);
                        warn.warn(p.modName, "the last apply on this mod was interrupted, so it may be half patched.", "Run apply; it puts the files back and applies again.");
                    } else if (hash.equals(str(s, "patch_hash")) && atB.isEmpty()) {
                        out.println("  Apply would do nothing: up to date.");
                        add("Already applied", p.modName);
                        versionWarning(p, m, s);
                        // apply leaves an up-to-date mod alone, so a backup it cannot read is no failed plan;
                        // the backup warning above says what it costs (undo cannot put that file back)
                        if (planFail != null && !bb.isEmpty()) { savedWarnings(p, m, null); continue; }
                    } else {
                        out.println("  Apply would put the files back and apply the current changes.");
                        boolean otherSet = !Text.sha256(p.raw).equals(str(s, "patch_sha256"));
                        if (otherSet) {
                            // one warning for every mod another version rebalanced, not one each
                            add("Other patch set", p.modName);
                            String v = str(s, "tool_version");
                            outOfDate.computeIfAbsent(v == null ? "?" : v, k -> new ArrayList<>()).add(p.modName);
                        } else {
                            add("Not patched", p.modName);
                            warn.warn(p.modName, !skippedButApplied(skippedNow, s) ? "patched with a different customization than the one in customize.txt now."
                                            : "patched with a change that apply now skips, because a change it needs is not applied.",
                                    "Run apply; it puts the files back and applies the current changes.");
                        }
                    }
                    savedWarnings(p, m, pl);
                    for (String rel : trackedPaths(s)) if (!Files2.writable(m.dir.resolve(rel))) { lockedWarning(p, m, rel); break; }
                } else {
                    String was = Saves.patchedVersion(saves, p.modId);
                    boolean on = enabled == null || enabled.contains(p.modId);
                    if (was != null)
                        warn.warn(p.modName + " " + m.version, "its folder was replaced since it was patched (at version " + was + "), so it is not patched now.",
                                "Run apply to patch the new folder.");
                    else if (planFail == null) unpatched.add(p.modName + (on ? "" : " (not enabled)")); // apply patches every installed mod, enabled or not
                }
                if (planFail != null) {
                    out.println("  " + (s == null ? "Not patched. " : "") + "Apply would fail: " + planFail);
                    anyBad = true; add("Failed", p.modName);
                    failWarning(p, m, planFail);
                    continue;
                }
                if (s == null) {
                    out.println("  Not patched. Apply would make " + count(pl.applied.size(), "change") + " in " + count(pl.results.size(), "file") + ":");
                    add("Not patched", p.modName);
                    for (String rel : pl.results.keySet()) if (!Files2.writable(m.dir.resolve(rel))) { lockedWarning(p, m, rel); break; }
                    savedWarnings(p, m, pl);
                } else out.println(pl.applied.size() == 1 ? "  The change and what it writes:" : "  The " + pl.applied.size() + " changes and what they write:");
                if (!p.tested.contains(m.version))
                    warn.warn(p.modName + " " + m.version, "this version is not one the patch was tested with (" + String.join(", ", p.tested) + "), but every anchor still matches, so apply patches it.",
                            "Nothing to do unless the mod behaves oddly; then run undo and stay on version " + String.join(" or ", p.tested) + ".");
                cannotFollowWarnings(p, pl);
                for (Patch.Change c : pl.applied) {
                    BigDecimal st = cust.strength(p.modId, c.id);
                    out.println("    " + c.title + "  [" + p.modId + ":" + c.id + (st.compareTo(BigDecimal.ONE) == 0 ? "" : ", strength " + Customize.plain(st)) + "]");
                    for (String sl : pl.shows.get(c.id)) out.println("        " + sl);
                }
                for (String k : pl.ws.luna.keySet()) {
                    String cur = saves == null ? null : Saves.peek(Saves.lunaFile(saves, m.id), k);
                    if (cur != null) out.println("    and the saved setting " + k + " (now " + cur + ")");
                }
            }
        }
        outOfDateWarnings();
        if (!unpatched.isEmpty())
            warn.warn(unpatched.size() + (unpatched.size() == 1 ? " mod" : " mods"), "not patched yet: " + String.join(", ", unpatched) + ".", "Run apply.");
        // mods another version rebalanced that this version does not change: apply puts them back
        Map<String, List<String>> dropped = new java.util.TreeMap<>();
        for (ModInfo m : all) {
            if (withPatch.contains(m.id) || m.id.equals(Checks.MONITOR_ID) || !Files.isRegularFile(stateDir(m.dir).resolve(SENTINEL))) continue;
            out.println();
            out.println("== " + m.name + " " + m.version + "  (" + m.dir.getFileName() + ")");
            Map<String, Object> rec;
            try { rec = readSentinel(m.dir); } catch (Failure f) {
                out.println("  " + f.getMessage() + ".");
                anyBad = true; add("Failed", m.name);
                warn.warn(m.name, f.getMessage() + ".", "Delete this mod's folder and install it again cleanly.");
                continue;
            }
            String by = byVersion(m.dir);
            List<String> bad;
            // a tracked file it cannot read counts as changed, as in status --json
            try { bad = "applying".equals(str(rec, "status")) ? List.of() : drifted(m.dir, rec, new ArrayList<>(), new ArrayList<>()); }
            catch (Failure f) { bad = List.of(f.getMessage()); }
            if (!bad.isEmpty()) {
                // apply and undo both refuse it (undo without --force), so neither is offered
                out.println("  Rebalanced by " + by + ", and this version has no changes for this mod. These files changed since: " + String.join(", ", bad) + ".");
                out.println("  Apply and undo leave it alone. Reinstall the mod cleanly, or run undo with --force to put the backups back over the update.");
                anyBad = true; add("Failed", m.name);
                warn.warn(m.name + " " + m.version, DROPPED_MODIFIED + " (changed: " + String.join(", ", bad) + ").", DROPPED_MODIFIED_ADVICE);
                continue;
            }
            out.println("  Rebalanced by " + by + ", and this version has no changes for this mod. Apply or undo puts its original files back.");
            add("To put back", m.name);
            dropped.computeIfAbsent(by, k -> new ArrayList<>()).add(m.name);
        }
        for (Map.Entry<String, List<String>> e : dropped.entrySet()) {
            List<String> l = e.getValue();
            if (l.size() == 1) warn.warn(l.get(0), "rebalanced by " + e.getKey() + ", and this version has no changes for it.", "Run apply; it puts this mod's original files back.");
            else warn.warn(l.size() + " mods", "rebalanced by " + e.getKey() + ", and this version has no changes for them: " + String.join(", ", l) + ".", "Run apply; it puts their original files back.");
        }
        monitorNote();
        finish();
        return anyBad ? 1 : 0;
    }

    static final String DROPPED_MODIFIED = "rebalanced by another version of the tool and updated in place since, so apply and undo leave it alone";
    static final String DROPPED_MODIFIED_ADVICE = "Delete this mod's folder and install it again cleanly. Or run \"app\\undo.bat --force\" to put the backups back over the update.";

    /** True when a change skipped now (a change it needs is not applied) was applied when the mod was patched. */
    static boolean skippedButApplied(Set<String> skippedNow, Map<String, Object> s) {
        Object ch = s.get("changes");
        if (ch instanceof List) for (Object id : (List<?>) ch) if (skippedNow.contains(String.valueOf(id))) return true;
        return false;
    }

    /** "another version of the tool (1.3.1)", from the mod's record; the version is left out when it is this one's or unknown. */
    static String byVersion(Path mod) {
        String v = null;
        try { Map<String, Object> s = readSentinel(mod); if (s != null) v = str(s, "tool_version"); } catch (Failure f) { /* the record is reported where it is read */ }
        return v == null || v.equals(Main.VERSION) ? "another version of the tool" : "another version of the tool (" + v + ")";
    }

    /** Status: the mods another version of the tool rebalanced, by that version. */
    final Map<String, List<String>> outOfDate = new java.util.TreeMap<>();

    /** One warning per tool version for the mods it rebalanced and this version would rebalance again. */
    void outOfDateWarnings() {
        for (Map.Entry<String, List<String>> e : outOfDate.entrySet()) {
            List<String> l = e.getValue();
            Map<String, Object> rec = new LinkedHashMap<>();
            if (!e.getKey().equals("?")) rec.put("tool_version", e.getKey());
            warn.warn(l.size() + (l.size() == 1 ? " mod" : " mods"), "patched with " + otherSetText(rec) + ": " + String.join(", ", l) + ".",
                    l.size() == 1 ? "Run apply; it puts the files back and applies the current changes." : "Run apply; it puts their files back and applies the current changes.");
        }
    }

    /** The patch set an earlier apply used, in words: older, newer, or another build of this version. */
    static String otherSetText(Map<String, Object> s) {
        String v = str(s, "tool_version");
        if (v == null) return "an older patch set than the one in this tool";
        int c = Checks.compareVersions(v, Main.VERSION);
        return c < 0 ? "an older patch set than the one in this tool (" + v + ")"
                : c > 0 ? "a newer patch set than the one in this tool (" + v + ")"
                : "another patch set than the one in this tool";
    }

    static List<String> trackedPaths(Map<String, Object> s) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : files(s)) out.add(str(f, "path"));
        return out;
    }

    /** check: status without its text; the warnings only. 0 clean, 2 warnings, 1 something would fail. */
    static int check(Main.Opts o, PrintStream console) throws Exception {
        Engine e = new Engine(o, new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        e.quiet = true;
        int rc = e.status();
        console.print(e.warn.render());
        if (rc != 0) return 1;
        return e.warn.count() > 0 ? 2 : 0;
    }

    static String titleOf(Patch p, String id) {
        for (Patch.Change c : p.changes) if (c.id.equals(id)) return c.title;
        return id;
    }

    // ------------------------------------------------------------------ output

    String cmd = "run";
    boolean quiet;

    void header(String cmd) {
        this.cmd = cmd;
        out.println("Survival Rebalance " + Main.versionText() + ": " + cmd + (dryRun ? " (dry run, nothing is written)" : ""));
        if (newerFolder != null) { out.println(); out.println(ToolFolder.newerText(newerFolder, Main.VERSION)); out.println(); }
        out.println("Mods folder: " + mods);
    }

    void add(String k, String name) {
        summary.get(k).add(name);
        if (curId == null) return;
        String st = k.equals("Applied") || k.equals("Already applied") ? "patched" : k.equals("Failed") ? "failed" : k.equals("Skipped") ? "skipped" : "off";
        String had = monState.get(curId);
        if (!"failed".equals(had) && !("patched".equals(had) && (st.equals("off") || st.equals("skipped")))) monState.put(curId, st);
    }

    /** The mod whose outcome add() records for the monitor's index, and those outcomes. */
    String curId;
    final Map<String, String> monState = new LinkedHashMap<>();

    /**
     * Rewrite the index inside every installed monitor: each mod the patch set changes, with
     * "patched", "skipped" (another version than the tested one), "off" (switched off, left out
     * or not installed) or "failed" from this run. "skips_other_versions" tells the monitor that
     * apply skips a mod of another version, so it must not tell the player to press Apply for one.
     */
    void writeMonitorIndex(boolean allOff) throws Failure {
        if (dryRun || loadedAll == null) return;
        List<ModInfo> mons = ModInfo.byId(ModInfo.scan(mods), Checks.MONITOR_ID);
        if (mons.isEmpty()) return;
        List<Patch> ps = new ArrayList<>();
        for (Loaded l : loadedAll) if (l.patch != null && !l.patch.changes.isEmpty()) ps.add(l.patch);
        ps.sort((a, b) -> a.modId.compareTo(b.modId));
        List<Object> list = new ArrayList<>();
        for (Patch p : ps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.modId);
            m.put("name", p.modName);
            m.put("tested_versions", new ArrayList<Object>(p.tested));
            m.put("changes", new Json.RawNum(String.valueOf(p.changes.size())));
            m.put("state", allOff ? "off" : monState.getOrDefault(p.modId, "off"));
            list.add(m);
        }
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("tool_version", Main.VERSION);
        idx.put("skips_other_versions", VersionPolicy.skipsOtherVersions());
        if (manifest.gameVersion != null) idx.put("game_version", manifest.gameVersion);
        idx.put("mods", list);
        byte[] b = Json.write(idx).getBytes(StandardCharsets.UTF_8);
        for (ModInfo m : mons) Files2.atomicWrite(m.dir.resolve("data").resolve("config").resolve("survival_rebalance_index.json"), b);
    }

    int finish() {
        out.println();
        out.println("Summary");
        for (Map.Entry<String, List<String>> e : summary.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            out.println(String.format("  %-16s %3d   %s", e.getKey(), e.getValue().size(), String.join(", ", e.getValue())));
        }
        boolean failed = !summary.get("Failed").isEmpty();
        if (failed) out.println(failedChanged.isEmpty() ? "Some mods failed. Their files were not changed. Read the FAILED lines above."
                : "Some mods failed. Read the FAILED lines above: each says what happened to that mod's files.");
        if (notice != null) {
            String v = notice.await();
            if (v != null) warn.note("Version " + v + " is available. Press Update in the Survival Rebalance window, or run app\\update.bat.");
        }
        out.println();
        out.print(warn.render());
        writeLog();
        return failed ? 1 : 0;
    }

    /** False for status --json, which the window runs often and which has nothing to log. */
    boolean writeLogs = true;

    void writeLog() {
        // the tool's files loose in another folder: the tool writes nothing there, so the console is the log
        if (toolDir == null || !writeLogs || ToolFolder.loose(appDir)) return;
        try {
            Path d = toolDir.resolve("logs");
            Files.createDirectories(d);
            String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")) + "_" + (quiet ? "check" : cmd) + ".log";
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(warn.render().getBytes(StandardCharsets.UTF_8));
            b.write('\n');
            b.write(logBuf.toByteArray());
            Files.write(d.resolve(name), b.toByteArray());
        } catch (IOException e) {
            // a log that cannot be written is not worth failing the run over
        }
    }

    static final class Tee extends OutputStream {
        final OutputStream a, b;
        Tee(OutputStream a, OutputStream b) { this.a = a; this.b = b; }
        @Override public void write(int x) throws IOException { a.write(x); b.write(x); }
        @Override public void write(byte[] x, int off, int len) throws IOException { a.write(x, off, len); b.write(x, off, len); }
        @Override public void flush() throws IOException { a.flush(); b.flush(); }
    }

    static boolean same(byte[] a, byte[] b) { return Arrays.equals(a, b); }

    /** "1 file", "2 files". */
    static String count(int n, String one) { return n + " " + one + (n == 1 ? "" : "s"); }

    // ------------------------------------------------------------------ customize

    /** customize: write customize.txt when missing, else append the mods and changes new since. Never touches user lines. */
    static int customize(Main.Opts o, PrintStream out) throws Exception {
        Path appDir = o.get("--tool-dir") != null ? Main.path(o.get("--tool-dir")) : jarDir();
        if (appDir == null) throw new Failure("give --tool-dir <dir>");
        Path toolDir = Updater.installRoot(appDir);
        Path pdir = o.get("--patches") != null ? Main.path(o.get("--patches")) : appDir.resolve("patches");
        List<Patch> ps = new ArrayList<>();
        Bundle bun = o.get("--patches") == null ? Bundle.current() : null;
        if (bun != null) {
            for (Map.Entry<String, byte[]> e : bun.patches().entrySet()) {
                try { ps.add(Patch.load(pdir.resolve(e.getKey()), e.getValue())); } catch (Failure f) { out.println("skipped " + e.getKey() + ": " + f.getMessage()); }
            }
        } else for (Path f : Build.jsonFiles(pdir)) {
            try { ps.add(Patch.load(f)); } catch (Failure e) { out.println("skipped " + f.getFileName() + ": " + e.getMessage()); }
        }
        ps.sort((a, b) -> a.modName.compareToIgnoreCase(b.modName));
        Path f = toolDir.resolve(Customize.FILE);
        if (!Files.isRegularFile(f)) {
            Files2.atomicWrite(f, Customize.fresh(ps).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            int n = 0;
            for (Patch p : ps) n += p.changes.size();
            out.println("Wrote " + f + " with " + ps.size() + " mods and " + n + " changes, every line commented out.");
            out.println("Open it in a text editor, remove the # in front of a line and change its value, then press Apply in the window (or run apply.bat).");
            return 0;
        }
        byte[] had = Files.readAllBytes(f);
        String text = Text.decode(had).body;
        String add = Customize.additions(text, ps, LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        if (add.isEmpty()) {
            out.println(f.getFileName() + " already lists every mod and change of this patch set. Nothing was added.");
            return 0;
        }
        boolean crlf = text.contains("\r\n") || !text.contains("\n");
        String sep = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
        String addText = sep + add;
        if (crlf) addText = addText.replace("\n", "\r\n");
        byte[] tail = addText.getBytes(Text.decode(had).charset);
        byte[] nb = new byte[had.length + tail.length];
        System.arraycopy(had, 0, nb, 0, had.length);
        System.arraycopy(tail, 0, nb, had.length, tail.length);
        Files2.atomicWrite(f, nb);
        out.println("Added the mods and changes that are new since " + f.getFileName() + " was written, at its end, as comments. Your lines were not changed.");
        return 0;
    }
}
