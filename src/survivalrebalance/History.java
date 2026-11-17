package survivalrebalance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the released versions of the tool had (history.txt, beside this class): every file one
 * of them shipped, with the sha256 of each set of bytes it shipped with, and every mod and change
 * one of their patch sets had. A file another version left in the tool folder is told from a file
 * of the player's by its path AND its bytes; a customize.txt line for a change an earlier version
 * had is told from a typo.
 */
final class History {
    /** Lower-case path relative to the release folder, to the sha256 of every version of it that shipped. */
    final Map<String, Set<String>> files = new TreeMap<>();
    /** Lower-case path to its spelling in the zip that shipped it first. */
    final Map<String, String> spelling = new TreeMap<>();
    final Set<String> mods = new HashSet<>(), changes = new HashSet<>();

    /** The tests set it to a history of their own. */
    static History loaded;

    static synchronized History get() {
        if (loaded == null) {
            loaded = new History();
            try {
                byte[] b = Bundle.resource(History.class.getClassLoader(), "survivalrebalance/history.txt");
                if (b != null) loaded.read(new String(b, StandardCharsets.UTF_8));
            } catch (IOException e) {
                // no history: nothing counts as another version's, so nothing is removed
            }
        }
        return loaded;
    }

    void read(String text) {
        for (String line : Text.lf(text).split("\n")) {
            int sp = line.indexOf(' ');
            if (line.startsWith("#") || sp < 0) continue;
            String kind = line.substring(0, sp), v = line.substring(sp + 1).trim();
            if (kind.equals("file")) {
                int s2 = v.indexOf(' ');
                if (s2 == 64) add(v.substring(65), v.substring(0, 64));
            } else if (kind.equals("mod")) mods.add(v);
            else if (kind.equals("change")) changes.add(v);
        }
    }

    static String key(String rel) { return rel.replace('\\', '/').toLowerCase(Locale.ROOT); }

    void add(String rel, String sha) {
        spelling.putIfAbsent(key(rel), rel.replace('\\', '/'));
        files.computeIfAbsent(key(rel), k -> new HashSet<>()).add(sha.toLowerCase(Locale.ROOT));
    }

    /** True when a released version shipped a file at this path (relative to the release folder, any case). */
    boolean named(String rel) { return files.containsKey(key(rel)); }

    /** True when a released version shipped exactly these bytes at this path. */
    boolean shipped(String rel, String sha) {
        Set<String> s = files.get(key(rel));
        return s != null && s.contains(sha.toLowerCase(Locale.ROOT));
    }
}
