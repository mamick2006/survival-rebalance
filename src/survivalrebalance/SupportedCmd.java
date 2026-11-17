package survivalrebalance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * supported [--tool-dir <dir>]: print every mod the tool supports, from manifest.json.
 * supported --manifest <file> --readme <template> --out <file>: write the README with the list
 * in place of the line holding MARK (the build does this, so the list is never typed by hand).
 */
final class SupportedCmd {
    private SupportedCmd() {}

    static final String MARK = "@SUPPORTED_MODS@";

    static int run(Main.Opts o, PrintStream out) throws Exception {
        Checks.Manifest m;
        if (o.get("--manifest") != null) {
            Path f = Main.path(o.get("--manifest"));
            if (!Files.isRegularFile(f)) throw new Failure("manifest not found: " + f);
            m = Checks.manifest(f.getParent(), f.getFileName().toString());
        } else if (Bundle.current() != null) {
            m = Checks.manifest(Bundle.current().manifest());
        } else {
            Path app = o.get("--tool-dir") != null ? Main.path(o.get("--tool-dir")) : Engine.jarDir();
            m = Checks.manifest(app);
        }
        if (!m.present) throw new Failure("manifest.json is missing, so the list of supported mods is not known. Get a fresh copy of the tool.");
        String list = render(m.supported);
        if (o.get("--readme") != null) {
            Path tpl = Main.path(o.get("--readme")), dst = Main.path(Build.req(o, "--out"));
            String text;
            try { text = Text.lf(Text.decode(Files.readAllBytes(tpl)).body); } catch (IOException e) { throw new Failure("cannot read " + tpl); }
            if (!text.contains(MARK)) throw new Failure(tpl.getFileName() + " has no " + MARK + " line");
            String body = text.replaceAll("(?m)^.*" + MARK + ".*$\n?", java.util.regex.Matcher.quoteReplacement(list));
            Files2.atomicWrite(dst, body.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
            out.println("wrote " + dst.getFileName() + " with " + m.supported.size() + " supported mods");
            return 0;
        }
        out.println("Survival Rebalance " + Main.VERSION + " supports " + m.supported.size() + " mods"
                + (m.gameVersion == null ? "" : ", tested on Starsector " + m.gameVersion) + ":");
        out.println();
        out.print(list);
        out.println();
        out.println("Any other mod was never analysed, so the tool does not rebalance it.");
        return 0;
    }

    /** One line per mod: name, tested version (any version for a mod that needs no change), what the tool does to it. */
    static String render(List<Checks.Supported> l) {
        int w = 0, vw = 10;
        for (Checks.Supported s : l) { w = Math.max(w, s.name.length()); vw = Math.max(vw, versions(s).length()); }
        w = Math.min(w, 40);
        vw = Math.min(vw, 20);
        StringBuilder b = new StringBuilder();
        for (Checks.Supported s : l) {
            String name = s.name.length() > 40 ? s.name.substring(0, 39) + "." : s.name;
            b.append("   ").append(pad(name, w)).append("  ").append(pad(versions(s), vw)).append("  ")
                    .append(s.changes == 0 ? "checked, needs no change" : s.changes + (s.changes == 1 ? " change" : " changes")).append('\n');
        }
        return b.toString();
    }

    /** The tested versions, or "any version" for a mod that needs no change. */
    static String versions(Checks.Supported s) { return s.anyVersion ? "any version" : String.join(", ", s.tested); }

    static String pad(String s, int w) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < w) b.append(' ');
        return b.toString();
    }
}
