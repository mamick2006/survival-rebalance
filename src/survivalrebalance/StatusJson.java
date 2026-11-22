package survivalrebalance;

import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * status --json: everything the window shows, as one JSON document. It changes nothing and
 * writes no log. The warnings are the ones check prints.
 *
 * Each supported mod has a "state":
 *   patched, not_patched, outdated (an older patch set), customized (other settings than
 *   customize.txt holds now), replaced (an update put in a new folder), modified (an update
 *   wrote over the patched files), interrupted, fails (apply would fail; see "reason"),
 *   off (switched off in customize.txt), off_but_patched, damaged (the tool's record cannot be
 *   read), not_installed, checked (the mod needs no change, at any version), other_version
 *   (another version than the tested one, so apply skips it), or other_version_patched (another
 *   version, in a folder an earlier apply patched: apply puts its original files back), or dropped
 *   (another version of the tool rebalanced it, and this version has no changes for it: apply puts
 *   its original files back). A dropped mod that is not on the checked list has the kind "dropped".
 * apply_todo and undo_todo name the mods each button changes; apply_has_work also counts a
 * Monitor to replace, a Monitor missing while a mod is rebalanced (monitor_missing), and the
 * tool folder to put right (folder_todo: the words after "Apply will", or null).
 * A dropped mod updated in place since has the state dropped_modified: Apply and Undo leave it
 * alone. newer_folder names a newer version whose files sit beside this jar: then Apply has no
 * work, and Undo still runs.
 */
final class StatusJson {
    private StatusJson() {}

    static int run(Main.Opts o, PrintStream out) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("tool_version", Main.VERSION);
        doc.put("tool_version_text", Main.versionText());
        Engine e;
        try {
            e = new Engine(o, new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        } catch (Failure f) {
            doc.put("error", f.getMessage());
            out.print(Json.write(doc));
            return 2;
        }
        e.quiet = true;
        e.writeLogs = false;
        String error = null;
        try { e.status(); } catch (Failure f) { error = f.getMessage(); }
        doc.put("game_dir", e.game == null ? null : e.game.toString());
        doc.put("mods_dir", e.mods.toString());
        doc.put("tool_dir", e.toolDir == null ? null : e.toolDir.toString());
        doc.put("game_version", Checks.gameVersion(e.game));
        doc.put("tested_game_version", e.manifest.gameVersion);
        if (error != null) {
            doc.put("error", error);
            putWarnings(doc, e);
            out.print(Json.write(doc));
            return 2;
        }
        Map<String, Object> cust = new LinkedHashMap<>();
        cust.put("all_strength", e.cust.all == null ? null : Customize.plain(e.cust.all));
        cust.put("check_updates", !Boolean.FALSE.equals(e.cust.checkUpdates));
        doc.put("customize", cust);

        List<ModInfo> all = ModInfo.scan(e.mods);
        Set<String> enabled = Checks.enabled(e.mods);
        List<Engine.Loaded> ps = e.patches();
        Set<String> known = new LinkedHashSet<>();
        List<Object> mods = new ArrayList<>();
        for (Engine.Loaded l : ps) {
            if (l.patch == null) continue;
            known.add(l.patch.modId);
            mods.add(patchMod(e, l.patch, all, ps, enabled));
        }
        for (String id : e.manifest.checked) {
            if (!known.add(id)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            List<ModInfo> found = ModInfo.byId(all, id);
            m.put("id", id);
            m.put("name", found.isEmpty() ? supportedName(e.manifest, id) : found.get(0).name);
            m.put("kind", "checked");
            put(m, found, enabled, id);
            m.put("tested_versions", List.of());
            m.put("any_version", true);
            m.put("state", found.isEmpty() ? "not_installed" : "checked");
            m.put("state_text", found.isEmpty() ? "Not installed." : "Checked for Survival Mode. It needs no change at any version.");
            m.put("changes", List.of());
            if (!found.isEmpty()) dropped(m, found.get(0));
            mods.add(m);
        }
        // a mod another version rebalanced that this version does not change at all: apply puts it back
        for (ModInfo mi : all) {
            if (known.contains(mi.id) || mi.id.equals(Checks.MONITOR_ID) || !Files.isRegularFile(Engine.stateDir(mi.dir).resolve(Engine.SENTINEL))) continue;
            known.add(mi.id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", mi.id);
            m.put("name", mi.name);
            m.put("kind", "dropped");
            put(m, List.of(mi), enabled, mi.id);
            m.put("tested_versions", List.of());
            m.put("any_version", false);
            m.put("changes", List.of());
            dropped(m, mi);
            mods.add(m);
        }
        doc.put("mods", mods);

        known.add(Checks.MONITOR_ID);
        List<Object> unsupported = new ArrayList<>();
        Set<String> listed = new LinkedHashSet<>();
        for (ModInfo m : all) {
            if (known.contains(m.id) || !listed.add(m.id)) continue;
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", m.id);
            u.put("name", m.name);
            u.put("installed_version", m.version);
            u.put("folder", String.valueOf(m.dir.getFileName()));
            u.put("enabled", enabled == null ? null : enabled.contains(m.id));
            unsupported.add(u);
        }
        doc.put("unsupported", unsupported);
        List<ModInfo> mon = ModInfo.byId(all, Checks.MONITOR_ID);
        doc.put("monitor", mon.isEmpty() ? "not_installed" : enabled != null && !enabled.contains(Checks.MONITOR_ID) ? "not_enabled" : "installed");
        doc.put("monitor_version", mon.isEmpty() ? null : mon.get(0).version);
        String shipMon = e.newerFolder != null ? null : e.olderMonitor(mon);
        doc.put("monitor_shipped_newer", shipMon);
        doc.put("monitor_to_replace", shipMon != null);
        // a mod is rebalanced but the Monitor that watches it is gone: putting it back is Apply's work
        boolean anyPatched = false;
        for (Object x : mods) if ("patched".equals(String.valueOf(((Map<?, ?>) x).get("state")))) anyPatched = true;
        Map<String, byte[]> ship = e.shipMonitor();
        boolean monMissing = e.newerFolder == null && mon.isEmpty() && anyPatched && ship != null && ship.containsKey("mod_info.json");
        doc.put("monitor_missing", monMissing);
        doc.put("newer_folder", e.newerFolder);
        // what Apply and Undo have to do; the window enables its buttons from these
        List<Object> applyTodo = new ArrayList<>(), undoTodo = new ArrayList<>();
        for (Object x : mods) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) x;
            if (TO_APPLY.contains(String.valueOf(m.get("state")))) applyTodo.add(m.get("name"));
            if (TO_UNDO.contains(String.valueOf(m.get("state")))) undoTodo.add(m.get("name"));
        }
        // with a newer version's files beside this jar, Apply refuses; Undo still runs
        boolean refused = e.newerFolder != null;
        // files another version left in the tool folder, or this version's files missing there: Apply puts them right
        String folderTodo = e.folder == null ? null : e.folder.todo;
        doc.put("folder_todo", folderTodo);
        doc.put("apply_todo", refused ? List.of() : applyTodo);
        doc.put("undo_todo", undoTodo);
        doc.put("apply_has_work", !refused && (!applyTodo.isEmpty() || shipMon != null || monMissing || folderTodo != null));
        doc.put("undo_has_work", !undoTodo.isEmpty());
        putWarnings(doc, e);
        out.print(Json.write(doc));
        return 0;
    }

    /** The states Apply changes something in, and the ones Undo puts back. */
    static final List<String> TO_APPLY = List.of("not_patched", "outdated", "customized", "replaced", "interrupted", "off_but_patched", "other_version_patched", "dropped");
    static final List<String> TO_UNDO = List.of("patched", "outdated", "customized", "interrupted", "off_but_patched", "modified", "other_version_patched", "dropped");

    /** A mod with a record but no patch in this version: its state says that apply puts it back. */
    static void dropped(Map<String, Object> m, ModInfo mi) {
        if (!Files.isRegularFile(Engine.stateDir(mi.dir).resolve(Engine.SENTINEL))) return;
        Map<String, Object> s;
        try { s = Engine.readSentinel(mi.dir); } catch (Failure f) {
            m.put("state", "damaged");
            m.put("reason", f.getMessage());
            m.put("state_text", "The tool's record in this mod's folder cannot be read. Reinstall the mod cleanly.");
            return;
        }
        List<String> bad;
        try { bad = "applying".equals(Engine.str(s, "status")) ? List.of() : Engine.drifted(mi.dir, s, new ArrayList<>(), new ArrayList<>()); }
        catch (Failure f) { bad = List.of(f.getMessage()); }
        if (!bad.isEmpty()) {
            // Apply and Undo both refuse it, so neither button is offered for it
            m.put("state", "dropped_modified");
            m.put("reason", "changed: " + String.join(", ", bad));
            m.put("state_text", "Rebalanced by " + Engine.byVersion(mi.dir) + " and updated in place since, so Apply and Undo leave it alone. Delete the mod's folder and install it again cleanly.");
            return;
        }
        m.put("state", "dropped");
        m.put("state_text", "Rebalanced by " + Engine.byVersion(mi.dir) + ". This version has no changes for this mod. Click Apply to put its original files back.");
    }

    static String supportedName(Checks.Manifest man, String id) {
        for (Checks.Supported s : man.supported) if (s.id.equals(id)) return s.name;
        return id;
    }

    static void put(Map<String, Object> m, List<ModInfo> found, Set<String> enabled, String id) {
        m.put("installed", !found.isEmpty());
        m.put("installed_version", found.isEmpty() ? null : found.get(0).version);
        m.put("folder", found.isEmpty() ? null : String.valueOf(found.get(0).dir.getFileName()));
        m.put("enabled", enabled == null ? null : enabled.contains(id));
    }

    static List<Object> warnings(Engine e) {
        List<Object> w = new ArrayList<>();
        for (Warnings.W x : e.warn.warnings) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", x.subject);
            m.put("text", x.text);
            m.put("advice", x.advice);
            w.add(m);
        }
        return w;
    }

    static void putWarnings(Map<String, Object> doc, Engine e) {
        doc.put("warnings", warnings(e));
        doc.put("notes", new ArrayList<Object>(e.warn.notes));
        doc.put("warnings_text", e.warn.render());
        doc.put("window_warnings", windowWarnings(e.warn.warnings));
        List<Object> notes = new ArrayList<>();
        for (String n : e.warn.notes) if (!n.contains("for mods you do not have") && !n.contains("for a mod you do not have") && !n.startsWith("the Survival Rebalance Monitor")) notes.add(n);
        doc.put("window_notes", notes);
    }

    /** The command line's words, as the window says them. */
    static final String[][] WINDOW_WORDS = {
            {"Run apply;", "Click Apply;"}, {"Run apply.", "Click Apply."}, {"then run apply again", "then click Apply again"},
            {"then run apply", "then click Apply"}, {"and run apply", "and click Apply"}, {"run apply again", "click Apply again"},
            {"Run apply", "Click Apply"}, {"run apply", "click Apply"}, {"Run undo", "Click Undo"}, {"run undo", "click Undo"},
            {"run the tool again", "click Check again"}, {"Run the tool again", "Click Check again"},
            {"Read the FAILED lines above;", "Open the log to see what failed;"},
            {"not patched yet", "not rebalanced yet"},
            {"patched with a different customization than the one in customize.txt now.", "rebalanced with other choices than the ones you have now."},
            {"patched with an older patch set than the one in this tool", "rebalanced by an older version of the tool"},
            {"patched with a newer patch set than the one in this tool", "rebalanced by a newer version of the tool"},
            {"patched with another patch set than the one in this tool", "rebalanced by another build of the tool"},
            {"so apply patches it", "so Apply rebalances it"}, {"so it is patched", "so it is rebalanced"}, {"cannot be patched", "cannot be rebalanced"},
            {"it puts the files back and applies the current changes", "it puts the files back and rebalances the mod again"},
            {"it puts their files back and applies the current changes", "it puts their files back and rebalances them again"},
            {"patched with a change that apply now skips", "rebalanced with a change that Apply now skips"},
            {"app\\update.bat checks for one.", "The window shows an Update button when there is one."},
    };

    static String windowWords(String s) {
        if (s == null) return null;
        for (String[] r : WINDOW_WORDS) s = s.replace(r[0], r[1]);
        return s;
    }

    static final java.util.regex.Pattern FOLLOW = java.util.regex.Pattern.compile("\\(([^():\\s]+:[^#()\\s]+)#\\d+(?:\\.\\d+)?\\)");

    /**
     * The warnings for the window: its own words, the not-supported mods marked so the window can
     * show them above the list, and the texts that cannot follow a strength folded to one line per mod.
     */
    static List<Object> windowWarnings(List<Warnings.W> ws) {
        List<Object> out = new ArrayList<>();
        Map<String, Set<String>> stuck = new LinkedHashMap<>();
        for (Warnings.W x : ws) {
            if (x.subject.equals("customize.txt") && (x.text.contains("may not match its number") || x.text.contains("does not know its old value"))) {
                int c = x.text.indexOf(": ");
                String mod = c < 0 ? "" : x.text.substring(0, c);
                java.util.regex.Matcher m = FOLLOW.matcher(x.text);
                stuck.computeIfAbsent(mod, k -> new LinkedHashSet<>()).add(m.find() ? m.group(1) : x.text);
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            boolean unsupported = x.text.startsWith("not supported.");
            m.put("kind", unsupported ? "unsupported" : "warning");
            m.put("subject", x.subject);
            m.put("text", windowWords(x.text));
            m.put("advice", windowWords(x.advice));
            out.add(m);
        }
        for (Map.Entry<String, Set<String>> e : stuck.entrySet()) {
            int n = e.getValue().size();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "strength");
            m.put("subject", e.getKey());
            m.put("text", "texts in " + n + (n == 1 ? " change" : " changes") + " still show the designed numbers at this strength.");
            m.put("advice", "Set the strength to 1 or 0 if the texts must match the numbers.");
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> patchMod(Engine e, Patch p, List<ModInfo> all, List<Engine.Loaded> ps, Set<String> enabled) throws Failure {
        Map<String, Object> m = new LinkedHashMap<>();
        List<ModInfo> found = ModInfo.byId(all, p.modId);
        if (!found.isEmpty()) p = Engine.atInstalled(p, found.get(0));
        m.put("id", p.modId);
        m.put("name", p.modName);
        m.put("kind", "patch");
        put(m, found, enabled, p.modId);
        m.put("tested_versions", new ArrayList<Object>(p.tested));
        Customize.Setting ms = e.cust.mods.get(p.modId);
        m.put("mod_off", e.cust.modOff(p.modId));
        m.put("mod_strength", ms == null || ms.strength == null ? null : Customize.plain(ms.strength));

        String state, text, reason = null;
        Set<String> off = e.offFor(p);
        Set<String> skipped = new LinkedHashSet<>();
        Engine.Planned pl = null;
        if (found.isEmpty()) {
            state = "not_installed";
            text = "Not installed.";
        } else if (e.cust.modOff(p.modId)) {
            boolean patched = Files.isRegularFile(Engine.stateDir(found.get(0).dir).resolve(Engine.SENTINEL));
            state = patched ? "off_but_patched" : "off";
            text = patched ? "Switched off by you, but still rebalanced. Click Apply to put its files back." : "Switched off by you. Apply leaves it as it is.";
        } else {
            ModInfo mi = found.get(0);
            boolean other = Engine.otherVersion(p, mi);
            Map<String, Object> s = null;
            String sentinelError = null;
            try { s = Engine.readSentinel(mi.dir); } catch (Failure f) { sentinelError = f.getMessage(); }
            Set<String> before = new LinkedHashSet<>(off);
            Engine.skipUnmet(p, off, k -> e.available(k, ps, all));
            for (String id : off) if (!before.contains(id)) skipped.add(id);
            String planFail = null;
            String hash = Patch.hashOf(p.raw, e.cust.lines(p, off)); // of the patch as loaded, as Engine hashes it, before compute rebinds p
            if (sentinelError == null && !other) {
                try { pl = e.compute(p, mi, off, s != null ? Engine.backups(mi.dir, s) : Map.of()); p = pl.patch; }
                catch (Failure f) { planFail = f.getMessage(); }
            }
            if (sentinelError != null) {
                state = "damaged"; reason = sentinelError;
                text = "The tool's record in this mod's folder cannot be read. Reinstall the mod cleanly, then click Apply.";
            } else if (s != null) {
                List<String> atB = new ArrayList<>(), atA = new ArrayList<>();
                List<String> bad = Engine.drifted(mi.dir, s, atB, atA);
                if (!bad.isEmpty()) {
                    state = "modified"; reason = "changed: " + String.join(", ", bad);
                    text = "Updated in place since it was rebalanced, so Apply leaves it alone. Reinstall the mod cleanly, then click Apply.";
                } else if (other) {
                    state = "other_version_patched";
                    text = Engine.skipFact(p, mi) + " An earlier Apply rebalanced this folder. Click Apply to put its original files back.";
                } else if ("applying".equals(Engine.str(s, "status"))) {
                    state = "interrupted";
                    text = "The last Apply was interrupted. Click Apply to put its files back and rebalance it again.";
                } else if (hash.equals(Engine.str(s, "patch_hash")) && atB.isEmpty()) {
                    state = "patched";
                    String was = Engine.str(s, "mod_version");
                    text = was != null && !was.equals(mi.version) ? "Rebalanced at version " + was + ". The mod's version changed since." : "Rebalanced.";
                } else if (!Text.sha256(p.raw).equals(Engine.str(s, "patch_sha256"))) {
                    state = "outdated";
                    String by = Engine.str(s, "tool_version");
                    int c = by == null ? -1 : Checks.compareVersions(by, Main.VERSION);
                    text = (c < 0 ? "Rebalanced by an older version of the tool" : c > 0 ? "Rebalanced by a newer version of the tool" : "Rebalanced by another build of the tool")
                            + (by == null || c == 0 ? "" : " (" + by + ")") + ". Click Apply to rebalance it with this version.";
                } else {
                    state = "customized";
                    // a change it was patched with is skipped now, because a change it needs is not applied
                    boolean lost = Engine.skippedButApplied(skipped, s);
                    text = lost ? "Rebalanced with a change that Apply now skips, because a change it needs is not applied. Click Apply to rebalance it again."
                            : "You changed your choices after this mod was rebalanced. Click Apply to use them.";
                }
            } else if (other) {
                state = "other_version";
                text = "Skipped. " + Engine.skipFact(p, mi) + " " + Engine.NOT_THE_MOD + " Install version " + Engine.testedText(p) + ", or wait for a Survival Rebalance update.";
            } else if (Saves.patchedVersion(e.saves, p.modId) != null) {
                state = "replaced";
                text = "An update replaced its folder, so it is not rebalanced now. Click Apply to rebalance it.";
            } else {
                state = "not_patched";
                text = "Not rebalanced yet. Click Apply to rebalance it.";
            }
            if (planFail != null && !state.equals("modified") && !state.equals("patched")) {
                state = "fails"; reason = planFail;
                text = "Apply would fail on this version, so it leaves the mod as it is.";
            }
            if (!other && !p.tested.contains(mi.version) && planFail == null && !state.equals("modified"))
                text += " Version " + mi.version + " is not a tested version, but every change still matches.";
        }
        m.put("state", state);
        m.put("state_text", text);
        m.put("reason", reason);

        List<Object> changes = new ArrayList<>();
        for (Patch.Change c : p.changes) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id);
            cm.put("title", c.title);
            cm.put("before", c.before);
            cm.put("after", c.after);
            cm.put("why", c.why);
            Customize.Setting cs = e.cust.changes.getOrDefault(p.modId, Map.of()).get(c.id);
            BigDecimal st = e.cust.strength(p.modId, c.id);
            cm.put("on", st.signum() != 0);
            cm.put("strength", Customize.plain(st));
            cm.put("change_strength", cs == null || cs.strength == null ? null : Customize.plain(cs.strength));
            cm.put("skipped", skipped.contains(c.id));
            cm.put("requires", new ArrayList<Object>(c.requires));
            List<Object> shows = new ArrayList<>();
            List<String> sh = pl == null ? null : pl.shows.get(c.id);
            if (sh != null) shows.addAll(sh);
            else for (int i = 0; i < c.ops.size(); i++) if (c.ops.get(i) != null) shows.add("#" + i + "  " + Customize.shows(c.ops.get(i), c.ops.get(i)));
            cm.put("ops", shows);
            // at a strength below 1: the "after" text with the numbers the tool will write, when the
            // before and after texts differ only in their numbers; else null (the window then lists the ops)
            boolean partial = st.signum() > 0 && st.compareTo(BigDecimal.ONE) < 0;
            cm.put("after_now", partial && c.before != null && c.after != null ? Customize.follow(c.before, c.after, st) : null);
            changes.add(cm);
        }
        m.put("changes", changes);
        return m;
    }
}
