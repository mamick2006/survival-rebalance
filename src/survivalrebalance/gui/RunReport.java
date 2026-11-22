package survivalrebalance.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The readable result of an apply, undo or update run, built from the run's own output. */
public final class RunReport {
    private RunReport() {}

    static final Pattern SUMMARY = Pattern.compile("^  (Applied|Already applied|Restored|Skipped|Other patch set|To put back|Not patched|Not installed|Switched off|Failed)\\s+(\\d+)\\s+(.*)$");
    static final Pattern SETTING = Pattern.compile("^Saved setting (changed|restored): (\\S+) (.+?): (.*) -> (.*)$");

    static final class Parsed {
        final Map<String, String> sum = new LinkedHashMap<>();
        final Map<String, List<String>> settings = new LinkedHashMap<>();
        final List<String> failed = new ArrayList<>(), errors = new ArrayList<>(), left = new ArrayList<>(), warnings = new ArrayList<>();
        boolean monitorAdded, monitorRemoved, monitorOff, monitorLeft, monitorKept;
        /** The engine's line under the Summary says no failed mod's files were changed. */
        boolean failedUntouched;
    }

    static int countOf(String names) {
        if (names == null || names.isEmpty()) return 0;
        return names.split(", ").length;
    }

    static Parsed parse(String out) {
        Parsed p = new Parsed();
        String[] lines = out.replace("\r", "").split("\n");
        boolean inSummary = false, inWarn = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i], t = l.trim();
            if (l.startsWith("ERROR: ")) p.errors.add(l.substring(7));
            Matcher sm = SETTING.matcher(t);
            if (sm.matches()) p.settings.computeIfAbsent(sm.group(2), k -> new ArrayList<>()).add(sm.group(3) + ": " + sm.group(4) + " to " + sm.group(5));
            else if (t.startsWith("Saved setting ") && t.contains("left as is")) p.left.add(t.substring("Saved setting ".length()));
            if (t.startsWith("FAILED:")) {
                StringBuilder f = new StringBuilder(t.substring(7).trim());
                for (int j = i + 1; j < lines.length && lines[j].startsWith("  ") && !lines[j].trim().startsWith("==") && j <= i + 3; j++) f.append("\n   ").append(lines[j].trim());
                p.failed.add(f.toString());
            }
            if (t.startsWith("Installed. It warns you") || t.startsWith("Updated from")) p.monitorAdded = true;
            if (t.startsWith("Removed it (the tool installed it)")) p.monitorRemoved = true;
            if (t.startsWith("Left in the mods folder: the tool did not install")) p.monitorLeft = true;
            if (t.startsWith("Kept: this undo covered only some mods")) p.monitorKept = true;
            if (t.contains("installed but not enabled") || t.startsWith("Enable \"")) p.monitorOff = true;
            if (t.startsWith("Some mods failed. Their files were not changed.")) p.failedUntouched = true;
            if (l.equals("Summary")) { inSummary = true; continue; }
            if (inSummary) {
                Matcher m = SUMMARY.matcher(l);
                if (m.matches()) { p.sum.put(m.group(1), m.group(3).trim()); continue; }
                inSummary = false;
            }
            if (l.startsWith("Warnings (") || l.equals("No warnings")) { inWarn = true; continue; }
            if (inWarn && !t.isEmpty() && !t.startsWith("Note:")) {
                if (t.matches("^\\d+\\. .*")) p.warnings.add(t.replaceFirst("^\\d+\\. ", ""));
                else if (!p.warnings.isEmpty()) p.warnings.set(p.warnings.size() - 1, p.warnings.get(p.warnings.size() - 1) + "\n      " + t);
            }
        }
        return p;
    }

    /** The line apply and undo print when they refuse because the game runs from this folder. */
    static final String GAME_RUNNING = "Starsector is running. Close the game first";
    /** The line every command prints first when a newer version's files sit beside this jar. */
    static final String NEWER = "This folder holds the files of Survival Rebalance ";

    /** The refusal that ended a run before it touched any mod, or null. */
    static String refusal(String out) {
        for (String l : out.replace("\r", "").split("\n")) if (l.startsWith(GAME_RUNNING) || l.startsWith(NEWER)) return l.trim();
        return null;
    }

    /** One sentence for the top of the result: what happened. */
    public static String headline(String title, String out, int code) {
        Parsed p = parse(out);
        String r = p.sum.isEmpty() ? refusal(out) : null;
        if (r != null) return title + " did not run. Nothing was changed.";
        if (!p.errors.isEmpty() && p.sum.isEmpty()) return title + " stopped with an error.";
        int failed = countOf(p.sum.get("Failed"));
        if (title.equals("Undo")) {
            int n = countOf(p.sum.get("Restored"));
            return failed > 0 ? "Done, but " + failed + (failed == 1 ? " mod" : " mods") + " could not be put back." : "Done. " + n + (n == 1 ? " mod" : " mods") + " put back.";
        }
        int n = countOf(p.sum.get("Applied")), already = countOf(p.sum.get("Already applied"));
        String h = "Done. " + n + (n == 1 ? " mod" : " mods") + " rebalanced.";
        if (already > 0) h += " " + already + (already == 1 ? " was" : " were") + " already up to date.";
        int skipped = countOf(p.sum.get("Skipped"));
        if (skipped > 0) h += " " + skipped + (skipped == 1 ? " mod skipped: another version." : " mods skipped: other versions.");
        if (failed > 0) h += " " + failed + (failed == 1 ? " mod" : " mods") + " failed.";
        return h;
    }

    public static String summary(String title, String out, int code) { return summary(title, out, code, Map.of()); }

    /** What changed, the next step, the warnings and every saved mod setting changed. names: mod id to name. */
    public static String summary(String title, String out, int code, Map<String, String> names) {
        if (title.equals("Update")) {
            // update prints a few plain sentences; they are the result
            String body = out.replace("\r", "").replaceFirst("^Survival Rebalance [^\n]*: update\n", "").trim();
            return body.isEmpty() ? (code == 0 ? "You have the latest version." : "The update ended with code " + code + ".") : body;
        }
        Parsed p = parse(out);
        String refused = p.sum.isEmpty() ? refusal(out) : null;
        if (refused != null) return refused.replace("then run this again", "then click " + title + " again");
        StringBuilder b = new StringBuilder();
        if (!p.errors.isEmpty()) b.append(title).append(" stopped: ").append(String.join(" ", p.errors)).append("\n\n");
        if (p.sum.get("Applied") != null) b.append("Rebalanced: ").append(p.sum.get("Applied")).append('\n');
        if (p.sum.get("Already applied") != null) b.append("Already up to date: ").append(p.sum.get("Already applied")).append('\n');
        if (p.sum.get("Restored") != null) b.append("Put back: ").append(p.sum.get("Restored")).append('\n');
        if (p.sum.get("Switched off") != null) b.append("Switched off by you, left as they are: ").append(p.sum.get("Switched off")).append('\n');
        if (p.sum.get("Failed") != null) {
            if (p.failedUntouched) b.append("FAILED, their files were not changed: ").append(p.sum.get("Failed")).append('\n');
            else b.append("FAILED: ").append(p.sum.get("Failed")).append(". What failed, below, says what happened to each one's files.\n");
        }
        if (p.sum.get("Skipped") != null) {
            boolean one = countOf(p.sum.get("Skipped")) == 1;
            b.append("\nSkipped, another version: ").append(p.sum.get("Skipped")).append('\n');
            b.append("Survival Rebalance does not change a version it was not tested with. ")
                    .append(one ? "This is not a problem in the mod, and its author cannot help with it." : "This is not a problem in those mods, and their authors cannot help with it.")
                    .append(" Install a tested version, or wait for a Survival Rebalance update.\n");
        }
        if (p.sum.isEmpty() && p.errors.isEmpty()) b.append(code == 0 ? "Nothing to do.\n" : "The run ended with code " + code + ". Show log has the full output.\n");
        if (!p.failed.isEmpty()) {
            b.append("\nWhat failed:\n");
            for (String f : p.failed) b.append(" - ").append(f).append('\n');
            b.append("You can keep playing those mods without the rebalance, or reinstall them cleanly and click Apply again.\n");
        }
        if (title.equals("Apply") && (p.monitorAdded || p.monitorOff))
            b.append("\nNext: switch on the Survival Rebalance Monitor. Click \"Enable the Monitor in the game's mod list\" in the main window, or tick it in the Starsector launcher. It warns you in the game when a mod update removes the rebalance.\n");
        if (title.equals("Undo") && p.monitorRemoved)
            b.append("\nThe Survival Rebalance Monitor mod was removed too. If it was switched on, the Starsector launcher drops it from the mod list the next time it opens, so there is nothing to switch off.\n");
        if (title.equals("Undo") && p.monitorLeft)
            b.append("\nThe Survival Rebalance Monitor mod is still in the mods folder, because the tool did not install that copy. Nothing is patched now, so it has nothing to check. Delete its folder, or switch it off in the Starsector launcher.\n");
        if (title.equals("Undo") && p.monitorKept)
            b.append("\nThe Survival Rebalance Monitor mod stays, because this undo covered only some mods.\n");
        if (!p.warnings.isEmpty()) {
            List<String> w = new ArrayList<>();
            for (String x : p.warnings) if (x.contains("not supported.")) w.add(x);
            for (String x : p.warnings) if (!x.contains("not supported.")) w.add(x);
            b.append("\nWarnings (").append(w.size()).append("):\n");
            for (String x : w) b.append(" - ").append(windowWords(x)).append('\n');
        }
        if (!p.settings.isEmpty() || !p.left.isEmpty()) {
            b.append(title.equals("Undo") ? "\nSaved mod settings (LunaLib) put back:\n" : "\nSaved mod settings (LunaLib) changed:\n");
            for (Map.Entry<String, List<String>> e : p.settings.entrySet()) {
                b.append("   ").append(names.getOrDefault(e.getKey(), e.getKey())).append('\n');
                for (String s : e.getValue()) b.append("      ").append(s).append('\n');
            }
            for (String s : p.left) b.append("   ").append(s).append('\n');
        } else b.append(title.equals("Undo") ? "\nSaved mod settings (LunaLib) put back: none\n" : "\nSaved mod settings (LunaLib) changed: none\n");
        return b.toString().trim();
    }

    static String windowWords(String s) {
        return s.replace("Run apply", "Click Apply").replace("run apply", "click Apply").replace("Run undo", "Click Undo").replace("run undo", "click Undo")
                .replace("not patched yet", "not rebalanced yet").replace("Read the FAILED lines above;", "See What failed above;")
                .replace("app\\update.bat checks for one.", "The window shows an Update button when there is one.");
    }
}
