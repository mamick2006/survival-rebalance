package survivalrebalance;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    static final String VERSION = "1.4.0";

    /** The version as the player sees it: with the build's note when this is not the release build. */
    static String versionText() {
        String n = VersionPolicy.buildNote();
        return n == null ? VERSION : VERSION + " (" + n + ")";
    }

    static final class Opts {
        final Map<String, String> v = new LinkedHashMap<>();
        final List<String> flags = new ArrayList<>();
        String get(String k) { return v.get(k); }
        boolean has(String f) { return flags.contains(f); }
    }

    static final List<String> VALUED = List.of("--game", "--mods", "--saves", "--patches", "--only", "--skip",
            "--patch", "--jar", "--class", "--method", "--out", "--tool-dir", "--lint", "--changes", "--game-version", "--index", "--checked", "--customize", "--update-url", "--notes", "--manifest", "--readme", "--stage");

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (Failure f) {
            System.out.println("ERROR: " + f.getMessage());
            code = 2;
        } catch (Exception e) {
            System.out.println("ERROR: " + e);
            e.printStackTrace(System.out);
            code = 3;
        }
        System.exit(code);
    }

    static int run(String[] args, PrintStream out) throws Exception {
        if (args.length == 0) { usage(out); return 2; }
        String cmd = args[0];
        Opts o = new Opts();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (VALUED.contains(a)) {
                if (i + 1 >= args.length) throw new Failure(a + " needs a value");
                o.v.put(a, args[++i]);
            } else if (a.startsWith("--")) o.flags.add(a);
            else throw new Failure("unexpected argument: " + a);
        }
        Files2.sawBusy = false;
        return dispatch(cmd, o, out);
    }

    static int dispatch(String cmd, Opts o, PrintStream out) throws Exception {
        switch (cmd) {
            case "verify": return Verify.run(o, out);
            case "dump-class": return dumpClass(o, out);
            case "apply": return new Engine(o, out).apply();
            case "undo": return new Engine(o, out).undo();
            case "status": return o.has("--json") ? StatusJson.run(o, out) : new Engine(o, out).status();
            case "check":
                try { return Engine.check(o, out); } catch (Failure f) { out.println("ERROR: " + f.getMessage()); return 1; }
            case "customize": newerFirst(o, out); return Engine.customize(o, out);
            case "fingerprint": return Build.fingerprint(o, out);
            case "seal": return Build.seal(o, out);
            case "manifest": return Manifests.run(o, out);
            case "roundtrip": return Build.roundtrip(o, out);
            case "update": if (!o.has("--finish")) newerFirst(o, out); return Updater.update(o, out);
            case "shiplist": return Updater.shipList(o, out);
            case "release-info": return Updater.releaseInfo(o, out);
            case "supported": return SupportedCmd.run(o, out);
            case "bundle": return Bundle.build(o, out);
            case "version": case "--version": out.println("Survival Rebalance " + versionText()); return 0;
            default: usage(out); return 2;
        }
    }

    static void usage(PrintStream out) {
        out.println("Survival Rebalance " + versionText());
        out.println("usage: apply | undo | status | check  [--game <dir>] [--mods <dir>] [--saves <dir>] [--patches <dir>]");
        out.println("                              [--only <id,id>] [--skip <id,id>] [--dry-run] [--force] [--dev] [--offline]");
        out.println("       update                 download the newest release from GitHub and stage it for update.bat");
        out.println("       update --finish        (run by update.bat) check the installed files and remove dropped ones");
        out.println("       supported              list the mods the tool supports");
        out.println("       status --json          everything the window shows, as JSON");
        out.println("       customize [--tool-dir <dir>] [--patches <dir>]");
        out.println("       verify --patch <file> [--mods <dir>] [--saves <dir>]");
        out.println("       dump-class --jar <jar> --class <name> [--method <name>]");
        out.println("       fingerprint --patches <dir> --mods <dir> --out <dir>");
        out.println("       seal --patches <dir> --out <dir> [--lint <file>]");
        out.println("       manifest --patches <dir> --game-version <v> --out <file> [--checked <file>] [--changes <changes.json>] [--index <file>]");
        out.println("       shiplist --out <stage dir>");
        out.println("       supported --manifest <file> --readme <template> --out <file>");
        out.println("       release-info --out <zip> --game-version <v> [--notes <text>]");
        out.println("       bundle --stage <release folder> --jar <its jar>");
    }

    static Path path(String s) { return Paths.get(s).toAbsolutePath().normalize(); }

    /** customize and update say first when a newer version's files sit beside this jar (apply, undo and status say it in their header). */
    static void newerFirst(Opts o, PrintStream out) {
        Path d = o.get("--tool-dir") != null ? path(o.get("--tool-dir")) : Engine.jarDir();
        if (d != null && Files.isDirectory(d.resolve("app"))) d = d.resolve("app");
        String v = d == null ? null : ToolFolder.newerFolder(d, VERSION);
        if (v != null) { out.println(ToolFolder.newerText(v, VERSION)); out.println(); }
    }

    static int dumpClass(Opts o, PrintStream out) throws Exception {
        String jar = o.get("--jar"), cls = o.get("--class");
        if (jar == null || cls == null) throw new Failure("dump-class needs --jar and --class");
        cls = cls.replace('.', '/');
        if (cls.endsWith("/class")) cls = cls.substring(0, cls.length() - 6);
        if (!Files.isRegularFile(path(jar))) throw new Failure("jar not found: " + path(jar));
        Zip z = new Zip(Files.readAllBytes(path(jar)));
        byte[] b = z.read(cls + ".class");
        if (b == null) {
            out.println("class " + cls + " not found in " + jar);
            String simple = cls.substring(cls.lastIndexOf('/') + 1);
            int shown = 0;
            for (String n : z.byName.keySet()) {
                if (n.endsWith(".class") && n.contains(simple) && shown++ < 20) out.println("  did you mean " + n.substring(0, n.length() - 6));
            }
            return 1;
        }
        out.print(ClassOps.dump(b, o.get("--method")));
        return 0;
    }
}
