package survivalrebalance;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The release this jar belongs to, carried inside the jar: every file of the release folder but
 * the jar itself (the patch files, the manifest, the companion mod, the bats, the exe, the
 * README), by path relative to the release folder. The tool reads its patch set, its manifest
 * and its companion mod from here, so loose files in the tool folder cannot add, remove or
 * change a change. A jar without it (the tests, the build's own jar) reads the folder instead.
 *
 * bundle --stage <release folder> --jar <its jar>: the build step that writes it into the jar.
 */
final class Bundle {
    static final String ROOT = "survivalrebalance/bundle/";
    static final String INDEX = ROOT + "index.txt";
    static final String FILES = ROOT + "files/";
    static final String APP = "app/";
    static final String PATCHES = APP + "patches/";
    static final String MANIFEST = APP + Checks.MANIFEST;
    static final String MONITOR = APP + Checks.MONITOR_DIR + "/";

    /** By path relative to the release folder, '/' separated. */
    final Map<String, byte[]> files = new TreeMap<>();
    final Map<String, String> sha = new TreeMap<>();
    private final Map<String, String> lower = new TreeMap<>();

    void put(String rel, byte[] b) {
        files.put(rel, b);
        sha.put(rel, Text.sha256(b));
        lower.put(rel.toLowerCase(Locale.ROOT), rel);
    }

    /** The bundled path with this spelling in any case, or null. */
    String find(String rel) { return lower.get(rel.replace('\\', '/').toLowerCase(Locale.ROOT)); }

    /** The patch files, by file name, in name order. */
    Map<String, byte[]> patches() {
        Map<String, byte[]> m = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(PATCHES) && k.indexOf('/', PATCHES.length()) < 0 && k.toLowerCase(Locale.ROOT).endsWith(".json")) m.put(k.substring(PATCHES.length()), e.getValue());
        }
        return m;
    }

    byte[] manifest() { return files.get(MANIFEST); }

    /** The companion mod's files, by path inside its folder; empty when this release has none. */
    Map<String, byte[]> monitor() {
        Map<String, byte[]> m = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) if (e.getKey().startsWith(MONITOR)) m.put(e.getKey().substring(MONITOR.length()), e.getValue());
        return m;
    }

    // ---------------------------------------------------------------- the one this jar carries

    /** Set by the tests: the bundle the engine uses in place of the jar's own. */
    static Bundle override;
    private static Bundle loaded;
    private static boolean tried;

    /** The bundle this jar carries, or null when it carries none. */
    static synchronized Bundle current() {
        if (override != null) return override;
        if (!tried) {
            tried = true;
            loaded = fromResources();
        }
        return loaded;
    }

    static Bundle fromResources() {
        try { return load(Bundle.class.getClassLoader()); } catch (IOException e) { return null; }
    }

    /** The bundle these resources hold, or null when there is none or it is damaged. */
    static Bundle load(ClassLoader cl) throws IOException {
        byte[] idx = resource(cl, INDEX);
        if (idx == null) return null;
        Bundle b = new Bundle();
        for (String line : Text.lf(new String(idx, StandardCharsets.UTF_8)).split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            int t = line.indexOf('\t');
            if (t < 0) return null;
            String want = line.substring(0, t), rel = line.substring(t + 1);
            byte[] f = resource(cl, FILES + rel);
            if (f == null || !Text.sha256(f).equals(want)) return null; // a damaged jar: the folder is all there is
            b.put(rel, f);
        }
        return b;
    }

    static byte[] resource(ClassLoader cl, String name) throws IOException {
        try (InputStream in = cl == null ? ClassLoader.getSystemResourceAsStream(name) : cl.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ---------------------------------------------------------------- the build step

    static int build(Main.Opts o, PrintStream out) throws Exception {
        Path stage = Main.path(Build.req(o, "--stage")), jar = Main.path(Build.req(o, "--jar"));
        if (!jar.startsWith(stage)) throw new Failure("the jar must be inside the release folder");
        String jarRel = stage.relativize(jar).toString().replace('\\', '/');
        Bundle b = new Bundle();
        try (Stream<Path> s = Files.walk(stage)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String rel = stage.relativize(p).toString().replace('\\', '/');
                if (rel.equals(jarRel)) continue;
                b.put(rel, Files.readAllBytes(p));
            }
        }
        // every file of this release must be in history.txt, so the versions after it know the file is the tool's
        List<String> unknown = new ArrayList<>();
        // this release's own version file is new with it, and its text names its version, so history need not list it
        String own = APP + ToolFolder.versionFile(Main.VERSION);
        for (String rel : b.files.keySet()) if (!History.get().named(rel) && !rel.equals(own)) unknown.add(rel);
        if (!History.get().named(jarRel)) unknown.add(jarRel);
        if (!unknown.isEmpty()) throw new Failure("src\\survivalrebalance\\history.txt does not name " + String.join(", ", unknown) + ". Add a file line (sha256 and path) for each of them.");

        StringBuilder idx = new StringBuilder("# Written by the Survival Rebalance build: sha256, a tab, the path in the release folder.\n");
        for (Map.Entry<String, String> e : b.sha.entrySet()) idx.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
        Path tmp = jar.resolveSibling(jar.getFileName() + ".srtmp");
        try (ZipFile in = new ZipFile(jar.toFile()); ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Enumeration<? extends ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith(ROOT)) continue; // a bundle from an earlier run of this step
                ZipEntry n = new ZipEntry(e.getName());
                n.setTime(e.getTime());
                zo.putNextEntry(n);
                if (!e.isDirectory()) try (InputStream is = in.getInputStream(e)) { is.transferTo(zo); }
                zo.closeEntry();
            }
            zo.putNextEntry(new ZipEntry(INDEX));
            zo.write(idx.toString().getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
            for (Map.Entry<String, byte[]> e : b.files.entrySet()) {
                zo.putNextEntry(new ZipEntry(FILES + e.getKey()));
                zo.write(e.getValue());
                zo.closeEntry();
            }
        }
        Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        // read it back the way the tool will
        Bundle back = read(jar);
        if (back == null || !back.sha.equals(b.sha)) throw new Failure("the bundle in " + jar + " does not read back");
        out.println("bundled " + b.files.size() + " files (" + b.patches().size() + " patch files) into " + jarRel);
        return 0;
    }

    /** The bundle in this jar file, read as the class loader would, or null. */
    static Bundle read(Path jar) throws IOException {
        try (java.net.URLClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            return load(cl);
        }
    }

    /** A bundle made from a release folder on disk, every file but the jar (for the tests). */
    static Bundle of(Path stage) throws IOException {
        Bundle b = new Bundle();
        try (Stream<Path> s = Files.walk(stage)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String rel = stage.relativize(p).toString().replace('\\', '/');
                if (rel.equalsIgnoreCase(APP + Updater.JAR)) continue;
                b.put(rel, Files.readAllBytes(p));
            }
        }
        return b;
    }
}
