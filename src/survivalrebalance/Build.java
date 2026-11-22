package survivalrebalance;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Build steps: fingerprint the class ops, seal the anchors, list what ships, and the real round trip. */
final class Build {
    private Build() {}

    static final int MAX_TOKEN = 40;

    /** The public-release text rule: a token with a space holds at most this many words and characters. */
    static final int MAX_WORDS = 3, MAX_SPACED = 20;
    static final String PROSE_RULE = "a token with a space may hold at most " + MAX_WORDS + " words of 2+ letters and " + MAX_SPACED + " characters";

    static boolean isProse(String t) {
        if (t.trim().indexOf(' ') < 0) return false;
        int words = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z]{2,}").matcher(t);
        while (m.find()) words++;
        return words > MAX_WORDS || t.length() > MAX_SPACED;
    }

    /** The shortest title and why the window may show; anything shorter is a placeholder. */
    static final int MIN_TITLE = 6, MIN_WHY = 15;
    static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("(?i)\\s*(x+|todo|tbd|fixme|placeholder|why|title|[?.\\-_]+)?\\s*");

    /**
     * What is wrong with a change's player-facing text field, or null. An empty field or a placeholder
     * word in any field is refused. With required set (the shipping patch set), title and why must
     * also be there and be real text, MIN_TITLE and MIN_WHY characters or more.
     */
    static String textProblem(String field, Object v, boolean required) {
        boolean sentence = field.equals("title") || field.equals("why");
        if (v == null) return required && sentence ? field + " is missing" : null;
        String t = String.valueOf(v);
        if (PLACEHOLDER.matcher(t).matches()) return field + " is empty or a placeholder: \"" + t + "\"";
        int min = field.equals("why") ? MIN_WHY : MIN_TITLE;
        if (sentence && required && t.trim().length() < min) return field + " is under " + min + " characters: \"" + t + "\"";
        return null;
    }

    static List<Path> jsonFiles(Path dir) throws Failure {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir)) throw new Failure("folder not found: " + dir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) files.add(p);
        } catch (IOException e) { throw new Failure("cannot list " + dir); }
        files.sort(null);
        return files;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadJava(Path f) throws Failure {
        try {
            Object o = Json.toJava(Json.parse(Text.decode(Files.readAllBytes(f)).body));
            return (Map<String, Object>) o;
        } catch (IOException | Json.ParseException | ClassCastException e) {
            throw new Failure(f.getFileName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> ops(Map<String, Object> doc) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object ch = doc.get("changes");
        if (!(ch instanceof List)) return out;
        for (Object c : (List<Object>) ch) {
            Object ops = ((Map<String, Object>) c).get("ops");
            if (ops instanceof List) for (Object op : (List<Object>) ops) out.add((Map<String, Object>) op);
        }
        return out;
    }

    /** The changes of a patch document, each a map. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> changes(Map<String, Object> doc) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object ch = doc.get("changes");
        if (ch instanceof List) for (Object c : (List<Object>) ch) out.add((Map<String, Object>) c);
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> opsOf(Map<String, Object> change) {
        Object ops = change.get("ops");
        return ops instanceof List ? (List<Map<String, Object>>) (List<?>) ops : List.of();
    }

    static boolean classOp(Json.Obj op) {
        String k = op.str("op");
        return "class_const".equals(k) || "class_string".equals(k);
    }

    /**
     * fingerprint --patches <authoring dir> --mods <dir>[;<dir>...] --out <dir>: for each tested
     * version of each patch, find that version of the mod in the mods folders, check every op as it
     * reads there (as verify does), and fingerprint each class op's method. The op keeps method_fp
     * for the first tested version it exists at; a later version whose method differs gets its own
     * in at[version].method_fp.
     */
    @SuppressWarnings("unchecked")
    static int fingerprint(Main.Opts o, PrintStream out) throws Exception {
        Path in = Main.path(req(o, "--patches")), dst = Main.path(req(o, "--out"));
        List<Path> folders = new ArrayList<>();
        for (String d : req(o, "--mods").split(";")) if (!d.isBlank()) folders.add(Main.path(d.trim()));
        Files.createDirectories(dst);
        List<ModInfo> all = new ArrayList<>();
        for (Path d : folders) all.addAll(ModInfo.scan(d));
        int bad = 0;
        for (Path f : jsonFiles(in)) {
            Patch p = Patch.load(f);
            List<ModInfo> found = ModInfo.byId(all, p.modId);
            if (p.tested.isEmpty()) {
                out.println("FAIL  " + f.getFileName() + ": no tested_versions, so no folder of " + p.modId + " can be checked");
                bad++;
                continue;
            }
            boolean ok = true;
            // per change, per op place: the fingerprint at each tested version the op exists at, in list order
            Map<String, Map<Integer, Map<String, String>>> fps = new LinkedHashMap<>();
            int n = 0;
            for (String v : p.tested) {
                ModInfo m = null;
                for (ModInfo x : found) if (x.version.equals(v)) m = x;
                if (m == null) {
                    out.println("FAIL  " + f.getFileName() + ": " + p.modId + " " + v + " is a tested version, but no folder in " + folders + " has it");
                    ok = false;
                    continue;
                }
                Patch pv = p.at(v);
                Workspace ws = new Workspace(m.dir);
                for (Patch.Change c : pv.changes) for (Json.Obj op : c.ops) {
                    if (op == null) continue;
                    ws.owner = "\"" + c.title + "\" (" + c.id + ")";
                    try { Ops.run(op, ws, true); } catch (Failure e) { out.println("FAIL  " + f.getFileName() + " " + v + " " + c.id + ": " + e.getMessage()); ok = false; }
                }
                try { ws.results(); } catch (Failure e) { out.println("FAIL  " + f.getFileName() + " " + v + ": " + e.getMessage()); ok = false; }
                for (Patch.Change c : pv.changes) for (Json.Obj op : c.ops) {
                    if (op == null || !"line_sub".equals(op.str("op")) || !CsvWidth.isCsv(op.str("file")) || !(op.get("subs") instanceof Json.Arr)) continue;
                    for (Json.Node pair : ((Json.Arr) op.get("subs")).items) {
                        if (!(pair instanceof Json.Arr) || ((Json.Arr) pair).items.size() < 2) continue;
                        String find = Json.display(((Json.Arr) pair).items.get(0)), repl = Json.display(((Json.Arr) pair).items.get(1));
                        String why = CsvWidth.unquotedRawSplit("line_sub", op.str("file"), ws.text(op.str("file")).body, op.str("line"), find, repl);
                        if (why != null) { out.println("FAIL  " + f.getFileName() + " " + v + " " + c.id + ": " + why); ok = false; }
                    }
                }
                if (!ok) continue;
                Workspace fresh = new Workspace(m.dir);
                for (Patch.Change c : pv.changes) for (int i = 0; i < c.ops.size(); i++) {
                    Json.Obj op = c.ops.get(i);
                    if (op == null || !classOp(op)) continue;
                    String cls = op.str("class").replace('.', '/');
                    String fp = ClassOps.fingerprintOf(fresh.originalClass(op.str("jar"), cls), op.str("method"), op.str("desc"));
                    String had = op.str("method_fp");
                    if (had != null && !had.equals(fp)) { out.println("FAIL  " + f.getFileName() + ": method_fp of " + cls + "." + op.str("method") + " is " + had + " but version " + v + " gives " + fp); ok = false; }
                    fps.computeIfAbsent(c.id, k -> new LinkedHashMap<>()).computeIfAbsent(i, k -> new LinkedHashMap<>()).put(v, fp);
                    n++;
                }
            }
            if (!ok) { bad++; continue; }
            Map<String, Object> doc = loadJava(f);
            for (Map<String, Object> c : changes(doc)) {
                Map<Integer, Map<String, String>> byOp = fps.get(String.valueOf(c.get("id")));
                if (byOp == null) continue;
                List<Map<String, Object>> ops = opsOf(c);
                for (Map.Entry<Integer, Map<String, String>> e : byOp.entrySet()) {
                    Map<String, Object> op = ops.get(e.getKey());
                    String base = null;
                    for (Map.Entry<String, String> vf : e.getValue().entrySet()) {
                        if (base == null) { base = vf.getValue(); op.put("method_fp", base); continue; }
                        if (vf.getValue().equals(base)) continue;
                        Object at = op.get("at");
                        if (!(at instanceof Map)) { at = new LinkedHashMap<String, Object>(); op.put("at", at); }
                        Object atv = ((Map<String, Object>) at).get(vf.getKey());
                        if (!(atv instanceof Map)) { atv = new LinkedHashMap<String, Object>(); ((Map<String, Object>) at).put(vf.getKey(), atv); }
                        ((Map<String, Object>) atv).put("method_fp", vf.getValue());
                    }
                }
            }
            Files2.atomicWrite(dst.resolve(f.getFileName()), Json.write(doc).getBytes(StandardCharsets.UTF_8));
            out.println("ok    " + f.getFileName() + " (" + p.modId + " " + String.join(", ", p.tested) + ", " + n + " class ops fingerprinted)");
        }
        return bad == 0 ? 0 : 1;
    }

    static String req(Main.Opts o, String k) throws Failure {
        String v = o.get(k);
        if (v == null) throw new Failure("needs " + k);
        return v;
    }

    static boolean numeric(Object v) {
        return v instanceof Json.RawNum || (v instanceof String && Json.number(((String) v).trim()) != null);
    }

    /** Replace one string anchor field by its hash. */
    static void hashField(Map<String, Object> op, String field, boolean keepNumeric, boolean trim) {
        Object v = op.get(field);
        if (v == null) return;
        if (!(v instanceof String)) return;          // numbers and booleans stay literal
        if (keepNumeric && numeric(v)) return;
        String s = (String) v;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : op.entrySet()) {
            if (e.getKey().equals(field)) copy.put(field + "_sha256", Text.anchorHash(trim ? s.trim() : s));
            else copy.put(e.getKey(), e.getValue());
        }
        op.clear();
        op.putAll(copy);
    }

    /**
     * seal --patches <fingerprinted dir> --out <dir> [--lint <file>] [--release]. --release (the
     * shipping patch set) also requires every change to have a title and a why.
     */
    @SuppressWarnings("unchecked")
    static int seal(Main.Opts o, PrintStream out) throws Exception {
        Path in = Main.path(req(o, "--patches")), dst = Main.path(req(o, "--out"));
        Files.createDirectories(dst);
        StringBuilder lint = new StringBuilder();
        lint.append("Every literal string that ships in the patch files, one per line.\n");
        lint.append("Hashes (expect_sha256, line_sha256, method_fp) are left out: they carry no text.\n");
        int bad = 0;
        for (Path f : jsonFiles(in)) {
            Map<String, Object> doc = loadJava(f);
            List<String> errs = new ArrayList<>();
            boolean release = o.has("--release");
            if (doc.get("changes") instanceof List) for (Object co : (List<Object>) doc.get("changes")) {
                if (!(co instanceof Map)) continue;
                Map<String, Object> c = (Map<String, Object>) co;
                for (String field : new String[]{"title", "why", "before", "after"}) {
                    String why = textProblem(field, c.get(field), release);
                    if (why != null) errs.add("change " + c.get("id") + ": " + why);
                }
            }
            for (Map<String, Object> c : changes(doc)) {
              List<Map<String, Object>> cops = opsOf(c);
              for (int n = 0; n < cops.size(); n++) {
                Map<String, Object> op = cops.get(n);
                String k = String.valueOf(op.get("op"));
                sealOne(op, k, errs);
                boolean cls = k.equals("class_const") || k.equals("class_string");
                // at[version]: its anchors are hashed and its tokens checked as the op's own, by the kind the op has there
                if (op.get("at") instanceof Map) for (Map.Entry<String, Object> ae : ((Map<String, Object>) op.get("at")).entrySet()) {
                    if (!(ae.getValue() instanceof Map)) continue;
                    Map<String, Object> ov = (Map<String, Object>) ae.getValue();
                    String kv = ov.get("op") == null ? k : String.valueOf(ov.get("op"));
                    sealOne(ov, kv, errs);
                    cls |= kv.equals("class_const") || kv.equals("class_string");
                    // what the kind there does not hash would ship as another mod's text
                    String where = "change " + c.get("id") + " op #" + n + " (" + k + "), at " + ae.getKey() + ": ";
                    if (ov.get("line") instanceof String) errs.add(where + "a literal line is left (an anchor must be hashed)");
                    for (String f2 : new String[]{"expect", "expect_default"})
                        if (ov.get(f2) instanceof String && !numeric(ov.get(f2))) errs.add(where + "a literal " + f2 + " is left (an anchor must be hashed)");
                    if (ov.containsKey("at")) errs.add(where + "has an at inside an at entry");
                }
                if (cls && op.get("method_fp") == null)
                    errs.add(k + " " + op.get("class") + "." + op.get("method") + " has no method_fp (run fingerprint first)");
              }
            }
            if (!errs.isEmpty()) {
                for (String e : errs) out.println("FAIL  " + f.getFileName() + ": " + e);
                bad++;
                continue;
            }
            Files2.atomicWrite(dst.resolve(f.getFileName()), Json.write(doc).getBytes(StandardCharsets.UTF_8));
            lint.append("\n== ").append(f.getFileName()).append('\n');
            lintStrings(doc, "", lint);
            out.println("sealed " + f.getFileName());
        }
        if (o.get("--lint") != null) Files2.atomicWrite(Main.path(o.get("--lint")), lint.toString().getBytes(StandardCharsets.UTF_8));
        return bad == 0 ? 0 : 1;
    }

    /** Hash the string anchors of an op (or of one at[version] entry, read as kind k) and check its tokens. */
    @SuppressWarnings("unchecked")
    static void sealOne(Map<String, Object> op, String k, List<String> errs) {
        switch (k) {
            case "csv_cell": hashField(op, "expect", true, false); break;
            case "json_value": hashField(op, "expect", false, false); break;
            case "luna": hashField(op, "expect_default", true, false); break;
            case "csv_cell_sub": case "class_string": case "csv_row_off": hashField(op, "expect", false, false); break;
            case "line_sub": hashField(op, "line", false, true); break;
            case "class_const": break;
            default: errs.add("unknown op " + k);
        }
        Object subs = op.get("subs");
        if (subs instanceof List) for (Object pair : (List<Object>) subs) {
            if (!(pair instanceof List)) continue;
            List<Object> pl = (List<Object>) pair;
            for (int i = 0; i < Math.min(2, pl.size()); i++) {
                String t = String.valueOf(pl.get(i));
                if (t.length() > MAX_TOKEN) errs.add("sub token over " + MAX_TOKEN + " characters: " + Text.clip(t));
                if (isProse(t)) errs.add("sub token reads as prose (" + PROSE_RULE + "): " + Text.clip(t));
            }
        }
        Object set = op.get("set");
        if (set instanceof String && ((String) set).length() > MAX_TOKEN) errs.add("set token over " + MAX_TOKEN + " characters: " + Text.clip((String) set));
    }

    @SuppressWarnings("unchecked")
    static void lintStrings(Object n, String path, StringBuilder b) {
        if (n instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) n).entrySet()) {
                if (e.getKey().endsWith("_sha256") || e.getKey().equals("method_fp")) continue;
                lintStrings(e.getValue(), path + "." + e.getKey(), b);
            }
        } else if (n instanceof List) {
            List<Object> l = (List<Object>) n;
            for (int i = 0; i < l.size(); i++) lintStrings(l.get(i), path + "[" + i + "]", b);
        } else if (n instanceof String) {
            b.append(path).append(" = ").append(Json.quote((String) n)).append('\n');
        }
    }

    // ---------------------------------------------------------------- real round trip

    /**
     * roundtrip --patches <dir> --mods <source mods> --saves <source saves> --out <work dir> [--customize <file>] [--dev]:
     * copy the mods the patches name into the work dir, hash every file, apply, undo, hash again.
     */
    static int roundtrip(Main.Opts o, PrintStream out) throws Exception {
        Path pdir = Main.path(req(o, "--patches")), src = Main.path(req(o, "--mods")), work = Main.path(req(o, "--out"));
        Path srcSaves = o.get("--saves") == null ? null : Main.path(o.get("--saves"));
        Files2.deleteTree(work);
        Path mods = work.resolve("mods"), saves = work.resolve("saves").resolve("common");
        Files.createDirectories(mods);
        Files.createDirectories(saves.resolve("LunaSettings"));
        List<ModInfo> all = ModInfo.scan(src);
        List<String> ids = new ArrayList<>();
        int copied = 0;
        for (Path f : jsonFiles(pdir)) {
            Patch p = Patch.load(f);
            ids.add(p.modId);
            // only a folder at a tested version: a folder of other versions holds just some of the mods
            for (ModInfo m : ModInfo.byId(all, p.modId)) if (p.tested.contains(m.version)) { copyTree(m.dir, mods.resolve(m.dir.getFileName().toString())); copied++; }
            if (srcSaves != null) {
                Path lf = Saves.lunaFile(srcSaves, p.modId);
                if (Files.isRegularFile(lf)) Files.copy(lf, saves.resolve("LunaSettings").resolve(lf.getFileName().toString()));
            }
        }
        if (o.get("--customize") != null) {
            Files.createDirectories(work.resolve("tool"));
            Files.copy(Main.path(o.get("--customize")), work.resolve("tool").resolve(Customize.FILE));
            out.println("round trip with customize.txt from " + Main.path(o.get("--customize")));
        }
        if (copied == 0) { out.println("round trip: no mod in " + src + " is at a version its patch was tested with, so there is nothing to apply"); return 0; }
        Map<String, String> before = treeHashes(work);
        out.println("round trip: " + ids.size() + " patch files, " + before.size() + " files copied into " + work);
        List<String> a = new ArrayList<>(List.of("apply", "--mods", mods.toString(), "--saves", saves.toString(), "--patches", pdir.toString(), "--tool-dir", work.resolve("tool").toString()));
        if (o.has("--dev")) a.add("--dev");
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf, true, StandardCharsets.UTF_8);
        int rc = Main.run(a.toArray(new String[0]), cap);
        Map<String, String> applied = treeHashes(work);
        int changed = 0;
        for (Map.Entry<String, String> e : before.entrySet()) if (!e.getValue().equals(applied.get(e.getKey()))) changed++;
        if (rc != 0) { out.print(buf.toString(StandardCharsets.UTF_8)); out.println("FAIL  apply exited " + rc); return 1; }
        a.set(0, "undo");
        buf.reset();
        rc = Main.run(a.toArray(new String[0]), cap);
        if (rc != 0) { out.print(buf.toString(StandardCharsets.UTF_8)); out.println("FAIL  undo exited " + rc); return 1; }
        Files2.deleteTree(work.resolve("tool"));
        Map<String, String> after = treeHashes(work);
        List<String> diff = new ArrayList<>();
        for (String k : before.keySet()) if (!before.get(k).equals(after.get(k))) diff.add(k);
        for (String k : after.keySet()) if (!before.containsKey(k)) diff.add(k + " (left behind)");
        if (changed == 0) { out.println("FAIL  apply changed no file"); return 1; }
        if (!diff.isEmpty()) { out.println("FAIL  " + diff.size() + " files differ after undo: " + diff.subList(0, Math.min(10, diff.size()))); return 1; }
        out.println("PASS  apply changed " + changed + " files; after undo all " + before.size() + " files are byte-identical and nothing was left behind");
        return 0;
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    static Map<String, String> treeHashes(Path root) throws Exception {
        Map<String, String> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("tool")) continue;
                if (Files.isDirectory(p)) m.put(rel + "/", "dir");
                else m.put(rel, Files2.sha(p));
            }
        }
        return m;
    }
}
