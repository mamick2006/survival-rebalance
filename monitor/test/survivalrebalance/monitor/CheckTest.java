package survivalrebalance.monitor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/** The monitor's one test: Check over the fixtures in test/fixtures. Run by build.bat. */
public final class CheckTest {

    private static int failed;
    private static Path fx;

    public static void main(String[] args) throws Exception {
        fx = Paths.get(args.length > 0 ? args[0] : "test/fixtures");
        List<Check.Entry> index = Check.parseIndex(json("index.json"));

        check("index keeps entries with an id and drops the rest", index.size() == 3);
        check("index name falls back to null when blank", index.get(2).name == null);
        check("no index means an empty list", Check.parseIndex(null).isEmpty());
        check("index without mods means an empty list", Check.parseIndex(new JSONObject("{}")).isEmpty());

        check("string version is kept", "1.2.0".equals(Check.normalizeVersion(json("mod_info_string.json").opt("version"))));
        check("object version is major.minor.patch",
                "2.0.1a".equals(Check.normalizeVersion(json("mod_info_object.json").opt("version"))));
        check("missing version is ?", "?".equals(Check.normalizeVersion(null)));

        Check.Applied ok = Check.parseApplied(json("applied_ok.json"));
        Check.Applied applying = Check.parseApplied(json("applied_applying.json"));
        check("applied.json fields read", "applied".equals(ok.status) && "1.2.0".equals(ok.modVersion));

        // All patched: nothing in the game, one log line.
        Map<String, Check.Installed> on = new HashMap<>();
        on.put("alpha", new Check.Installed("Alpha Mod", "1.2.0"));
        on.put("beta", new Check.Installed("Beta Mod", "1.2.0"));
        Map<String, Check.Applied> st = new HashMap<>();
        st.put("alpha", ok);
        st.put("beta", ok);
        Check.Result r = Check.decide(index, on, st);
        check("all patched is ok", r.ok());
        check("all patched has no message", r.message() == null);
        check("all patched logs one line", r.logLines().size() == 1
                && r.logLines().get(0).equals("3 mods in the patch list, 2 of them enabled, all patched."));

        // A listed mod that is not enabled is ignored, even with no applied.json.
        check("disabled mod is ignored", r.enabled == 2);

        // One missing applied.json.
        st.remove("beta");
        r = Check.decide(index, on, st);
        check("missing applied.json is not patched", one(r, "beta", Check.Problem.NOT_PATCHED));
        expect("single message", r.message(),
                "Survival Rebalance: Beta Mod is not patched. Close the game, open SurvivalRebalance.exe and press Apply.");
        check("log has the summary and one line per mod", r.logLines().size() == 2);

        // Updated in place: applied.json survived but the version moved.
        on.put("beta", new Check.Installed("Beta Mod", "1.3.0"));
        st.put("beta", ok);
        r = Check.decide(index, on, st);
        check("version change is updated", one(r, "beta", Check.Problem.UPDATED));
        check("updated log names both versions", r.logLines().get(1).contains("1.3.0") && r.logLines().get(1).contains("1.2.0"));

        // Interrupted run and an unreadable file.
        st.put("alpha", applying);
        st.put("beta", Check.Applied.UNREADABLE);
        on.put("beta", new Check.Installed("Beta Mod", "1.2.0"));
        r = Check.decide(index, on, st);
        check("applying is interrupted", r.findings.size() == 2 && r.findings.get(0).problem == Check.Problem.INTERRUPTED);
        check("unparsable file is unreadable", r.findings.get(1).problem == Check.Problem.UNREADABLE);
        expect("two names", r.message(),
                "Survival Rebalance: 2 mods are not patched (Index Alpha and Beta Mod). Close the game, open SurvivalRebalance.exe and press Apply.");

        // Name fallback: index name, then the mod's own name, then the id.
        on.put("gamma", new Check.Installed(null, "1.0"));
        r = Check.decide(index, on, new HashMap<>());
        check("name falls back to the id", r.findings.get(2).name.equals("gamma"));

        // Many mods: five names, then a count.
        List<Check.Entry> many = new ArrayList<>();
        Map<String, Check.Installed> manyOn = new HashMap<>();
        for (int i = 1; i <= 8; i++) {
            many.add(new Check.Entry("m" + i, "Mod " + i));
            manyOn.put("m" + i, new Check.Installed(null, "1"));
        }
        r = Check.decide(many, manyOn, new HashMap<>());
        expect("eight names", r.message(),
                "Survival Rebalance: 8 mods are not patched (Mod 1, Mod 2, Mod 3, Mod 4, Mod 5 and 3 more). Close the game, open SurvivalRebalance.exe and press Apply.");
        check("log lists all eight", r.logLines().size() == 9);

        // States from the last apply: patched (and no state) is checked, off is ignored, failed is logged.
        List<Check.Entry> states = Check.parseIndex(json("index_states.json"));
        check("states parse", states.size() == 5
                && states.get(0).state == Check.State.PATCHED && states.get(1).state == Check.State.PATCHED
                && states.get(2).state == Check.State.OFF && states.get(3).state == Check.State.FAILED
                && states.get(4).state == Check.State.PATCHED);
        check("only patched entries need applied.json",
                Check.needsApplied(states.get(0)) && !Check.needsApplied(states.get(2)) && !Check.needsApplied(states.get(3)));
        Map<String, Check.Installed> all = new HashMap<>();
        for (String id : new String[]{"pat", "nostate", "offmod", "failmod", "odd"}) all.put(id, new Check.Installed(null, "1.0"));
        Map<String, Check.Applied> good = new HashMap<>();
        Check.Applied ok10 = new Check.Applied("applied", "1.0", false);
        good.put("pat", ok10);
        good.put("nostate", ok10);
        good.put("odd", ok10);

        // Only a failed mod: nothing in the game, a warn line in the log.
        r = Check.decide(states, all, good);
        check("off mod is ignored and not counted", r.enabled == 4);
        check("failed alone is ok in the game", r.ok() && r.message() == null);
        check("failed alone is logged", r.failed.size() == 1 && r.logLines().size() == 2
                && r.logLines().get(0).equals("5 mods in the patch list, 4 of them enabled, 0 not patched, 1 refused by the last apply:")
                && r.logLines().get(1).startsWith("  Failing Mod (failmod) 1.0: the last apply could not patch it"));

        // A failed mod beside a real problem: the second sentence joins the message.
        good.remove("nostate");
        r = Check.decide(states, all, good);
        expect("failed joins a real problem", r.message(),
                "Survival Rebalance: nostate is not patched. Close the game, open SurvivalRebalance.exe and press Apply."
                        + " The last apply could not patch Failing Mod.");
        check("log has both", r.logLines().size() == 3);

        // An off mod with no applied.json never warns.
        good.put("nostate", ok10);
        Map<String, Check.Applied> offMissing = new HashMap<>(good);
        offMissing.remove("offmod");
        r = Check.decide(states, all, offMissing);
        check("off mod with no applied.json is silent", r.ok() && r.findings.isEmpty());

        // After Undo every entry is off: the log must not say "all patched".
        List<Check.Entry> undone = new ArrayList<>();
        for (Check.Entry e : states) undone.add(new Check.Entry(e.id, e.name, Check.State.OFF));
        r = Check.decide(undone, all, new HashMap<>());
        check("undone is quiet in the game", r.ok() && r.message() == null);
        expect("undone log says none patched", String.join("|", r.logLines()),
                "5 mods in the patch list, 5 of them enabled, none of them patched (Survival Rebalance was undone, or they are switched off).");
        r = Check.decide(undone, new HashMap<>(), new HashMap<>());
        expect("nothing enabled log", String.join("|", r.logLines()), "5 mods in the patch list, none of them enabled.");

        // Any status other than applying or applied, a missing one included, counts as damaged.
        good.put("pat", new Check.Applied("restoring", "1.0", false));
        good.put("odd", Check.parseApplied(new JSONObject("{\"mod_version\": \"1.0\"}")));
        r = Check.decide(states, all, good);
        check("unknown and missing status are damaged", r.findings.size() == 2
                && r.findings.get(0).problem == Check.Problem.UNREADABLE
                && r.findings.get(1).problem == Check.Problem.UNREADABLE);

        // Another version: the released tool skips it, so the message must not say press Apply.
        org.json.JSONObject sk = json("index_skipped.json");
        List<Check.Entry> skIndex = Check.parseIndex(sk);
        check("skipped state parses", skIndex.get(1).state == Check.State.SKIPPED && Check.needsApplied(skIndex.get(1)));
        check("tested versions parse", skIndex.get(1).tested.size() == 2 && skIndex.get(1).tested.get(1).equals("1.0.1"));
        check("index says the tool skips other versions", Check.skipsOtherVersions(sk) && !Check.skipsOtherVersions(json("index.json")));
        Map<String, Check.Installed> skOn = new HashMap<>();
        skOn.put("pat", new Check.Installed(null, "1.0"));
        skOn.put("skip", new Check.Installed(null, "1.2"));
        Map<String, Check.Applied> skAp = new HashMap<>();
        skAp.put("pat", ok10);
        r = Check.decide(skIndex, skOn, skAp, true);
        check("a skipped mod is reported as skipped, not as not patched", r.findings.isEmpty() && r.skipped.size() == 1
                && r.skipped.get(0).problem == Check.Problem.OTHER_VERSION && !r.ok());
        expect("one skipped mod", r.message(),
                "Survival Rebalance: Skipped Mod is version 1.2, and Survival Rebalance supports 1.0 or 1.0.1, so it is not rebalanced."
                        + " This is not a bug in Skipped Mod. Look for a Survival Rebalance update.");
        check("the skip message never says press Apply", !r.message().contains("Apply"));
        check("skip log line", r.logLines().size() == 2
                && r.logLines().get(0).equals("3 mods in the patch list, 2 of them enabled, 0 not patched, 1 skipped for another version:")
                && r.logLines().get(1).contains("supports 1.0 or 1.0.1, so apply skips this version"));

        skOn.put("skip2", new Check.Installed(null, "2.1"));
        r = Check.decide(skIndex, skOn, skAp, true);
        expect("two skipped mods", r.message(),
                "Survival Rebalance: 2 mods are not rebalanced, because Survival Rebalance does not support the versions you have:"
                        + " Skipped Mod 1.2 (supports 1.0 or 1.0.1) and Second Mod 2.1 (supports 2.0). This is not a bug in those mods. Look for a Survival Rebalance update.");

        // A skipped mod the player has since put on a tested version: Apply would patch it now.
        skOn.remove("skip2");
        skOn.put("skip", new Check.Installed(null, "1.0.1"));
        r = Check.decide(skIndex, skOn, skAp, true);
        check("skipped, now at a tested version, is not patched", r.skipped.isEmpty() && one(r, "skip", Check.Problem.NOT_PATCHED));

        // A patched mod replaced by another version in a clean folder: the strict tool would skip it.
        skOn.put("skip", new Check.Installed(null, "1.0"));
        skOn.put("pat", new Check.Installed(null, "1.1"));
        skAp.put("skip", ok10);
        r = Check.decide(skIndex, skOn, skAp, true);
        check("replaced by another version: skipped, not press Apply", r.findings.isEmpty() && r.skipped.size() == 1 && r.skipped.get(0).id.equals("pat"));
        r = Check.decide(skIndex, skOn, skAp, false);
        check("a lenient tool's index keeps the old answer", r.skipped.isEmpty() && one(r, "pat", Check.Problem.UPDATED));

        // A real problem and a skip in one message: Apply for the first, an update for the second.
        skOn.put("pat", new Check.Installed(null, "1.0"));
        skAp.remove("pat");
        skOn.put("skip2", new Check.Installed(null, "2.1"));
        r = Check.decide(skIndex, skOn, skAp, true);
        expect("not patched and skipped", r.message(),
                "Survival Rebalance: Patched Mod is not patched. Close the game, open SurvivalRebalance.exe and press Apply."
                        + " Second Mod is version 2.1, and Survival Rebalance supports 2.0, so it is not rebalanced. This is not a bug in Second Mod. Look for a Survival Rebalance update.");

        if (failed > 0) {
            System.out.println("monitor test: " + failed + " FAILED");
            System.exit(1);
        }
        System.out.println("monitor test: all passed");
    }

    private static boolean one(Check.Result r, String id, Check.Problem p) {
        return r.findings.size() == 1 && r.findings.get(0).id.equals(id) && r.findings.get(0).problem == p;
    }

    private static JSONObject json(String name) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(fx.resolve(name)), StandardCharsets.UTF_8));
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + what);
        }
    }

    private static void expect(String what, String got, String want) {
        if (!want.equals(got)) {
            failed++;
            System.out.println("FAIL: " + what + "\n  want: " + want + "\n  got:  " + got);
        }
    }
}
