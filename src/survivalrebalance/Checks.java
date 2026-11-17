package survivalrebalance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The install around the mods: game version, enabled mods, the manifest, the companion mod. */
final class Checks {
    private Checks() {}

    static final String MANIFEST = "manifest.json";
    static final String MONITOR_ID = "survival_rebalance_monitor";
    static final String MONITOR_DIR = "Survival Rebalance Monitor";
    static final String MONITOR_MARK = "installed_by_survival_rebalance.txt";

    // ---------------------------------------------------------------- manifest

    static final class Manifest {
        String gameVersion;
        /** Mods that were checked and need no change, at any version: their ids. */
        final Set<String> checked = new LinkedHashSet<>();
        /** Every supported mod, from the manifest's "supported" list (empty in a manifest older than 1.2.0). */
        final List<Supported> supported = new ArrayList<>();
        boolean present;
    }

    static final class Supported {
        String id, name;
        final List<String> tested = new ArrayList<>();
        int changes;
        /** A mod that needs no change: supported at any version, so it carries no tested version. */
        boolean anyVersion;
    }

    @SuppressWarnings("unchecked")
    static Manifest manifest(Path toolDir) { return manifest(toolDir, MANIFEST); }

    @SuppressWarnings("unchecked")
    static Manifest manifest(Path toolDir, String name) {
        Manifest m = new Manifest();
        if (toolDir == null) return m;
        Path f = toolDir.resolve(name);
        if (!Files.isRegularFile(f)) return m;
        try { return manifest(Files.readAllBytes(f)); } catch (IOException e) { return m; }
    }

    /** The manifest from its bytes (the jar's own copy); an empty one for null. */
    @SuppressWarnings("unchecked")
    static Manifest manifest(byte[] bytes) {
        Manifest m = new Manifest();
        if (bytes == null) return m;
        try {
            Object o = Json.toJava(Json.parse(Text.decode(bytes).body));
            if (!(o instanceof Map)) return m;
            Map<String, Object> mm = (Map<String, Object>) o;
            m.present = true;
            if (mm.get("game_version") != null) m.gameVersion = String.valueOf(mm.get("game_version"));
            Object c = mm.get("checked_mods");
            if (c instanceof Map) m.checked.addAll(((Map<String, Object>) c).keySet()); // the form before 1.3.1: id -> version
            else if (c instanceof List) for (Object x : (List<Object>) c) m.checked.add(String.valueOf(x));
            Object s = mm.get("supported");
            if (s instanceof List) for (Object x : (List<Object>) s) {
                if (!(x instanceof Map)) continue;
                Map<String, Object> e = (Map<String, Object>) x;
                Supported u = new Supported();
                u.id = String.valueOf(e.get("id"));
                u.name = e.get("name") == null ? u.id : String.valueOf(e.get("name"));
                if (e.get("tested_versions") instanceof List) for (Object v : (List<Object>) e.get("tested_versions")) u.tested.add(String.valueOf(v));
                try { u.changes = Integer.parseInt(String.valueOf(e.get("changes"))); } catch (NumberFormatException ex) { u.changes = 0; }
                u.anyVersion = Boolean.TRUE.equals(e.get("any_version")) || (u.changes == 0 && m.checked.contains(u.id));
                m.supported.add(u);
            }
        } catch (Json.ParseException e) {
            // a damaged manifest only costs the checks that need it
        }
        return m;
    }

    // ---------------------------------------------------------------- game version

    static final Pattern GAME_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?[a-z]?-RC\\d+");

    /** The game's version string from starsector-core\starfarer_obf.jar, or null. */
    static String gameVersion(Path game) {
        if (game == null) return null;
        Path jar = game.resolve("starsector-core").resolve("starfarer_obf.jar");
        if (!Files.isRegularFile(jar)) return null;
        try {
            Zip z = new Zip(Files.readAllBytes(jar));
            List<String> first = new ArrayList<>(), second = new ArrayList<>();
            for (String n : z.byName.keySet()) {
                if (!n.endsWith(".class")) continue;
                if (n.toLowerCase(java.util.Locale.ROOT).contains("version")) first.add(n);
                else if (n.startsWith("com/fs/starfarer/") && n.indexOf('/', "com/fs/starfarer/".length()) < 0) second.add(n);
            }
            for (List<String> pass : List.of(first, second)) {
                for (String n : pass) {
                    byte[] b = z.read(n);
                    if (b == null) continue;
                    Matcher m = GAME_VERSION.matcher(new String(b, StandardCharsets.ISO_8859_1));
                    if (m.find()) return m.group();
                }
            }
        } catch (IOException | Failure e) {
            return null;
        }
        return null;
    }

    // ---------------------------------------------------------------- enabled mods

    /** The ids in mods\enabled_mods.json, or null when the file is missing or unreadable. */
    static Set<String> enabled(Path mods) {
        Path f = mods.resolve("enabled_mods.json");
        if (!Files.isRegularFile(f)) return null;
        try {
            Json.Node n = Json.parse(Text.decode(Files.readAllBytes(f)).body);
            if (!(n instanceof Json.Obj)) return null;
            Json.Arr a = ((Json.Obj) n).arr("enabledMods");
            if (a == null) return null;
            Set<String> out = new LinkedHashSet<>();
            for (Json.Node x : a.items) out.add(Json.display(x));
            return out;
        } catch (IOException | Json.ParseException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- versions

    /** Negative when a is older than b, by the numbers in them ("1.2.10" is newer than "1.2.9"). */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        List<Long> xa = new ArrayList<>(), ya = new ArrayList<>();
        for (String s : x) if (!s.isEmpty()) xa.add(Long.parseLong(s.length() > 18 ? s.substring(0, 18) : s));
        for (String s : y) if (!s.isEmpty()) ya.add(Long.parseLong(s.length() > 18 ? s.substring(0, 18) : s));
        for (int i = 0; i < Math.max(xa.size(), ya.size()); i++) {
            long p = i < xa.size() ? xa.get(i) : 0, q = i < ya.size() ? ya.get(i) : 0;
            if (p != q) return Long.compare(p, q);
        }
        return a.compareTo(b);
    }

    // ---------------------------------------------------------------- the companion mod

    /** The monitor folder that ships beside the jar, or null. */
    static Path monitorSource(Path toolDir) {
        if (toolDir == null) return null;
        Path d = toolDir.resolve(MONITOR_DIR);
        return Files.isRegularFile(d.resolve("mod_info.json")) ? d : null;
    }

    static final String MONITOR_INDEX = "data/config/survival_rebalance_index.json";

    /**
     * The companion mod this tool carries, by path inside its folder: the bundle's when the jar
     * has one, else the folder beside the jar. Null when there is none.
     */
    static java.util.Map<String, byte[]> monitorFiles(Path appDir, Bundle b) {
        java.util.Map<String, byte[]> m = new java.util.TreeMap<>();
        if (b != null) m.putAll(b.monitor());
        else {
            Path src = monitorSource(appDir);
            if (src == null) return null;
            try (java.util.stream.Stream<Path> s = Files.walk(src)) {
                for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) m.put(src.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            } catch (IOException e) { return null; }
        }
        return m.containsKey("mod_info.json") ? m : null;
    }

    /**
     * True when the installed companion mod holds exactly these files. A jar counts as the same
     * when it holds the same entries with the same bytes, so two builds of one Monitor whose jars
     * differ only in their zip timestamps are the same copy. Its index (apply rewrites it) and the
     * tool's mark file are left out of the comparison.
     */
    static boolean sameMonitor(Path installed, java.util.Map<String, byte[]> ship) {
        java.util.Map<String, String> have = new java.util.TreeMap<>(), want = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, byte[]> e : ship.entrySet()) if (!e.getKey().equals(MONITOR_INDEX)) want.put(e.getKey(), contentSha(e.getKey(), e.getValue()));
        try (java.util.stream.Stream<Path> s = Files.walk(installed)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String rel = installed.relativize(p).toString().replace('\\', '/');
                if (rel.equals(MONITOR_INDEX) || rel.equals(MONITOR_MARK)) continue;
                have.put(rel, contentSha(rel, Files.readAllBytes(p)));
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return false;
        }
        return have.equals(want);
    }

    /** The sha256 of a file; for a jar, of its entry names and their bytes, without the zip's timestamps. */
    static String contentSha(String rel, byte[] b) {
        if (!rel.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return Text.sha256(b);
        java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(b))) {
            for (java.util.zip.ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (entries.put(e.getName(), e.isDirectory() ? "dir" : Text.sha256(z.readAllBytes())) != null) return Text.sha256(b);
            }
        } catch (IOException | IllegalArgumentException e) {
            return Text.sha256(b);
        }
        if (entries.isEmpty()) return Text.sha256(b);
        StringBuilder all = new StringBuilder("jar\n");
        for (java.util.Map.Entry<String, String> e : entries.entrySet()) all.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        return Text.sha256(all.toString().getBytes(StandardCharsets.UTF_8));
    }
    static void copyTree(Path from, Path to) throws Failure {
        try { Build.copyTree(from, to); } catch (IOException e) { throw new Failure("cannot copy " + from + " to " + to + ": " + e.getMessage()); }
    }

    static void writeMark(Path dir) throws Failure {
        Files2.atomicWrite(dir.resolve(MONITOR_MARK), ("Survival Rebalance copied this mod here. undo.bat removes it again.\n").getBytes(StandardCharsets.UTF_8));
    }
}
