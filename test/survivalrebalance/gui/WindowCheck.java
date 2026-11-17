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
            w.frame.setSize(1120, 760);
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
            w.frame.setSize(1120, 760);
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