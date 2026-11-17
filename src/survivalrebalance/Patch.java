package survivalrebalance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One patch file: the changes for one mod. */
final class Patch {
    static final class Change {
        String id, title, why, before, after;
        final List<Json.Obj> ops = new ArrayList<>();
        final List<String> requires = new ArrayList<>();
    }

    Path file;
    byte[] raw;
    Json.Obj root;
    String modId, modName;
    final List<String> tested = new ArrayList<>();
    final List<Change> changes = new ArrayList<>();

    static Patch load(Path f) throws Failure {
        byte[] raw;
        try { raw = Files.readAllBytes(f); } catch (IOException e) { throw new Failure("cannot read " + f + ": " + e.getMessage()); }
        return load(f, raw);
    }

    /** A patch from its bytes; f names it (the jar's own patch files have no file of their own). */
    static Patch load(Path f, byte[] raw) throws Failure {
        Json.Node n;
        try { n = Json.parse(Text.decode(raw).body); } catch (Json.ParseException e) { throw new Failure(f.getFileName() + " is not valid JSON: " + e.getMessage()); }
        if (!(n instanceof Json.Obj)) throw new Failure(f.getFileName() + ": top level must be an object");
        Patch p = new Patch();
        p.file = f;
        p.raw = raw;
        p.root = (Json.Obj) n;
        String fmt = p.root.str("format");
        if (!"1".equals(fmt)) throw new Failure(f.getFileName() + ": \"format\" must be 1");
        p.modId = p.root.str("mod_id");
        if (p.modId == null || p.modId.isEmpty()) throw new Failure(f.getFileName() + ": missing \"mod_id\"");
        p.modName = p.root.str("mod_name");
        if (p.modName == null) p.modName = p.modId;
        Json.Arr tv = p.root.arr("tested_versions");
        if (tv != null) for (Json.Node v : tv.items) p.tested.add(Json.display(v));
        Json.Arr ch = p.root.arr("changes");
        if (ch == null) throw new Failure(f.getFileName() + ": missing \"changes\"");
        List<String> ids = new ArrayList<>();
        for (Json.Node cn : ch.items) {
            if (!(cn instanceof Json.Obj)) throw new Failure(f.getFileName() + ": each change must be an object");
            Json.Obj co = (Json.Obj) cn;
            Change c = new Change();
            c.id = co.str("id");
            if (c.id == null) throw new Failure(f.getFileName() + ": a change has no \"id\"");
            if (ids.contains(c.id)) throw new Failure(f.getFileName() + ": change id " + c.id + " is used twice");
            ids.add(c.id);
            c.title = co.str("title") == null ? c.id : co.str("title");
            c.why = co.str("why");
            c.before = co.str("before");
            c.after = co.str("after");
            Json.Arr req = co.arr("requires");
            if (req != null) for (Json.Node r : req.items) c.requires.add(Json.display(r));
            Json.Arr ops = co.arr("ops");
            if (ops == null || ops.items.isEmpty()) throw new Failure(f.getFileName() + ": change " + c.id + " has no ops");
            for (Json.Node on : ops.items) {
                if (!(on instanceof Json.Obj)) throw new Failure(f.getFileName() + ": change " + c.id + ": each op must be an object");
                c.ops.add((Json.Obj) on);
            }
            p.changes.add(c);
        }
        return p;
    }

    static String hashOf(byte[] raw, List<String> disabled) {
        StringBuilder b = new StringBuilder(Text.sha256(raw));
        for (String d : disabled) b.append('\n').append(d);
        return Text.sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }
}
