package survivalrebalance.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The monitor's whole decision, with no game types in it: given the patch list, the enabled mods
 * and what each one's survival_rebalance/applied.json says, which mods are not patched and what
 * the player is told. MonitorModPlugin does the reading; the build's test drives this class.
 */
public final class Check {

    /** Where the tool keeps its state inside a patched mod, relative to that mod's folder. */
    public static final String APPLIED_PATH = "survival_rebalance/applied.json";
    /** The patch list the tool writes into this mod. */
    public static final String INDEX_PATH = "data/config/survival_rebalance_index.json";

    /** Names in the campaign message before the rest are counted. */
    public static final int MAX_NAMES = 5;

    /**
     * What the last apply did with a mod, from the index entry's "state". No state means PATCHED.
     * SKIPPED: the installed version was not one the patch was tested with, so apply left it alone.
     */
    public enum State { PATCHED, OFF, FAILED, SKIPPED }

    /** One mod the patch set covers. */
    public static final class Entry {
        public final String id, name;
        public final State state;
        /** The mod versions the patch was tested with; empty when the index does not say. */
        public final List<String> tested;
        public Entry(String id, String name) { this(id, name, State.PATCHED); }
        public Entry(String id, String name, State state) { this(id, name, state, Collections.<String>emptyList()); }
        public Entry(String id, String name, State state, List<String> tested) { this.id = id; this.name = name; this.state = state; this.tested = tested; }
    }

    /** An enabled mod as the game sees it. */
    public static final class Installed {
        public final String name, version;
        public Installed(String name, String version) { this.name = name; this.version = version; }
    }

    /** What applied.json said. A null value in the map passed to decide means the file was not there. */
    public static final class Applied {
        public final String status, modVersion;
        public final boolean unreadable;
        public Applied(String status, String modVersion, boolean unreadable) {
            this.status = status; this.modVersion = modVersion; this.unreadable = unreadable;
        }
        public static final Applied UNREADABLE = new Applied(null, null, true);
    }

    /** OTHER_VERSION: the installed version is not one the patch supports, so apply skips the mod. */
    public enum Problem { NOT_PATCHED, UPDATED, INTERRUPTED, UNREADABLE, FAILED, OTHER_VERSION }

    public static final class Finding {
        public final String id, name, installedVersion, patchedVersion;
        public final Problem problem;
        /** For OTHER_VERSION: the versions the patch supports, joined with " or ". */
        public final String supported;
        Finding(String id, String name, String installedVersion, String patchedVersion, Problem problem) {
            this(id, name, installedVersion, patchedVersion, problem, null);
        }
        Finding(String id, String name, String installedVersion, String patchedVersion, Problem problem, String supported) {
            this.id = id; this.name = name; this.installedVersion = installedVersion;
            this.patchedVersion = patchedVersion; this.problem = problem; this.supported = supported;
        }
    }

    public static final class Result {
        /** enabled: enabled mods the last run patched or meant to; switchedOff: enabled mods the index marks off (undone, switched off or left out). */
        public final int listed, enabled, switchedOff;
        /** Mods that should be patched and are not. Pressing Apply fixes them. */
        public final List<Finding> findings;
        /** Enabled mods the last apply refused. The tool already told the player why. */
        public final List<Finding> failed;
        /** Enabled mods of a version the patch does not support. Apply skips them, so only an update of the tool helps. */
        public final List<Finding> skipped;
        Result(int listed, int enabled, int switchedOff, List<Finding> findings, List<Finding> failed, List<Finding> skipped) {
            this.listed = listed; this.enabled = enabled; this.switchedOff = switchedOff;
            this.findings = findings; this.failed = failed; this.skipped = skipped;
        }
        public boolean ok() { return findings.isEmpty() && skipped.isEmpty(); }

        /** The one campaign message, or null when there is nothing to say in the game. */
        public String message() {
            if (ok()) return null;
            StringBuilder b = new StringBuilder("Survival Rebalance:");
            if (!findings.isEmpty()) {
                b.append(' ');
                if (findings.size() == 1) {
                    b.append(findings.get(0).name).append(" is not patched.");
                } else {
                    b.append(findings.size()).append(" mods are not patched (");
                    names(b, findings);
                    b.append(").");
                }
                b.append(" Close the game, open SurvivalRebalance.exe and press Apply.");
            }
            if (!skipped.isEmpty()) {
                b.append(' ');
                if (skipped.size() == 1) {
                    Finding f = skipped.get(0);
                    b.append(f.name).append(" is version ").append(f.installedVersion).append(", and Survival Rebalance supports ")
                            .append(f.supported).append(", so it is not rebalanced. This is not a bug in ").append(f.name).append('.');
                } else {
                    b.append(skipped.size()).append(" mods are not rebalanced, because Survival Rebalance does not support the versions you have: ");
                    int shown = Math.min(MAX_NAMES, skipped.size());
                    for (int i = 0; i < shown; i++) {
                        if (i > 0) b.append(i == shown - 1 && shown == skipped.size() ? " and " : ", ");
                        Finding f = skipped.get(i);
                        b.append(f.name).append(' ').append(f.installedVersion).append(" (supports ").append(f.supported).append(')');
                    }
                    if (shown < skipped.size()) b.append(" and ").append(skipped.size() - shown).append(" more");
                    b.append(". This is not a bug in those mods.");
                }
                b.append(" Look for a Survival Rebalance update.");
            }
            if (!failed.isEmpty()) {
                b.append(" The last apply could not patch ");
                names(b, failed);
                b.append('.');
            }
            return b.toString();
        }

        private static void names(StringBuilder b, List<Finding> list) {
            int shown = Math.min(MAX_NAMES, list.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0) b.append(i == shown - 1 && shown == list.size() ? " and " : ", ");
                b.append(list.get(i).name);
            }
            if (shown < list.size()) b.append(" and ").append(list.size() - shown).append(" more");
        }

        /** Every line for the log: one summary line, then one per mod that is not patched or failed. */
        public List<String> logLines() {
            List<String> out = new ArrayList<>();
            if (enabled == 0) {
                // after Undo every entry is off: nothing is patched, so "all patched" would be false
                out.add(listed + " mods in the patch list, " + (switchedOff == 0 ? "none of them enabled."
                        : switchedOff + " of them enabled, none of them patched (Survival Rebalance was undone, or they are switched off)."));
                return out;
            }
            String head = listed + " mods in the patch list, " + enabled + " of them enabled, ";
            String tail = (failed.isEmpty() ? "" : ", " + failed.size() + " refused by the last apply")
                    + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped for another version");
            if (findings.isEmpty() && failed.isEmpty() && skipped.isEmpty()) {
                out.add(head + "all patched.");
                return out;
            }
            out.add(head + findings.size() + " not patched" + tail + ":");
            for (Finding f : findings) out.add(line(f));
            for (Finding f : failed) out.add(line(f));
            for (Finding f : skipped) out.add(line(f));
            return out;
        }

        private static String line(Finding f) {
            String why;
            switch (f.problem) {
                case UPDATED:
                    why = "updated since it was patched (patched at " + f.patchedVersion + ")";
                    break;
                case INTERRUPTED:
                    why = "the last apply did not finish";
                    break;
                case UNREADABLE:
                    why = APPLIED_PATH + " is damaged (unreadable, or its status is not applied)";
                    break;
                case FAILED:
                    why = "the last apply could not patch it (the tool's output says why)";
                    break;
                case OTHER_VERSION:
                    why = "Survival Rebalance supports " + f.supported + ", so apply skips this version (not a bug in the mod)";
                    break;
                default:
                    why = "never patched, or its folder was replaced (no " + APPLIED_PATH + ")";
            }
            return "  " + f.name + " (" + f.id + ") " + f.installedVersion + ": " + why;
        }
    }

    private Check() {}

    /** Reads the index. Entries without an id are dropped; a missing name falls back to the mod's own. */
    public static List<Entry> parseIndex(JSONObject index) {
        if (index == null) return Collections.emptyList();
        JSONArray mods = index.optJSONArray("mods");
        if (mods == null) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < mods.length(); i++) {
            JSONObject m = mods.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "").trim();
            if (id.isEmpty()) continue;
            String name = m.optString("name", "").trim();
            List<String> tested = new ArrayList<>();
            JSONArray tv = m.optJSONArray("tested_versions");
            if (tv != null) for (int j = 0; j < tv.length(); j++) if (!tv.optString(j, "").isEmpty()) tested.add(tv.optString(j));
            out.add(new Entry(id, name.isEmpty() ? null : name, parseState(m.optString("state", "")), tested));
        }
        return out;
    }

    /** True when the tool that wrote the index skips a mod of another version (the released tool does). */
    public static boolean skipsOtherVersions(JSONObject index) {
        return index != null && index.optBoolean("skips_other_versions", false);
    }

    /** "off", "failed" and "skipped" as named; anything else, absent included, is PATCHED. */
    static State parseState(String s) {
        String v = s == null ? "" : s.trim();
        if (v.equalsIgnoreCase("off")) return State.OFF;
        if (v.equalsIgnoreCase("failed")) return State.FAILED;
        if (v.equalsIgnoreCase("skipped")) return State.SKIPPED;
        return State.PATCHED;
    }

    /** Reads the two fields the monitor uses from applied.json. */
    public static Applied parseApplied(JSONObject applied) {
        if (applied == null) return Applied.UNREADABLE;
        return new Applied(applied.optString("status", null), applied.optString("mod_version", null), false);
    }

    /**
     * A mod_info.json version the way the tool records it: a string as is, an object as
     * major.minor.patch with missing parts left out. Must match ModInfo.normalizeVersion in the tool.
     */
    public static String normalizeVersion(Object v) {
        if (v == null || v == JSONObject.NULL) return "?";
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            StringBuilder b = new StringBuilder();
            for (String k : new String[]{"major", "minor", "patch"}) {
                if (!o.has(k) || o.isNull(k)) continue;
                if (b.length() > 0) b.append('.');
                b.append(o.opt(k).toString());
            }
            return b.length() == 0 ? "?" : b.toString();
        }
        return v.toString();
    }

    /** True when the plugin should read this entry's applied.json. */
    public static boolean needsApplied(Entry e) { return e.state == State.PATCHED || e.state == State.SKIPPED; }

    public static Result decide(List<Entry> index, Map<String, Installed> enabled, Map<String, Applied> applied) {
        return decide(index, enabled, applied, false);
    }

    /**
     * @param index   the patch list
     * @param enabled the enabled mods by id; a listed mod absent here is ignored
     * @param applied applied.json per enabled PATCHED or SKIPPED mod; a missing key or null value means no file
     * @param strict  the tool skips a mod of another version (skipsOtherVersions of the index): such a
     *                mod is reported as skipped, never as one to press Apply for
     */
    public static Result decide(List<Entry> index, Map<String, Installed> enabled, Map<String, Applied> applied, boolean strict) {
        List<Finding> out = new ArrayList<>();
        List<Finding> failed = new ArrayList<>();
        List<Finding> skipped = new ArrayList<>();
        int on = 0, off = 0;
        for (Entry e : index) {
            Installed in = enabled.get(e.id);
            if (in == null) continue;
            if (e.state == State.OFF) { off++; continue; }
            on++;
            String name = e.name != null ? e.name : in.name != null ? in.name : e.id;
            if (e.state == State.FAILED) {
                failed.add(new Finding(e.id, name, in.version, null, Problem.FAILED));
                continue;
            }
            // a SKIPPED mod now at a tested version is checked like any other: Apply would patch it
            boolean otherVersion = !e.tested.isEmpty() && !e.tested.contains(in.version);
            if (otherVersion && (strict || e.state == State.SKIPPED)) {
                skipped.add(new Finding(e.id, name, in.version, null, Problem.OTHER_VERSION, String.join(" or ", e.tested)));
                continue;
            }
            Applied a = applied.get(e.id);
            if (a == null) {
                out.add(new Finding(e.id, name, in.version, null, Problem.NOT_PATCHED));
            } else if (a.unreadable || !("applying".equals(a.status) || "applied".equals(a.status))) {
                out.add(new Finding(e.id, name, in.version, null, Problem.UNREADABLE));
            } else if ("applying".equals(a.status)) {
                out.add(new Finding(e.id, name, in.version, a.modVersion, Problem.INTERRUPTED));
            } else if (a.modVersion != null && !a.modVersion.equals(in.version)) {
                out.add(new Finding(e.id, name, in.version, a.modVersion, Problem.UPDATED));
            }
        }
        return new Result(index.size(), on, off, out, failed, skipped);
    }
}
