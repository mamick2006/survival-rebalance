package survivalrebalance;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the window uses from the engine in its own process: reading JSON and editing
 * customize.txt. Everything that touches the mods runs in a child process instead.
 */
public final class Api {
    private Api() {}

    public static String version() { return Main.VERSION; }

    /** The version as the window shows it, with the build's note when this is not the release build. */
    public static String versionText() { return Main.versionText(); }

    /** Why update is off in this build, or null when the update check and the Update button work. */
    public static String updateOff() { return VersionPolicy.updateOff(); }

    public static String sha256(byte[] b) { return Text.sha256(b); }

    /**
     * Switch the Survival Rebalance Monitor on in the game's mod list (mods\enabled_mods.json),
     * on the player's click. Returns false when it was already on.
     */
    @SuppressWarnings("unchecked")
    public static boolean enableMonitor(Path mods) throws IOException {
        Path f = mods.resolve("enabled_mods.json");
        Map<String, Object> doc = new java.util.LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            Object o = parseJson(Text.decode(Files.readAllBytes(f)).body);
            if (o instanceof Map) doc = (Map<String, Object>) o;
        }
        List<Object> on = doc.get("enabledMods") instanceof List ? (List<Object>) doc.get("enabledMods") : new ArrayList<>();
        if (on.contains(Checks.MONITOR_ID)) return false;
        on.add(Checks.MONITOR_ID);
        doc.put("enabledMods", on);
        try { Files2.atomicWrite(f, Json.write(doc).getBytes(StandardCharsets.UTF_8)); }
        catch (Failure e) { throw new IOException(e.getMessage()); }
        return true;
    }

    /** The install root (customize.txt, logs, update) for the jar's folder: its parent when the jar is in app\. */
    public static Path installRoot(Path jarDir) { return Updater.installRoot(jarDir); }

    /** True when the tool's files lie loose in a folder that is not its own (the tool writes nothing there). */
    public static boolean looseFolder(Path appDir) { return ToolFolder.loose(appDir); }

    /** Strict or loose JSON text as maps, lists, strings, numbers (as text) and booleans. */
    public static Object parseJson(String text) throws IOException {
        try { return plain(Json.toJava(Json.parse(text))); } catch (Json.ParseException e) { throw new IOException(e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private static Object plain(Object o) {
        if (o instanceof Map) { for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) e.setValue(plain(e.getValue())); return o; }
        if (o instanceof List) { List<Object> l = (List<Object>) o; for (int i = 0; i < l.size(); i++) l.set(i, plain(l.get(i))); return l; }
        if (o instanceof Json.RawNum) return o.toString();
        return o;
    }

    // ---------------------------------------------------------------- customize.txt

    public static final String BEGIN = "# ---- Set in the Survival Rebalance window. The window rewrites the lines from here to the end line.";
    public static final String END = "# ---- End of the lines set in the window. Write your own lines above this block.";

    /**
     * Change the window's lines in customize.txt. Each key is a customize.txt key
     * ("some_mod", "some_mod:strength", "some_mod:some-change") and each value its new value,
     * or null to drop the window's line for it. Lines the player wrote are kept byte for byte;
     * the window's own lines live in one block at the end, so they win over the player's lines
     * for the same key. A window line that changes nothing is dropped.
     */
    public static void setCustomize(Path toolDir, Path patchesDir, Map<String, String> edits) throws IOException {
        Path f = toolDir.resolve(Customize.FILE);
        List<Patch> ps = patches(patchesDir);
        Text t;
        if (Files.isRegularFile(f)) t = Text.decode(Files.readAllBytes(f));
        else {
            List<Patch> sorted = new ArrayList<>(ps);
            sorted.sort((a, b) -> a.modName.compareToIgnoreCase(b.modName));
            t = Text.decode(Customize.fresh(sorted).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        try { Files2.atomicWrite(f, t.withBody(editText(t.body, edits, ps)).encode()); }
        catch (Failure e) { throw new IOException(e.getMessage()); }
    }

    /** The new text of customize.txt; see setCustomize. */
    static String editText(String text, Map<String, String> edits, List<Patch> ps) {
        String nl = text.contains("\r\n") || text.isEmpty() ? "\r\n" : "\n";
        String lf = Text.lf(text);
        Map<String, String> mine = new TreeMap<>();
        String hand = lf;
        int b = lf.indexOf(BEGIN);
        if (b >= 0 && (b == 0 || lf.charAt(b - 1) == '\n')) {
            int e = lf.indexOf(END, b);
            int stop = e < 0 ? lf.length() : lf.indexOf('\n', e) < 0 ? lf.length() : lf.indexOf('\n', e) + 1;
            for (String line : lf.substring(b, e < 0 ? lf.length() : e).split("\n")) {
                String t = line.trim();
                if (t.startsWith("#") || t.indexOf('=') < 0) continue;
                mine.put(t.substring(0, t.indexOf('=')).trim(), t.substring(t.indexOf('=') + 1).trim());
            }
            hand = lf.substring(0, b) + lf.substring(stop);
        }
        for (Map.Entry<String, String> e : edits.entrySet()) {
            if (e.getValue() == null) mine.remove(e.getKey());
            else mine.put(e.getKey(), e.getValue());
        }
        // drop the lines that change nothing (with no patches to compare against, keep them all)
        String want = effect(hand, mine, ps);
        if (!ps.isEmpty()) for (String k : new ArrayList<>(mine.keySet())) {
            String v = mine.remove(k);
            if (!effect(hand, mine, ps).equals(want)) mine.put(k, v);
        }
        StringBuilder out = new StringBuilder(hand);
        if (!mine.isEmpty()) {
            while (out.length() > 0 && out.charAt(out.length() - 1) == '\n' && out.length() > 1 && out.charAt(out.length() - 2) == '\n') out.setLength(out.length() - 1);
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
            if (out.length() > 0) out.append('\n');
            out.append(BEGIN).append('\n');
            for (Map.Entry<String, String> e : mine.entrySet()) out.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            out.append(END).append('\n');
        }
        String r = out.toString();
        return nl.equals("\n") ? r : r.replace("\n", "\r\n");
    }

    private static String block(Map<String, String> mine) {
        StringBuilder b = new StringBuilder("\n");
        for (Map.Entry<String, String> e : mine.entrySet()) b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        return b.toString();
    }

    /** Every change's effective strength under this text: the fingerprint used to drop lines that change nothing. */
    private static String effect(String hand, Map<String, String> mine, List<Patch> ps) {
        Customize c = Customize.parse(hand + block(mine));
        StringBuilder b = new StringBuilder();
        for (Patch p : ps) {
            b.append(p.modId).append(c.modOff(p.modId) ? " off" : "").append('\n');
            for (Patch.Change ch : p.changes) {
                BigDecimal s = c.strength(p.modId, ch.id);
                b.append(' ').append(ch.id).append('=').append(Customize.plain(s)).append('\n');
            }
        }
        return b.toString();
    }

    private static List<Patch> patches(Path dir) {
        List<Patch> ps = new ArrayList<>();
        Bundle b = Bundle.current();
        if (b != null) {
            // the patch set this jar carries, not the files in the folder
            for (Map.Entry<String, byte[]> e : b.patches().entrySet()) {
                try { ps.add(Patch.load(dir.resolve(e.getKey()), e.getValue())); } catch (Failure f) { /* status reports it */ }
            }
            return ps;
        }
        try {
            for (Path f : Build.jsonFiles(dir)) {
                try { ps.add(Patch.load(f)); } catch (Failure e) { /* status reports a bad patch file */ }
            }
        } catch (Exception e) { /* no patches folder: nothing to compare against */ }
        return ps;
    }
}
