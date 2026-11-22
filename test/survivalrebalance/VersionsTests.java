package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static survivalrebalance.Tests.check;
import static survivalrebalance.Tests.fails;
import static survivalrebalance.Tests.hashes;
import static survivalrebalance.Tests.mod;
import static survivalrebalance.Tests.read;
import static survivalrebalance.Tests.run;

/**
 * One patch file for two tested versions of a mod (PATCH_FORMAT.md, "A mod with more than one
 * tested version"). Version 1.0 is the fixture mod of Tests; version 2.0 differs in three places:
 * the hull's fuel is 400, the cost line says 60, and the method other() has new code around the
 * same constant. Every expected value below is written out by hand.
 */
final class VersionsTests {
    private VersionsTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("versions: an op with an at override applies at each version and undoes byte for byte", VersionsTests::overrideBoth);
        t.put("versions: an op that is null at a version is not there", VersionsTests::nullOp);
        t.put("versions: a change with no op at a version is not reported there", VersionsTests::absentChange);
        t.put("versions: a version not on the list is still skipped", VersionsTests::otherSkipped);
        t.put("versions: an untested version tries the last tested version's ops first", VersionsTests::readingsOrder);
        t.put("versions: #N names the same op at both versions", VersionsTests::directBoth);
        t.put("versions: load refuses a bad at", VersionsTests::badAt);
        t.put("versions: seal hashes an override's anchor, and the sealed file applies at both versions", VersionsTests::sealBoth);
        t.put("versions: fingerprint fails when a tested version has no folder", VersionsTests::missingFolder);
        t.put("versions: seal refuses an at entry with a literal anchor, an at inside it, or a long or prose token", VersionsTests::sealRefusesAt);
        t.put("versions: a one-version patch seals with no at, and an untested folder beside it changes nothing", VersionsTests::singleNoAt);
        t.put("versions: roundtrip applies at a tested version, and says so when a folder holds only an untested one", VersionsTests::roundtripVersions);
    }

    static final String CLASS_V2 = Tests.CLASS_SRC.replace("public static int other() { return 3; }",
            "public static int other() { int k = Integer.parseInt(\"7\"); return 3 + k - 7; }");

    /**
     * #0 json_value, gone at 2.0. #1 csv_cell with its anchor and value at 2.0. #2 line_sub with
     * its line at 2.0. #3 class_const the same at both. #4 csv_cell whose number at 1.0 is a word
     * at 2.0, so sealing hashes the override's anchor and keeps the op's own. Change c-old exists
     * only at 1.0.
     */
    static String patch() {
        return "{\n\"format\": 1, \"mod_id\": \"fx_mod\", \"mod_name\": \"Fx Mod\", \"tested_versions\": [\"1.0\", \"2.0\"],\n\"changes\": [\n" +
                "{\"id\": \"c-fuel\", \"title\": \"Fuel lower\", \"ops\": [\n" +
                "  {\"op\": \"json_value\", \"file\": \"data/config/settings.json\", \"path\": [\"block\", \"mult\"], \"expect\": 0.5, \"set\": 0.20, \"at\": {\"2.0\": null}},\n" +
                "  {\"op\": \"csv_cell\", \"file\": \"data/hulls/ship_data.csv\", \"key\": {\"id\": \"fx_hull\"}, \"column\": \"fuel\", \"expect\": \"300\", \"set\": \"150\", \"at\": {\"2.0\": {\"expect\": \"400\", \"set\": \"200\"}}},\n" +
                "  {\"op\": \"line_sub\", \"file\": \"data/strings/names.txt\", \"line\": \"Caf\\u00e9 cost 50\", \"subs\": [[\"50\", \"40\"]], \"at\": {\"2.0\": {\"line\": \"Caf\\u00e9 cost 60\", \"subs\": [[\"60\", \"45\"]]}}},\n" +
                "  {\"op\": \"class_const\", \"jar\": \"jars/fx.jar\", \"class\": \"fx/Skill\", \"method\": \"other\", \"type\": \"int\", \"from\": 3, \"to\": 4, \"count\": 1},\n" +
                "  {\"op\": \"csv_cell\", \"file\": \"data/hulls/ship_data.csv\", \"key\": {\"id\": \"fx_other\"}, \"column\": \"fuel\", \"expect\": \"300\", \"set\": \"250\", \"at\": {\"2.0\": {\"expect\": \"none\", \"set\": \"0\"}}}]},\n" +
                "{\"id\": \"c-old\", \"title\": \"Old rule off\", \"ops\": [\n" +
                "  {\"op\": \"csv_row_off\", \"file\": \"data/campaign/rules.csv\", \"key\": {\"id\": \"fx_rule\"}, \"expect\": " + Json.quote(Tests.RULES_ROW) + ", \"at\": {\"2.0\": null}}]}\n" +
                "]}\n";
    }

    static byte[] jar(String src) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (JarOutputStream jo = new JarOutputStream(bo)) {
            jo.putNextEntry(new JarEntry("META-INF/"));
            jo.closeEntry();
            ZipEntry ce = new ZipEntry("fx/Skill.class");
            ce.setTime(1_600_000_000_000L);
            jo.putNextEntry(ce); jo.write(Tests.compile(src, true)); jo.closeEntry();
        }
        return bo.toByteArray();
    }

    /** The fixture mod at version 1.0 or 2.0 in the folder mod. */
    static void writeMod(Path mod, String version) throws Exception {
        Tests.writeMod(mod, version);
        if (version.equals("1.0")) return;
        Path csv = mod.resolve("data/hulls/ship_data.csv");
        Files.writeString(csv, Files.readString(csv).replace("Fx Hull,fx_hull,300,", "Fx Hull,fx_hull,400,").replace("Other,fx_other,300,", "Other,fx_other,none,"));
        Files.write(mod.resolve("data/strings/names.txt"), "Café cost 60\nkeep ÿ\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(mod.resolve("jars/fx.jar"), jar(CLASS_V2));
    }

    /** A game with the fixture mod at version, and the two-version patch. */
    static Path game(String version) throws Exception {
        Path root = Tests.game(version);
        Path m = mod(root);
        Files2.deleteTree(m);
        writeMod(m, version.startsWith("2.") ? "2.0" : "1.0");
        if (!version.equals("1.0") && !version.equals("2.0")) {
            Path info = m.resolve("mod_info.json");
            Files.writeString(info, Files.readString(info).replaceAll("\"version\": [^\\n]*", "\"version\": \"" + version + "\","));
        }
        Files.writeString(root.resolve("patches/fx_mod.json"), patch());
        return root;
    }

    static String settings(Path m) throws Exception { return read(m.resolve("data/config/settings.json")); }

    static void overrideBoth() throws Exception {
        Path one = game("1.0");
        Map<String, String> before = hashes(one);
        check(run(one, "apply", "--dev") == 0, "apply at 1.0: " + Tests.lastOut);
        Path m = mod(one);
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("Fx Hull,fx_hull,150,") && read(m.resolve("data/hulls/ship_data.csv")).contains("Other,fx_other,250,"), "1.0 fuel 150 and 250");
        check(read(m.resolve("data/strings/names.txt")).startsWith("Café cost 40\n"), "1.0 cost 40");
        check(((Integer) Tests.call(m.resolve("jars/fx.jar"), "other")) == 4, "1.0 other() 4");
        check(run(one, "undo") == 0 && hashes(one).equals(before), "undo at 1.0 byte for byte: " + Tests.lastOut);

        Path two = game("2.0");
        before = hashes(two);
        check(run(two, "apply", "--dev") == 0, "apply at 2.0: " + Tests.lastOut);
        m = mod(two);
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("Fx Hull,fx_hull,200,") && read(m.resolve("data/hulls/ship_data.csv")).contains("Other,fx_other,0,"), "2.0 fuel 200 and 0");
        check(read(m.resolve("data/strings/names.txt")).startsWith("Café cost 45\n"), "2.0 cost 45");
        check(((Integer) Tests.call(m.resolve("jars/fx.jar"), "other")) == 4, "2.0 other() 4");
        check(run(two, "undo") == 0 && hashes(two).equals(before), "undo at 2.0 byte for byte: " + Tests.lastOut);
    }

    static void nullOp() throws Exception {
        Path one = game("1.0");
        check(run(one, "apply", "--dev") == 0 && settings(mod(one)).contains("mult: 0.20,"), "1.0 sets mult: " + Tests.lastOut);
        Path two = game("2.0");
        check(run(two, "apply", "--dev") == 0 && settings(mod(two)).equals(Tests.SETTINGS), "2.0 leaves settings.json alone: " + Tests.lastOut);
        check(run(two, "status", "--dev") == 0 && Tests.lastOut.contains("#1  400 -> 200") && !Tests.lastOut.contains("#0  "), "status at 2.0 lists #1 to #3, no #0: " + Tests.lastOut);
    }

    static void absentChange() throws Exception {
        Path one = game("1.0");
        check(run(one, "apply", "--dev") == 0 && Tests.lastOut.contains("Applied: Old rule off"), "1.0 has c-old: " + Tests.lastOut);
        check(read(mod(one).resolve("data/campaign/rules.csv")).contains("#fx_rule,"), "1.0 row off");
        Path two = game("2.0");
        check(run(two, "apply", "--dev") == 0 && Tests.lastOut.contains("Applied: Fuel lower") && !Tests.lastOut.contains("Old rule off"), "2.0 does not name c-old: " + Tests.lastOut);
        check(read(mod(two).resolve("data/campaign/rules.csv")).equals(Tests.RULES_CSV), "2.0 rules untouched");
        check(run(two, "status", "--dev") == 0 && !Tests.lastOut.contains("Old rule off") && !Tests.lastOut.contains("FAIL"), "status at 2.0: " + Tests.lastOut);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Main.run(Tests.args(two, "status", "--json", "--dev"), new PrintStream(b, true, StandardCharsets.UTF_8));
        String js = b.toString(StandardCharsets.UTF_8);
        check(js.contains("\"c-fuel\"") && !js.contains("\"c-old\"") && js.contains("\"state\": \"patched\""), "the window's list at 2.0 has c-fuel only: " + js);
    }

    static void otherSkipped() throws Exception {
        Path root = game("3.0");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Skipped: Survival Rebalance was tested with version 1.0 or 2.0 of this mod and does not change version 3.0."), "skipped: " + Tests.lastOut);
        check(hashes(root).equals(before), "nothing written");
    }

    static void readingsOrder() throws Exception {
        Patch p = Patch.load(Path.of("fx_mod.json"), patch().getBytes(StandardCharsets.UTF_8));
        List<Patch> r = p.readings("2.1");
        check(r.size() == 2 && r.get(0).version.equals("2.0") && r.get(1).version.equals("1.0"), "2.0 first, then 1.0");
        check(r.get(0).changes.size() == 1 && r.get(0).changes.get(0).ops.get(0) == null && r.get(0).changes.get(0).ops.get(1).str("expect").equals("400"), "at 2.0: #0 gone, #1 expects 400");
        check(r.get(1).changes.size() == 2 && r.get(1).changes.get(0).ops.get(1).str("expect").equals("300") && r.get(1).changes.get(0).ops.get(1).get("at") == null, "at 1.0: #1 expects 300, no at left");
        check(p.readings("1.0").size() == 1 && p.readings("1.0").get(0).version.equals("1.0"), "a tested version reads only itself");
    }

    static void directBoth() throws Exception {
        for (String v : new String[]{"1.0", "2.0"}) {
            Path root = game(v);
            Files.writeString(root.resolve("tool/customize.txt"), "fx_mod:c-fuel#1 = 123\n");
            check(run(root, "apply", "--dev") == 0 && read(mod(root).resolve("data/hulls/ship_data.csv")).contains("Fx Hull,fx_hull,123,"), v + ": #1 is the fuel cell: " + Tests.lastOut);
            check(!Tests.lastOut.contains("was ignored"), v + ": no warning: " + Tests.lastOut);
        }
    }

    static void badAt() throws Exception {
        String notTested = patch().replaceFirst("\"at\": \\{\"2\\.0\": null\\}", "\"at\": {\"2.5\": null}");
        check(!notTested.equals(patch()), "the edit took");
        fails(() -> Patch.load(Path.of("fx_mod.json"), notTested.getBytes(StandardCharsets.UTF_8)), "\"at\" names 2.5, which is not in tested_versions");
        String notObject = patch().replaceFirst("\"at\": \\{\"2\\.0\": null\\}", "\"at\": {\"2.0\": 5}");
        fails(() -> Patch.load(Path.of("fx_mod.json"), notObject.getBytes(StandardCharsets.UTF_8)), "\"at\" 2.0 must be an object or null");
        String atList = patch().replaceFirst("\"at\": \\{\"2\\.0\": null\\}", "\"at\": [1]");
        fails(() -> Patch.load(Path.of("fx_mod.json"), atList.getBytes(StandardCharsets.UTF_8)), "\"at\" must be an object");
    }

    /** fingerprint over mods (1.0) and mods2 (2.0), then seal. Returns the sealed folder. */
    static Path sealTwo(Path root) throws Exception {
        writeMod(root.resolve("mods2").resolve("FxMod-2.0"), "2.0");
        Path fp = root.resolve("fp"), sealed = root.resolve("sealed");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        check(Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods") + ";" + root.resolve("mods2"), "--out", fp.toString()}, ps) == 0, "fingerprint: " + b);
        check(b.toString(StandardCharsets.UTF_8).contains("ok    fx_mod.json (fx_mod 1.0, 2.0, 2 class ops fingerprinted)"), "both versions fingerprinted: " + b);
        check(Main.run(new String[]{"seal", "--patches", fp.toString(), "--out", sealed.toString()}, ps) == 0, "seal: " + b);
        return sealed;
    }

    @SuppressWarnings("unchecked")
    static void sealBoth() throws Exception {
        Path root = game("1.0");
        Path sealed = sealTwo(root);
        String s = Files.readString(sealed.resolve("fx_mod.json"));
        check(!s.contains("cost 60") && !s.contains("cost 50") && !s.contains("OpenDialog"), "no literal line ships: " + s);
        Map<String, Object> doc = Build.loadJava(sealed.resolve("fx_mod.json"));
        List<Map<String, Object>> ops = Build.opsOf(Build.changes(doc).get(0));
        Map<String, Object> line = ops.get(2), at2 = (Map<String, Object>) ((Map<String, Object>) line.get("at")).get("2.0");
        check(at2.containsKey("line_sha256") && !at2.containsKey("line") && line.containsKey("line_sha256") && !line.get("line_sha256").equals(at2.get("line_sha256")), "the override's line is hashed on its own: " + line);
        Map<String, Object> cls = ops.get(3);
        Object fp2 = ((Map<String, Object>) ((Map<String, Object>) cls.get("at")).get("2.0")).get("method_fp");
        check(cls.get("method_fp") != null && fp2 != null && !fp2.equals(cls.get("method_fp")), "other() has a fingerprint for each version: " + cls);
        Map<String, Object> word = ops.get(4), wordAt = (Map<String, Object>) ((Map<String, Object>) word.get("at")).get("2.0");
        check("300".equals(word.get("expect")) && wordAt.containsKey("expect_sha256") && !wordAt.containsKey("expect"), "the number stays, the word is hashed: " + word);
        check(!ops.get(1).containsKey("at") || !((Map<String, Object>) ((Map<String, Object>) ops.get(1).get("at")).get("2.0")).containsKey("method_fp"), "a csv op gets no fingerprint");

        // the sealed file, with no --dev, at each version
        for (String v : new String[]{"1.0", "2.0"}) {
            Path g = game(v);
            Files.copy(sealed.resolve("fx_mod.json"), g.resolve("patches/fx_mod.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Map<String, String> before = hashes(g);
            check(run(g, "apply") == 0 && Tests.lastOut.contains("Applied: Fuel lower"), v + " sealed apply: " + Tests.lastOut);
            check(read(mod(g).resolve("data/strings/names.txt")).startsWith(v.equals("1.0") ? "Café cost 40\n" : "Café cost 45\n"), v + " cost line");
            check(read(mod(g).resolve("data/hulls/ship_data.csv")).contains(v.equals("1.0") ? "Other,fx_other,250," : "Other,fx_other,0,"), v + " the word anchor");
            check(run(g, "undo") == 0 && hashes(g).equals(before), v + " sealed undo byte for byte");
        }
    }

    static void roundtripVersions() throws Exception {
        Path root = game("1.0");
        Path sealed = sealTwo(root);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        int rc = Main.run(new String[]{"roundtrip", "--patches", sealed.toString(), "--mods", root.resolve("mods2").toString(), "--out", root.resolve("rt").toString()}, ps);
        String s = b.toString(StandardCharsets.UTF_8);
        check(rc == 0 && s.contains("PASS  apply changed "), "2.0 round trip applies: " + s);
        writeMod(root.resolve("mods3").resolve("FxMod-3.0"), "2.0");
        Path info = root.resolve("mods3").resolve("FxMod-3.0").resolve("mod_info.json");
        Files.writeString(info, Files.readString(info).replaceAll("\"version\": [^\n]*", "\"version\": \"3.0\","));
        b.reset();
        rc = Main.run(new String[]{"roundtrip", "--patches", sealed.toString(), "--mods", root.resolve("mods3").toString(), "--out", root.resolve("rt3").toString()}, ps);
        s = b.toString(StandardCharsets.UTF_8);
        check(rc == 0 && s.contains("is at a version its patch was tested with, so there is nothing to apply") && !s.contains("PASS"), "3.0 only: nothing to apply: " + s);
    }

    static void missingFolder() throws Exception {
        Path root = game("1.0");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int rc = Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods").toString(), "--out", root.resolve("fp").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
        String s = b.toString(StandardCharsets.UTF_8);
        check(rc == 1 && s.contains("FAIL  fx_mod.json: fx_mod 2.0 is a tested version, but no folder in [" + root.resolve("mods") + "] has it"), "names the mod, version and folders: " + s);
        check(!Files.exists(root.resolve("fp/fx_mod.json")), "nothing written");
    }

    /** seal refuses an at entry that would ship another mod's text, and names the file, change, op and version. */
    static void sealRefusesAt() throws Exception {
        String line = "{\"op\": \"line_sub\", \"file\": \"a.txt\", \"line\": \"cost 50\", \"subs\": [[\"50\", \"40\"]], \"at\": {\"2.0\": ";
        String[][] cases = {
            {"{\"op\": \"csv_cell\", \"file\": \"a.csv\", \"key\": {\"id\": \"x\"}, \"column\": \"v\", \"expect\": \"300\", \"set\": \"150\", \"at\": {\"2.0\": {\"line\": \"cost 60\"}}}",
                "change c op #0 (csv_cell), at 2.0: a literal line is left"},
            {line + "{\"expect\": \"some text\"}}}", "change c op #0 (line_sub), at 2.0: a literal expect is left"},
            {line + "{\"op\": \"class_const\", \"expect_default\": \"word\"}}}", "change c op #0 (line_sub), at 2.0: a literal expect_default is left"},
            {line + "{\"at\": {\"1.0\": null}}}}", "change c op #0 (line_sub), at 2.0: has an at inside an at entry"},
            {line + "{\"subs\": [[\"" + "x".repeat(41) + "\", \"y\"]]}}}", "sub token over 40 characters"},
            {line + "{\"subs\": [[\"25%\", \"now one of four ships\"]]}}}", "sub token reads as prose"},
        };
        int n = 0;
        for (String[] c : cases) {
            Path dir = Tests.tmp.resolve("sealat" + n++), in = dir.resolve("in");
            Files.createDirectories(in);
            Files.writeString(in.resolve("fx_mod.json"), "{\"format\": 1, \"mod_id\": \"fx_mod\", \"mod_name\": \"Fx Mod\", \"tested_versions\": [\"1.0\", \"2.0\"], \"changes\": [{\"id\": \"c\", \"ops\": [" + c[0] + "]}]}");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int rc = Main.run(new String[]{"seal", "--patches", in.toString(), "--out", dir.resolve("out").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
            String s = b.toString(StandardCharsets.UTF_8);
            check(rc == 1 && s.contains("FAIL  fx_mod.json: ") && s.contains(c[1]), "refused with \"" + c[1] + "\": " + s);
            check(!Files.exists(dir.resolve("out/fx_mod.json")), "nothing written: " + c[1]);
        }
    }

    /** The one-version fixture patch seals with no at, and a 2.0 folder beside it changes nothing. */
    static void singleNoAt() throws Exception {
        Path root = Tests.game("1.0");
        Path sealed = Tests.seal(root);
        String s = Files.readString(sealed.resolve("fx_mod.json"));
        check(!s.contains("\"at\""), "no at written: " + s);
        Files.createDirectories(root.resolve("mods2"));
        writeMod(root.resolve("mods2").resolve("FxMod-2.0"), "2.0");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(b, true, StandardCharsets.UTF_8);
        check(Main.run(new String[]{"fingerprint", "--patches", root.resolve("patches").toString(), "--mods", root.resolve("mods") + ";" + root.resolve("mods2"), "--out", root.resolve("fp2").toString()}, ps) == 0, "fingerprint: " + b);
        check(Main.run(new String[]{"seal", "--patches", root.resolve("fp2").toString(), "--out", root.resolve("sealed2").toString()}, ps) == 0, "seal: " + b);
        check(Files.readString(root.resolve("sealed2/fx_mod.json")).equals(s), "an untested folder beside it changes nothing");
    }
}
