package survivalrebalance;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A mod folder and what its mod_info.json says. */
final class ModInfo {
    Path dir;
    String id, name, version;

    static String normalizeVersion(Json.Node v) {
        if (v == null) return "?";
        if (v instanceof Json.Obj) {
            Json.Obj o = (Json.Obj) v;
            StringBuilder b = new StringBuilder();
            for (String k : new String[]{"major", "minor", "patch"}) {
                String s = o.str(k);
                if (s == null) continue;
                if (b.length() > 0) b.append('.');
                b.append(s);
            }
            return b.length() == 0 ? "?" : b.toString();
        }
        return Json.display(v);
    }

    static ModInfo read(Path dir) {
        Path f = dir.resolve("mod_info.json");
        if (!Files.isRegularFile(f)) return null;
        try { return parse(dir, Files.readAllBytes(f)); } catch (IOException e) { return null; }
    }

    /** A mod_info.json's bytes, for the mod in dir (null for one that is not on disk). */
    static ModInfo parse(Path dir, byte[] bytes) {
        try {
            Json.Node n = Json.parse(Text.decode(bytes).body);
            if (!(n instanceof Json.Obj)) return null;
            Json.Obj o = (Json.Obj) n;
            ModInfo m = new ModInfo();
            m.dir = dir;
            m.id = o.str("id");
            if (m.id == null) return null;
            m.name = o.str("name") == null || o.str("name").trim().isEmpty() ? m.id : o.str("name").trim();
            m.version = normalizeVersion(o.get("version"));
            return m;
        } catch (Json.ParseException e) {
            return null;
        }
    }

    /** Every mod folder in the mods folder, in name order. Unreadable mod_info files are skipped. */
    static List<ModInfo> scan(Path mods) throws Failure {
        List<ModInfo> out = new ArrayList<>();
        if (!Files.isDirectory(mods)) throw new Failure("mods folder not found: " + mods);
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path p : ds) if (Files.isDirectory(p)) dirs.add(p);
        } catch (IOException e) { throw new Failure("cannot list " + mods + ": " + e.getMessage()); }
        dirs.sort(null);
        for (Path d : dirs) {
            ModInfo m = read(d);
            if (m != null) out.add(m);
        }
        return out;
    }

    static List<ModInfo> byId(List<ModInfo> all, String id) {
        List<ModInfo> out = new ArrayList<>();
        for (ModInfo m : all) if (m.id.equals(id)) out.add(m);
        return out;
    }
}
