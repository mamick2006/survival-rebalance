package survivalrebalance;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

/** Unit and engine tests on fixtures written here. Run: java -cp <classes> survivalrebalance.Tests */
public final class Tests {
    interface T { void run() throws Exception; }

    static int pass, fail;
    static final List<String> failures = new ArrayList<>();
    static Path tmp;
    static byte[] fixtureJar;

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("srtests");
        fixtureJar = buildJar();
        Map<String, T> tests = new LinkedHashMap<>();
        tests.put("loose json parse and spans", Tests::looseJson);
        tests.put("csv quoting and multi-line cells", Tests::csvParse);
        tests.put("csv_cell keeps CRLF, BOM and quoting", Tests::csvCellBytes);
        tests.put("csv anchor failures", Tests::csvFailures);
        tests.put("csv_cell_sub counts", Tests::csvSub);
        tests.put("csv_row_off on a multi-line row", Tests::rowOff);
        tests.put("json_value numbers by value, failures", Tests::jsonValue);
        tests.put("line_sub and Latin-1 bytes", Tests::lineSub);
        tests.put("class_const all encodings, which, count", Tests::classConst);
        tests.put("class_string plain and indy recipe", Tests::classString);
        tests.put("fingerprint ignores debug info, sees code", Tests::fingerprint);
        tests.put("jar rewrite identity", Tests::jarIdentity);
        tests.put("verify passes and fails", Tests::verify);
        tests.put("apply then undo is byte-identical", Tests::roundTrip);
        tests.put("apply twice", Tests::applyTwice);
        tests.put("apply after a patch change", Tests::patchChange);
        tests.put("crash recovery from applying", Tests::crashRecovery);
        tests.put("replaced folder", Tests::replacedFolder);
        tests.put("in-place update detection", Tests::inPlaceUpdate);
        tests.put("all or nothing per mod", Tests::allOrNothing);
        tests.put("two folders with one id", Tests::twoFolders);
        tests.put("another version is skipped", Tests::untested);
        tests.put("class op without method_fp needs --dev", Tests::needsFp);
        tests.put("sealed patch applies", Tests::sealed);
        tests.put("seal refuses long tokens", Tests::sealLong);
        tests.put("seal refuses mod prose in tokens", Tests::sealProse);
        tests.put("seal refuses placeholder player text", Tests::sealPlaceholder);
        tests.put("csv width guard rules", Tests::csvWidthUnit);
        tests.put("a csv width change fails apply and verify", Tests::csvWidthApply);
        tests.put("saved setting changed by the user", Tests::lunaUserChanged);
        tests.put("switched-off change and mod", Tests::switchedOff);
        tests.put("locked file fails the mod", Tests::locked);
        tests.put("dry run writes nothing", Tests::dryRun);
        tests.put("status reports", Tests::status);
        tests.put("requires across mods", Tests::requires);
        tests.put("which on class_string and line_sub", Tests::whichOps);
        tests.put("luna with csv false", Tests::lunaNoCsv);
        tests.put("ops resolve against the original", Tests::originalTargets);
        tests.put("two ops on one target fail", Tests::conflicts);
        CustomTests.register(tests);
        UpdateTests.register(tests);
        StrictTests.register(tests);
        GuiTests.register(tests);
        SwitchTests.register(tests);
        FolderTests.register(tests);
        for (Map.Entry<String, T> e : tests.entrySet()) {
            if (args.length > 0 && !e.getKey().contains(args[0])) continue; // a name part runs only those tests
            try {
                e.getValue().run();
                pass++;
            } catch (Throwable t) {
                fail++;
                failures.add(e.getKey() + ": " + t);
            }
        }
        for (String f : failures) System.out.println("FAIL  " + f);
        System.out.println((fail == 0 ? "tests OK: " : "tests FAILED: ") + pass + " passed, " + fail + " failed");
        try { Files2.deleteTree(tmp); } catch (Failure ignored) {}
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }

    static void fails(T t, String contains) throws Exception {
        try { t.run(); } catch (Failure f) {
            check(f.getMessage().contains(contains), "failure text \"" + f.getMessage() + "\" lacks \"" + contains + "\"");
            return;
        }
        throw new AssertionError("expected a failure containing " + contains);
    }

    // ------------------------------------------------------------------ fixtures

    static final String CLASS_SRC =
            "package fx;\n" +
            "public class Skill {\n" +
            "  public static float mult() { return 0.25f; }\n" +
            "  public static int count(int x) { if (x > 5) { return 3; } return x * 3 + 200; }\n" +
            "  public static long big() { return 1L; }\n" +
            "  public static double half() { return 0.5; }\n" +
            "  public static String tip(int n) { return \"Gives +25% speed to \" + n + \" ships\"; }\n" +
            "  public static String plain() { return \"Range 25%\"; }\n" +
            "  public static String two() { String a = \"+1\"; String b = \"+1\"; return a.trim() + \"|\" + b.trim(); }\n" +
            "  public static int other() { return 3; }\n" +
            "  public static int mix() { int a = 4; int b = 5; int c = 1; int d = 1; return a * 1000 + b * 100 + c * 10 + d; }\n" +
            "}\n";

    static byte[] compile(String src, boolean debug) throws Exception {
        Path d = Files.createTempDirectory(tmp, "javac");
        Path s = d.resolve("fx").resolve("Skill.java");
        Files.createDirectories(s.getParent());
        Files.writeString(s, src);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        int rc = jc.run(null, null, null, "--release", "17", debug ? "-g" : "-g:none", "-d", d.toString(), s.toString());
        check(rc == 0, "javac failed");
        return Files.readAllBytes(d.resolve("fx").resolve("Skill.class"));
    }

    static byte[] buildJar() throws Exception {
        byte[] cls = compile(CLASS_SRC, true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (JarOutputStream jo = new JarOutputStream(bo)) {
            jo.putNextEntry(new JarEntry("META-INF/"));
            jo.closeEntry();
            byte[] txt = "stored resource\n".getBytes(StandardCharsets.UTF_8);
            ZipEntry st = new ZipEntry("data/readme.txt");
            st.setMethod(ZipEntry.STORED);
            st.setSize(txt.length);
            CRC32 c = new CRC32(); c.update(txt);
            st.setCrc(c.getValue());
            st.setTime(1_500_000_000_000L);
            jo.putNextEntry(st); jo.write(txt); jo.closeEntry();
            ZipEntry ce = new ZipEntry("fx/Skill.class");
            ce.setTime(1_600_000_000_000L);
            jo.putNextEntry(ce); jo.write(cls); jo.closeEntry();
            ZipEntry big = new ZipEntry("data/big.txt");
            jo.putNextEntry(big); jo.write("lots of words ".repeat(500).getBytes(StandardCharsets.UTF_8)); jo.closeEntry();
        }
        return bo.toByteArray();
    }

    static final String SHIP_CSV = "﻿name,id,fuel,desc\r\n" +
            "#comment,fx_hull,1,x\r\n" +
            "Fx Hull,fx_hull,300,\"Fast hull, with\r\n25% more \"\"speed\"\"\"\r\n" +
            "Other,fx_other,300,plain\r\n";

    static final String RULES_ROW = "fx_rule,OpenDialog,,\"SetText\nmore\",Hello,\"0:opt:Pay 50000\"";
    static final String RULES_CSV = "id,trigger,conditions,script,text,options\n" +
            ",,,,,\n" + RULES_ROW + "\nfx_next,OpenDialog,,,Bye,\n";

    static final String SETTINGS = "# loose json\n{\n  block: {\n    mult: 0.50, # half\n    name: \"x\",\n  },\n  list: [1, 2, 3,],\n}\n";

    static final String LUNA_CSV = "fieldID,fieldName,fieldType,defaultValue\nfx_mult,Mult,Double,0.75\nfx_flag,Flag,Boolean,true\n";

    /** A game folder with one mod, its saved settings and a patch file. Returns the root. */
    static Path game(String version) throws Exception {
        return game(Files.createTempDirectory(tmp, "case"), version);
    }

    static Path game(Path root, String version) throws Exception {
        Path mod = root.resolve("mods").resolve("FxMod-" + version);
        writeMod(mod, version);
        Path luna = root.resolve("saves").resolve("common").resolve("LunaSettings");
        Files.createDirectories(luna);
        Files.writeString(luna.resolve("fx_mod.json.data"), "{\n   \"fx_flag\": true,\n   \"fx_mult\": 0.75\n}");
        Files.createDirectories(root.resolve("patches"));
        Files.writeString(root.resolve("patches").resolve("fx_mod.json"), patchJson(false));
        Files.createDirectories(root.resolve("tool"));
        return root;
    }

    static void writeMod(Path mod, String version) throws Exception {
        Files.createDirectories(mod.resolve("data/hulls"));
        Files.createDirectories(mod.resolve("data/campaign"));
        Files.createDirectories(mod.resolve("data/config"));
        Files.createDirectories(mod.resolve("data/strings"));
        Files.createDirectories(mod.resolve("jars"));
        String ver = version.equals("1.0") ? "{major:1, minor:0}" : "\"" + version + "\"";
        Files.writeString(mod.resolve("mod_info.json"), "{\n  # loose\n  \"id\": \"fx_mod\",\n  name: \"Fx Mod\",\n  \"version\": " + ver + ",\n}\n");
        Files.writeString(mod.resolve("data/hulls/ship_data.csv"), SHIP_CSV);
        Files.writeString(mod.resolve("data/campaign/rules.csv"), RULES_CSV);
        Files.writeString(mod.resolve("data/config/settings.json"), SETTINGS);
        Files.writeString(mod.resolve("data/config/LunaSettings.csv"), LUNA_CSV);
        Files.write(mod.resolve("data/strings/names.txt"), "Café cost 50\nkeep ÿ\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(mod.resolve("jars/fx.jar"), fixtureJar);
        Files.writeString(mod.resolve("untouched.txt"), "never edited\n");
    }

    static String patchJson(boolean bad) {
        String recipe = "Gives +25% speed to \\u0001 ships";
        return "{\n\"format\": 1, \"mod_id\": \"fx_mod\", \"mod_name\": \"Fx Mod\", \"tested_versions\": [\"1.0\"],\n\"changes\": [\n" +
                "{\"id\": \"c-csv\", \"title\": \"Fuel halved\", \"ops\": [\n" +
                "  {\"op\": \"csv_cell\", \"file\": \"data/hulls/ship_data.csv\", \"key\": {\"id\": \"fx_hull\"}, \"column\": \"fuel\", \"expect\": \"300\", \"set\": \"150\"},\n" +
                "  {\"op\": \"csv_cell_sub\", \"file\": \"data/hulls/ship_data.csv\", \"key\": {\"id\": \"fx_hull\"}, \"column\": \"desc\", \"expect\": \"Fast hull, with\\n25% more \\\"speed\\\"\", \"subs\": [[\"25%\", \"10%\"]]}]},\n" +
                "{\"id\": \"c-json\", \"title\": \"Mult lower\", \"ops\": [\n" +
                "  {\"op\": \"json_value\", \"file\": \"data/config/settings.json\", \"path\": [\"block\", \"mult\"], \"expect\": 0.5, \"set\": 0.20}]},\n" +
                "{\"id\": \"c-line\", \"title\": \"Cost lower\", \"ops\": [\n" +
                "  {\"op\": \"line_sub\", \"file\": \"data/strings/names.txt\", \"line\": \"Caf\\u00e9 cost 50\", \"subs\": [[\"50\", \"40\"]]}]},\n" +
                "{\"id\": \"c-row\", \"title\": \"Rule off\", \"ops\": [\n" +
                "  {\"op\": \"csv_row_off\", \"file\": \"data/campaign/rules.csv\", \"key\": {\"id\": \"fx_rule\"}, \"expect\": " + Json.quote(RULES_ROW) + "}]},\n" +
                "{\"id\": \"c-class\", \"title\": \"Code numbers\", \"ops\": [\n" +
                "  {\"op\": \"class_const\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"mult\", \"type\": \"float\", \"from\": 0.25, \"to\": 0.1, \"count\": 1},\n" +
                "  {\"op\": \"class_const\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"count\", \"type\": \"int\", \"from\": 3, \"to\": 300, \"count\": 2, \"which\": [0]},\n" +
                "  {\"op\": \"class_const\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"big\", \"type\": \"long\", \"from\": 1, \"to\": 70000, \"count\": 1},\n" +
                "  {\"op\": \"class_const\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"half\", \"type\": \"double\", \"from\": 0.5, \"to\": 0.25, \"count\": 1},\n" +
                "  {\"op\": \"class_string\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"tip\", \"expect\": \"" + recipe + "\", \"subs\": [[\"25%\", \"10%\"]]},\n" +
                "  {\"op\": \"class_string\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"plain\", \"expect\": \"Range 25%\", \"subs\": [[\"25%\", \"15%\"]]}]},\n" +
                "{\"id\": \"c-luna\", \"title\": \"Luna mult\", \"ops\": [\n" +
                "  {\"op\": \"luna\", \"key\": \"fx_mult\", \"expect_default\": \"0.75\", \"set\": \"0.5\"}]}" +
                (bad ? ",\n{\"id\": \"c-bad\", \"title\": \"Wrong\", \"ops\": [\n  {\"op\": \"csv_cell\", \"file\": \"data/hulls/ship_data.csv\", \"key\": {\"id\": \"fx_other\"}, \"column\": \"fuel\", \"expect\": \"999\", \"set\": \"1\"}]}" : "") +
                "\n]}\n";
    }

    static String[] args(Path root, String cmd, String... extra) {
        List<String> a = new ArrayList<>(List.of(cmd, "--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString(),
                "--patches", root.resolve("patches").toString(), "--tool-dir", root.resolve("tool").toString()));
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    static String lastOut;

    static String[] concat(String[] a, String... b) {
        String[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static int run(Path root, String cmd, String... extra) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int rc = Main.run(args(root, cmd, extra), new PrintStream(b, true, StandardCharsets.UTF_8));
        lastOut = b.toString(StandardCharsets.UTF_8);
        return rc;
    }

    static Map<String, String> hashes(Path root) throws Exception {
        Map<String, String> m = Build.treeHashes(root);
        m.keySet().removeIf(k -> k.startsWith("tool") || k.startsWith("patches"));
        return m;
    }

    static Path mod(Path root) throws Exception {
        try (var s = Files.list(root.resolve("mods"))) { return s.sorted().findFirst().get(); }
    }

    static String read(Path p) throws Exception { return Text.decode(Files.readAllBytes(p)).body; }

    static Object call(Path jar, String method, Object... a) throws Exception {
        try (URLClassLoader cl = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            Class<?> c = cl.loadClass("fx.Skill");
            for (Method m : c.getMethods()) if (m.getName().equals(method)) return m.invoke(null, a);
        }
        throw new AssertionError("no method " + method);
    }

    // ------------------------------------------------------------------ unit tests

    static void looseJson() throws Exception {
        Json.Node n = Json.parse(SETTINGS);
        Json.Node v = Json.resolve(n, List.of(new Json.Str("block", true), new Json.Str("mult", true)));
        check(SETTINGS.substring(v.start, v.end).equals("0.50"), "span of mult");
        check(Json.scalarEquals(Json.parse("0.5"), v), "0.5 equals 0.50");
        Json.Node l = Json.resolve(n, List.of(new Json.Str("list", true), new Json.Num("2", new java.math.BigDecimal(2))));
        check(Json.display(l).equals("3"), "array index");
        Json.Node odd = Json.parse("{a = 1; b: hello world, c: \"#not a comment\"}");
        check(Json.display(((Json.Obj) odd).get("b")).equals("hello world"), "unquoted value");
        check(Json.display(((Json.Obj) odd).get("c")).equals("#not a comment"), "hash inside string");
        fails(() -> { try { Json.resolve(Json.parse("{a:1, a:2}"), List.of(new Json.Str("a", true))); } catch (Json.ParseException e) { throw new Failure(e.getMessage()); } }, "appears 2 times");
    }

    static void csvParse() throws Exception {
        Csv c = new Csv(Text.decode(SHIP_CSV.getBytes(StandardCharsets.UTF_8)).body);
        check(c.rows.size() == 4, "4 rows, got " + c.rows.size());
        check(c.rows.get(1).comment(), "comment row");
        check(c.rows.get(2).cells.get(3).value.equals("Fast hull, with\r\n25% more \"speed\""), "multi-line cell value");
        Map<String, String> k = new LinkedHashMap<>(); k.put("id", "fx_hull");
        check(c.findRow(k) == 2, "key skips comment row");
        Csv r = new Csv(RULES_CSV);
        check(r.rows.size() == 4 && r.rowText(2).equals(RULES_ROW), "rules row text");
    }

    static Workspace ws(Path root) throws Exception { return new Workspace(mod(root)); }

    static Json.Obj op(String json) throws Exception { return (Json.Obj) Json.parse(json); }

    static void csvCellBytes() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"150\"}"), w, true);
        Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_other\"},\"column\":\"desc\",\"expect\":\"plain\",\"set\":\"a, b\"}"), w, true);
        byte[] nb = w.results().get("data/hulls/ship_data.csv");
        String want = SHIP_CSV.replace("Fx Hull,fx_hull,300,", "Fx Hull,fx_hull,150,").replace("fx_other,300,plain", "fx_other,300,\"a, b\"");
        check(new String(nb, StandardCharsets.UTF_8).equals(want), "only the two cells changed, BOM and CRLF kept");
        Workspace w2 = ws(root);
        Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"fuel\",\"expect\":300.0,\"set\":150}"), w2, true);
    }

    static void csvFailures() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"fuel\",\"expect\":\"301\",\"set\":\"1\"}"), w, true), "expected \"301\"");
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"fuel\":\"300\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"1\"}"), w, true), "2 rows match");
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"nope\",\"expect\":\"300\",\"set\":\"1\"}"), w, true), "not in the header");
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"gone\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"1\"}"), w, true), "no row with");
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/none.csv\",\"key\":{\"id\":\"gone\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"1\"}"), w, true), "not found");
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"../x.csv\",\"key\":{\"id\":\"gone\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"1\"}"), w, true), "inside the mod folder");
        check(w.results().isEmpty(), "failed ops changed nothing");
    }

    static void csvSub() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        String base = "{\"op\":\"csv_cell_sub\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"desc\",\"expect\":\"Fast hull, with\\n25% more \\\"speed\\\"\",\"subs\":";
        fails(() -> Ops.run(op(base + "[[\"e\",\"E\"]]}"), w, true), "occurs 3 times");
        Ops.run(op(base + "[[\"e\",\"E\",3],[\"25%\",\"10%\"]]}"), w, true);
        String s = new String(w.results().get("data/hulls/ship_data.csv"), StandardCharsets.UTF_8);
        check(s.contains("\"Fast hull, with\r\n10% morE \"\"spEEd\"\"\""), "sub kept quoting and CRLF: " + s);
    }

    static void rowOff() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        Ops.run(op("{\"op\":\"csv_row_off\",\"file\":\"data/campaign/rules.csv\",\"key\":{\"id\":\"fx_rule\"},\"expect\":" + Json.quote(RULES_ROW) + "}"), w, true);
        String s = new String(w.results().get("data/campaign/rules.csv"), StandardCharsets.UTF_8);
        check(s.equals(RULES_CSV.replace("\nfx_rule,", "\n#fx_rule,")), "row commented");
        Workspace w2 = ws(root);
        fails(() -> Ops.run(op("{\"op\":\"csv_row_off\",\"file\":\"data/campaign/rules.csv\",\"key\":{\"id\":\"fx_rule\"},\"expect\":\"fx_rule,OpenDialog\"}"), w2, true), "expected");
    }

    static void jsonValue() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        Ops.run(op("{\"op\":\"json_value\",\"file\":\"data/config/settings.json\",\"path\":[\"block\",\"mult\"],\"expect\":0.5,\"set\":0.20}"), w, true);
        Ops.run(op("{\"op\":\"json_value\",\"file\":\"data/config/settings.json\",\"path\":[\"list\",1],\"expect\":2,\"set\":5}"), w, true);
        Ops.run(op("{\"op\":\"json_value\",\"file\":\"data/config/settings.json\",\"path\":[\"block\",\"name\"],\"expect\":\"x\",\"set\":\"y\"}"), w, true);
        String s = new String(w.results().get("data/config/settings.json"), StandardCharsets.UTF_8);
        check(s.equals(SETTINGS.replace("0.50", "0.20").replace("[1, 2, 3,]", "[1, 5, 3,]").replace("\"x\"", "\"y\"")), "json edits: " + s);
        Workspace w2 = ws(root);
        fails(() -> Ops.run(op("{\"op\":\"json_value\",\"file\":\"data/config/settings.json\",\"path\":[\"block\",\"mult\"],\"expect\":0.6,\"set\":0.2}"), w2, true), "expected 0.6");
        fails(() -> Ops.run(op("{\"op\":\"json_value\",\"file\":\"data/config/settings.json\",\"path\":[\"block\",\"gone\"],\"expect\":0.6,\"set\":0.2}"), w2, true), "not found");
        fails(() -> Ops.run(op("{\"op\":\"json_value\",\"file\":\"data/config/settings.json\",\"path\":[\"block\"],\"expect\":0.6,\"set\":0.2}"), w2, true), "object or array");
    }

    static void lineSub() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        Ops.run(op("{\"op\":\"line_sub\",\"file\":\"data/strings/names.txt\",\"line\":\"  Caf\\u00e9 cost 50  \",\"subs\":[[\"50\",\"40\"]]}"), w, true);
        byte[] b = w.results().get("data/strings/names.txt");
        check(java.util.Arrays.equals(b, "Café cost 40\nkeep ÿ\n".getBytes(StandardCharsets.ISO_8859_1)), "Latin-1 bytes kept");
        Workspace w2 = ws(root);
        fails(() -> Ops.run(op("{\"op\":\"line_sub\",\"file\":\"data/strings/names.txt\",\"line\":\"nothing\",\"subs\":[[\"5\",\"4\"]]}"), w2, true), "occurs 0 times");
        fails(() -> Ops.run(op("{\"op\":\"line_sub\",\"file\":\"data/strings/names.txt\",\"line\":\"Caf\\u00e9 cost 50\",\"count\":2,\"subs\":[[\"5\",\"4\"]]}"), w2, true), "expected 2");
    }

    static Path patchedJar(Path root, String... ops) throws Exception {
        Workspace w = ws(root);
        for (String o : ops) Ops.run(op(o), w, true);
        Path out = root.resolve("patched.jar");
        Files.write(out, w.results().get("jars/fx.jar"));
        return out;
    }

    static final String CC = "{\"op\":\"class_const\",\"jar\":\"jars/fx.jar\",\"class\":\"fx/Skill\",";

    static void classConst() throws Exception {
        Path root = game("1.0");
        Path j = patchedJar(root,
                CC + "\"method\":\"mult\",\"type\":\"float\",\"from\":0.25,\"to\":0.1,\"count\":1}",
                CC + "\"method\":\"count\",\"type\":\"int\",\"from\":3,\"to\":300,\"count\":2,\"which\":[0]}",
                CC + "\"method\":\"big\",\"type\":\"long\",\"from\":1,\"to\":70000,\"count\":1}",
                CC + "\"method\":\"half\",\"type\":\"double\",\"from\":0.5,\"to\":1,\"count\":1}");
        check(((Float) call(j, "mult")) == 0.1f, "float patched");
        check(((Integer) call(j, "count", 9)) == 300, "int iconst became sipush, branch still works");
        check(((Integer) call(j, "count", 2)) == 206, "second 3 untouched (which)");
        check(((Long) call(j, "big")) == 70000L, "long patched");
        check(((Double) call(j, "half")) == 1.0, "double became dconst_1");
        check(((Integer) call(j, "other")) == 3, "other method untouched");
        Workspace w = ws(root);
        fails(() -> Ops.run(op(CC + "\"method\":\"count\",\"type\":\"int\",\"from\":3,\"to\":4,\"count\":1}"), w, true), "occurs 2 times");
        fails(() -> Ops.run(op(CC + "\"method\":\"count\",\"type\":\"int\",\"from\":3,\"to\":4,\"count\":2,\"which\":[2]}"), w, true), "out of range");
        fails(() -> Ops.run(op(CC + "\"method\":\"nope\",\"type\":\"int\",\"from\":3,\"to\":4,\"count\":1}"), w, true), "not found");
        fails(() -> Ops.run(op("{\"op\":\"class_const\",\"jar\":\"jars/fx.jar\",\"class\":\"fx/Gone\",\"method\":\"m\",\"type\":\"int\",\"from\":3,\"to\":4,\"count\":1}"), w, true), "not found in");
        fails(() -> Ops.run(op(CC + "\"method\":\"mult\",\"type\":\"float\",\"from\":0.25,\"to\":0.1,\"count\":1,\"method_fp\":\"0000000000000000\"}"), w, true), "has changed in this version");
    }

    static void classString() throws Exception {
        Path root = game("1.0");
        Path j = patchedJar(root,
                "{\"op\":\"class_string\",\"jar\":\"jars/fx.jar\",\"class\":\"fx/Skill\",\"method\":\"tip\",\"expect\":\"Gives +25% speed to \\u0001 ships\",\"subs\":[[\"25%\",\"10%\"]]}",
                "{\"op\":\"class_string\",\"jar\":\"jars/fx.jar\",\"class\":\"fx/Skill\",\"method\":\"plain\",\"expect\":\"Range 25%\",\"subs\":[[\"25%\",\"15%\"]]}");
        check(call(j, "tip", 4).equals("Gives +10% speed to 4 ships"), "indy recipe patched: " + call(j, "tip", 4));
        check(call(j, "plain").equals("Range 15%"), "ldc string patched");
        Workspace w = ws(root);
        fails(() -> Ops.run(op("{\"op\":\"class_string\",\"jar\":\"jars/fx.jar\",\"class\":\"fx/Skill\",\"method\":\"plain\",\"expect\":\"Range 20%\",\"subs\":[[\"2\",\"1\"]]}"), w, true), "occurs 0 times");
    }

    static void fingerprint() throws Exception {
        byte[] a = compile(CLASS_SRC, true), b = compile(CLASS_SRC, false);
        byte[] c = compile(CLASS_SRC.replace("return 0.25f;", "return 0.25f * 2f;"), true);
        byte[] d = compile("\n\n" + CLASS_SRC, true);
        check(ClassOps.fingerprintOf(a, "mult", null).equals(ClassOps.fingerprintOf(b, "mult", null)), "debug info ignored");
        check(ClassOps.fingerprintOf(a, "tip", null).equals(ClassOps.fingerprintOf(d, "tip", null)), "line numbers ignored");
        check(!ClassOps.fingerprintOf(a, "mult", null).equals(ClassOps.fingerprintOf(c, "mult", null)), "code change seen");
        check(ClassOps.fingerprintOf(a, "other", null).equals(ClassOps.fingerprintOf(c, "other", null)), "other methods unaffected");
    }

    static void jarIdentity() throws Exception {
        Zip z = new Zip(fixtureJar);
        check(java.util.Arrays.equals(z.rewrite(Map.of()), fixtureJar), "rewrite with nothing replaced is identical");
        byte[] nb = z.rewrite(Map.of("data/readme.txt", "changed\n".getBytes(StandardCharsets.UTF_8)));
        Zip n = new Zip(nb);
        check(n.entries.size() == z.entries.size(), "same entry count");
        for (int i = 0; i < z.entries.size(); i++) {
            Zip.Entry a = z.entries.get(i), b = n.entries.get(i);
            check(a.name.equals(b.name), "same order");
            if (a.name.equals("data/readme.txt")) { check(b.method == 0, "stored stays stored"); continue; }
            check(java.util.Arrays.equals(java.util.Arrays.copyOfRange(fixtureJar, a.localOffset, a.localEnd), java.util.Arrays.copyOfRange(nb, b.localOffset, b.localEnd)), a.name + " raw bytes identical");
            check(java.util.Arrays.equals(java.util.Arrays.copyOfRange(fixtureJar, a.cdOffset + 4, a.cdOffset + 42), java.util.Arrays.copyOfRange(nb, b.cdOffset + 4, b.cdOffset + 42)), a.name + " central record identical");
        }
        check(new String(n.read("data/readme.txt"), StandardCharsets.UTF_8).equals("changed\n"), "new content");
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(nb))) {
            int k = 0;
            while (zi.getNextEntry() != null) { zi.readAllBytes(); k++; }
            check(k == z.entries.size(), "java.util.zip reads every entry");
        }
    }

    // ------------------------------------------------------------------ engine tests

    static void verify() throws Exception {
        Path root = game("1.0");
        Path pf = root.resolve("patches/fx_mod.json");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int rc = Main.run(new String[]{"verify", "--patch", pf.toString(), "--mods", root.resolve("mods").toString(), "--saves", root.resolve("saves/common").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
        String s = b.toString(StandardCharsets.UTF_8);
        check(rc == 0 && s.contains("OK: 12 passed, 0 failed"), "verify passes: " + s);
        check(s.contains("saved value 0.75 would become 0.5"), "luna info line");
        Files.writeString(pf, patchJson(true));
        b.reset();
        rc = Main.run(new String[]{"verify", "--patch", pf.toString(), "--mods", root.resolve("mods").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
        check(rc == 1 && b.toString(StandardCharsets.UTF_8).contains("FAIL  c-bad#0"), "verify fails the bad op");
    }

    static void roundTrip() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0, "apply: " + lastOut);
        check(lastOut.contains("Applied: Fuel halved") && lastOut.contains("Saved setting changed: fx_mod fx_mult: 0.75 -> 0.5"), "apply output: " + lastOut);
        Path m = mod(root);
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("fx_hull,150,"), "csv patched on disk");
        check(read(m.resolve("data/config/LunaSettings.csv")).contains("fx_mult,Mult,Double,0.5"), "luna default patched");
        check(read(root.resolve("saves/common/LunaSettings/fx_mod.json.data")).contains("\"fx_mult\": 0.5"), "saved value patched");
        check(Files.exists(root.resolve("saves/common/survival_rebalance.json.data")), "saved original recorded");
        check(((Float) call(m.resolve("jars/fx.jar"), "mult")) == 0.1f, "jar patched on disk");
        check(Files.exists(m.resolve("survival_rebalance/backup/jars/fx.jar.orig")), "backup kept");
        check(Files.exists(root.resolve("tool/logs")), "log written");
        check(run(root, "undo") == 0, "undo: " + lastOut);
        check(lastOut.contains("Saved setting restored"), "undo restores saved: " + lastOut);
        check(hashes(root).equals(before), "byte-identical after undo");
    }

    static void applyTwice() throws Exception {
        Path root = game("1.0");
        check(run(root, "apply", "--dev") == 0, "first apply");
        Map<String, String> once = hashes(root);
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("Already applied."), "second apply: " + lastOut);
        check(hashes(root).equals(once), "second apply changed nothing");
    }

    static void patchChange() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        run(root, "apply", "--dev");
        Files.writeString(root.resolve("tool/customize.txt"), "# off\nfx_mod:c-json = off\n");
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("differ from the last apply"), "reapply: " + lastOut);
        Path m = mod(root);
        check(read(m.resolve("data/config/settings.json")).equals(SETTINGS), "switched-off change put back");
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("fx_hull,150,"), "other changes still applied");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity after reapply");
    }

    static void crashRecovery() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        run(root, "apply", "--dev");
        Path s = mod(root).resolve("survival_rebalance/applied.json");
        Files.writeString(s, Files.readString(s).replace("\"status\": \"applied\"", "\"status\": \"applying\""));
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("interrupted") && lastOut.contains("Applied: Fuel halved"), "recovery: " + lastOut);
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity after recovery");
        // a crash before any file was written: state says applying, files still original
        run(root, "apply", "--dev");
        Path m = mod(root);
        String st = Files.readString(s).replace("\"status\": \"applied\"", "\"status\": \"applying\"");
        run(root, "undo");
        Files.createDirectories(s.getParent());
        Files.writeString(s, st);
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo of an interrupted run that wrote nothing: " + lastOut);
        check(!Files.exists(m.resolve("survival_rebalance")), "state removed");
    }

    static void replacedFolder() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        run(root, "apply", "--dev");
        Path m = mod(root);
        Files2.deleteTree(m);
        writeMod(m, "1.0");
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("Applied: Fuel halved"), "fresh folder applies: " + lastOut);
        check(read(root.resolve("saves/common/survival_rebalance.json.data")).contains("\"original\": \"0.75\""), "first-seen original kept");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity incl. saved setting");
    }

    static void inPlaceUpdate() throws Exception {
        Path root = game("1.0");
        run(root, "apply", "--dev");
        Path m = mod(root);
        Path f = m.resolve("data/config/settings.json");
        Files.writeString(f, "{ updated: 1 }");
        Map<String, String> mid = hashes(root);
        check(run(root, "apply", "--dev") == 1 && lastOut.contains("changed after the tool patched them") && lastOut.contains("data/config/settings.json"), "apply refuses: " + lastOut);
        check(hashes(root).equals(mid), "nothing touched");
        check(run(root, "undo") == 1 && lastOut.contains("--force"), "undo refuses without --force");
        check(run(root, "undo", "--force") == 0, "undo --force: " + lastOut);
        check(read(f).equals(SETTINGS), "forced undo restores the original");
        check(!Files.exists(m.resolve("survival_rebalance")), "state removed");
    }

    static void allOrNothing() throws Exception {
        Path root = game("1.0");
        Files.writeString(root.resolve("patches/fx_mod.json"), patchJson(true));
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 1, "apply fails");
        check(lastOut.contains("FAILED: Fx Mod 1.0 (tested: 1.0)") && lastOut.contains("\"Wrong\" (c-bad) did not match") && lastOut.contains("Nothing in this mod was changed"), "failure text: " + lastOut);
        check(hashes(root).equals(before), "nothing written");
    }

    static void twoFolders() throws Exception {
        Path root = game("1.0");
        writeMod(root.resolve("mods").resolve("FxMod-copy"), "1.0");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0, "apply both: " + lastOut);
        check(lastOut.split("Applied: Fuel halved", -1).length == 3, "both folders patched: " + lastOut);
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity, both folders");
    }

    static void untested() throws Exception {
        Path root = game("1.1");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("Skipped: Survival Rebalance was tested with version 1.0 of this mod and does not change version 1.1."), "skipped: " + lastOut);
        check(hashes(root).equals(before), "nothing written");
    }

    static void needsFp() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        check(run(root, "apply") == 1 && lastOut.contains("no method_fp"), "needs --dev: " + lastOut);
        check(hashes(root).equals(before), "nothing written");
    }

    static Path seal(Path root) throws Exception {
        Path fp = root.resolve("fp"), sealed = root.resolve("sealed");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        check(Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods").toString(), "--out", fp.toString()}, ps) == 0, "fingerprint: " + b);
        check(Main.run(new String[]{"seal", "--patches", fp.toString(), "--out", sealed.toString(), "--lint", root.resolve("lint.txt").toString()}, ps) == 0, "seal: " + b);
        return sealed;
    }

    static void sealed() throws Exception {
        Path root = game("1.0");
        Path sealed = seal(root);
        String s = Files.readString(sealed.resolve("fx_mod.json"));
        check(!s.contains("Fast hull") && !s.contains("Range 25%") && !s.contains("Caf") && !s.contains("OpenDialog"), "no literal anchors ship: " + s);
        check(s.contains("expect_sha256") && s.contains("line_sha256") && s.contains("method_fp"), "hashes present");
        check(s.contains("\"expect\": \"300\""), "numeric expect stays literal");
        String lint = Files.readString(root.resolve("lint.txt"));
        check(lint.contains(".changes[0].title = \"Fuel halved\"") && !lint.contains("Fast hull"), "lint lists strings: " + lint);
        Files2.deleteTree(root.resolve("patches"));
        Files.move(sealed, root.resolve("patches"));
        Map<String, String> before = hashes(root);
        check(run(root, "apply") == 0 && lastOut.contains("Applied: Code numbers"), "sealed apply without --dev: " + lastOut);
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity, sealed");
    }

    static void sealLong() throws Exception {
        Path root = game("1.0");
        Path pf = root.resolve("patches/fx_mod.json");
        Files.writeString(pf, Files.readString(pf).replace("[[\"25%\", \"15%\"]]", "[[\"25%\", \"a replacement sentence that is far too long to ship\"]]"));
        Path fp = root.resolve("fp");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods").toString(), "--out", fp.toString()}, ps);
        check(Main.run(new String[]{"seal", "--patches", fp.toString(), "--out", root.resolve("sealed").toString()}, ps) == 1 && b.toString(StandardCharsets.UTF_8).contains("over 40 characters"), "seal refuses: " + b);
    }

    static void sealProse() throws Exception {
        check(!Build.isProse("15%") && !Build.isProse("rat_beatSingularity_off") && !Build.isProse("half a million")
                && !Build.isProse("(Off) Requires") && !Build.isProse("0 Story Points"), "short tokens pass");
        check(Build.isProse("no longer reduce the fleets") && Build.isProse("Favorable terms (10 relation)")
                && Build.isProse("Adds extra one launch bay."), "phrases are prose");
        Path root = game("1.0");
        Path pf = root.resolve("patches/fx_mod.json");
        Files.writeString(pf, Files.readString(pf).replace("[[\"25%\", \"15%\"]]", "[[\"25%\", \"now one of four ships\"]]"));
        Path fp = root.resolve("fp");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods").toString(), "--out", fp.toString()}, ps);
        check(Main.run(new String[]{"seal", "--patches", fp.toString(), "--out", root.resolve("sealed").toString()}, ps) == 1
                && b.toString(StandardCharsets.UTF_8).contains("reads as prose"), "seal refuses prose: " + b);
    }

    static void sealPlaceholder() throws Exception {
        check(Build.textProblem("why", "x", false) != null && Build.textProblem("why", "", false) != null
                && Build.textProblem("title", " TODO ", false) != null && Build.textProblem("why", "Too short", true) != null && Build.textProblem("title", "luna", false) == null
                && Build.textProblem("after", "", false) != null && Build.textProblem("before", "x", false) != null, "placeholders refused");
        check(Build.textProblem("after", "1x", false) == null && Build.textProblem("before", "all", false) == null
                && Build.textProblem("why", "Survival fuel costs x3.", false) == null, "real values pass");
        check(Build.textProblem("why", null, false) == null && Build.textProblem("why", null, true) != null
                && Build.textProblem("before", null, true) == null, "a missing why is refused only for the release set");
        Path root = game("1.0");
        Path pf = root.resolve("patches/fx_mod.json");
        Files.writeString(pf, Files.readString(pf).replace("\"title\": \"Fuel halved\",", "\"title\": \"Fuel halved\", \"why\": \"x\","));
        Path fp = root.resolve("fp");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods").toString(), "--out", fp.toString()}, ps);
        check(Main.run(new String[]{"seal", "--patches", fp.toString(), "--out", root.resolve("sealed").toString()}, ps) == 1
                && b.toString(StandardCharsets.UTF_8).contains("change c-csv: why is empty or a placeholder"), "seal refuses a placeholder why: " + b);
        Files.writeString(pf, Files.readString(pf).replace("\"why\": \"x\",", ""));
        b.reset();
        Files2.deleteTree(fp);
        Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods").toString(), "--out", fp.toString()}, ps);
        check(Main.run(new String[]{"seal", "--release", "--patches", fp.toString(), "--out", root.resolve("sealed").toString()}, ps) == 1
                && b.toString(StandardCharsets.UTF_8).contains("why is missing"), "release seal requires a why: " + b);
    }

    static void csvWidthUnit() throws Exception {
        CsvWidth.check("a.csv", "id,desc\nx,\"one, two\"\n", "id,desc\nx,\"one, three\"\n");
        CsvWidth.check("a.txt", "a,b\n", "a,b,c\n");
        fails(() -> CsvWidth.check("a.csv", "id,desc\nx,plain\n", "id,desc\nx,plain, wide\n"), "from 2 to 3 fields");
        check(CsvWidth.rawSplitRisk("line_sub", "data/hulls/ship_data.csv", "plain", "plain, wide") != null, "raw comma refused");
        check(CsvWidth.rawSplitRisk("line_sub", "data/hulls/ship_data.csv", "plain", "pl\"ain") != null, "raw quote refused");
        check(CsvWidth.rawSplitRisk("line_sub", "data/hulls/ship_data.csv", "25%", "10%") == null, "plain token passes");
        check(CsvWidth.rawSplitRisk("csv_cell_sub", "data/hulls/ship_data.csv", "plain", "plain, wide") == null, "cell ops re-encode");
        check(CsvWidth.rawSplitRisk("line_sub", "data/strings/names.txt", "a", "a, b") == null, "not a csv");
        String rules = "id,text\nr1,\"\"\"Paid 5 million,\"\" he said.\"\nr2,Paid 5 million\n";
        check(CsvWidth.unquotedRawSplit("line_sub", "data/campaign/rules.csv", rules, "r1,\"\"\"Paid 5 million,\"\" he said.\"", "5 million", "500,000") == null, "quoted cell may take a comma");
        check(CsvWidth.unquotedRawSplit("line_sub", "data/campaign/rules.csv", rules, "r2,Paid 5 million", "5 million", "500,000") != null, "unquoted cell refuses a comma");
    }

    static void csvWidthApply() throws Exception {
        Path root = game("1.0");
        Path pf = root.resolve("patches/fx_mod.json");
        String extra = ",\n{\"id\": \"c-wide\", \"title\": \"Wide\", \"ops\": [\n  {\"op\": \"line_sub\", \"file\": \"data/hulls/ship_data.csv\", \"line\": \"Other,fx_other,300,plain\", \"subs\": [[\"plain\", \"plain, wide\"]]}]}";
        String pj = patchJson(false);
        Files.writeString(pf, pj.substring(0, pj.lastIndexOf("\n]}")) + extra + "\n]}\n");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 1, "apply fails: " + lastOut);
        check(lastOut.contains("from 4 to 5 fields") && lastOut.contains("Nothing in this mod was changed"), "width failure text: " + lastOut);
        check(hashes(root).equals(before), "nothing written");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int rc = Main.run(new String[]{"verify", "--patch", pf.toString(), "--mods", root.resolve("mods").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
        check(rc == 1 && b.toString(StandardCharsets.UTF_8).contains("from 4 to 5 fields"), "verify fails: " + b);
    }

    static void lunaUserChanged() throws Exception {
        Path root = game("1.0");
        run(root, "apply", "--dev");
        Path sf = root.resolve("saves/common/LunaSettings/fx_mod.json.data");
        Files.writeString(sf, read(sf).replace("\"fx_mult\": 0.5", "\"fx_mult\": 2"));
        check(run(root, "undo") == 0 && lastOut.contains("left as is"), "undo leaves user value: " + lastOut);
        check(read(sf).contains("\"fx_mult\": 2"), "user value kept");
        check(!Files.exists(root.resolve("saves/common/survival_rebalance.json.data")), "empty record removed");
    }

    static void switchedOff() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        Files.writeString(root.resolve("tool/customize.txt"), "fx_mod:c-class = off\n");
        check(run(root, "apply", "--dev") == 0 && !lastOut.contains("Code numbers"), "change off: " + lastOut);
        check(Files.readString(mod(root).resolve("survival_rebalance/applied.json")).contains("c-class"), "sentinel lists disabled change");
        Files.writeString(root.resolve("tool/customize.txt"), "fx_mod = off  # whole mod\n");
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("Switched off"), "mod off: " + lastOut);
        check(hashes(root).equals(before), "switching the mod off put it back");
    }

    static void locked() throws Exception {
        Path root = game("1.0");
        Path jar = mod(root).resolve("jars/fx.jar");
        Map<String, String> before = hashes(root);
        try (FileChannel c = FileChannel.open(jar, StandardOpenOption.WRITE); FileLock l = c.lock()) {
            check(run(root, "apply", "--dev") == 1 && lastOut.contains("most likely Starsector") && lastOut.contains("Nothing in this mod was changed."), "locked: " + lastOut);
        }
        check(hashes(root).equals(before), "nothing written");
    }

    static void dryRun() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev", "--dry-run") == 0 && lastOut.contains("Would apply: Fuel halved") && lastOut.contains("Saved setting would change"), "dry: " + lastOut);
        check(hashes(root).equals(before), "dry run wrote nothing");
    }

    static void status() throws Exception {
        Path root = game("1.0");
        check(run(root, "status", "--dev") == 0 && lastOut.contains("Not patched. Apply would make 6 changes in 6 files"), "status before: " + lastOut);
        run(root, "apply", "--dev");
        check(run(root, "status", "--dev") == 0 && lastOut.contains("up to date"), "status after: " + lastOut);
        Path f = mod(root).resolve("data/config/settings.json");
        Files.writeString(f, "{}");
        check(run(root, "status", "--dev") == 1 && lastOut.contains("changed after the tool patched them"), "status drift: " + lastOut);
    }
    static void requires() throws Exception {
        Path root = game("1.0");
        Path dep = root.resolve("mods").resolve("AaDep");
        Files.createDirectories(dep.resolve("data"));
        Files.writeString(dep.resolve("mod_info.json"), "{\"id\": \"aa_dep\", \"name\": \"Dep\", \"version\": \"2.0\"}");
        Files.writeString(dep.resolve("data/t.csv"), "id,v\na,1\nb,2\n");
        // aa_dep sorts first but needs fx_mod, so the engine must run fx_mod first
        Files.writeString(root.resolve("patches/aa_dep.json"), "{\"format\":1,\"mod_id\":\"aa_dep\",\"tested_versions\":[\"2.0\"],\"changes\":[" +
                "{\"id\":\"needs-json\",\"title\":\"Follows the mult\",\"requires\":[\"fx_mod:c-json\"],\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/t.csv\",\"key\":{\"id\":\"a\"},\"column\":\"v\",\"expect\":\"1\",\"set\":\"5\"}]}," +
                "{\"id\":\"needs-gone\",\"title\":\"Follows a missing mod\",\"requires\":[\"no_such_mod:x\"],\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/t.csv\",\"key\":{\"id\":\"b\"},\"column\":\"v\",\"expect\":\"2\",\"set\":\"6\"}]}," +
                "{\"id\":\"needs-local\",\"title\":\"Follows the skipped one\",\"requires\":[\"needs-gone\"],\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/t.csv\",\"key\":{\"id\":\"b\"},\"column\":\"id\",\"expect\":\"b\",\"set\":\"c\"}]}]}");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0, "apply: " + lastOut);
        check(lastOut.indexOf("== Fx Mod") < lastOut.indexOf("== aa_dep"), "required mod first: " + lastOut);
        check(lastOut.contains("Applied: Follows the mult") && lastOut.contains("Skipped: Follows a missing mod") && lastOut.contains("Skipped: Follows the skipped one"), "skips: " + lastOut);
        check(read(dep.resolve("data/t.csv")).equals("id,v\na,5\nb,2\n"), "only the met change applied");
        Files.writeString(root.resolve("tool/customize.txt"), "fx_mod:c-json = off\n");
        check(run(root, "apply", "--dev") == 0 && lastOut.contains("Skipped: Follows the mult"), "switching the base change off skips the dependent: " + lastOut);
        check(read(dep.resolve("data/t.csv")).equals("id,v\na,1\nb,2\n"), "dependent change put back");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity");
    }

    static void whichOps() throws Exception {
        Path root = game("1.0");
        Files.writeString(mod(root).resolve("data/dup.txt"), "x = 5\r\ny\r\nx = 5\r\nx = 5\r\n");
        Workspace w = ws(root);
        Ops.run(op("{\"op\":\"line_sub\",\"file\":\"data/dup.txt\",\"line\":\"x = 5\",\"count\":3,\"which\":[2,0],\"subs\":[[\"5\",\"7\"]]}"), w, true);
        check(new String(w.results().get("data/dup.txt"), StandardCharsets.UTF_8).equals("x = 7\r\ny\r\nx = 5\r\nx = 7\r\n"), "line_sub which");
        Path j = patchedJar(root, "{\"op\":\"class_string\",\"jar\":\"jars/fx.jar\",\"class\":\"fx/Skill\",\"method\":\"two\",\"expect\":\"+1\",\"count\":2,\"which\":[1],\"subs\":[[\"1\",\"0\"]]}");
        check(call(j, "two").equals("+1|+0"), "class_string which gives one ldc its own constant: " + call(j, "two"));
    }

    static void lunaNoCsv() throws Exception {
        Path root = game("1.0");
        Files.writeString(root.resolve("patches/fx_mod.json"), "{\"format\":1,\"mod_id\":\"fx_mod\",\"tested_versions\":[\"1.0\"],\"changes\":[" +
                "{\"id\":\"l\",\"title\":\"Flag off\",\"ops\":[{\"op\":\"luna\",\"key\":\"fx_flag\",\"csv\":false,\"set\":\"false\"}," +
                "{\"op\":\"luna\",\"key\":\"fx_absent\",\"csv\":false,\"set\":\"3\"}]}]}");
        Map<String, String> before = hashes(root);
        check(run(root, "apply") == 0 && lastOut.contains("Saved setting changed: fx_mod fx_flag: true -> false"), "csv false apply: " + lastOut);
        check(read(mod(root).resolve("data/config/LunaSettings.csv")).equals(LUNA_CSV), "csv untouched");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity: " + lastOut);
    }
    static void originalTargets() throws Exception {
        // the lead's case: earlier ops create new 1s; "which" must still mean the original 1s
        String a = CC + "\"method\":\"mix\",\"type\":\"int\",\"from\":4,\"to\":1,\"count\":1}";
        String b = CC + "\"method\":\"mix\",\"type\":\"int\",\"from\":5,\"to\":1,\"count\":1}";
        String c = CC + "\"method\":\"mix\",\"type\":\"int\",\"from\":1,\"to\":0,\"count\":2,\"which\":[1]}";
        Path root = game("1.0");
        check(((Integer) call(patchedJar(root, a, b, c), "mix")) == 1110, "which counts the original constants");
        check(((Integer) call(patchedJar(root, c, b, a), "mix")) == 1110, "op order does not matter");
        Workspace w = ws(root);
        fails(() -> Ops.run(op(CC + "\"method\":\"mix\",\"type\":\"int\",\"from\":1,\"to\":0,\"count\":4}"), w, true), "occurs 2 times");
        // text ops: a line_sub and a csv op locate their targets in the original text
        Workspace t = ws(root);
        Ops.run(op("{\"op\":\"line_sub\",\"file\":\"data/strings/names.txt\",\"line\":\"Caf\\u00e9 cost 50\",\"subs\":[[\"50\",\"keep \\u00ff\"]]}"), t, true);
        Ops.run(op("{\"op\":\"line_sub\",\"file\":\"data/strings/names.txt\",\"line\":\"keep \\u00ff\",\"subs\":[[\"keep\",\"kept\"]]}"), t, true);
        check(new String(t.results().get("data/strings/names.txt"), StandardCharsets.ISO_8859_1).equals("Caf\u00e9 cost keep \u00ff\nkept \u00ff\n"), "second op saw the original line only");
    }

    static void conflicts() throws Exception {
        Path root = game("1.0");
        Workspace w = ws(root);
        w.owner = "\"First\" (first)";
        Ops.run(op(CC + "\"method\":\"mult\",\"type\":\"float\",\"from\":0.25,\"to\":0.1,\"count\":1}"), w, true);
        w.owner = "\"Second\" (second)";
        fails(() -> Ops.run(op(CC + "\"method\":\"mult\",\"type\":\"float\",\"from\":0.25,\"to\":0.2,\"count\":1}"), w, true), "change \"First\" (first) already edits constant");
        w.owner = "\"Third\" (third)";
        Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"150\"}"), w, true);
        w.owner = "\"Fourth\" (fourth)";
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"100\"}"), w, true), "change \"Third\" (third) already edits the same place");
        Ops.run(op("{\"op\":\"csv_row_off\",\"file\":\"data/campaign/rules.csv\",\"key\":{\"id\":\"fx_rule\"},\"expect\":" + Json.quote(RULES_ROW) + "}"), w, true);
        fails(() -> Ops.run(op("{\"op\":\"csv_cell\",\"file\":\"data/campaign/rules.csv\",\"key\":{\"id\":\"fx_rule\"},\"column\":\"id\",\"expect\":\"fx_rule\",\"set\":\"x\"}"), w, true), "already edits the same place");
        // apply names both changes and writes nothing
        Path pf = root.resolve("patches/fx_mod.json");
        Files.writeString(pf, Files.readString(pf).replace("\n]}\n", ",\n{\"id\": \"c-dup\", \"title\": \"Fuel again\", \"ops\": [{\"op\": \"csv_cell\", \"file\": \"data/hulls/ship_data.csv\", \"key\": {\"id\": \"fx_hull\"}, \"column\": \"fuel\", \"expect\": \"300\", \"set\": \"100\"}]}\n]}\n"));
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 1 && lastOut.contains("\"Fuel again\" (c-dup) did not match: change \"Fuel halved\" (c-csv) already edits the same place"), "apply conflict: " + lastOut);
        check(hashes(root).equals(before), "nothing written");
    }
}