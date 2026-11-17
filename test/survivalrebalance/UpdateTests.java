package survivalrebalance;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static survivalrebalance.Tests.check;

/** The updater, on a local HTTP server the tests start. Nothing here contacts github.com. */
final class UpdateTests {
    private UpdateTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("update: a newer version is staged, installed, dropped files removed, user files kept", UpdateTests::newer);
        t.put("update: the same or an older version does nothing", UpdateTests::sameOrOlder);
        t.put("update: a bad hash is refused", UpdateTests::badHash);
        t.put("update: a truncated or unusable zip is refused", UpdateTests::badZip);
        t.put("update: offline and 404", UpdateTests::offline);
        t.put("update: an interrupted install is detected and finished", UpdateTests::interrupted);
        t.put("update notice: on, off in customize.txt, off with --offline, never on undo", UpdateTests::notice);
        t.put("apply after an update: a dropped patch file puts its mod back", UpdateTests::droppedPatch);
        t.put("update --check, and a release that keeps the jar in a subfolder", UpdateTests::checkAndSubfolder);
        t.put("update.bat end to end: installs, replaces itself, applies", UpdateTests::endToEnd);
    }

    // ---------------------------------------------------------------- the local server

    static final class Server implements AutoCloseable {
        final HttpServer http;
        final Map<String, byte[]> files = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> asked = new CopyOnWriteArrayList<>();
        volatile String agent;
        volatile int delayMs;

        Server() throws IOException {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/", ex -> {
                String p = ex.getRequestURI().getPath();
                asked.add(p);
                agent = ex.getRequestHeaders().getFirst("User-Agent");
                if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                // like GitHub: the stable address redirects to the asset host
                if (p.startsWith("/latest/download/")) {
                    ex.getResponseHeaders().add("Location", "/assets/" + p.substring("/latest/download/".length()));
                    ex.sendResponseHeaders(302, -1);
                    ex.close();
                    return;
                }
                byte[] b = p.startsWith("/assets/") ? files.get(p.substring("/assets/".length())) : null;
                if (b == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            http.setExecutor(Executors.newCachedThreadPool(r -> { Thread th = new Thread(r); th.setDaemon(true); return th; }));
            http.start();
        }

        String base() { return "http://127.0.0.1:" + http.getAddress().getPort() + "/latest/download/"; }

        boolean askedFor(String name) { for (String a : asked) if (a.endsWith("/" + name)) return true; return false; }

        void serve(String version, byte[] zip) { serve(version, zip, Text.sha256(zip)); }

        void serve(String version, byte[] zip, String sha) {
            files.put("SurvivalRebalance.zip", zip);
            files.put("latest.json", ("{\"version\": \"" + version + "\", \"game\": \"0.98.5a-RC7\", \"zip\": \"SurvivalRebalance.zip\", \"sha256\": \"" + sha + "\", \"notes\": \"test release\"}").getBytes(StandardCharsets.UTF_8));
        }

        @Override public void close() { http.stop(0); }
    }

    static String deadBase() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return "http://127.0.0.1:" + s.getLocalPort() + "/latest/download/"; }
    }

    // ---------------------------------------------------------------- fixtures

    /** rel -> bytes under SurvivalRebalance/, with shipped.txt made from the names. */
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        Map<String, byte[]> all = new TreeMap<>(files);
        all.putIfAbsent(Updater.SHIPPED, shipped(all.keySet()));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bo)) {
            z.putNextEntry(new ZipEntry("SurvivalRebalance/"));
            z.closeEntry();
            for (Map.Entry<String, byte[]> e : all.entrySet()) {
                z.putNextEntry(new ZipEntry("SurvivalRebalance/" + e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bo.toByteArray();
    }

    static byte[] shipped(java.util.Collection<String> names) {
        java.util.TreeSet<String> s = new java.util.TreeSet<>(names);
        s.add(Updater.SHIPPED);
        return (String.join("\r\n", s) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static Map<String, byte[]> release(String version) {
        Map<String, byte[]> m = new TreeMap<>();
        m.put("SurvivalRebalance.jar", b("jar " + version));
        for (String bat : new String[]{"apply.bat", "undo.bat", "status.bat", "update.bat"}) m.put(bat, b("rem " + bat + " " + version + "\r\n"));
        m.put("README.txt", b("readme " + version));
        m.put("patches/a_mod.json", b("{\"v\": \"" + version + "\"}"));
        m.put("patches/new_mod.json", b("{}"));
        return m;
    }

    /** An installed tool: shipped files (a dropped patch among them) and the user's own files. */
    static Path oldTool() throws Exception {
        Path tool = Files.createTempDirectory(Tests.tmp, "tool");
        Map<String, byte[]> m = new TreeMap<>();
        m.put("SurvivalRebalance.jar", b("old jar"));
        for (String bat : new String[]{"apply.bat", "undo.bat", "status.bat", "update.bat"}) m.put(bat, b("rem old " + bat + "\r\n"));
        m.put("README.txt", b("old readme"));
        m.put("patches/a_mod.json", b("{\"v\": \"old\"}"));
        m.put("patches/old_mod.json", b("{}"));
        m.put(Updater.SHIPPED, shipped(m.keySet()));
        for (Map.Entry<String, byte[]> e : m.entrySet()) { Path p = tool.resolve(e.getKey()); Files.createDirectories(p.getParent()); Files.write(p, e.getValue()); }
        Files.writeString(tool.resolve("customize.txt"), "second_in_command:strength = 0.5\r\n");
        Files.writeString(tool.resolve("game_dir.txt"), "C:\\Games\\Starsector");
        Files.createDirectories(tool.resolve("logs"));
        Files.writeString(tool.resolve("logs/old.log"), "a log");
        Files.writeString(tool.resolve("my_notes.txt"), "the user's own file");
        return tool;
    }

    static String out;

    static int upd(Path tool, String base, String... extra) throws Exception {
        List<String> a = new ArrayList<>(List.of("update", "--tool-dir", tool.toString()));
        if (base != null) { a.add("--update-url"); a.add(base); }
        a.addAll(List.of(extra));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int rc = Main.run(a.toArray(new String[0]), new PrintStream(bo, true, StandardCharsets.UTF_8));
        out = bo.toString(StandardCharsets.UTF_8);
        return rc;
    }

    /** What install_update.bat's robocopy does: the staged files over the tool folder. */
    static void copyStaged(Path tool, int limit) throws IOException {
        Path st = tool.resolve("update").resolve("staged");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(st)) { s.filter(Files::isRegularFile).sorted().forEach(files::add); }
        int n = 0;
        for (Path p : files) {
            if (n++ >= limit) break;
            Path t = tool.resolve(st.relativize(p).toString());
            Files.createDirectories(t.getParent());
            Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Map<String, String> tree(Path d) throws Exception { return Build.treeHashes(d); }

    // ---------------------------------------------------------------- tests

    static void newer() throws Exception {
        Path tool = oldTool();
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(release("9.0.0")));
            check(upd(tool, s.base()) == Updater.INSTALL, "update stages: " + out);
            check(out.contains("Version 9.0.0 is available: test release") && out.contains("Downloaded and checked version 9.0.0"), "update output: " + out);
            check(("SurvivalRebalance/" + Main.VERSION).equals(s.agent), "user agent names only the tool: " + s.agent);
            check(Tests.read(tool.resolve("SurvivalRebalance.jar")).equals("old jar"), "nothing installed by the java side");
            Path ud = tool.resolve("update");
            check(Files.isRegularFile(ud.resolve("update.json")) && Files.isRegularFile(ud.resolve("install_update.bat")), "marker and installer written");
            check(Tests.read(ud.resolve("staged/SurvivalRebalance.jar")).equals("jar 9.0.0"), "new jar staged");
            check(!Files.exists(ud.resolve("SurvivalRebalance.zip")), "download removed after staging");
            String helper = Tests.read(ud.resolve("install_update.bat"));
            check(helper.contains("robocopy") && helper.contains("update --finish") && helper.contains("\r\n"), "installer bat: " + helper);
            check(Tests.read(ud.resolve("remove.txt")).equals("patches/old_mod.json\r\n"), "remove.txt lists the dropped file: " + Tests.read(ud.resolve("remove.txt")));
            copyStaged(tool, Integer.MAX_VALUE);
            check(upd(tool, null, "--finish") == 0, "finish: " + out);
            check(out.contains("from " + Main.VERSION + " to 9.0.0") && out.contains("Removed 1 file "), "finish output: " + out);
        }
        check(!Files.exists(tool.resolve("patches/old_mod.json")), "the patch the new version dropped is removed");
        check(Files.exists(tool.resolve("patches/new_mod.json")) && Tests.read(tool.resolve("patches/a_mod.json")).contains("9.0.0"), "new patches installed");
        check(Tests.read(tool.resolve("SurvivalRebalance.jar")).equals("jar 9.0.0") && Tests.read(tool.resolve("update.bat")).contains("9.0.0"), "jar and update.bat replaced");
        check(Tests.read(tool.resolve("customize.txt")).equals("second_in_command:strength = 0.5\r\n"), "customize.txt kept");
        check(Tests.read(tool.resolve("game_dir.txt")).equals("C:\\Games\\Starsector") && Tests.read(tool.resolve("logs/old.log")).equals("a log"), "game_dir.txt and logs kept");
        check(Tests.read(tool.resolve("my_notes.txt")).equals("the user's own file"), "a file the tool never shipped is kept");
        check(!Files.exists(tool.resolve("update/staged")) && !Files.exists(tool.resolve("update/update.json")), "update folder cleared");
        check(Tests.read(tool.resolve("shipped.txt")).contains("patches/new_mod.json") && !Tests.read(tool.resolve("shipped.txt")).contains("old_mod"), "new shipped list");
    }

    static void sameOrOlder() throws Exception {
        for (String v : new String[]{Main.VERSION, "1.1.0", "0.9"}) {
            Path tool = oldTool();
            Map<String, String> before = tree(tool);
            try (Server s = new Server()) {
                s.serve(v, zip(release(v)));
                check(upd(tool, s.base()) == 0 && out.contains("You have the latest version (" + Main.VERSION + ")"), v + ": " + out);
                check(!s.askedFor("SurvivalRebalance.zip"), v + ": the zip is not downloaded");
            }
            check(tree(tool).equals(before), v + ": nothing changed");
        }
        check(Checks.compareVersions("1.10.0", "1.9.9") > 0 && Checks.compareVersions("1.2.0", "1.2.0") == 0, "numeric part by part");
    }

    static void refused(Path tool, Map<String, String> before, String why) throws Exception {
        check(out.contains("UPDATE FAILED") && out.contains(why), "message names the reason (" + why + "): " + out);
        check(out.contains("still works") && out.contains(Updater.RELEASES), "message says the old version works and where to download: " + out);
        check(!Files.exists(tool.resolve("update/staged")) && !Files.exists(tool.resolve("update/update.json")) && !Files.exists(tool.resolve("update/SurvivalRebalance.zip")), "nothing staged or left over");
        Map<String, String> now = tree(tool);
        now.keySet().removeIf(k -> k.startsWith("update/"));
        check(now.equals(before), "the installed files are untouched: " + now.keySet() + " vs " + before.keySet());
    }

    static void badHash() throws Exception {
        Path tool = oldTool();
        Map<String, String> before = tree(tool);
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(release("9.0.0")), Text.sha256(b("something else")));
            check(upd(tool, s.base()) == 1, "refused: " + out);
        }
        refused(tool, before, "does not match its checksum");
    }

    static void badZip() throws Exception {
        Path tool = oldTool();
        Map<String, String> before = tree(tool);
        byte[] full = zip(release("9.0.0"));
        byte[] cut = java.util.Arrays.copyOf(full, full.length / 2);
        try (Server s = new Server()) {
            s.serve("9.0.0", cut); // the hash matches the cut file, so the zip check must catch it
            check(upd(tool, s.base()) == 1, "truncated refused: " + out);
            refused(tool, before, "not a usable release");
            Map<String, byte[]> noBat = release("9.0.0");
            noBat.put(Updater.SHIPPED, shipped(noBat.keySet()));
            noBat.remove("update.bat"); // listed in shipped.txt, missing from the zip
            s.serve("9.0.0", zip(noBat));
            check(upd(tool, s.base()) == 1, "missing bat refused: " + out);
            refused(tool, before, "does not hold update.bat");
            Map<String, byte[]> sneaky = release("9.0.0");
            sneaky.put("customize.txt", b("all = off"));
            s.serve("9.0.0", zip(sneaky));
            check(upd(tool, s.base()) == 1, "a zip that writes a user file is refused: " + out);
            refused(tool, before, "belongs to you");
        }
        check(Tests.read(tool.resolve("customize.txt")).startsWith("second_in_command"), "customize.txt untouched");
    }

    static void offline() throws Exception {
        Path tool = oldTool();
        Map<String, String> before = tree(tool);
        check(upd(tool, deadBase()) == 1, "offline: " + out);
        refused(tool, before, "cannot reach GitHub");
        try (Server s = new Server()) {
            check(upd(tool, s.base()) == 1, "404 on latest.json: " + out);
            refused(tool, before, "404");
            s.serve("9.0.0", zip(release("9.0.0")));
            s.files.remove("SurvivalRebalance.zip");
            check(upd(tool, s.base()) == 1, "404 on the zip: " + out);
            refused(tool, before, "did not complete");
        }
    }

    static void interrupted() throws Exception {
        Path root = Tests.game("1.0");
        Path tool = oldTool();
        String[] engineArgs = {"--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString(),
                "--patches", root.resolve("patches").toString(), "--tool-dir", tool.toString()};
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(release("9.0.0")));
            check(upd(tool, s.base()) == Updater.INSTALL, "staged: " + out);
        }
        copyStaged(tool, 2); // the copy stops part way
        check(upd(tool, null, "--finish") == 1 && out.contains("did not install completely"), "finish sees the half copy: " + out);
        check(Files.exists(tool.resolve("update/update.json")), "marker kept for the next run");
        String check = runMain("check", engineArgs);
        check(check.contains("the update to version 9.0.0 did not finish installing") && check.contains("or run update.bat again"), "check warns: " + check);
        String status = runMain("status", engineArgs);
        check(status.contains("did not finish installing"), "status warns: " + status);
        // the next update.bat finishes it without the network
        check(upd(tool, deadBase()) == Updater.INSTALL && out.contains("did not finish installing. Installing it now"), "resumes: " + out);
        copyStaged(tool, Integer.MAX_VALUE);
        check(upd(tool, null, "--finish") == 0, "finished: " + out);
        check(Tests.read(tool.resolve("SurvivalRebalance.jar")).equals("jar 9.0.0") && !Files.exists(tool.resolve("patches/old_mod.json")), "installed");
        check(!runMain("check", engineArgs).contains("did not finish"), "warning gone");

        // staged files damaged before the install: they are thrown away and downloaded again
        Path tool2 = oldTool();
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(release("9.0.0")));
            check(upd(tool2, s.base()) == Updater.INSTALL, "staged 2: " + out);
            Files.delete(tool2.resolve("update/staged/README.txt"));
            check(upd(tool2, s.base()) == Updater.INSTALL && out.contains("incomplete. Removing them and downloading again"), "downloads again: " + out);
            check(Files.exists(tool2.resolve("update/staged/README.txt")), "staged again");
        }
    }

    static String runMain(String cmd, String[] rest, String... extra) throws Exception {
        List<String> a = new ArrayList<>(List.of(cmd));
        a.addAll(List.of(rest));
        a.addAll(List.of(extra));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Main.run(a.toArray(new String[0]), new PrintStream(bo, true, StandardCharsets.UTF_8));
        return bo.toString(StandardCharsets.UTF_8);
    }

    static void notice() throws Exception {
        Path root = Tests.game("1.0");
        String line = "Version 9.0.0 is available. Press Update in the Survival Rebalance window, or run app\\update.bat.";
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(release("9.0.0")));
            check(Tests.run(root, "apply", "--dev", "--update-url", s.base()) == 0 && Tests.lastOut.contains(line), "apply shows the notice: " + Tests.lastOut);
            check(Tests.lastOut.indexOf(line) > Tests.lastOut.indexOf("Summary"), "in the warnings section");
            check(!s.askedFor("SurvivalRebalance.zip"), "the notice downloads only latest.json");
            check(Tests.run(root, "status", "--dev", "--update-url", s.base()) == 0 && Tests.lastOut.contains(line), "status shows it: " + Tests.lastOut);
            check(Tests.run(root, "check", "--dev", "--update-url", s.base()) != 1 && Tests.lastOut.contains(line), "check shows it: " + Tests.lastOut);
            int asked = s.asked.size();
            check(Tests.run(root, "apply", "--dev", "--offline", "--update-url", s.base()) == 0 && !Tests.lastOut.contains("is available"), "--offline: no notice");
            check(Tests.run(root, "undo", "--update-url", s.base()) == 0, "undo: " + Tests.lastOut);
            check(s.asked.size() == asked, "--offline and undo make no request");
            Files.writeString(root.resolve("tool/customize.txt"), "# mine\r\nall:check_updates = off\r\n");
            check(Tests.run(root, "apply", "--dev", "--update-url", s.base()) == 0 && !Tests.lastOut.contains("is available"), "switched off: " + Tests.lastOut);
            check(!Tests.lastOut.contains("customize.txt: line"), "the line is understood: " + Tests.lastOut);
            check(s.asked.size() == asked, "switched off makes no request");
            Files.writeString(root.resolve("tool/customize.txt"), "all:check_updates = on\r\n");
            check(Tests.run(root, "status", "--dev", "--update-url", s.base()) == 0 && Tests.lastOut.contains(line), "on again");
            s.serve(Main.VERSION, zip(release(Main.VERSION)));
            check(Tests.run(root, "status", "--dev", "--update-url", s.base()) == 0 && !Tests.lastOut.contains("is available"), "same version: no notice");
            s.serve("9.0.0", zip(release("9.0.0")));
            s.delayMs = 8000;
            long t0 = System.currentTimeMillis();
            check(Tests.run(root, "status", "--dev", "--update-url", s.base()) == 0 && !Tests.lastOut.contains("is available"), "slow server: no notice");
            long took = System.currentTimeMillis() - t0;
            check(took < 6000, "a slow server costs at most the timeout: " + took + " ms");
            s.delayMs = 0;
        }
        check(Tests.run(root, "status", "--dev", "--update-url", deadBase()) == 0 && !Tests.lastOut.contains("is available") && !Tests.lastOut.contains("ERROR"), "no network is not an error: " + Tests.lastOut);
        check(Tests.run(root, "status", "--dev") == 0, "a run with --tool-dir and no URL never uses the network");
        Customize c = Customize.parse("all:check_updates = maybe\n");
        check(c.problems.size() == 1 && c.problems.get(0).contains("needs on or off"), "bad value is a problem: " + c.problems);
    }

    static void droppedPatch() throws Exception {
        Path root = Tests.game("1.0");
        Map<String, String> before = Tests.hashes(root);
        check(Tests.run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Applied: Fuel halved"), "first apply: " + Tests.lastOut);
        check(!Tests.hashes(root).equals(before), "patched");
        // the new version has no patch for fx_mod, and one for a mod that is not installed
        Files.delete(root.resolve("patches/fx_mod.json"));
        Files.writeString(root.resolve("patches/other_mod.json"), Tests.patchJson(false).replace("fx_mod", "other_mod").replace("Fx Mod", "Other Mod"));
        check(Tests.run(root, "apply", "--dev") == 0, "apply after the update: " + Tests.lastOut);
        check(Tests.lastOut.contains("this version has no changes for this mod. Putting its original files back.") && Tests.lastOut.contains("Restored"), "says so: " + Tests.lastOut);
        check(Tests.hashes(root).equals(before), "byte-identical to before the first apply");
        check(Tests.run(root, "apply", "--dev") == 0 && !Tests.lastOut.contains("Restored"), "a third apply has nothing to put back: " + Tests.lastOut);
        // an unreadable patch file could be this mod's: then nothing is put back
        Path root2 = Tests.game("1.0");
        check(Tests.run(root2, "apply", "--dev") == 0, "apply 2");
        Files.writeString(root2.resolve("patches/fx_mod.json"), "{ broken");
        Tests.run(root2, "apply", "--dev");
        check(!Tests.lastOut.contains("this version has no changes for this mod"), "an unreadable patch file keeps the mod as it is: " + Tests.lastOut);
    }

    static void checkAndSubfolder() throws Exception {
        Path tool = oldTool();
        Map<String, String> before = tree(tool);
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(release("9.0.0")));
            check(upd(tool, s.base(), "--check") == Updater.INSTALL && out.contains("Version 9.0.0 is available."), "--check newer: " + out);
            check(!s.askedFor("SurvivalRebalance.zip"), "--check downloads only latest.json");
            s.serve(Main.VERSION, zip(release(Main.VERSION)));
            check(upd(tool, s.base(), "--check") == 0 && out.contains("You have the latest version"), "--check same: " + out);
        }
        check(upd(tool, deadBase(), "--check") == 1, "--check offline: " + out);
        check(tree(tool).equals(before), "--check changes nothing");

        // an install that keeps the jar in app\ and shipped.txt at the top
        Path root = Files.createTempDirectory(Tests.tmp, "sub");
        Map<String, byte[]> inst = new TreeMap<>();
        inst.put("app/SurvivalRebalance.jar", b("old"));
        inst.put("app/patches/old_mod.json", b("{}"));
        inst.put("update.bat", b("rem old"));
        inst.put(Updater.SHIPPED, shipped(inst.keySet()));
        for (Map.Entry<String, byte[]> e : inst.entrySet()) { Path p = root.resolve(e.getKey()); Files.createDirectories(p.getParent()); Files.write(p, e.getValue()); }
        Files.writeString(root.resolve("customize.txt"), "mine");
        Map<String, byte[]> rel = new TreeMap<>();
        rel.put("app/SurvivalRebalance.jar", b("new"));
        rel.put("app/patches/a_mod.json", b("{}"));
        rel.put("update.bat", b("rem new"));
        try (Server s = new Server()) {
            s.serve("9.0.0", zip(rel));
            check(upd(root.resolve("app"), s.base()) == Updater.INSTALL, "subfolder stages: " + out);
        }
        check(Files.exists(root.resolve("update/staged/app/SurvivalRebalance.jar")) && !Files.exists(root.resolve("app/update")), "staged at the install root, as the zip lays it out");
        check(Tests.read(root.resolve("update/install_update.bat")).contains("%ROOT%\\app\\SurvivalRebalance.jar"), "installer runs the new jar where the release puts it");
        check(Tests.read(root.resolve("update/remove.txt")).equals("app/patches/old_mod.json\r\n"), "remove.txt: " + Tests.read(root.resolve("update/remove.txt")));
        copyStaged(root, Integer.MAX_VALUE);
        check(upd(root.resolve("app"), null, "--finish") == 0, "finish from app: " + out);
        check(!Files.exists(root.resolve("app/patches/old_mod.json")) && Tests.read(root.resolve("customize.txt")).equals("mine") && !Files.exists(root.resolve("update/staged")), "installed at the root");
        check(Updater.userOwned("app/customize.txt") && Updater.userOwned("app/logs/x.log") && !Updater.userOwned("app/patches/x.json"), "user files are protected in a subfolder too");
        Path ud = Files.createTempDirectory(Tests.tmp, "helper");
        for (String bad : new String[]{"app/a&b/SurvivalRebalance.jar", "a%PATH%/SurvivalRebalance.jar", "x\"y/SurvivalRebalance.jar", "a^b/SurvivalRebalance.jar"})
            Tests.fails(() -> Updater.writeHelper(ud, bad), "characters the installer cannot use");
        check(!Files.exists(ud.resolve(Updater.HELPER)), "no installer is written for a jar path cmd would read as a command");
    }

    /**
     * The folder names endToEnd installs under. Each name holds a character cmd treats as special
     * outside quotes; "Games" (the text before it) is the folder a broken parse would reach.
     */
    static final String[] E2E_FOLDERS = {"Games and Mods", "Games & Mods", "Games&Mods", "Games % Mods", "Games ^ Mods", "Games ! Mods!", "Games (x86) Mods", "Games, Mods"};

    /** The real bats and the jar build.bat just made, on a fixture install, through cmd, under every folder name above. */
    static void endToEnd() throws Exception {
        Path jar = Path.of("build", "SurvivalRebalance.jar").toAbsolutePath(), bats = Path.of("dist-src").toAbsolutePath();
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(bats.resolve("update.bat")) || !System.getProperty("os.name").startsWith("Windows")) {
            System.out.println("  (update.bat end to end skipped: run it from build.bat on Windows)");
            return;
        }
        for (int i = 0; i < E2E_FOLDERS.length; i++) endToEnd(jar, bats, i);
    }

    static void endToEnd(Path jar, Path bats, int n) throws Exception {
        String folder = E2E_FOLDERS[n];
        Path base = Files.createTempDirectory(Tests.tmp, "e2e");
        Path root = base.resolve(folder).resolve("Starsector");
        Files.createDirectories(root);
        Tests.game(root, "1.0");
        Path tool = root.resolve("SurvivalRebalance");
        // decoys in every folder a broken parse could reach: the folder named by the text before the special
        // character, the parent, the game and tool folders, the suite's temp folder and the system temp folder
        String decoy = "sr-e2e-decoy-" + ProcessHandle.current().pid() + "-" + n + ".txt";
        List<Path> decoys = new ArrayList<>();
        for (Path d : new Path[]{base.resolve("Games"), base, base.resolve(folder), root, tool, Tests.tmp, Tests.tmp.getParent()}) {
            Files.createDirectories(d);
            Path f = d.resolve(decoy);
            Files.writeString(f, "the player's own file");
            decoys.add(f);
        }
        try {
            endToEndIn(jar, bats, folder, root, tool, n == 0);
            for (Path f : decoys) check(Files.isRegularFile(f) && Tests.read(f).equals("the player's own file"), "[" + folder + "] the decoy survives: " + f);
        } finally {
            Files.deleteIfExists(Tests.tmp.resolve(decoy));
            Files.deleteIfExists(Tests.tmp.getParent().resolve(decoy));
        }
    }

    static void endToEndIn(Path jar, Path bats, String folder, Path root, Path tool, boolean viaCaller) throws Exception {
        String at = "[" + folder + "] ";
        Map<String, byte[]> install = new TreeMap<>();
        install.put("SurvivalRebalance.jar", Files.readAllBytes(jar));
        for (String bat : new String[]{"apply.bat", "undo.bat", "status.bat", "check.bat", "update.bat"}) install.put(bat, Files.readAllBytes(bats.resolve(bat)));
        install.put("patches/fx_mod.json", b(Tests.patchJson(false)));
        install.put(Updater.SHIPPED, shipped(install.keySet()));
        for (Map.Entry<String, byte[]> e : install.entrySet()) { Path p = tool.resolve(e.getKey()); Files.createDirectories(p.getParent()); Files.write(p, e.getValue()); }
        Files.writeString(tool.resolve("customize.txt"), "# mine\r\n");
        Files.writeString(tool.resolve("game_dir.txt"), root.toString());
        Map<String, String> mods0 = tree(root.resolve("mods"));
        String[] engineArgs = {"--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString(), "--tool-dir", tool.toString(), "--dev"};
        check(runMain("apply", engineArgs).contains("Applied: Fuel halved"), "the old version patched fx_mod");

        Map<String, byte[]> rel = new TreeMap<>(install);
        rel.remove(Updater.SHIPPED);
        rel.remove("patches/fx_mod.json");
        rel.put("patches/other_mod.json", b(Tests.patchJson(false).replace("fx_mod", "other_mod").replace("Fx Mod", "Other Mod")));
        rel.put("update.bat", (new String(install.get("update.bat"), StandardCharsets.UTF_8) + "rem the new release\r\n").getBytes(StandardCharsets.UTF_8));
        rel.put("README.txt", b("new readme"));
        String text;
        int rc;
        try (Server s = new Server()) {
            s.serve("9.9.9", zip(rel));
            ProcessBuilder pb;
            if (viaCaller) {
                // a script that calls update.bat goes on with its next line after the install
                Path caller = root.resolve("caller.bat");
                Files.writeString(caller, "@echo off\r\ncall \"" + tool.resolve("update.bat") + "\" %*\r\necho CALLER GOES ON\r\n");
                pb = new ProcessBuilder("cmd.exe", "/c", caller.toString(), "--update-url", s.base(), "--dev");
            } else {
                // as Explorer starts a double-clicked bat: cmd /c ""<path>" <args>", in the bat's folder
                pb = new ProcessBuilder("cmd.exe", "/c", "\"\"" + tool.resolve("update.bat") + "\" --update-url " + s.base() + " --dev\"");
            }
            pb.directory(tool.toFile());
            pb.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin") + File.pathSeparator + System.getenv("PATH"));
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] o = p.getInputStream().readAllBytes();
            check(p.waitFor(120, TimeUnit.SECONDS), "update.bat finished");
            rc = p.exitValue();
            text = new String(o, StandardCharsets.UTF_8);
        }
        check(rc == 0, at + "update.bat exit " + rc + ": " + text);
        check(text.contains("Copying the new files into \"") && text.contains(folder + "\\Starsector\\SurvivalRebalance\""), at + "the installer names the folder whole: " + text);
        check(text.contains("Downloaded and checked version 9.9.9") && text.contains("to 9.9.9. Removed 1 file "), at + "update.bat output: " + text);
        check(text.contains("this version has no changes for this mod"), at + "apply ran with the new files and put fx_mod back: " + text);
        check(!text.contains("is not recognized") && !text.contains("cannot find") && !text.contains("syntax of the command"), at + "no line of the bats broke apart: " + text);
        check(Tests.read(tool.resolve("update.bat")).contains("rem the new release"), at + "update.bat replaced itself");
        check(Tests.read(tool.resolve("README.txt")).equals("new readme") && Files.exists(tool.resolve("patches/other_mod.json")), at + "new files installed");
        check(!Files.exists(tool.resolve("patches/fx_mod.json")), at + "the dropped patch file removed");
        check(Tests.read(tool.resolve("customize.txt")).equals("# mine\r\n") && Files.exists(tool.resolve("game_dir.txt")), at + "user files kept");
        check(!Files.exists(tool.resolve("update/staged")) && !Files.exists(tool.resolve("update/update.json")), at + "update folder cleared");
        if (viaCaller) check(text.contains("CALLER GOES ON"), at + "the calling script went on: " + text);
        // the installer does not delete itself (a later start of the tool removes it); give a stray process time to act
        Thread.sleep(3000);
        check(Updater.isHelper(Files.readAllBytes(tool.resolve("update/install_update.bat"))), at + "the installer is left for the tool to remove later");
        check(tree(root.resolve("mods")).equals(mods0), at + "fx_mod byte-identical to its original");
        check(Tests.read(root.resolve("saves/common/LunaSettings/fx_mod.json.data")).contains("\"fx_mult\": 0.75"), at + "saved setting put back");
    }
}
