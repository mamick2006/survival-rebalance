package survivalrebalance.monitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.json.JSONObject;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModManagerAPI;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * Checks, once at application load (log only) and once per game load, that every enabled mod in
 * the patch list still carries the patch. Nothing reaches the save: the only thing added to the
 * sector is a transient script that posts one message and ends.
 */
public class MonitorModPlugin extends BaseModPlugin {

    static final String MOD_ID = "survival_rebalance_monitor";
    private static final Logger LOG = Global.getLogger(MonitorModPlugin.class);

    @Override
    public void onApplicationLoad() {
        run();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        Check.Result r = run();
        if (r != null && !r.ok()) Global.getSector().addTransientScript(new Notice(r.message()));
    }

    /** Reads everything, logs the outcome, and returns it; null when there is no patch list. */
    private static Check.Result run() {
        try {
            SettingsAPI settings = Global.getSettings();
            List<Check.Entry> index;
            boolean strict = false;
            try {
                JSONObject doc = settings.loadJSON(Check.INDEX_PATH, MOD_ID);
                index = Check.parseIndex(doc);
                strict = Check.skipsOtherVersions(doc);
            } catch (Exception e) {
                index = null;
            }
            if (index == null || index.isEmpty()) {
                LOG.info("Survival Rebalance Monitor: no patch list in " + Check.INDEX_PATH + ", nothing to check.");
                return null;
            }

            ModManagerAPI mods = settings.getModManager();
            Map<String, Check.Installed> enabled = new HashMap<>();
            Map<String, Check.Applied> applied = new HashMap<>();
            for (Check.Entry e : index) {
                if (e.state == Check.State.OFF || !mods.isModEnabled(e.id)) continue;
                ModSpecAPI spec = mods.getModSpec(e.id);
                enabled.put(e.id, new Check.Installed(spec == null ? null : spec.getName(), installedVersion(settings, e.id, spec)));
                if (Check.needsApplied(e)) applied.put(e.id, readApplied(settings, e.id));
            }

            Check.Result r = Check.decide(index, enabled, applied, strict);
            boolean quiet = r.ok() && r.failed.isEmpty();
            for (String l : r.logLines()) {
                String line = "Survival Rebalance Monitor: " + l;
                if (quiet) LOG.info(line); else LOG.warn(line);
            }
            return r;
        } catch (Throwable t) {
            LOG.warn("Survival Rebalance Monitor: the check failed and was skipped, so no warning can be shown this load.", t);
            return null;
        }
    }

    /** The version in the mod's mod_info.json, normalized the way the tool records it. */
    private static String installedVersion(SettingsAPI settings, String modId, ModSpecAPI spec) {
        try {
            JSONObject info = settings.loadJSON("mod_info.json", modId);
            return Check.normalizeVersion(info.opt("version"));
        } catch (Exception e) {
            return spec == null || spec.getVersion() == null ? "?" : spec.getVersion();
        }
    }

    /** Null when the file is not there; Applied.UNREADABLE when it is there but does not parse. */
    private static Check.Applied readApplied(SettingsAPI settings, String modId) {
        String text;
        try {
            text = settings.loadText(Check.APPLIED_PATH, modId);
        } catch (Exception e) {
            return null;
        }
        if (text == null) return null;
        try {
            return Check.parseApplied(new JSONObject(text));
        } catch (Exception e) {
            return Check.Applied.UNREADABLE;
        }
    }

    /** Posts the message once the campaign UI exists, then ends. Added with addTransientScript. */
    private static final class Notice implements EveryFrameScript {
        private final String text;
        private boolean done;

        Notice(String text) { this.text = text; }

        @Override public boolean isDone() { return done; }
        @Override public boolean runWhilePaused() { return true; }

        @Override
        public void advance(float amount) {
            if (done) return;
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null) return;
            ui.addMessage(text, Misc.getNegativeHighlightColor());
            done = true;
        }
    }
}
