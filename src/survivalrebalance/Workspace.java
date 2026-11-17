package survivalrebalance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every file a mod's ops touch, held in memory. Each op locates its target in the file or
 * class as it was before any op of the patch touched it, and records an edit; results() then
 * applies all edits at once. Two ops that claim the same target fail. Nothing is written to
 * disk here.
 */
final class Workspace {
    final Path modDir;
    final Map<String, byte[]> original = new LinkedHashMap<>();
    final Map<String, Text> texts = new LinkedHashMap<>();
    final Map<String, Zip> jars = new LinkedHashMap<>();
    /** Saved LunaLib settings the luna ops want: key -> new value text. */
    final Map<String, String> luna = new LinkedHashMap<>();
    /** Names the change whose ops are running, for conflict messages. */
    String owner = "?";

    static final class TEdit {
        final int start, end;
        final String repl, owner;
        TEdit(int s, int e, String r, String o) { start = s; end = e; repl = r; owner = o; }
    }

    static final class CEdit {
        final String type, owner;
        final Object value;
        CEdit(String t, Object v, String o) { type = t; value = v; owner = o; }
    }

    final Map<String, List<TEdit>> edits = new LinkedHashMap<>();
    /** jar -> class -> method name+desc -> constant ordinal -> edit */
    final Map<String, Map<String, Map<String, Map<Integer, CEdit>>>> classEdits = new LinkedHashMap<>();

    Workspace(Path modDir) { this.modDir = modDir; }

    static String checkRel(String rel) throws Failure {
        if (rel == null || rel.isEmpty()) throw new Failure("missing file path");
        String r = rel.replace('\\', '/');
        if (r.startsWith("/") || r.contains("../") || r.equals("..") || r.matches("^[A-Za-z]:.*")) throw new Failure("file path must stay inside the mod folder: " + rel);
        return r;
    }

    byte[] originalBytes(String rel) throws Failure {
        rel = checkRel(rel);
        byte[] b = original.get(rel);
        if (b != null) return b;
        Path p = modDir.resolve(rel);
        if (!Files.isRegularFile(p)) throw new Failure("file " + rel + " not found");
        try { b = Files.readAllBytes(p); } catch (IOException e) { throw new Failure("cannot read " + rel + " (" + HELD + "): " + e.getMessage()); }
        original.put(rel, b);
        return b;
    }

    /** The words for a file another program holds; Engine.failWarning looks for them. */
    static final String HELD = "another program may have it open, most likely Starsector";

    /** The file's original text. */
    Text text(String rel) throws Failure {
        rel = checkRel(rel);
        Text t = texts.get(rel);
        if (t == null) { t = Text.decode(originalBytes(rel)); texts.put(rel, t); }
        return t;
    }

    static boolean overlaps(TEdit o, int s, int e) {
        if (s == e && o.start == o.end) return s == o.start;
        if (s == e) return o.start <= s && s < o.end;
        if (o.start == o.end) return s <= o.start && o.start < e;
        return s < o.end && o.start < e;
    }

    /** Replace [start, end) of the original text. */
    void edit(String rel, int start, int end, String repl) throws Failure {
        rel = checkRel(rel);
        List<TEdit> l = edits.computeIfAbsent(rel, k -> new ArrayList<>());
        for (TEdit o : l)
            if (overlaps(o, start, end))
                throw new Failure("change " + o.owner + " already edits the same place in " + rel + " (merge the two into one op)");
        l.add(new TEdit(start, end, repl, owner));
    }

    Zip jar(String rel) throws Failure {
        rel = checkRel(rel);
        Zip z = jars.get(rel);
        if (z == null) { z = new Zip(originalBytes(rel)); jars.put(rel, z); }
        return z;
    }

    byte[] originalClass(String jarRel, String cls) throws Failure {
        Zip z = jar(jarRel);
        byte[] b = z.read(cls + ".class");
        if (b == null) throw new Failure("class " + cls + " not found in " + jarRel);
        return b;
    }

    /** Set the constant at this ordinal (in ClassOps.constants order of the original method). */
    void classEdit(String jarRel, String cls, String methodKey, int ordinal, String type, Object value) throws Failure {
        Map<Integer, CEdit> m = classEdits.computeIfAbsent(checkRel(jarRel), k -> new LinkedHashMap<>())
                .computeIfAbsent(cls, k -> new LinkedHashMap<>())
                .computeIfAbsent(methodKey, k -> new LinkedHashMap<>());
        CEdit o = m.get(ordinal);
        if (o != null) throw new Failure("change " + o.owner + " already edits constant " + ordinal + " of " + cls + "." + methodKey + " (merge the two into one op)");
        m.put(ordinal, new CEdit(type, value, owner));
    }

    /** Final new bytes of every file whose content changed. */
    Map<String, byte[]> results() throws Failure {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<TEdit>> e : edits.entrySet()) {
            Text t = texts.get(e.getKey());
            List<TEdit> l = new ArrayList<>(e.getValue());
            l.sort((a, b) -> Integer.compare(a.start, b.start));
            StringBuilder b = new StringBuilder();
            int cur = 0;
            for (TEdit x : l) { b.append(t.body, cur, x.start).append(x.repl); cur = x.end; }
            b.append(t.body.substring(cur));
            CsvWidth.check(e.getKey(), t.body, b.toString());
            byte[] nb = t.withBody(b.toString()).encode();
            if (!java.util.Arrays.equals(nb, original.get(e.getKey()))) out.put(e.getKey(), nb);
        }
        for (Map.Entry<String, Map<String, Map<String, Map<Integer, CEdit>>>> e : classEdits.entrySet()) {
            Map<String, byte[]> repl = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Map<Integer, CEdit>>> c : e.getValue().entrySet()) {
                byte[] orig = originalClass(e.getKey(), c.getKey());
                repl.put(c.getKey() + ".class", ClassOps.rewriteAll(orig, c.getValue()));
            }
            byte[] nb = jars.get(e.getKey()).rewrite(repl);
            Zip check = new Zip(nb);
            for (Map.Entry<String, byte[]> c : repl.entrySet())
                if (!java.util.Arrays.equals(check.read(c.getKey()), c.getValue())) throw new Failure("jar rewrite check failed for " + c.getKey());
            if (!java.util.Arrays.equals(nb, original.get(e.getKey()))) out.put(e.getKey(), nb);
        }
        return out;
    }
}
