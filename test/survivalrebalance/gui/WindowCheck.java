package survivalrebalance.gui;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The window after a switch that leaves one mod another version rebalanced and this one does not
 * change: Apply is enabled and says what it does, pressing it puts the mod back, and then Apply
 * and Undo have nothing left. It drives the real window on the real jar (child processes), and
 * takes a picture of each step.
 */
public final class WindowCheck {
    private WindowCheck() {}

    static Window w;
    static final List<String> told = new ArrayList<>();

    /** Null when everything holds, else what did not. */
    public static String afterSwitch(Path tool, Path jar, Path game, Path shots) throws Exception {
        Files.createDirectories(shots);
        told.clear();
        SwingUtilities.invokeAndWait(() -> {
            w = new Window(tool, jar, game);
            w.prompter = new Window.Prompter() {
                public boolean confirm(java.awt.Component parent, String title, String message, String yes) { told.add(title + ": " + message); return true; }
                public void tell(java.awt.Component parent, String title, String message, int type) { told.add(title + ": " + message); }
            };
            w.gameRunning = () -> false;
            w.frame.setSize(1170, 760);
            w.frame.setVisible(true);
        });
        try {
            refresh();
            shot(shots, "window_after_switch");
            String[] state = new String[6];
            edt(() -> {
                state[0] = String.valueOf(w.apply.isEnabled());
                state[1] = w.headline.getText();
                state[2] = w.apply.getText();
                for (Window.Row r : w.model.rows) if ("sol_mod".equals(r.id) && r.mod != null) { state[3] = r.label; state[4] = String.valueOf(r.mod.get("state_text")); }
                state[5] = w.warnings.getText();
            });
            if (!"true".equals(state[0])) return "Apply is greyed out after the switch: " + state[1];
            if (!state[1].contains("Apply puts back the original files of Sol Mod")) return "the headline does not say what Apply does: " + state[1];
            if (!"Rebalanced by another version".equals(state[3]) || state[4].contains("needs no change") || !state[4].contains("Click Apply")) return "the row: " + state[3] + " / " + state[4];
            if (!state[5].contains("Click Apply; it puts this mod's original files back.")) return "the warning: " + state[5];
            select("sol_mod");
            shot(shots, "window_after_switch_selected");

            CountDownLatch done = new CountDownLatch(1), again = new CountDownLatch(1);
            edt(() -> { w.afterRun = done::countDown; w.afterRefresh = again::countDown; });
            edt(() -> w.apply());
            if (!done.await(120, TimeUnit.SECONDS)) return "Apply did not finish; asked: " + told;
            if (!again.await(120, TimeUnit.SECONDS)) return "no refresh after Apply";
            Thread.sleep(200);
            edt(() -> { if (w.lastRun != null) w.lastRun.dispose(); });
            String asked = String.join("\n", told);
            if (!asked.contains("Put back the original files of 1 mod?")) return "the confirm text: " + asked;
            edt(() -> {});
            shot(shots, "window_after_apply");
            edt(() -> {
                state[0] = String.valueOf(w.apply.isEnabled());
                state[1] = String.valueOf(w.undo.isEnabled());
                state[2] = w.headline.getText();
            });
            if (!"false".equals(state[0]) || !"false".equals(state[1])) return "after Apply the buttons still have work: apply " + state[0] + ", undo " + state[1] + ": " + state[2];
            return null;
        } finally {
            edt(() -> w.frame.dispose());
        }
    }

    /**
     * Every mod up to date and a file another version left in the tool folder: Apply is lit and
     * the note says what it does, the confirm asks it, Apply removes the file, and afterwards the
     * note is gone and Apply has nothing left. Null when everything holds, else what did not.
     */
    public static String tidyOnly(Path tool, Path jar, Path game, Path shots) throws Exception {
        Files.createDirectories(shots);
        told.clear();
        SwingUtilities.invokeAndWait(() -> {
            w = new Window(tool, jar, game);
            w.prompter = new Window.Prompter() {
                public boolean confirm(java.awt.Component parent, String title, String message, String yes) { told.add(title + ": " + message); return true; }
                public void tell(java.awt.Component parent, String title, String message, int type) { told.add(title + ": " + message); }
            };
            w.gameRunning = () -> false;
            w.frame.setSize(1170, 760);
            w.frame.setVisible(true);
        });
        try {
            refresh();
            shot(shots, "window_tidy_before");
            String[] state = new String[3];
            edt(() -> { state[0] = String.valueOf(w.apply.isEnabled()); state[1] = w.warnings.getText(); state[2] = w.headline.getText(); });
            if (!state[1].contains("Apply will remove the files another version of the tool left in its folder (app\\old_only.txt)")) return "the note: " + state[1];
            if (!"true".equals(state[0])) return "Apply is greyed out beside the note: " + state[2] + " / " + state[1];
            CountDownLatch done = new CountDownLatch(1), again = new CountDownLatch(1);
            edt(() -> { w.afterRun = done::countDown; w.afterRefresh = again::countDown; });
            edt(() -> w.apply());
            String asked = String.join(" ", told).replace("\n", " ");
            if (!asked.contains("Remove the files another version of the tool left in its folder")) return "the confirm text: " + asked;
            if (!done.await(120, TimeUnit.SECONDS)) return "Apply did not finish; asked: " + told;
            if (!again.await(120, TimeUnit.SECONDS)) return "no refresh after Apply";
            Thread.sleep(200);
            edt(() -> { if (w.lastRun != null) w.lastRun.dispose(); });
            edt(() -> {});
            shot(shots, "window_tidy_after");
            edt(() -> { state[0] = String.valueOf(w.apply.isEnabled()); state[1] = w.warnings.getText(); });
            if (state[1].contains("Apply will")) return "the note is still there after Apply: " + state[1];
            if (!"false".equals(state[0])) return "after Apply, Apply still has work: " + state[1];
            return null;
        } finally {
            edt(() -> w.frame.dispose());
        }
    }

    /**
     * The mod list at the window's own size, in the look the window starts with, holds the
     * longest texts the real mods give each column: two tested versions, a long version, long
     * names, every state with " (not enabled)" after it, the two other-version states at a
     * version off the list, and as many mods as a real list, so the list scrolls and its scroll
     * bar takes its width. Null when every cell is as wide as its text, else the cells cut. An
     * other-version row's Tested cell also has to name both versions in its tooltip.
     */
    public static String cellsFit(Path dir) throws Exception {
        List<Map<String, Object>> mods = new ArrayList<>();
        for (int i = 10; i < 70; i++) mods.add(mod("mod_" + i, "Mod " + i, "1.0", List.of("1.0"), true));
        mods.addAll(List.of(
                mod("exiledSector", "Exiled Sector", "0.13.0", List.of("0.12.7", "0.13.0"), true),
                mod("terraformingmadeeasy", "Terraforming Made Easy", "2.10.6", List.of("2.10.0", "2.10.6"), true),
                mod("kadur_remnant", "Kadur Remnant", "3.2.4support3", List.of("3.2.4support3"), true),
                mod("WideHorizons", "Wide Horizons", "1.5.1a", List.of("1.5.0", "1.5.1a"), true),
                mod("gas_station", "Phillip Andrada: Gas Station Manager", "1.0.0", List.of("1.0.0"), false),
                mod("et_ix", "Emergent Threats: Nexerelin IX Loader", "1.5.0", List.of("1.4.3", "1.5.0"), false)));
        for (String state : Window.STATE.keySet()) {
            if (state.equals("not_installed")) continue;
            boolean other = state.startsWith("other_version");
            Map<String, Object> m = mod("st_" + state, "State " + state, other ? "0.14.0" : "0.13.0", List.of("0.12.7", "0.13.0"), false);
            m.put("state", state);
            mods.add(m);
        }
        Map<String, Object> st = Map.of("mods", mods);
        javax.swing.LookAndFeel was = javax.swing.UIManager.getLookAndFeel();
        List<String> cut = new ArrayList<>();
        try {
            edt(() -> {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
                Window x = new Window(dir, dir.resolve("app/SurvivalRebalance.jar"), dir);
                // not the dialogs: with them, closing the window ends the JVM (exit 0) and the suite with it
                x.prompter = new Window.Prompter() {
                    public boolean confirm(java.awt.Component parent, String title, String message, String yes) { return false; }
                    public void tell(java.awt.Component parent, String title, String message, int type) {}
                };
                try {
                    x.model.fill(st, false);
                    x.frame.validate();
                    javax.swing.JTable t = x.table;
                    for (int r = 0; r < t.getRowCount(); r++) {
                        if (x.model.rows.get(r).header()) continue;
                        for (int c = 0; c < t.getColumnCount(); c++) {
                            int need = t.prepareRenderer(t.getCellRenderer(r, c), r, c).getPreferredSize().width, have = t.getColumnModel().getColumn(c).getWidth();
                            if (need > have) cut.add(t.getColumnName(c) + " \"" + t.getValueAt(r, c) + "\" needs " + need + " px, has " + have);
                        }
                        if (x.model.rows.get(r).otherVersion) {
                            Object tip = ((javax.swing.JComponent) t.prepareRenderer(t.getCellRenderer(r, 2), r, 2)).getToolTipText();
                            if (!"Installed 0.14.0, tested with 0.12.7 or 0.13.0. Apply skips it.".equals(tip)) cut.add("Tested tooltip of \"" + t.getValueAt(r, 0) + "\": " + tip);
                        }
                    }
                    if (!cut.isEmpty()) cut.add("window " + x.frame.getWidth() + "x" + x.frame.getHeight());
                } finally {
                    x.frame.dispose();
                    javax.swing.UIManager.setLookAndFeel(was);
                }
            });
        } catch (RuntimeException e) {
            return "the window: " + e;
        }
        return cut.isEmpty() ? null : String.join("; ", cut);
    }

    static Map<String, Object> mod(String id, String name, String installed, List<String> tested, boolean enabled) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("kind", "patch");
        m.put("installed", true);
        m.put("enabled", enabled);
        m.put("installed_version", installed);
        m.put("tested_versions", tested);
        m.put("state", "not_patched");
        return m;
    }

    interface R { void run() throws Exception; }

    static void edt(R r) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { r.run(); } catch (Exception e) { throw new RuntimeException(e); } });
    }

    static void refresh() throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        edt(() -> { w.afterRefresh = l::countDown; w.refresh(); });
        if (!l.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("refresh did not finish");
        edt(() -> {});
    }

    static void select(String id) throws Exception {
        edt(() -> {
            for (int r = 0; r < w.model.rows.size(); r++)
                if (id.equals(w.model.rows.get(r).id) && w.model.rows.get(r).mod != null) w.table.getSelectionModel().setSelectionInterval(r, r);
        });
    }

    static void shot(Path dir, String name) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        edt(() -> {
            java.awt.Container c = w.frame.getRootPane();
            c.validate();
            img[0] = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            c.paint(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", dir.resolve(name + ".png").toFile());
    }
}