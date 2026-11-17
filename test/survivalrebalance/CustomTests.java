package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static survivalrebalance.Tests.check;
import static survivalrebalance.Tests.game;
import static survivalrebalance.Tests.hashes;
import static survivalrebalance.Tests.mod;
import static survivalrebalance.Tests.read;
import static survivalrebalance.Tests.run;

/** customize.txt, strength, direct values, the warnings, check and the companion mod. */
final class CustomTests {
    private CustomTests() {}

    static void register(Map<String, Tests.T> t) {
        t.put("strength: interpolation and text rules", CustomTests::rules);
        t.put("strength 0.5 on every op type, code and tooltip agree", CustomTests::halfEverything);
        t.put("switch rule and text that cannot follow", CustomTests::switches);
        t.put("direct values", CustomTests::directs);
        t.put("unknown ids and bad values warn, never stop the run", CustomTests::unknownIds);
        t.put("patch hash follows the customization, per mod", CustomTests::hashFollows);
        t.put("customize writes the file and never touches user lines", CustomTests::customizeFile);
        t.put("switching a luna change off puts its saved setting back", CustomTests::lunaOff);
        t.put("warnings: clean run, check exit codes", CustomTests::cleanAndCheck);
        t.put("warnings: game version", CustomTests::gameVersion);
        t.put("warnings: mod updates, replaced and in place", CustomTests::updates);
        t.put("warnings: older patch set and other customization", CustomTests::stale);
        t.put("warnings: saved settings, backups, requires, locked", CustomTests::misc);
        t.put("warnings: enabled mods not analysed, companion, not installed", CustomTests::coverage);
        t.put("companion mod install, update and removal", CustomTests::monitor);
        t.put("applied.json fields the monitor reads stay stable", CustomTests::monitorContract);
        t.put("a LunaLib Double follows as a decimal, an Int as an integer", CustomTests::lunaTypes);
    }

    static Path cust(Path root, String text) throws Exception {
        Path f = root.resolve("tool").resolve(Customize.FILE);
        Files.writeString(f, text);
        return f;
    }

    static BigDecimal d(String s) { return new BigDecimal(s); }

    // ------------------------------------------------------------------ pure rules

    static void rules() throws Exception {
        check(Customize.interp("3", "300", d("0.5"), true).equals("152"), "151.5 rounds half up");
        check(Customize.interp("25", "10", d("0.5"), true).equals("18"), "17.5 rounds half up");
        check(Customize.interp("1", "2", d("0.3"), true).equals("1"), "1.3 -> 1");
        check(Customize.interp("2", "1", d("0.3"), true).equals("2"), "1.7 -> 2");
        check(Customize.interp("5", "6", d("0.001"), true).equals("5"), "stays inside old..new");
        check(Customize.interp("0.25", "0.1", d("0.5"), false).equals("0.18"), "designed decimals + 1: " + Customize.interp("0.25", "0.1", d("0.5"), false));
        check(Customize.interp("0.75", "0.85", d("0.5"), false).equals("0.80"), "keeps the token's decimals");
        check(Customize.interp("0.5", "0.25", d("0.5"), false).equals("0.375"), "0.375");
        check(Customize.interp("0.25", "0.1", d("1"), false).equals("0.1"), "strength 1 is the designed value");
        check("18%".equals(Customize.follow("25%", "10%", d("0.5"))), "percent");
        check("55,000".equals(Customize.follow("10,000", "100,000", d("0.5"))), "thousands: " + Customize.follow("10,000", "100,000", d("0.5")));
        check("1,250 credits".equals(Customize.follow("1,000 credits", "1,500 credits", d("0.5"))), "grouping kept");
        check("x0.80".equals(Customize.follow("x0.75", "x0.85", d("0.5"))), "multiplier");
        check("false, 3".equals(Customize.follow("false, 4", "false, 3", d("0.6"))), "a number after a word");
        check(Customize.follow("fast", "slow", d("0.5")) == null, "a word cannot follow");
        check(Customize.follow("+5", "-5", d("0.5")) == null, "different text around the number cannot follow");
        check(Customize.follow("2 and 3", "4", d("0.5")) == null, "different number count cannot follow");
        Customize c = Customize.parse("all:strength = 0.8\nm:strength = 0.5\nm:a = 0.25\nm:b = off\nm:c = on\nn = off\n# m:d = 0\nm:e#2 = 60000  # mine\n");
        check(c.strength("m", "a").compareTo(d("0.25")) == 0, "change wins");
        check(c.strength("m", "b").signum() == 0, "off is 0");
        check(c.strength("m", "c").compareTo(d("0.5")) == 0, "on falls through to the mod");
        check(c.strength("m", "d").compareTo(d("0.5")) == 0, "commented line ignored");
        check(c.strength("x", "a").compareTo(d("0.8")) == 0, "all");
        check(c.modOff("n") && c.changeOff("n", "z"), "mod off");
        check(c.directsFor("m", "e").size() == 1 && c.directsFor("m", "e").get(0).value.equals("60000") && c.directsFor("m", "e").get(0).op == 2, "direct value, inline comment stripped");
        check(c.problems.isEmpty(), "no problems: " + c.problems);
    }

    // ------------------------------------------------------------------ every op type at 0.5

    static void halfEverything() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        cust(root, "fx_mod:strength = 0.5\n");
        check(run(root, "apply", "--dev") == 0, "apply: " + Tests.lastOut);
        check(Tests.lastOut.contains("Applied: Fuel halved  (strength 0.5)"), "strength shown: " + Tests.lastOut);
        Path m = mod(root);
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("fx_hull,225,"), "csv_cell 300 -> 150 at 0.5 is 225");
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("18% more"), "csv_cell_sub 25% -> 10% at 0.5 is 18%");
        check(read(m.resolve("data/config/settings.json")).contains("mult: 0.35,"), "json_value 0.50 -> 0.20 at 0.5: " + read(m.resolve("data/config/settings.json")));
        check(read(m.resolve("data/strings/names.txt")).startsWith("Café cost 45"), "line_sub 50 -> 40 at 0.5");
        check(read(m.resolve("data/campaign/rules.csv")).contains("\n#fx_rule,"), "csv_row_off is a switch: on above 0");
        check(read(m.resolve("data/config/LunaSettings.csv")).contains("fx_mult,Mult,Double,0.63"), "luna default 0.75 -> 0.5 at 0.5: " + read(m.resolve("data/config/LunaSettings.csv")));
        check(read(root.resolve("saves/common/LunaSettings/fx_mod.json.data")).contains("\"fx_mult\": 0.63"), "saved value follows the default");
        Path jar = m.resolve("jars/fx.jar");
        float mult = (Float) Tests.call(jar, "mult");
        check(mult == 0.18f, "float 0.25 -> 0.1 at 0.5: " + mult);
        check(((Integer) Tests.call(jar, "count", 9)) == 152, "int 3 -> 300 at 0.5");
        check(((Long) Tests.call(jar, "big")) == 35001L, "long 1 -> 70000 at 0.5");
        check(((Double) Tests.call(jar, "half")) == 0.375, "double 0.5 -> 0.25 at 0.5");
        String tip = (String) Tests.call(jar, "tip", 4);
        check(tip.equals("Gives +18% speed to 4 ships"), "tooltip: " + tip);
        check(tip.contains(Math.round(mult * 100) + "%"), "the code's number and the tooltip's agree");
        check(Tests.call(jar, "plain").equals("Range 20%"), "class_string 25% -> 15% at 0.5");
        check(!Tests.lastOut.contains("may not match its number"), "every text followed, no warning: " + Tests.lastOut);
        check(run(root, "status", "--dev") == 0 && Tests.lastOut.contains("strength 0.5]") && Tests.lastOut.contains("#0  300 -> 225"), "status shows strength and values: " + Tests.lastOut);
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity at 0.5");
    }

    static final String SWITCH_PATCH = "{\"format\":1,\"mod_id\":\"fx_mod\",\"mod_name\":\"Fx Mod\",\"tested_versions\":[\"1.0\"],\"changes\":[" +
            "{\"id\":\"words\",\"title\":\"Words\",\"ops\":[" +
            "{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_other\"},\"column\":\"desc\",\"expect\":\"plain\",\"set\":\"fancy\"}," +
            "{\"op\":\"csv_cell_sub\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"desc\",\"expect\":\"Fast hull, with\\n25% more \\\"speed\\\"\",\"subs\":[[\"Fast\",\"Slow\"],[\"25%\",\"10%\"]]}]}," +
            "{\"id\":\"row\",\"title\":\"Row\",\"ops\":[{\"op\":\"csv_row_off\",\"file\":\"data/campaign/rules.csv\",\"key\":{\"id\":\"fx_rule\"},\"expect\":" + Json.quote(Tests.RULES_ROW) + "}]}]}";

    static void switches() throws Exception {
        Path root = game("1.0");
        Files.writeString(root.resolve("patches/fx_mod.json"), SWITCH_PATCH);
        Map<String, String> before = hashes(root);
        cust(root, "all:strength = 0.5\n");
        check(run(root, "apply") == 0, "apply: " + Tests.lastOut);
        String csv = read(mod(root).resolve("data/hulls/ship_data.csv"));
        check(csv.contains("fx_other,300,fancy"), "a word swap is a switch, applied in full");
        check(csv.contains("Slow hull, with\r\n18% more"), "the word pair applies in full, the number pair follows: " + csv);
        check(Tests.lastOut.contains("may not match its number") && Tests.lastOut.contains("\"Fast\" to \"Slow\""), "warned about the text that cannot follow: " + Tests.lastOut);
        check(!Tests.lastOut.contains("(fx_mod:row#0)"), "no warning for a row switch");
        cust(root, "all:strength = 1\n");
        check(run(root, "apply") == 0 && !Tests.lastOut.contains("may not match"), "no warning at strength 1: " + Tests.lastOut);
        cust(root, "fx_mod:strength = 0\n");
        check(run(root, "apply") == 0 && Tests.lastOut.contains("Every change of this mod is switched off"), "strength 0 is off: " + Tests.lastOut);
        check(hashes(root).equals(before), "strength 0 put the mod back");
    }

    static void directs() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        cust(root, "fx_mod:c-class#0 = 0.3\nfx_mod:c-csv#1 = 12%\nfx_mod:c-class#9 = 1\nfx_mod:c-row#0 = 1\nfx_mod:c-class#1 = lots\n");
        check(run(root, "apply", "--dev") == 0, "apply: " + Tests.lastOut);
        Path m = mod(root);
        check(((Float) Tests.call(m.resolve("jars/fx.jar"), "mult")) == 0.3f, "class_const takes the direct value");
        check(read(m.resolve("data/hulls/ship_data.csv")).contains("12% more"), "a text op takes the direct value");
        check(((Integer) Tests.call(m.resolve("jars/fx.jar"), "count", 9)) == 300, "other ops as designed");
        check(Tests.lastOut.contains("Your own value is used in \"Code numbers\"") && Tests.lastOut.contains("#0  float 0.25 -> 0.3  (your value)") && Tests.lastOut.contains("#1  int 3 -> 300"), "lists the change's other ops: " + Tests.lastOut);
        check(Tests.lastOut.contains("has ops #0 to #5, so #9 was ignored"), "op number out of range warns: " + Tests.lastOut);
        check(Tests.lastOut.contains("switches a row off and has no value"), "row op has no value: " + Tests.lastOut);
        check(Tests.lastOut.contains("lots is not a number"), "a word for a number warns: " + Tests.lastOut);
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity");
    }

    static void unknownIds() throws Exception {
        Path root = game("1.0");
        cust(root, "fx_mdo = off\nfx_mod:c-jsno = off\nfx_mod:strength = 2\nnonsense line\nfx_mod:c-csv = maybe\n");
        check(run(root, "apply", "--dev") == 0, "a typo does not stop the run: " + Tests.lastOut);
        String o = Tests.lastOut;
        check(o.contains("Did you mean fx_mod?"), "unknown mod: " + o);
        check(o.contains("Did you mean fx_mod:c-json?"), "unknown change: " + o);
        check(o.contains("a strength must be from 0 to 1"), "out of range refused: " + o);
        check(o.contains("has no \"=\""), "bad line: " + o);
        check(o.contains("needs on, off, or a strength"), "bad value: " + o);
        check(o.contains("Applied: Fuel halved") && !o.contains("(strength"), "the refused strength left everything at 1");
        check(!o.contains("No warnings"), "warnings section present");
    }

    // ------------------------------------------------------------------ hash and file

    static void addDep(Path root) throws Exception {
        Path dep = root.resolve("mods").resolve("AaDep");
        Files.createDirectories(dep.resolve("data"));
        Files.writeString(dep.resolve("mod_info.json"), "{\"id\": \"aa_dep\", \"name\": \"Dep\", \"version\": \"2.0\"}");
        Files.writeString(dep.resolve("data/t.csv"), "id,v\na,10\n");
        Files.writeString(root.resolve("patches/aa_dep.json"), "{\"format\":1,\"mod_id\":\"aa_dep\",\"mod_name\":\"Dep\",\"tested_versions\":[\"2.0\"],\"changes\":[" +
                "{\"id\":\"v\",\"title\":\"V lower\",\"before\":\"10\",\"after\":\"2\",\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/t.csv\",\"key\":{\"id\":\"a\"},\"column\":\"v\",\"expect\":\"10\",\"set\":\"2\"}]}]}");
    }

    static void hashFollows() throws Exception {
        Path root = game("1.0");
        addDep(root);
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0, "apply");
        Path fx = root.resolve("mods/FxMod-1.0/survival_rebalance/applied.json");
        String h1 = read(fx);
        cust(root, "aa_dep:strength = 0.5\n");
        check(run(root, "apply", "--dev") == 0, "reapply: " + Tests.lastOut);
        String o = Tests.lastOut;
        check(o.substring(o.indexOf("== Dep")).contains("differ from the last apply"), "the customized mod is redone: " + o);
        check(o.substring(o.indexOf("== Fx Mod"), o.indexOf("== Dep") > o.indexOf("== Fx Mod") ? o.indexOf("== Dep") : o.length()).contains("Already applied."), "the other mod is untouched: " + o);
        check(read(root.resolve("mods/AaDep/data/t.csv")).equals("id,v\na,6\n"), "10 -> 2 at 0.5 is 6");
        check(read(fx).equals(h1), "fx_mod's record unchanged");
        check(read(root.resolve("mods/AaDep/survival_rebalance/applied.json")).contains("\"v strength 0.5\""), "customization recorded");
        cust(root, "aa_dep:strength = 0.5   # same, written differently\n\n");
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Already applied.") && !Tests.lastOut.contains("differ from"), "same effect, same hash: " + Tests.lastOut);
        Files.delete(root.resolve("tool").resolve(Customize.FILE));
        check(run(root, "apply", "--dev") == 0 && read(root.resolve("mods/AaDep/data/t.csv")).equals("id,v\na,2\n"), "back to as designed");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity");
    }

    static int customize(Path root, ByteArrayOutputStream b) throws Exception {
        b.reset();
        return Main.run(new String[]{"customize", "--tool-dir", root.resolve("tool").toString(), "--patches", root.resolve("patches").toString()}, new PrintStream(b, true, StandardCharsets.UTF_8));
    }

    static void customizeFile() throws Exception {
        Path root = game("1.0");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        Path f = root.resolve("tool").resolve(Customize.FILE);
        check(customize(root, b) == 0 && Files.isRegularFile(f), "written: " + b);
        String gen = read(f);
        check(gen.contains("# ==== Fx Mod  (fx_mod, 6 changes)") && gen.contains("# fx_mod = off") && gen.contains("# fx_mod:c-csv = 1") && gen.contains("#   #0  300 -> 150"), "lists mods, changes, ops: " + gen);
        check(gen.contains("\r\n"), "Notepad line ends");
        check(Customize.parse(gen).problems.isEmpty() && Customize.parse(gen).mods.isEmpty() && Customize.parse(gen).changes.isEmpty(), "the generated file changes nothing");
        byte[] mine = "fx_mod:c-csv = 0.5\r\n# my own note\r\nfx_mod:c-json = off".getBytes(StandardCharsets.UTF_8);
        Files.write(f, mine);
        addDep(root);
        check(customize(root, b) == 0, "append: " + b);
        byte[] now = Files.readAllBytes(f);
        check(now.length > mine.length && java.util.Arrays.equals(java.util.Arrays.copyOf(now, mine.length), mine), "the user's bytes are untouched");
        String added = new String(now, mine.length, now.length - mine.length, StandardCharsets.UTF_8);
        check(added.contains("# aa_dep:v = 1") && added.contains("# aa_dep = off"), "new mod appended: " + added);
        check(added.contains("# fx_mod:c-line = 1") && !added.contains("fx_mod:c-csv") && !added.contains("fx_mod:c-json") && !added.contains("# fx_mod = off"), "only changes the file did not mention: " + added);
        check(added.startsWith("\r\n") && !added.replace("\r\n", "").contains("\n"), "appended on a new line, in the file's line ends");
        check(customize(root, b) == 0 && b.toString(StandardCharsets.UTF_8).contains("Nothing was added") && java.util.Arrays.equals(Files.readAllBytes(f), now), "a second run adds nothing");
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("No warnings") && Tests.lastOut.contains("Fuel halved  (strength 0.5)") && !Tests.lastOut.contains("Mult lower"), "the file applies: " + Tests.lastOut);
    }

    static void lunaOff() throws Exception {
        Path root = game("1.0");
        Map<String, String> before = hashes(root);
        run(root, "apply", "--dev");
        Path sf = root.resolve("saves/common/LunaSettings/fx_mod.json.data");
        check(read(sf).contains("\"fx_mult\": 0.5"), "saved value set");
        cust(root, "fx_mod:c-luna = off\n");
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Saved setting restored: fx_mod fx_mult: 0.5 -> 0.75"), "switched off, saved value put back: " + Tests.lastOut);
        check(read(sf).contains("\"fx_mult\": 0.75"), "saved value back");
        check(!read(root.resolve("saves/common/survival_rebalance.json.data")).contains("fx_mult"), "record forgets it");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity");
    }

    // ------------------------------------------------------------------ warnings

    static int chk(Path root, String... extra) throws Exception { return run(root, "check", extra); }

    static void cleanAndCheck() throws Exception {
        Path root = game("1.0");
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.trim().endsWith("No warnings"), "a clean apply ends with No warnings: " + Tests.lastOut);
        check(chk(root, "--dev") == 0 && Tests.lastOut.trim().equals("No warnings"), "check prints only the section, exit 0: " + Tests.lastOut);
        Path logs = root.resolve("tool/logs");
        try (var s = Files.list(logs)) {
            Path last = s.filter(p -> p.toString().endsWith("_apply.log")).sorted().reduce((a, b2) -> b2).get();
            check(read(last).startsWith("No warnings"), "the log starts with the warnings section");
        }
        Path root2 = game("1.1");
        check(chk(root2, "--dev") == 2 && Tests.lastOut.contains("Fx Mod 1.1: skipped. Survival Rebalance was tested with version 1.0") && Tests.lastOut.contains("its author cannot help with it"), "another version: exit 2: " + Tests.lastOut);
        Path root3 = game("1.0");
        Files.writeString(root3.resolve("patches/fx_mod.json"), Tests.patchJson(true));
        check(chk(root3, "--dev") == 1 && Tests.lastOut.contains("\"Wrong\" (c-bad) did not match"), "a change that would fail: exit 1, names the change: " + Tests.lastOut);
        check(!Tests.lastOut.contains("== "), "check prints nothing but the warnings");
    }

    static void fakeGame(Path root, String version) throws Exception {
        Path core = root.resolve("starsector-core");
        Files.createDirectories(core);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(core.resolve("starfarer_obf.jar")))) {
            z.putNextEntry(new ZipEntry("com/fs/starfarer/Version.class"));
            z.write(("Êþº¾ junk " + version + " more").getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        Files.writeString(root.resolve("tool/manifest.json"), "{\"game_version\": \"0.98.5a-RC7\", \"checked_mods\": {}}");
    }

    static void gameVersion() throws Exception {
        Path root = game("1.0");
        fakeGame(root, "0.98.5a-RC7");
        check(chk(root, "--dev") == 2 && !Tests.lastOut.contains("tested on Starsector"), "same version: no game warning: " + Tests.lastOut);
        fakeGame(root, "0.99a-RC1");
        check(chk(root, "--dev") == 2 && Tests.lastOut.contains("Starsector 0.99a-RC1: the patches were tested on Starsector 0.98.5a-RC7"), "other version warns: " + Tests.lastOut);
        check(Checks.gameVersion(root).equals("0.99a-RC1"), "read from the jar");
    }

    static void updates() throws Exception {
        Path root = game("1.0");
        run(root, "apply", "--dev");
        check(chk(root, "--dev") == 0, "patched and clean: " + Tests.lastOut);
        Path m = mod(root);
        Files2.deleteTree(m);
        Tests.writeMod(m, "1.0");
        check(chk(root, "--dev") == 2 && Tests.lastOut.contains("folder was replaced since it was patched (at version 1.0), so it is not patched now") && Tests.lastOut.contains("Run apply to patch the new folder"), "replaced: " + Tests.lastOut);
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("folder was replaced since it was patched"), "apply says so and patches it: " + Tests.lastOut);
        Files.writeString(m.resolve("data/config/settings.json"), "{ updated: 1 }");
        check(chk(root, "--dev") == 1 && Tests.lastOut.contains("updated in place since it was patched (changed: data/config/settings.json)") && Tests.lastOut.contains("install it again cleanly"), "in place: " + Tests.lastOut);
        Path root2 = game("1.0");
        run(root2, "apply", "--dev");
        Path mi = mod(root2).resolve("mod_info.json");
        Files.writeString(mi, read(mi).replace("{major:1, minor:0}", "\"1.0.1\""));
        check(chk(root2, "--dev") == 2 && Tests.lastOut.contains("Fx Mod 1.0.1: skipped."), "version changed, files untouched: the version rule: " + Tests.lastOut);
    }

    static void stale() throws Exception {
        Path root = game("1.0");
        run(root, "apply", "--dev");
        cust(root, "fx_mod:c-json = off\n");
        check(chk(root, "--dev") == 2 && Tests.lastOut.contains("patched with a different customization") && Tests.lastOut.contains("Run apply"), "customization differs: " + Tests.lastOut);
        Files.delete(root.resolve("tool").resolve(Customize.FILE));
        check(chk(root, "--dev") == 0, "back to the same: " + Tests.lastOut);
        Path pf = root.resolve("patches/fx_mod.json");
        Files.writeString(pf, read(pf).replace("\"Fuel halved\"", "\"Fuel cut\""));
        check(chk(root, "--dev") == 2 && Tests.lastOut.contains("patched with another patch set"), "another patch set: " + Tests.lastOut);
    }

    static void misc() throws Exception {
        // saved setting changed in the game, and a key the saved file lacks
        Path root = game("1.0");
        run(root, "apply", "--dev");
        Path sf = root.resolve("saves/common/LunaSettings/fx_mod.json.data");
        Files.writeString(sf, read(sf).replace("\"fx_mult\": 0.5", "\"fx_mult\": 2"));
        check(chk(root, "--dev") == 2 && Tests.lastOut.contains("saved setting fx_mult holds 2, not the 0.5 the tool set"), "drift: " + Tests.lastOut);
        Path root2 = game("1.0");
        Path sf2 = root2.resolve("saves/common/LunaSettings/fx_mod.json.data");
        Files.writeString(sf2, "{\n   \"fx_flag\": true\n}");
        check(run(root2, "apply", "--dev") == 0 && Tests.lastOut.contains("names the setting fx_mult, which the saved settings file does not hold"), "missing key: " + Tests.lastOut);
        // backups
        Path bk = mod(root2).resolve("survival_rebalance/backup/data/config/settings.json.orig");
        Files.writeString(bk, "damaged");
        check(chk(root2, "--dev") != 0 && Tests.lastOut.contains("the backup of data/config/settings.json (damaged)"), "damaged backup: " + Tests.lastOut);
        Files.delete(bk);
        check(chk(root2, "--dev") != 0 && Tests.lastOut.contains("the backup of data/config/settings.json (missing)"), "missing backup: " + Tests.lastOut);
        // requires
        Path root3 = game("1.0");
        Files.writeString(root3.resolve("patches/fx_mod.json"), "{\"format\":1,\"mod_id\":\"fx_mod\",\"mod_name\":\"Fx Mod\",\"tested_versions\":[\"1.0\"],\"changes\":[" +
                "{\"id\":\"n\",\"title\":\"Needs more\",\"requires\":[\"no_such_mod:x\"],\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_hull\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"150\"}]}," +
                "{\"id\":\"o\",\"title\":\"Other\",\"ops\":[{\"op\":\"csv_cell\",\"file\":\"data/hulls/ship_data.csv\",\"key\":{\"id\":\"fx_other\"},\"column\":\"fuel\",\"expect\":\"300\",\"set\":\"100\"}]}]}");
        check(run(root3, "apply") == 0 && Tests.lastOut.contains("the change \"Needs more\" is skipped: it needs no_such_mod:x, which is not applied."), "skipped requires warns: " + Tests.lastOut);
        // locked
        Path root4 = game("1.0");
        try (FileChannel c = FileChannel.open(mod(root4).resolve("jars/fx.jar"), StandardOpenOption.WRITE); FileLock l = c.lock()) {
            // the game-running check does not see a game here, so the words name another program, Starsector most likely
            check(run(root4, "apply", "--dev") == 1 && Tests.lastOut.contains("Fx Mod 1.0: another program has a file open that the tool must read.") && Tests.lastOut.contains("most likely Starsector")
                    && !Tests.lastOut.contains("Starsector is running"), "locked warns: " + Tests.lastOut);
            check(chk(root4, "--dev") != 0 && Tests.lastOut.contains("another program has a file open that the tool must read"), "check sees the lock too: " + Tests.lastOut);
        }
        // old disabled_changes.txt
        Files.writeString(root4.resolve("tool/disabled_changes.txt"), "fx_mod\n");
        check(chk(root4, "--dev") == 2 && Tests.lastOut.contains("disabled_changes.txt: this file is no longer read"), "old file warns: " + Tests.lastOut);
    }

    static void coverage() throws Exception {
        Path root = game("1.0");
        for (String id : new String[]{"other_mod", "checked_mod"}) {
            Path d = root.resolve("mods").resolve(id);
            Files.createDirectories(d);
            Files.writeString(d.resolve("mod_info.json"), "{\"id\": \"" + id + "\", \"name\": \" Name " + id + " \", \"version\": \"1\"}");
        }
        Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\", \"other_mod\", \"checked_mod\", \"absent_mod\"]}");
        Files.writeString(root.resolve("patches/zz_gone.json"), "{\"format\":1,\"mod_id\":\"zz_gone\",\"tested_versions\":[\"1\"],\"changes\":[]}");
        Files.writeString(root.resolve("tool/manifest.json"), "{\"game_version\": \"0.98.5a-RC7\", \"checked_mods\": {\"checked_mod\": \"1\"}}");
        check(run(root, "apply", "--dev") == 0, "apply: " + Tests.lastOut);
        String o = Tests.lastOut;
        check(o.contains("Name other_mod 1: not supported. It has not been checked for Survival Mode, so it stays as it is."), "not supported, one line with its name: " + o);
        check(!o.contains("checked_mod)"), "a checked mod is not listed");
        check(o.contains("Note: 1 patch is for a mod you do not have."), "not installed note: " + o);
        check(o.contains("Note: the Survival Rebalance Monitor is not installed"), "companion note: " + o);
        Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\", \"other_mod\", \"checked_mod\"]}");
        Files.writeString(root.resolve("tool/manifest.json"), "{\"game_version\": \"0.98.5a-RC7\", \"checked_mods\": {\"checked_mod\": \"1\", \"other_mod\": \"1\"}}");
        check(run(root, "apply", "--dev") == 0 && !Tests.lastOut.contains("not supported"), "all covered: " + Tests.lastOut);
    }

    static void monitor() throws Exception {
        Path root = game("1.0");
        Path src = root.resolve("tool").resolve(Checks.MONITOR_DIR);
        Files.createDirectories(src.resolve("data/config"));
        Files.writeString(src.resolve("mod_info.json"), "{\"id\": \"survival_rebalance_monitor\", \"name\": \"Survival Rebalance Monitor\", \"version\": \"1.0.0\", \"utility\": true}");
        Files.writeString(src.resolve("data/config/survival_rebalance_index.json"), "{\"mods\": []}");
        Files.writeString(root.resolve("mods/enabled_mods.json"), "{\"enabledMods\": [\"fx_mod\"]}");
        Map<String, String> before = hashes(root);
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Installed.") && Tests.lastOut.contains("Enable \"Survival Rebalance Monitor\" in the Starsector launcher"), "installed: " + Tests.lastOut);
        Path dst = root.resolve("mods").resolve(Checks.MONITOR_DIR);
        check(Files.isRegularFile(dst.resolve("data/config/survival_rebalance_index.json")) && Files.isRegularFile(dst.resolve(Checks.MONITOR_MARK)), "copied with a marker");
        check(Tests.lastOut.contains("installed but not enabled"), "not enabled note: " + Tests.lastOut);
        check(read(root.resolve("mods/enabled_mods.json")).equals("{\"enabledMods\": [\"fx_mod\"]}"), "enabled_mods.json untouched");
        check(run(root, "apply", "--dev") == 0 && !Tests.lastOut.contains("Survival Rebalance Monitor 1.0.0"), "same version: left alone: " + Tests.lastOut);
        Files.writeString(src.resolve("mod_info.json"), read(src.resolve("mod_info.json")).replace("1.0.0", "1.1.0"));
        // an older installed monitor: status, check and the window say so, not only apply
        run(root, "status", "--dev");
        check(Tests.lastOut.contains("older than the 1.1.0 this tool ships"), "status says the monitor is older: " + Tests.lastOut);
        check(chk(root, "--dev") == 2 && Tests.lastOut.contains("older than the 1.1.0 this tool ships"), "check says it too: " + Tests.lastOut);
        check(run(root, "status", "--json", "--dev") == 0 && Tests.lastOut.contains("\"monitor_shipped_newer\": \"1.1.0\"")
                && Tests.lastOut.contains("Click Apply; it updates the monitor."), "the window gets it: " + Tests.lastOut);
        check(run(root, "apply", "--dev") == 0 && Tests.lastOut.contains("Updated from 1.0.0"), "newer copy replaces it: " + Tests.lastOut);
        run(root, "status", "--dev");
        check(!Tests.lastOut.contains("older than the"), "an up to date monitor is not reported: " + Tests.lastOut);
        check(run(root, "undo") == 0 && Tests.lastOut.contains("Removed it (the tool installed it)"), "undo removes it: " + Tests.lastOut);
        check(hashes(root).equals(before), "undo identity with the companion");
        // one the user installed is never removed
        Checks.copyTree(src, dst);
        check(run(root, "undo") == 0 && Files.isDirectory(dst), "a user-installed monitor stays");
        check(Tests.lastOut.contains(Engine.MONITOR_LEFT), "undo says it left the monitor and why: " + Tests.lastOut);
        check(survivalrebalance.gui.RunReport.summary("Undo", Tests.lastOut, 0).contains("still in the mods folder, because the tool did not install that copy"), "the window says it too");
        check(run(root, "undo", "--only", "fx_mod") == 0 && Files.isDirectory(dst) && Tests.lastOut.contains(Engine.MONITOR_KEPT_PARTIAL), "a partial undo says it keeps the monitor: " + Tests.lastOut);
        Path idx = dst.resolve("data/config/survival_rebalance_index.json");
        check(read(idx).contains("\"id\": \"fx_mod\"") && read(idx).contains("\"state\": \"off\""), "undo writes every entry off: " + read(idx));
        // apply writes each mod's state from this run into the installed index
        check(run(root, "apply", "--dev") == 0 && read(idx).contains("\"state\": \"patched\"") && read(idx).contains("\"changes\": 6"), "patched: " + read(idx));
        cust(root, "fx_mod = off\n");
        check(run(root, "apply", "--dev") == 0 && read(idx).contains("\"state\": \"off\""), "switched off: " + read(idx));
        Files.delete(root.resolve("tool").resolve(Customize.FILE));
        check(run(root, "apply", "--dev", "--skip", "fx_mod") == 0 && read(idx).contains("\"state\": \"off\""), "left out by --skip: " + read(idx));
        Files.writeString(root.resolve("patches/fx_mod.json"), Tests.patchJson(true));
        check(run(root, "apply", "--dev") == 1 && read(idx).contains("\"state\": \"failed\""), "failed: " + read(idx));
    }

    /** The two applied.json fields and the version normalization the monitor relies on. */
    static void monitorContract() throws Exception {
        Path root = game("1.0");
        run(root, "apply", "--dev");
        Json.Obj s = (Json.Obj) Json.parse(read(mod(root).resolve("survival_rebalance/applied.json")));
        check("applied".equals(s.str("status")), "status field");
        check("1.0".equals(s.str("mod_version")), "mod_version of {major:1, minor:0} is 1.0");
        check(ModInfo.normalizeVersion(Json.parse("{\"major\":1,\"minor\":15,\"patch\":2}")).equals("1.15.2"), "object version joined with dots");
        check(ModInfo.normalizeVersion(Json.parse("{major:2, minor:\"1b\"}")).equals("2.1b"), "missing patch skipped");
        check(ModInfo.normalizeVersion(Json.parse("\"0.5.1a\"")).equals("0.5.1a"), "a string version as written");
    }

    static void lunaTypes() throws Exception {
        Path root = game("1.0");
        Path m = mod(root);
        Files.writeString(m.resolve("data/config/LunaSettings.csv"), "fieldID,fieldName,fieldType,defaultValue\nfx_mult,Mult,Double,1\nfx_n,N,Int,1\n");
        Files.writeString(root.resolve("saves/common/LunaSettings/fx_mod.json.data"), "{\n   \"fx_mult\": 1,\n   \"fx_n\": 1\n}");
        Files.writeString(root.resolve("patches/fx_mod.json"), "{\"format\":1,\"mod_id\":\"fx_mod\",\"tested_versions\":[\"1.0\"],\"changes\":[" +
                "{\"id\":\"l\",\"title\":\"Luna\",\"ops\":[{\"op\":\"luna\",\"key\":\"fx_mult\",\"expect_default\":\"1\",\"set\":\"2\"},{\"op\":\"luna\",\"key\":\"fx_n\",\"expect_default\":\"1\",\"set\":\"2\"}]}]}");
        Map<String, String> before = hashes(root);
        cust(root, "all:strength = 0.5\n");
        check(run(root, "apply") == 0, "apply: " + Tests.lastOut);
        String csv = read(m.resolve("data/config/LunaSettings.csv"));
        check(csv.contains("fx_mult,Mult,Double,1.5") && csv.contains("fx_n,N,Int,2"), "Double 1 -> 2 at 0.5 is 1.5, Int rounds to 2: " + csv);
        check(read(root.resolve("saves/common/LunaSettings/fx_mod.json.data")).contains("\"fx_mult\": 1.5"), "saved value too");
        check(run(root, "undo") == 0 && hashes(root).equals(before), "undo identity");
    }
}