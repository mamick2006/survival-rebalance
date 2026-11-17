package survivalrebalance.gui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A scripted run of the window through the whole flow on a test install, with a screenshot of
 * each step. It really applies and undoes, so point it only at a copy of a game.
 *
 *   java -cp <classes;test classes> survivalrebalance.gui.GuiShots <tool dir> <game dir> <out dir> [scale [release zip, exe]]
 */
public final class GuiShots {
    // For a scaled run, also start Java with -Dsun.java2d.uiScale=<scale> so the layout is measured at that scale.

    static Window w;
    static Path out;
    static double scale = 1;
    static int n;

    public static void main(String[] a) {
        try {
            flow(a);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    static void flow(String[] a) throws Exception {
        Path tool = Path.of(a[0]), game = Path.of(a[1]);
        Files.deleteIfExists(tool.resolve("customize.txt")); // start from the shipped settings
        out = Path.of(a[2]);
        if (a.length > 3) scale = Double.parseDouble(a[3]);
        Files.createDirectories(out);
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeAndWait(() -> {
            Path jar = Files.isRegularFile(tool.resolve("app/SurvivalRebalance.jar")) ? tool.resolve("app/SurvivalRebalance.jar") : tool.resolve("SurvivalRebalance.jar");
            w = new Window(tool, jar, game);
            w.prompter = new Shooter();
            if (scale == 1) w.frame.setSize(1120, 760); else { w.frame.setSize(1100, 680); w.frame.setLocation(0, 0); }
            w.frame.setVisible(true);
        });
        refresh();
        shot(w.frame.getRootPane(), "start");
        select("second_in_command");
        shot(w.frame.getRootPane(), "selected");
        select("stelnet");
        shot(w.frame.getRootPane(), "would_fail");
        select("salvage_magnet");
        shot(w.frame.getRootPane(), "other_version");
        // the refusal while the game runs (the detection itself is replaced here; Child.gameRunning is the real one)
        edt(() -> { w.gameRunning = () -> true; w.apply(); w.gameRunning = () -> false; });

        run(() -> w.apply(), "apply");
        CountDownLatch mon = new CountDownLatch(1);
        edt(() -> { w.afterRefresh = mon::countDown; w.enableMonitor(); });
        await(mon, "enable the monitor");
        shot(w.frame.getRootPane(), "monitor_enabled");

        select("second_in_command");
        clickChange(0);
        select("second_in_command");
        shot(w.frame.getRootPane(), "change_off");
        slider(15);
        shot(w.frame.getRootPane(), "strength");

        run(() -> w.apply(), "apply_again");
        run(() -> w.undo(), "undo");

        edt(() -> w.toggleMissing());
        select("bounty_board_extras");
        shot(w.frame.getRootPane(), "unsupported_and_missing");
        if (a.length > 5) updateFlow(tool, Path.of(a[4]), Path.of(a[5]));
        edt(() -> w.frame.dispose());
        System.out.println("shots in " + out);
        System.exit(0);
    }

    /**
     * The Update button against a local copy of the release server: latest.json names version
     * 9.9.9 and the given release zip. The window downloads and stages it; then the real exe
     * installs it, the way it does when the window exits with 42.
     */
    static void updateFlow(Path tool, Path zip, Path exe) throws Exception {
        byte[] z = Files.readAllBytes(zip);
        String sha = survivalrebalance.Api.sha256(z);
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", x -> {
            String p = x.getRequestURI().getPath();
            byte[] body = p.endsWith("latest.json")
                    ? ("{\"version\": \"9.9.9\", \"game\": \"0.98.5a-RC7\", \"zip\": \"SurvivalRebalance.zip\", \"sha256\": \"" + sha + "\", \"notes\": \"Test release.\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    : p.endsWith("SurvivalRebalance.zip") ? z : null;
            if (body == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            x.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream o = x.getResponseBody()) { o.write(body); }
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/";
            edt(() -> { w.updateArgs = new String[]{"--update-url", base}; w.launcher = true; w.checkForUpdate(); });
            for (int i = 0; i < 300 && w.newer == null; i++) Thread.sleep(100);
            if (w.newer == null) throw new IllegalStateException("update --check found no new version");
            edt(() -> {});
            shot(w.frame.getRootPane(), "update_available");
            run(() -> w.update(), "update");
            if (!Files.isRegularFile(tool.resolve("update/update.json"))) throw new IllegalStateException("nothing was staged");
            if (!w.restartRequested) throw new IllegalStateException("the window did not ask for a restart");
        } finally {
            srv.stop(0);
        }
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Process p = new ProcessBuilder(exe.toString(), "--install-update", tool.toString(), javaExe.toString()).redirectErrorStream(true).start();
        String said = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0 || Files.exists(tool.resolve("update/update.json"))) throw new IllegalStateException("the exe did not install it (" + rc + "): " + said);
        System.out.println("exe installed the staged update");
        edt(() -> { w.newer = null; w.updatedTo = "9.9.9"; w.showNotice(); });
        refresh();
        shot(w.frame.getRootPane(), "after_update");
    }

    interface R { void run() throws Exception; }

    static void edt(R r) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { r.run(); } catch (Exception e) { throw new RuntimeException(e); } });
    }

    static void refresh() throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        edt(() -> { w.afterRefresh = l::countDown; w.refresh(); });
        await(l, "refresh");
    }

    static void await(CountDownLatch l, String what) throws Exception {
        if (!l.await(300, TimeUnit.SECONDS)) throw new IllegalStateException(what + " did not finish");
        Thread.sleep(300);
        edt(() -> {});
    }

    static void select(String id) throws Exception {
        edt(() -> {
            for (int r = 0; r < w.model.rows.size(); r++)
                if (id.equals(w.model.rows.get(r).id) && w.model.rows.get(r).mod != null) {
                    w.table.getSelectionModel().setSelectionInterval(r, r);
                    w.table.scrollRectToVisible(w.table.getCellRect(r, 0, true));
                }
        });
    }

    /** Click the n-th change's switch in the details, as a player would, and wait for the refresh. */
    static void clickChange(int index) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        edt(() -> {
            w.afterRefresh = l::countDown;
            int seen = 0;
            for (Component c : w.details.getComponents()) {
                if (!(c instanceof JPanel)) continue;
                JCheckBox b = find((Container) c, JCheckBox.class);
                if (b != null && seen++ == index) { b.doClick(); return; }
            }
            throw new IllegalStateException("no change switch " + index);
        });
        await(l, "change switch");
    }

    static void slider(int v) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        edt(() -> {
            w.afterRefresh = l::countDown;
            JSlider s = find(w.details, JSlider.class);
            s.setValue(v);
        });
        await(l, "slider");
    }

    static <T> T find(Container c, Class<T> k) {
        for (Component x : c.getComponents()) {
            if (k.isInstance(x)) return k.cast(x);
            if (x instanceof Container) { T t = find((Container) x, k); if (t != null) return t; }
        }
        return null;
    }

    static void run(R action, String name) throws Exception {
        CountDownLatch done = new CountDownLatch(1), again = new CountDownLatch(1);
        edt(() -> { w.afterRun = done::countDown; w.afterRefresh = again::countDown; });
        edt(action);
        await(done, name);
        shot(w.lastRun.getRootPane(), name + "_result");
        await(again, name + " refresh");
        edt(() -> w.lastRun.dispose());
        shot(w.frame.getRootPane(), "after_" + name);
    }

    static void shot(Component c, String name) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        edt(() -> {
            c.validate();
            int width = (int) Math.round(c.getWidth() * scale), height = (int) Math.round(c.getHeight() * scale);
            img[0] = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(scale, scale);
            c.paint(g);
            g.dispose();
        });
        String file = String.format("%02d_%s.png", ++n, name);
        ImageIO.write(img[0], "png", out.resolve(file).toFile());
        System.out.println(file);
    }

    /** Answers yes to every question, and photographs each one first. */
    static final class Shooter implements Window.Prompter {
        public boolean confirm(Component parent, String title, String message, String yes) {
            Object[] opts = {yes, "Cancel"};
            JOptionPane p = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION, null, opts, opts[1]);
            dialog(p, title + "_confirm");
            return true;
        }
        public void tell(Component parent, String title, String message, int type) {
            dialog(new JOptionPane(message, type), title + "_message");
        }
        void dialog(JOptionPane p, String name) {
            JDialog d = p.createDialog(w.frame, name);
            d.pack();
            try {
                Container cp = d.getContentPane();
                BufferedImage img = new BufferedImage((int) Math.round(cp.getWidth() * scale), (int) Math.round(cp.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.scale(scale, scale);
                cp.paint(g);
                g.dispose();
                ImageIO.write(img, "png", out.resolve(String.format("%02d_%s.png", ++n, name.toLowerCase())).toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
            d.dispose();
        }
    }
}
