package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * update: the tool replaces itself with the newest release from its GitHub repository.
 *
 * The running JVM holds the jar open, so this class never overwrites the installed files. It
 * downloads the release zip, checks its SHA-256 against latest.json, unpacks it into
 * update/staged/ and writes update/update.json (the marker: every staged file with its hash,
 * and the files to remove). It then exits with INSTALL, and update.bat hands over to
 * update/install_update.bat, which copies the staged files over the tool folder and runs
 * "update --finish" and then "apply" with the new jar. --finish checks every copied file
 * against the marker, removes the files the new version no longer ships, and clears update/.
 * A marker that is still there on a later run means the copy did not finish; update.bat
 * then installs the staged files again, or downloads again when they are damaged.
 */
final class Updater {
    private Updater() {}

    static final String REPO = "https://github.com/mamick2006/survival-rebalance";
    static final String RELEASES = REPO + "/releases";
    static final String DEFAULT_BASE = RELEASES + "/latest/download/";
    static final String LATEST = "latest.json";
    static final String ZIP = "SurvivalRebalance.zip";
    static final String TOP = "SurvivalRebalance/";
    static final String JAR = "SurvivalRebalance.jar";
    static final String DIR = "update", STAGED = "staged", MARKER = "update.json", HELPER = "install_update.bat";
    /** Every file a release ships, relative to the tool folder, one per line. The build writes it. */
    static final String SHIPPED = "shipped.txt";
    /** update's exit code when the staged files are ready for update.bat to install. */
    static final int INSTALL = 10;
    /** Written beside the marker: the files to delete, one path per line, for an installer that reads no JSON. */
    static final String REMOVE = "remove.txt";
    static final int NOTICE_MS = 3000;

    /** Files that belong to the user (or to the updater itself): an update never writes or deletes them. */
    static boolean userOwned(String rel) {
        String l = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = l.substring(l.lastIndexOf('/') + 1);
        if (name.equals("customize.txt") || name.equals("game_dir.txt") || name.equals("disabled_changes.txt")) return true;
        if (l.equals(DIR) || l.startsWith(DIR + "/")) return true;
        for (String seg : l.split("/")) if (seg.equals("logs")) return true;
        return false;
    }

    /**
     * The folder a release unpacks into, decided by where the jar sits: the parent of a jar
     * folder named app (the release layout), else the jar's own folder (1.2.0 and older kept
     * everything flat). shipped.txt only lists the files; a missing one moves nothing.
     */
    static Path installRoot(Path jarDir) {
        if (jarDir == null || Files.isRegularFile(jarDir.resolve(SHIPPED))) return jarDir;
        return ToolFolder.root(jarDir) != null ? ToolFolder.root(jarDir) : jarDir;
    }

    /** The jar among these release paths (the one nearest the top), or null. */
    static String jarIn(java.util.Collection<String> rels) {
        String best = null;
        for (String r : rels) {
            String n = r.substring(r.lastIndexOf('/') + 1);
            if (n.equalsIgnoreCase(JAR) && (best == null || r.length() < best.length())) best = r;
        }
        return best;
    }

    static List<String> lines(byte[] b) {
        List<String> out = new ArrayList<>();
        for (String l : Text.lf(Text.decode(b).body).split("\n")) if (!l.trim().isEmpty()) out.add(l.trim().replace('\\', '/'));
        return out;
    }

    static String userAgent() { return "SurvivalRebalance/" + Main.VERSION; }

    /** Where latest.json and the zip are, or null when this run must not use the network. */
    static String base(Main.Opts o) {
        String u = o.get("--update-url");
        if (u != null) return u.endsWith("/") ? u : u + "/";
        // a run with --tool-dir is a test or a build step: no network unless a URL is given
        return o.get("--tool-dir") == null ? DEFAULT_BASE : null;
    }

    static String manualAdvice() {
        return "To update by hand, download " + ZIP + " from " + RELEASES + " and unzip it into the Starsector folder. When Windows asks, choose to replace the files. Then run apply.";
    }

    // ---------------------------------------------------------------- network

    /** GET with redirects followed; the body, at most max bytes. */
    static byte[] fetch(String url, int timeoutMs, long max) throws IOException {
        String at = url;
        for (int hop = 0; hop < 6; hop++) {
            URL u;
            try { u = URI.create(at).toURL(); } catch (IllegalArgumentException e) { throw new IOException("bad address " + at); }
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", userAgent());
            c.setRequestProperty("Accept", "*/*");
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("redirect without an address");
                    at = URI.create(at).resolve(loc).toString();
                    continue;
                }
                if (code != 200) throw new IOException(code == 404 ? "not found (404)" : "the server answered " + code);
                long len = c.getContentLengthLong();
                if (len > max) throw new IOException("the file is too large (" + len + " bytes)");
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream(len > 0 ? (int) len : 8192);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        b.write(buf, 0, n);
                        if (b.size() > max) throw new IOException("the file is too large");
                    }
                    if (len >= 0 && b.size() != len) throw new IOException("the download stopped after " + b.size() + " of " + len + " bytes");
                    return b.toByteArray();
                }
            } finally { c.disconnect(); }
        }
        throw new IOException("too many redirects");
    }

    static String reason(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return "no answer in time";
        if (e instanceof java.net.UnknownHostException) return "no network, or GitHub cannot be found";
        if (e instanceof java.net.ConnectException) return "the connection was refused";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    static final class Release {
        String version, game, zip, sha256, notes;
    }

    @SuppressWarnings("unchecked")
    static Release parseRelease(byte[] b) throws Failure {
        Object o;
        try { o = Json.toJava(Json.parse(Text.decode(b).body)); } catch (Json.ParseException e) { throw new Failure("latest.json is not valid JSON: " + e.getMessage()); }
        if (!(o instanceof Map)) throw new Failure("latest.json holds no object");
        Map<String, Object> m = (Map<String, Object>) o;
        Release r = new Release();
        r.version = Engine.str(m, "version");
        r.game = Engine.str(m, "game");
        r.zip = Engine.str(m, "zip") == null ? ZIP : Engine.str(m, "zip");
        r.sha256 = Engine.str(m, "sha256");
        r.notes = Engine.str(m, "notes");
        if (r.version == null || !r.version.matches("[0-9][0-9A-Za-z.\\-]{0,40}")) throw new Failure("latest.json has no usable version");
        if (r.sha256 == null || !r.sha256.toLowerCase(Locale.ROOT).matches("[0-9a-f]{64}")) throw new Failure("latest.json has no usable sha256");
        r.sha256 = r.sha256.toLowerCase(Locale.ROOT);
        if (!r.zip.matches("[A-Za-z0-9._\\-]{1,100}")) throw new Failure("latest.json names a bad file: " + r.zip);
        return r;
    }

    // ---------------------------------------------------------------- the notice on ordinary runs

    /** One small request in the background; await() gives a newer version, or null. */
    static final class Notice {
        final long deadline;
        volatile String newer;
        Thread thread;
        Notice(long deadline) { this.deadline = deadline; }

        String await() {
            if (thread == null) return newer;
            long left = deadline - System.currentTimeMillis();
            try { if (left > 0) thread.join(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return newer;
        }
    }

    /** Starts the check, or returns null when it is off: --offline, all:check_updates = off, or no URL for this run. */
    static Notice startNotice(Main.Opts o, Customize cust) {
        if (o.has("--offline") || Boolean.FALSE.equals(cust.checkUpdates) || VersionPolicy.updateOff() != null) return null;
        String base = base(o);
        if (base == null) return null;
        Notice n = new Notice(System.currentTimeMillis() + NOTICE_MS);
        n.thread = new Thread(() -> {
            try {
                Release r = parseRelease(fetch(base + LATEST, NOTICE_MS, 1 << 16));
                if (Checks.compareVersions(r.version, Main.VERSION) > 0) n.newer = r.version;
            } catch (Exception e) {
                // no network is a normal state: no notice
            }
        }, "update-check");
        n.thread.setDaemon(true);
        n.thread.start();
        return n;
    }

    /** The warning for an update that was downloaded but not completely installed. */
    static void pendingWarning(Path toolDir, Warnings warn) {
        if (toolDir == null) return;
        Marker m = readMarker(installRoot(toolDir).resolve(DIR));
        if (m == null) return;
        warn.warn("Survival Rebalance", "the update to version " + m.version + " did not finish installing.",
                "Open SurvivalRebalance.exe, or run update.bat again; either one finishes the install. " + manualAdvice());
    }

    // ---------------------------------------------------------------- the marker

    static final class Marker {
        String version, from;
        final Map<String, String> files = new TreeMap<>();
        final List<String> remove = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    static Marker readMarker(Path ud) {
        Path f = ud.resolve(MARKER);
        if (!Files.isRegularFile(f)) return null;
        Marker m = new Marker();
        try {
            Object o = Json.toJava(Json.parse(Text.decode(Files.readAllBytes(f)).body));
            Map<String, Object> mm = (Map<String, Object>) o;
            m.version = Engine.str(mm, "version");
            m.from = Engine.str(mm, "from");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) mm.get("files")).entrySet()) m.files.put(e.getKey(), String.valueOf(e.getValue()));
            for (Object x : (List<Object>) mm.get("remove")) m.remove.add(String.valueOf(x));
        } catch (IOException | Json.ParseException | RuntimeException e) {
            m.version = "?"; // damaged: it still says an install did not finish, and its files list is empty
            m.files.clear();
        }
        return m;
    }

    static void writeMarker(Path ud, Marker m) throws Failure {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("about", "Written by Survival Rebalance while it installs an update. update.bat removes it when the install is done.");
        o.put("version", m.version);
        o.put("from", m.from);
        o.put("files", new LinkedHashMap<String, Object>(m.files));
        o.put("remove", new ArrayList<Object>(m.remove));
        Files2.atomicWrite(ud.resolve(MARKER), Json.write(o).getBytes(StandardCharsets.UTF_8));
    }

    /** True when every staged file is there with the hash the marker gives it. */
    static boolean stagedComplete(Path ud, Marker m) {
        if (m.files.isEmpty()) return false;
        Path st = ud.resolve(STAGED);
        for (Map.Entry<String, String> e : m.files.entrySet()) {
            Path p = st.resolve(e.getKey());
            try { if (!Files.isRegularFile(p) || !Files2.sha(p).equals(e.getValue())) return false; } catch (Failure f) { return false; }
        }
        return true;
    }

    // ---------------------------------------------------------------- the zip

    /** The release zip's files under SurvivalRebalance/, by path relative to the tool folder. */
    static Map<String, byte[]> unpack(byte[] zip) throws Failure {
        Zip z;
        try { z = new Zip(zip); } catch (RuntimeException e) { throw new Failure("it is cut short or damaged"); }
        Map<String, byte[]> out = new TreeMap<>();
        Set<String> lower = new TreeSet<>();
        for (Zip.Entry e : z.entries) {
            String name = e.name.replace('\\', '/');
            if (!name.startsWith(TOP)) throw new Failure("it holds " + name + " outside the " + TOP + " folder");
            String rel = name.substring(TOP.length());
            if (rel.isEmpty() || rel.endsWith("/")) continue;
            if (rel.startsWith("/") || rel.contains("../") || rel.contains("/..") || rel.equals("..") || rel.contains(":")) throw new Failure("it holds a bad path " + name);
            if (userOwned(rel)) throw new Failure("it holds " + rel + ", which belongs to you");
            if (!lower.add(rel.toLowerCase(Locale.ROOT))) throw new Failure("it holds " + rel + " twice");
            byte[] b;
            try { b = z.read(e.name); } catch (RuntimeException x) { throw new Failure("it is cut short or damaged at " + rel); }
            CRC32 c = new CRC32();
            c.update(b);
            if (c.getValue() != e.crc) throw new Failure("it is damaged at " + rel);
            out.put(rel, b);
        }
        byte[] list = out.get(SHIPPED);
        if (list == null) throw new Failure("it does not hold " + SHIPPED);
        List<String> missing = new ArrayList<>();
        for (String r : lines(list)) if (!out.containsKey(r)) missing.add(r);
        if (!missing.isEmpty()) throw new Failure("it does not hold " + String.join(", ", missing.subList(0, Math.min(5, missing.size()))));
        if (jarIn(out.keySet()) == null) throw new Failure("it does not hold " + JAR);
        return out;
    }

    /** Files the installed version shipped that the new one does not: these are removed. */
    static List<String> removals(Path tool, Set<String> newFiles) throws IOException {
        Set<String> old = new TreeSet<>();
        Path sh = tool.resolve(SHIPPED);
        if (Files.isRegularFile(sh)) {
            old.addAll(lines(Files.readAllBytes(sh)));
        } else {
            // a version before 1.2.0 kept no list: its patch files and its companion mod were all its own
            Path pd = tool.resolve("patches");
            if (Files.isDirectory(pd)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(pd, "*.json")) { for (Path p : ds) old.add("patches/" + p.getFileName()); }
            Path md = tool.resolve(Checks.MONITOR_DIR);
            if (Files.isDirectory(md)) try (Stream<Path> s = Files.walk(md)) {
                for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) old.add(tool.relativize(p).toString().replace('\\', '/'));
            }
        }
        Set<String> keep = new TreeSet<>();
        for (String n : newFiles) keep.add(n.toLowerCase(Locale.ROOT));
        List<String> out = new ArrayList<>();
        for (String r : old) {
            if (keep.contains(r.toLowerCase(Locale.ROOT)) || userOwned(r)) continue;
            if (r.startsWith("/") || r.contains("..") || r.contains(":")) continue;
            if (Files.isRegularFile(tool.resolve(r))) out.add(r);
        }
        return out;
    }

    // ---------------------------------------------------------------- update

    static Path toolDir(Main.Opts o) throws Failure {
        Path t = o.get("--tool-dir") != null ? Main.path(o.get("--tool-dir")) : Engine.jarDir();
        if (t == null) throw new Failure("give --tool-dir <dir>");
        return installRoot(t);
    }

    static int failed(PrintStream out, String what) {
        out.println("UPDATE FAILED: " + what);
        out.println("Nothing was installed. Your version " + Main.VERSION + " still works.");
        out.println(manualAdvice());
        return 1;
    }

    static int update(Main.Opts o, PrintStream out) throws Exception {
        Path tool = toolDir(o);
        Path ud = tool.resolve(DIR);
        if (o.has("--finish")) return finish(tool, out);
        out.println("Survival Rebalance " + Main.versionText() + ": update");
        if (VersionPolicy.updateOff() != null) {
            out.println(VersionPolicy.updateOff());
            return 0;
        }
        Marker pending = readMarker(ud);
        if (pending != null) {
            if (stagedComplete(ud, pending)) {
                out.println("The update to version " + pending.version + " was downloaded but did not finish installing. Installing it now.");
                writeHelper(ud, jarIn(pending.files.keySet()));
                return INSTALL;
            }
            out.println("An unfinished update was found, but its downloaded files are incomplete. Removing them and downloading again.");
            clear(ud);
        }
        String base = base(o);
        if (base == null) throw new Failure("give --update-url <address>");
        Release r;
        try { r = parseRelease(fetch(base + LATEST, 15000, 1 << 16)); }
        catch (IOException e) { return failed(out, "cannot reach GitHub to look for a new version (" + reason(e) + ")."); }
        catch (Failure f) { return failed(out, "the release information on GitHub cannot be read (" + f.getMessage() + ")."); }
        if (o.has("--check")) {
            boolean newer = Checks.compareVersions(r.version, Main.VERSION) > 0;
            out.println(newer ? "Version " + r.version + " is available." : "You have the latest version (" + Main.VERSION + ").");
            return newer ? INSTALL : 0;
        }
        if (Checks.compareVersions(r.version, Main.VERSION) <= 0) {
            out.println("You have the latest version (" + Main.VERSION + ").");
            return 0;
        }
        String notes = r.notes == null ? "" : Customize.oneLine(r.notes).trim();
        out.println("Version " + r.version + " is available" + (notes.isEmpty() ? "." : ": " + notes + (notes.matches(".*[.!?]$") ? "" : ".")));
        if (r.game != null) out.println("It was tested on Starsector " + r.game + ".");
        out.println("Downloading " + r.zip + "...");
        byte[] zip;
        try { zip = fetch(base + r.zip, 60000, 512L << 20); }
        catch (IOException e) { return failed(out, "the download of version " + r.version + " did not complete (" + reason(e) + ")."); }
        Path zf = ud.resolve(ZIP);
        try { Files2.atomicWrite(zf, zip); }
        catch (Failure f) { return failed(out, "the download cannot be saved in " + ud + " (" + f.getMessage() + "). Free some disk space, then run update.bat again."); }
        if (!Text.sha256(zip).equals(r.sha256)) {
            clear(ud);
            return failed(out, "the downloaded file does not match its checksum, so it may be damaged or not the real release.");
        }
        Map<String, byte[]> files;
        try { files = unpack(zip); }
        catch (Failure f) { clear(ud); return failed(out, "the downloaded file is not a usable release: " + f.getMessage() + "."); }
        Marker m = new Marker();
        m.version = r.version;
        m.from = Main.VERSION;
        Path st = ud.resolve(STAGED);
        try {
            Files2.deleteTree(st);
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path p = st.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
                m.files.put(e.getKey(), Text.sha256(e.getValue()));
            }
            m.remove.addAll(removals(tool, files.keySet()));
            writeHelper(ud, jarIn(files.keySet()));
            Files2.atomicWrite(ud.resolve(REMOVE), (String.join("\r\n", m.remove) + (m.remove.isEmpty() ? "" : "\r\n")).getBytes(StandardCharsets.UTF_8));
            writeMarker(ud, m); // last: a marker means every staged file is in place
            Files.deleteIfExists(zf);
        } catch (IOException | Failure e) {
            clear(ud);
            return failed(out, "the update cannot be unpacked into " + st + " (" + (e instanceof IOException ? reason((IOException) e) : e.getMessage()) + "). Free some disk space, then run update.bat again.");
        }
        out.println("Downloaded and checked version " + r.version + " (" + files.size() + " files). Installing it.");
        return INSTALL;
    }

    static void clear(Path ud) {
        try { Files2.deleteTree(ud.resolve(STAGED)); } catch (Failure ignored) {}
        try { Files.deleteIfExists(ud.resolve(MARKER)); } catch (IOException ignored) {}
        try { Files.deleteIfExists(ud.resolve(REMOVE)); } catch (IOException ignored) {}
        try { Files.deleteIfExists(ud.resolve(ZIP)); } catch (IOException ignored) {}
    }

    /** update --finish, run by install_update.bat with the new jar after it copied the staged files. */
    static int finish(Path tool, PrintStream out) throws Exception {
        Path ud = tool.resolve(DIR);
        Marker m = readMarker(ud);
        if (m == null) { out.println("No update is waiting to be installed."); return 0; }
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, String> e : m.files.entrySet()) {
            Path p = tool.resolve(e.getKey());
            try { if (!Files.isRegularFile(p) || !Files2.sha(p).equals(e.getValue())) wrong.add(e.getKey()); } catch (Failure f) { wrong.add(e.getKey()); }
        }
        if (m.files.isEmpty() || !wrong.isEmpty()) {
            out.println("UPDATE FAILED: the update to version " + m.version + " did not install completely ("
                    + (wrong.isEmpty() ? "its record is damaged" : wrong.size() + " files were not copied, for example " + wrong.get(0)) + ").");
            out.println("Close any program that has a file of this folder open, then run update.bat again. " + manualAdvice());
            return 1;
        }
        int removed = 0;
        for (String r : m.remove) {
            if (userOwned(r) || m.files.containsKey(r)) continue;
            Path p = tool.resolve(r).normalize();
            if (!p.startsWith(tool)) continue;
            if (Files.deleteIfExists(p)) removed++;
            for (Path d = p.getParent(); d != null && !d.equals(tool) && d.startsWith(tool); d = d.getParent()) {
                if (!Files.isDirectory(d)) continue; // an installer that removed the files first removed the folder too
                try (Stream<Path> s = Files.list(d)) { if (s.findAny().isPresent()) break; }
                Files.delete(d);
            }
        }
        clear(ud);
        out.println("Updated Survival Rebalance from " + m.from + " to " + m.version + "."
                + (removed > 0 ? " Removed " + removed + (removed == 1 ? " file" : " files") + " the new version no longer ships." : ""));
        out.println("Your customize.txt, game_dir.txt and logs were kept.");
        return 0;
    }

    /**
     * update.bat's last line starts this file without "call", so cmd never reads update.bat
     * again and the copy may replace it. The file lives in update/, which no release writes to.
     */
    static String helperBat(String jarRel) {
        String jar = "%ROOT%\\" + (jarRel == null ? JAR : jarRel.replace('/', '\\'));
        return String.join("\r\n",
            "@echo off",
            "rem Survival Rebalance: installs the update that update.bat downloaded. update.bat starts this file.",
            "setlocal",
            "set \"TOOL=%~1\"",
            "set \"JAVA=%~2\"",
            "set \"GAME=%~3\"",
            "if not defined TOOL goto direct",
            "for %%I in (\"%~dp0..\") do set \"ROOT=%%~fI\"",
            "set \"ARGS=\"",
            ":args",
            "if \"%~4\"==\"\" goto argsdone",
            "set ARGS=%ARGS% %4",
            "shift /4",
            "goto args",
            ":argsdone",
            // every path stays inside quotes: a folder name with & ( ) ^ % ! or a comma is text, never a command
            "echo Copying the new files into \"%ROOT%\"",
            "robocopy \"%ROOT%\\update\\staged\" \"%ROOT%\" /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP >nul",
            "if errorlevel 8 goto copyfail",
            "\"%JAVA%\" -jar \"" + jar + "\" update --finish%ARGS%",
            "if errorlevel 1 goto finishfail",
            "echo.",
            "\"%JAVA%\" -jar \"" + jar + "\" apply --game \"%GAME%\"%ARGS%",
            "set \"RC=%ERRORLEVEL%\"",
            "echo.",
            "pause",
            // the install is done. This file does not delete itself: no path goes onto a second command
            // line. The tool removes it at a later start (ToolFolder), and a script that called update.bat
            // goes on with its next line.
            "exit /b %RC%",
            "",
            ":copyfail",
            "echo UPDATE FAILED: the new files could not be copied into the tool folder.",
            "echo Close any program that has a file of this folder open, then run update.bat again.",
            "echo To update by hand, download " + ZIP + " from " + RELEASES + " and unzip it",
            "echo into the Starsector folder. When Windows asks, choose to replace the files. Then run apply.",
            "echo.",
            "pause",
            "exit /b 1",
            "",
            ":finishfail",
            "echo.",
            "pause",
            "exit /b 1",
            "",
            ":direct",
            "echo Run update.bat instead. This file only finishes what update.bat starts.",
            "pause",
            "exit /b 2",
            "");
    }

    /** How every installer since 1.2.0 begins; ToolFolder removes a left-over one only when it begins so. */
    static final String HELPER_HEAD = "@echo off\r\nrem Survival Rebalance: installs the update that update.bat downloaded.";

    /** True when these bytes are an installer the tool wrote (any version since 1.2.0), not some other file of that name. */
    static boolean isHelper(byte[] b) {
        return b.length < 16384 && new String(b, StandardCharsets.ISO_8859_1).startsWith(HELPER_HEAD);
    }

    static void writeHelper(Path ud, String jarRel) throws Failure {
        // the jar's path goes into the installer's text: only plain characters, never one cmd reads as a command
        if (jarRel != null && !jarRel.matches("[A-Za-z0-9._/ \\-]+")) throw new Failure("the release names its jar with characters the installer cannot use: " + jarRel);
        Files2.atomicWrite(ud.resolve(HELPER), helperBat(jarRel).getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------- build steps

    /** shiplist --out <stage dir>: write shipped.txt there, listing every file in it (itself included). */
    static int shipList(Main.Opts o, PrintStream out) throws Exception {
        Path st = Main.path(Build.req(o, "--out"));
        // the file that tells a later jar which version this folder's files are from (ToolFolder.newerFolder)
        if (Files.isDirectory(st.resolve("app")))
            Files2.atomicWrite(st.resolve("app").resolve(ToolFolder.versionFile(Main.VERSION)), ToolFolder.versionText(Main.VERSION).getBytes(StandardCharsets.UTF_8));
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (Stream<Path> s = Files.walk(st)) {
            for (Path p : (Iterable<Path>) s::iterator) if (Files.isRegularFile(p)) names.add(st.relativize(p).toString().replace('\\', '/'));
        }
        names.add(SHIPPED);
        for (String n : names) if (userOwned(n)) throw new Failure("the release must not ship " + n);
        Files2.atomicWrite(st.resolve(SHIPPED), (String.join("\r\n", names) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.println("wrote " + SHIPPED + " with " + names.size() + " files");
        return 0;
    }

    /** release-info --out <zip> --game-version <v> [--notes <text>]: write latest.json and <zip>.sha256 beside the zip. */
    static int releaseInfo(Main.Opts o, PrintStream out) throws Exception {
        Path zip = Main.path(Build.req(o, "--out"));
        byte[] b = Files.readAllBytes(zip);
        unpack(b); // the zip the build made must pass the updater's own checks
        String sha = Text.sha256(b);
        String name = zip.getFileName().toString();
        Files2.atomicWrite(zip.resolveSibling(name + ".sha256"), (sha + "  " + name + "\n").getBytes(StandardCharsets.US_ASCII));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", Main.VERSION);
        m.put("game", Build.req(o, "--game-version"));
        m.put("zip", name);
        m.put("sha256", sha);
        String notes = o.get("--notes");
        m.put("notes", notes == null || notes.isEmpty() ? "Survival Rebalance " + Main.VERSION + " for Starsector " + Build.req(o, "--game-version") + "." : notes);
        Files2.atomicWrite(zip.resolveSibling(LATEST), Json.write(m).getBytes(StandardCharsets.UTF_8));
        out.println("wrote " + LATEST + " and " + name + ".sha256 (" + sha + ")");
        return 0;
    }
}
