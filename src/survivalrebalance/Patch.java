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
        /** In file order. In a patch read at one version (at()), an op that does not exist there is null, so #N stays its place in the file. */
        final List<Json.Obj> ops = new ArrayList<>();
        final List<String> requires = new ArrayList<>();
    }

    Path file;
    byte[] raw;
    Json.Obj root;
    String modId, modName;
    final List<String> tested = new ArrayList<>();
    final List<Change> changes = new ArrayList<>();
    /** The version this patch was read at by at(), or null for the file as written. */
    String version;

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
                Json.Obj op = (Json.Obj) on;
                Json.Node at = op.get("at");
                if (at != null) {
                    if (!(at instanceof Json.Obj)) throw new Failure(f.getFileName() + ": change " + c.id + ": \"at\" must be an object");
                    Json.Obj ao = (Json.Obj) at;
                    for (int i = 0; i < ao.keys.size(); i++) {
                        String v = ao.keys.get(i);
                        if (!p.tested.contains(v)) throw new Failure(f.getFileName() + ": change " + c.id + ": \"at\" names " + v + ", which is not in tested_versions " + p.tested);
                        Json.Node x = ao.values.get(i);
                        if (!(x instanceof Json.Obj) && !(x instanceof Json.Null)) throw new Failure(f.getFileName() + ": change " + c.id + ": \"at\" " + v + " must be an object or null");
                    }
                }
                c.ops.add(op);
            }
            p.changes.add(c);
        }
        return p;
    }

    /**
     * The patch as it reads at version v: each op with the fields of its at[v] laid over its own,
     * null where at[v] is null, and without its "at". A change with no op at v is left out.
     */
    Patch at(String v) {
        Patch r = new Patch();
        r.file = file; r.raw = raw; r.root = root; r.modId = modId; r.modName = modName; r.version = v;
        r.tested.addAll(tested);
        for (Change c : changes) {
            Change rc = new Change();
            rc.id = c.id; rc.title = c.title; rc.why = c.why; rc.before = c.before; rc.after = c.after;
            rc.requires.addAll(c.requires);
            boolean any = false;
            for (Json.Obj op : c.ops) {
                Json.Obj o = overlay(op, v);
                rc.ops.add(o);
                any |= o != null;
            }
            if (any) r.changes.add(rc);
        }
        return r;
    }

    /**
     * The ways to read this patch for a mod at version v, to try in order: at v when it is tested;
     * else at each tested version, the last listed first. A patch already read at a version is itself.
     */
    List<Patch> readings(String v) {
        if (version != null) return List.of(this);
        if (tested.contains(v) || tested.isEmpty()) return List.of(at(v));
        List<Patch> out = new ArrayList<>();
        for (int i = tested.size() - 1; i >= 0; i--) out.add(at(tested.get(i)));
        return out;
    }

    /**
     * One op at version v. A field given in at[v] replaces the op's own; a sealed field replaces its
     * literal form too and the other way round (expect_sha256 and expect are one anchor).
     */
    static Json.Obj overlay(Json.Obj op, String v) {
        Json.Obj at = op.obj("at");
        if (at == null && op.get("at") == null) return op;
        Json.Node over = at == null ? null : at.get(v);
        if (over instanceof Json.Null) return null;
        Json.Obj ov = over instanceof Json.Obj ? (Json.Obj) over : new Json.Obj();
        java.util.Set<String> drop = new java.util.HashSet<>(List.of("at"));
        for (String k : ov.keys) {
            drop.add(k);
            drop.add(k.endsWith("_sha256") ? k.substring(0, k.length() - "_sha256".length()) : k + "_sha256");
        }
        Json.Obj r = new Json.Obj();
        r.start = op.start; r.end = op.end;
        for (int i = 0; i < op.keys.size(); i++) {
            if (drop.contains(op.keys.get(i))) continue;
            r.keys.add(op.keys.get(i));
            r.values.add(op.values.get(i));
        }
        r.keys.addAll(ov.keys);
        r.values.addAll(ov.values);
        return r;
    }

    static String hashOf(byte[] raw, List<String> disabled) {
        StringBuilder b = new StringBuilder(Text.sha256(raw));
        for (String d : disabled) b.append('\n').append(d);
        return Text.sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }
}
