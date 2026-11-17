package survivalrebalance.gui;

import survivalrebalance.Api;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The Survival Rebalance window: the game it found, every mod with its state, the changes of
 * the selected mod with their switches, the warnings, and Check, Apply and Undo.
 *
 * Start: java -cp SurvivalRebalance.jar survivalrebalance.gui.Window [--game <dir>]
 * Exit code 42 asks the launcher to install a downloaded update and start the window again.
 */
public final class Window {
    static final int EXIT_RESTART = 42;

    /** How the window asks and tells; replaced in the scripted screenshot run. */
    interface Prompter {
        boolean confirm(Component parent, String title, String message, String yes);
        void tell(Component parent, String title, String message, int type);
    }

    static final Prompter DIALOGS = new Prompter() {
        public boolean confirm(Component parent, String title, String message, String yes) {
            Object[] opts = {yes, "Cancel"};
            return JOptionPane.showOptionDialog(parent, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[1]) == 0;
        }
        public void tell(Component parent, String title, String message, int type) {
            JOptionPane.showMessageDialog(parent, message, title, type);
        }
    };

    /** toolDir: the install root (customize.txt, logs, game_dir.txt, update). appDir: the jar's folder (patches). */
    final Path toolDir, appDir, jar;
    /** Started by SurvivalRebalance.exe or its .bat or .sh, which install a downloaded update when the window exits with 42. */
    boolean launcher;
    /** The version the launcher just installed, or null. */
    String updatedTo;
    /** The newer version update --check found, or null. */
    String newer;
    Path game;
    Prompter prompter = DIALOGS;
    /** Called on the event thread after each refresh and each finished run (the screenshot run waits on it). */
    Runnable afterRefresh, afterRun;

    final JFrame frame = new JFrame("Survival Rebalance");
    final JLabel gameLabel = new JLabel(), versionLabel = new JLabel(), toolLabel = new JLabel(), statusLine = new JLabel(" ");
    final JButton changeGame = new JButton("Change...");
    final JButton update = new JButton("Update...");
    final JLabel notice = new JLabel(" ");
    final JButton check = new JButton("Check"), apply = new JButton("Apply..."), undo = new JButton("Undo..."), openLog = new JButton("Open log");
    /** The state in one sentence, with the main action beside it. */
    final JLabel headline = new JLabel(" ");
    /** Above the list while an enabled mod is not supported. */
    final JPanel strip = new JPanel(new BorderLayout(8, 0));
    final JLabel stripText = new JLabel();
    /** The one step left to the player after apply: switch the Monitor mod on. */
    final JPanel monitorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    final JButton enableMonitor = new JButton("Enable the Monitor in the game's mod list");
    /** Whether Starsector is running; replaced in the scripted screenshot run. */
    java.util.function.BooleanSupplier gameRunning = () -> Child.gameRunning(game);
    final JTextArea warnings = new JTextArea(6, 60);
    final JLabel warningsTitle = new JLabel("Warnings");
    final ModTable model = new ModTable();
    final JTable table = new JTable(model);
    final JPanel details = new Details();

    /** The details column: as wide as its scroll pane, so its text wraps instead of running off. */
    static final class Details extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(16, r.height - 32); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    RunDialog lastRun;
    JScrollPane detailScroll;
    String shownId;

    Map<String, Object> status;
    boolean showMissing;
    String selectedId;
    boolean busy;

    Window(Path toolDir, Path jar, Path game) {
        this.toolDir = toolDir;
        this.appDir = jar.getParent();
        this.jar = jar;
        this.game = game;
        build();
    }

    // ------------------------------------------------------------------ start

    public static void main(String[] args) {
        Path jar = ownJar();
        Path toolDir = jar == null ? Path.of("").toAbsolutePath() : Api.installRoot(jar.getParent());
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // the default look works too
        }
        Path given = null;
        String upd = null;
        boolean fromLauncher = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--launcher")) fromLauncher = true;
            else if (i + 1 < args.length && args[i].equals("--game")) given = Path.of(args[++i]);
            else if (i + 1 < args.length && args[i].equals("--updated")) upd = args[++i];
        }
        Path g = given;
        String updatedTo = upd;
        boolean launched = fromLauncher;
        SwingUtilities.invokeLater(() -> {
            try {
                if (jar == null) throw new IllegalStateException("The window must run from SurvivalRebalance.jar.");
                Path found = g != null && Games.isGame(g) ? g : Games.find(toolDir);
                if (found == null) found = Games.ask(null, toolDir, "Survival Rebalance cannot find your Starsector folder. Choose the folder that holds the mods folder.");
                if (found == null) System.exit(0);
                Window w = new Window(toolDir, jar, found);
                w.launcher = launched;
                w.updatedTo = updatedTo;
                w.show();
                w.checkForUpdate();
            } catch (Throwable t) {
                crash(toolDir, t);
            }
        });
    }

    static void crash(Path toolDir, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        try {
            // the tool's files loose in another folder: it writes nothing there, not even this log
            if (Api.looseFolder(toolDir.resolve("app"))) throw new IOException("loose");
            Files.createDirectories(toolDir.resolve("logs"));
            Files.writeString(toolDir.resolve("logs").resolve("window_error.txt"), sw.toString());
        } catch (IOException ignored) {
            // the message box below still shows it
        }
        JOptionPane.showMessageDialog(null, "The Survival Rebalance window stopped with an error:\n\n" + t
                + "\n\nThe details are in logs\\window_error.txt. You can still use the .bat files in the app folder.", "Survival Rebalance", JOptionPane.ERROR_MESSAGE);
        System.exit(3);
    }

    static Path ownJar() {
        try {
            Path p = Path.of(Window.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) { return null; }
    }

    void show() {
        frame.setVisible(true);
        refresh();
    }

    // ------------------------------------------------------------------ layout

    void build() {
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { if (prompter == DIALOGS) System.exit(0); }
        });
        try (InputStream in = Window.class.getResourceAsStream("icon.png")) {
            if (in != null) frame.setIconImage(ImageIO.read(in));
        } catch (IOException ignored) {
            // no icon is fine
        }
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        frame.setContentPane(root);

        // top: the game and the tool
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 8);
        c.gridx = 0; c.gridy = 0;
        top.add(new JLabel("Game folder:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        top.add(gameLabel, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        changeGame.setToolTipText("Choose another Starsector folder. The choice is saved in game_dir.txt.");
        changeGame.setMnemonic(KeyEvent.VK_G);
        changeGame.addActionListener(e -> changeGame());
        top.add(changeGame, c);
        c.gridx = 0; c.gridy = 1;
        top.add(new JLabel("Starsector:"), c);
        c.gridx = 1; c.gridwidth = 2;
        top.add(versionLabel, c);
        c.gridx = 0; c.gridy = 2; c.gridwidth = 1;
        top.add(new JLabel("This tool:"), c);
        c.gridx = 1; c.gridwidth = 2;
        top.add(toolLabel, c);
        JPanel np = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        notice.setFont(notice.getFont().deriveFont(Font.BOLD));
        np.add(notice);
        np.add(Box.createHorizontalStrut(10));
        update.setMnemonic(KeyEvent.VK_P);
        update.setVisible(false);
        update.addActionListener(e -> update());
        np.add(update);
        c.gridx = 1; c.gridy = 3; c.gridwidth = 2;
        top.add(np, c);
        root.add(top, BorderLayout.NORTH);

        // middle: the mods and the selected mod
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 6);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(260);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setPreferredWidth(190);
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) selectRow(table.getSelectedRow()); });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                if (r >= 0 && model.rows.get(r).group == Group.MISSING_HEADER) toggleMissing();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggleGroup");
        table.getActionMap().put("toggleGroup", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                int r = table.getSelectedRow();
                if (r >= 0 && model.rows.get(r).group == Group.MISSING_HEADER) toggleMissing();
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(620, 380));

        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(new EmptyBorder(8, 10, 8, 10));
        detailScroll = new JScrollPane(details);
        detailScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailScroll.setPreferredSize(new Dimension(440, 380));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detailScroll);
        split.setResizeWeight(0.55);
        split.setBorder(null);

        // bottom: warnings and actions
        warnings.setEditable(false);
        warnings.setLineWrap(true);
        warnings.setWrapStyleWord(true);
        warnings.setFont(new JLabel().getFont());
        JPanel warn = new JPanel(new BorderLayout(0, 4));
        warningsTitle.setFont(warningsTitle.getFont().deriveFont(Font.BOLD));
        warn.add(warningsTitle, BorderLayout.NORTH);
        warn.add(new JScrollPane(warnings), BorderLayout.CENTER);

        JSplitPane vsplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, split, warn);
        vsplit.setResizeWeight(0.75);
        vsplit.setBorder(null);

        // the state in one sentence, the main action beside it, then what needs the player's eye
        JPanel head = new JPanel(new BorderLayout(12, 0));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, headline.getFont().getSize2D() * 1.3f));
        head.add(headline, BorderLayout.CENTER);
        apply.setFont(apply.getFont().deriveFont(Font.BOLD, apply.getFont().getSize2D() * 1.15f));
        apply.setMargin(new Insets(6, 18, 6, 18));
        head.add(apply, BorderLayout.EAST);
        enableMonitor.setToolTipText("Adds the Survival Rebalance Monitor to the mods the game loads. It warns you in the game when a mod update removes the rebalance.");
        enableMonitor.addActionListener(e -> enableMonitor());
        JLabel monLabel = new JLabel("Next: the Survival Rebalance Monitor mod is installed but switched off.  ");
        monitorRow.add(monLabel);
        monitorRow.add(enableMonitor);
        monitorRow.setVisible(false);
        strip.setBackground(new Color(0xFF, 0xF1, 0xC9));
        strip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xD9, 0x9A, 0x1E)), new EmptyBorder(6, 8, 6, 8)));
        Icon wi = UIManager.getIcon("OptionPane.warningIcon");
        if (wi != null) strip.add(new JLabel(wi), BorderLayout.WEST);
        strip.add(stripText, BorderLayout.CENTER);
        strip.setVisible(false);
        JPanel heads = new JPanel();
        heads.setLayout(new BoxLayout(heads, BoxLayout.Y_AXIS));
        for (JComponent x : new JComponent[]{head, monitorRow, strip}) {
            x.setAlignmentX(Component.LEFT_ALIGNMENT);
            heads.add(x);
            heads.add(Box.createVerticalStrut(6));
        }
        JPanel middle = new JPanel(new BorderLayout(0, 4));
        middle.add(heads, BorderLayout.NORTH);
        middle.add(vsplit, BorderLayout.CENTER);
        root.add(middle, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        check.setMnemonic(KeyEvent.VK_C);
        apply.setMnemonic(KeyEvent.VK_A);
        undo.setMnemonic(KeyEvent.VK_U);
        openLog.setMnemonic(KeyEvent.VK_L);
        check.setToolTipText("Look at the mods again and list the warnings. Changes nothing.");
        openLog.setToolTipText("Open the log of the last Apply or Undo.");
        check.addActionListener(e -> refresh());
        apply.addActionListener(e -> apply());
        undo.addActionListener(e -> undo());
        openLog.addActionListener(e -> openLog());
        for (JButton b : new JButton[]{check, undo, openLog}) {
            buttons.add(b);
            buttons.add(Box.createHorizontalStrut(8));
        }
        JPanel bottom = new JPanel(new BorderLayout(12, 0));
        bottom.add(buttons, BorderLayout.WEST);
        bottom.add(statusLine, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.getRootPane().setDefaultButton(null);
        frame.pack();
        frame.setSize(new Dimension(Math.max(frame.getWidth(), 1120), Math.max(frame.getHeight(), 760)));
        frame.setMinimumSize(new Dimension(760, 520));
        frame.setLocationRelativeTo(null);
        showDetails(null);
    }

    // ------------------------------------------------------------------ status

    /** Read the state of every mod again (status --json in a child process) and redraw. */
    void refresh() {
        if (busy) return;
        setBusy(true, "Looking at the mods...");
        new SwingWorker<Map<String, Object>, Void>() {
            String error;
            @SuppressWarnings("unchecked")
            @Override protected Map<String, Object> doInBackground() {
                try {
                    Child.Result r = Child.run(jar, game, null, "status", "--json", "--offline");
                    int at = r.out.indexOf('{');
                    if (at < 0) { error = r.out.trim(); return null; }
                    return (Map<String, Object>) Api.parseJson(r.out.substring(at));
                } catch (Exception e) {
                    error = e.toString();
                    return null;
                }
            }
            @Override protected void done() {
                Map<String, Object> s = null;
                try { s = get(); } catch (Exception ignored) { /* error is set */ }
                setBusy(false, " ");
                if (s == null) {
                    statusLine.setText("Could not read the mods: " + error);
                } else {
                    status = s;
                    redraw();
                    statusLine.setText("Checked at " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + ".");
                }
                if (afterRefresh != null) afterRefresh.run();
            }
        }.execute();
    }

    void setBusy(boolean b, String text) {
        busy = b;
        for (JButton x : new JButton[]{check, apply, undo, changeGame, update}) x.setEnabled(!b);
        frame.setCursor(b ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        statusLine.setText(text);
    }

    static String s(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : String.valueOf(v); }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> m, String k) {
        Object v = m.get(k);
        List<String> out = new ArrayList<>();
        if (v instanceof List) for (Object o : (List<Object>) v) out.add(String.valueOf(o));
        return out;
    }

    void redraw() {
        gameLabel.setText(String.valueOf(game));
        String gv = s(status, "game_version"), tv = s(status, "tested_game_version");
        String vt = gv == null ? "version not found" : gv;
        if (tv != null) vt += gv != null && gv.equals(tv) ? " (the version the patches were tested on)" : ", but the patches were tested on " + tv;
        versionLabel.setText(vt);
        toolLabel.setText("Survival Rebalance " + Api.versionText());
        if (s(status, "error") != null) {
            warningsTitle.setText("Error");
            warnings.setText(s(status, "error"));
            headline.setText("The tool cannot read your mods. The error is below.");
            apply.setEnabled(false);
            undo.setEnabled(false);
        } else {
            List<Map<String, Object>> w = new ArrayList<>(list(status, "window_warnings"));
            w.sort((a, b) -> Boolean.compare(!"unsupported".equals(s(a, "kind")), !"unsupported".equals(s(b, "kind")))); // not supported first
            warningsTitle.setText(w.isEmpty() ? "Warnings: none" : "Warnings (" + w.size() + ")");
            StringBuilder b = new StringBuilder();
            int i = 1;
            for (Map<String, Object> x : w) {
                if ("unsupported".equals(s(x, "kind"))) b.append(i++).append(". ").append(s(x, "subject")).append(" is ").append(s(x, "text")).append('\n');
                else b.append(i++).append(". ").append(s(x, "subject")).append(": ").append(s(x, "text")).append('\n');
                if (s(x, "advice") != null) b.append("    What to do: ").append(s(x, "advice")).append('\n');
            }
            for (String n : strings(status, "window_notes")) b.append("Note: ").append(n).append('\n');
            if (b.length() == 0) b.append("No warnings.");
            warnings.setText(b.toString().trim());
            warnings.setCaretPosition(0);
            headline();
        }
        model.fill(status, showMissing);
        int sel = -1;
        for (int r = 0; r < model.rows.size(); r++) if (selectedId != null && selectedId.equals(model.rows.get(r).id) && model.rows.get(r).mod != null) sel = r;
        if (sel >= 0) {
            table.getSelectionModel().setSelectionInterval(sel, sel);
            showDetails(model.rows.get(sel));
        } else showDetails(null);
    }

    /** Installed supported mods that change, by state group; enabled mods that are not supported. */
    List<Map<String, Object>> installedPatchMods() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : list(status, "mods")) if ("patch".equals(s(m, "kind")) && Boolean.TRUE.equals(m.get("installed"))) out.add(m);
        return out;
    }

    List<String> unsupportedEnabled() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : list(status, "unsupported")) if (!Boolean.FALSE.equals(m.get("enabled"))) out.add(s(m, "name"));
        return out;
    }

    /** The sentence at the top, the main button, the Monitor step and the not-supported strip. */
    void headline() {
        List<String> todo = namesIn(TO_APPLY), done = namesIn(List.of("patched")), failing = namesIn(List.of("fails", "modified", "damaged"));
        int have = installedPatchMods().size(), skipped = namesIn(List.of("other_version")).size();
        String h;
        if (!todo.isEmpty()) h = todo.size() + " of " + count(have, "supported mod") + " you have " + (todo.size() == 1 ? "is" : "are") + " not rebalanced yet.";
        else if (!done.isEmpty() && done.size() == have) h = "All " + count(done.size(), "supported mod") + " you have " + (done.size() == 1 ? "is" : "are") + " rebalanced. Undo puts everything back.";
        else if (!done.isEmpty()) h = done.size() + " of " + count(have, "supported mod") + " you have " + (done.size() == 1 ? "is" : "are") + " rebalanced. Undo puts everything back.";
        else if (have == 0 && !namesIn(List.of("dropped")).isEmpty()) h = "";
        else if (have == 0 && !namesIn(List.of("checked")).isEmpty()) h = "The supported mods you have need no change.";
        else if (have == 0) h = "None of your mods is on the supported list.";
        else h = "No supported mod is rebalanced.";
        if (skipped > 0) h += " " + count(skipped, "mod") + " skipped: " + (skipped == 1 ? "another version." : "other versions.");
        if (!failing.isEmpty()) h += " " + count(failing.size(), "mod") + " cannot be rebalanced; see the warnings.";
        List<String> stuck = namesIn(List.of("dropped_modified"));
        if (!stuck.isEmpty()) h += " Apply and Undo leave " + String.join(", ", stuck) + " alone: " + (stuck.size() == 1 ? "it was" : "they were") + " updated in place since another version of the tool rebalanced " + (stuck.size() == 1 ? "it" : "them") + ". See the warnings.";
        List<String> dropped = namesIn(List.of("dropped")), work = strings(status, "apply_todo");
        boolean monitorWork = Boolean.TRUE.equals(status.get("monitor_to_replace")), monitorGone = Boolean.TRUE.equals(status.get("monitor_missing"));
        if (dropped.size() == 1) h += " Apply puts back the original files of " + dropped.get(0) + ", which another version of the tool rebalanced.";
        else if (!dropped.isEmpty()) h += " Apply puts back the original files of " + count(dropped.size(), "mod") + " that another version of the tool rebalanced.";
        if (monitorWork) h += " Apply " + (work.isEmpty() ? "" : "also ") + "puts in this version's copy of the Survival Rebalance Monitor.";
        if (monitorGone) h += " The Survival Rebalance Monitor is missing from your mods folder. Apply " + (work.isEmpty() ? "" : "also ") + "puts it back.";
        // a newer version's files beside this program: that is the one thing to say
        if (s(status, "newer_folder") != null) h = newerHeadline(s(status, "newer_folder"), s(status, "tool_version"));
        headline.setText("<html>" + esc(h.trim()) + "</html>");
        boolean applyWork = Boolean.TRUE.equals(status.get("apply_has_work"));
        apply.setText(work.isEmpty() ? "Apply..." : "Apply to " + count(work.size(), "mod") + "...");
        apply.setEnabled(!busy && applyWork);
        apply.setToolTipText(!applyWork ? (skipped > 0 ? "Nothing to apply. Apply skips a mod of another version than the tested one." : "Nothing to apply. Every supported mod you have is rebalanced with your current choices.")
                : "Rebalance the supported mods you have. The tool backs up every file first.");
        List<String> undoable = strings(status, "undo_todo");
        undo.setText(undoable.isEmpty() ? "Undo..." : "Undo " + count(undoable.size(), "mod") + "...");
        undo.setEnabled(!busy && Boolean.TRUE.equals(status.get("undo_has_work")));
        undo.setToolTipText(undoable.isEmpty() ? "Nothing to undo. No mod is rebalanced." : "Put back the original files of every rebalanced mod.");
        monitorRow.setVisible("not_enabled".equals(s(status, "monitor")));
        if (updatedTo != null) showNotice();
        List<String> un = unsupportedEnabled();
        strip.setVisible(!un.isEmpty());
        if (!un.isEmpty()) {
            String t = un.size() > 3
                    ? un.size() + " enabled mods are not supported. They are at the top of the list. They have not been checked for Survival Mode, so they stay as they are."
                    : (un.size() == 1 ? "1 enabled mod is not supported: " : un.size() + " enabled mods are not supported: ") + String.join(", ", un) + ". "
                    + (un.size() == 1 ? "It has" : "They have") + " not been checked for Survival Mode, so " + (un.size() == 1 ? "it stays as it is." : "they stay as they are.");
            stripText.setText("<html>" + esc(t) + "</html>");
        }
    }

    /** The headline when a newer version's files sit beside this program: the tool cannot tell which unzip did it, so both. */
    public static String newerHeadline(String folder, String running) {
        return "This folder holds the files of version " + folder + ", but this program is version " + running + "."
                + " If you unzipped " + folder + " and kept the old files, unzip " + folder + " again and choose to replace the files."
                + " If you unzipped " + running + " over " + folder + " to go back, copy customize.txt out, delete the SurvivalRebalance folder and unzip " + running + " again."
                + " Apply is off until then. Undo still works.";
    }

    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    void enableMonitor() {
        try {
            boolean did = Api.enableMonitor(game.resolve("mods"));
            prompter.tell(frame, "Survival Rebalance Monitor", did
                    ? "The Survival Rebalance Monitor is now switched on in the game's mod list. It warns you in the game when a mod update removes the rebalance. You can switch it off in the Starsector launcher at any time."
                    : "The Survival Rebalance Monitor was already switched on.", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            prompter.tell(frame, "Survival Rebalance Monitor", "Cannot change the game's mod list: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
        refresh();
    }

    void toggleMissing() {
        showMissing = !showMissing;
        model.fill(status, showMissing);
        redraw();
    }

    void selectRow(int r) {
        if (r < 0 || r >= model.rows.size()) return;
        Row row = model.rows.get(r);
        if (row.mod == null) return;
        if (row.id.equals(selectedId)) return;
        selectedId = row.id;
        showDetails(row);
    }

    // ------------------------------------------------------------------ the selected mod

    void showDetails(Row row) {
        String id = row == null || row.mod == null ? null : row.id;
        int keep = id != null && id.equals(shownId) && detailScroll != null ? detailScroll.getVerticalScrollBar().getValue() : 0;
        shownId = id;
        details.removeAll();
        if (row == null || row.mod == null) {
            details.add(text("Select a mod to see what the rebalance changes in it.", false));
        } else if (row.group == Group.UNSUPPORTED) {
            Map<String, Object> m = row.mod;
            details.add(heading(s(m, "name")));
            details.add(text("Installed " + s(m, "installed_version") + (Boolean.FALSE.equals(m.get("enabled")) ? ", not enabled" : ""), true));
            details.add(gap());
            details.add(text("Not supported. It has not been checked for Survival Mode, so it stays as it is.", false));
        } else {
            Map<String, Object> m = row.mod;
            boolean patchKind = "patch".equals(s(m, "kind"));
            details.add(heading(s(m, "name")));
            boolean any = Boolean.TRUE.equals(m.get("any_version"));
            String tested = any ? "any version" : String.join(" or ", strings(m, "tested_versions"));
            boolean droppedKind = "dropped".equals(s(m, "kind"));
            boolean other = !any && !droppedKind && Boolean.TRUE.equals(m.get("installed")) && !strings(m, "tested_versions").contains(s(m, "installed_version"));
            String inst = droppedKind ? "Installed " + s(m, "installed_version") + (Boolean.FALSE.equals(m.get("enabled")) ? ". Not enabled in the game's mod list." : "")
                    : Boolean.TRUE.equals(m.get("installed")) ? "Installed " + s(m, "installed_version") + (any ? ", supported at any version" : ", tested with " + tested)
                    + (other ? " (a different version)" : "") + (Boolean.FALSE.equals(m.get("enabled")) ? ". Not enabled in the game's mod list." : "") : any ? "Not installed. Supported at any version" : "Not installed. Tested with " + tested;
            JComponent vl = text(inst, !other);
            if (other) vl.setForeground(TODO);
            details.add(vl);
            details.add(gap());
            details.add(text(s(m, "state_text"), false));
            if (s(m, "reason") != null) details.add(text(s(m, "reason"), true));
            if (patchKind) changesPanel(m);
        }
        details.add(Box.createVerticalGlue());
        details.revalidate();
        details.repaint();
        if (detailScroll != null) SwingUtilities.invokeLater(() -> detailScroll.getVerticalScrollBar().setValue(keep));
    }

    void changesPanel(Map<String, Object> m) {
        String id = s(m, "id");
        List<Map<String, Object>> changes = list(m, "changes");
        details.add(gap());
        boolean modOn = !Boolean.TRUE.equals(m.get("mod_off"));
        JCheckBox whole = new JCheckBox("Rebalance this mod", modOn);
        whole.setToolTipText("Off: apply leaves this mod as it is, and puts it back if it is patched.");
        whole.addActionListener(e -> edit(Map.of(id, whole.isSelected() ? "on" : "off")));
        details.add(left(whole));

        // strength: the mod's own line, else all:strength, else as designed
        String ms = s(m, "mod_strength");
        String all = status.get("customize") instanceof Map ? s(cast(status.get("customize")), "all_strength") : null;
        String eff = ms != null ? ms : all != null ? all : "1";
        JPanel sp = new JPanel(new BorderLayout(6, 0));
        sp.add(new JLabel("Strength:"), BorderLayout.WEST);
        JSlider slider = new JSlider(0, 20, (int) Math.round(Double.parseDouble(eff) * 20));
        slider.setMajorTickSpacing(10);
        slider.setMinorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setSnapToTicks(true);
        slider.setEnabled(modOn);
        slider.getAccessibleContext().setAccessibleName("Strength of this mod's changes");
        JLabel val = new JLabel(strengthText(eff));
        slider.addChangeListener(e -> {
            String v = plain(slider.getValue() / 20.0);
            val.setText(strengthText(v));
            if (!slider.getValueIsAdjusting() && !v.equals(eff)) edit(Map.of(id + ":strength", v));
        });
        sp.add(slider, BorderLayout.CENTER);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        val.setPreferredSize(new Dimension(val.getFontMetrics(val.getFont()).stringWidth("1 (as designed)") + 4, val.getPreferredSize().height));
        east.add(val);
        JButton designed = new JButton("As designed");
        designed.setToolTipText(modOn && !"1".equals(eff) ? "Set the strength back to 1: every change as designed." : "The strength is already 1.");
        designed.setEnabled(modOn && !"1".equals(eff));
        designed.addActionListener(e -> edit(Map.of(id + ":strength", "1")));
        east.add(designed);
        sp.add(east, BorderLayout.EAST);
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, sp.getPreferredSize().height));
        details.add(left(sp));
        String pct = "1".equals(eff) || "0".equals(eff) ? null : Math.round(Double.parseDouble(eff) * 100) + "%";
        details.add(text((pct == null ? "At 0.5 every number goes halfway from Before to After." : "At " + eff + " each number goes " + pct + " of the way from Before to the designed After.")
                + " Your choices are saved as you make them. Click Apply to put them into the mod.", true));

        details.add(gap());
        JLabel h = new JLabel(changes.size() + (changes.size() == 1 ? " change" : " changes"));
        h.setFont(h.getFont().deriveFont(Font.BOLD));
        details.add(left(h));
        for (Map<String, Object> c : changes) {
            String cid = s(c, "id");
            boolean on = Boolean.TRUE.equals(c.get("on"));
            String own = s(c, "change_strength");
            String title = s(c, "title") + (on && own != null && !"1".equals(own) ? "  (strength " + own + ", set in customize.txt)" : "");
            JCheckBox box = new JCheckBox("", on);
            box.getAccessibleContext().setAccessibleName(s(c, "title"));
            box.setEnabled(modOn && !"0".equals(eff));
            box.setToolTipText(!modOn ? "The whole mod is switched off." : "0".equals(eff) ? "Strength 0 switches every change off." : "Switch this change on or off.");
            box.addActionListener(e -> edit(Map.of(id + ":" + cid, box.isSelected() ? "on" : "off")));
            JComponent label = text(title, false);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            label.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (box.isEnabled()) box.doClick(); }
            });
            JPanel row = new JPanel(new BorderLayout(2, 0));
            row.add(box, BorderLayout.WEST);
            row.add(label, BorderLayout.CENTER);
            details.add(Box.createVerticalStrut(6));
            details.add(left(row));
            if (Boolean.TRUE.equals(c.get("skipped")))
                details.add(indent(text("Skipped: it needs " + String.join(", ", strings(c, "requires")) + ", which is not applied.", false)));
            if (s(c, "before") != null) details.add(indent(text("Before: " + s(c, "before"), false)));
            boolean partial = on && !"1".equals(s(c, "strength"));
            if (s(c, "after") != null) {
                if (!partial) details.add(indent(text("After: " + s(c, "after"), false)));
                else if (s(c, "after_now") != null) details.add(indent(text("After: " + s(c, "after_now") + " (as designed: " + s(c, "after") + ")", false)));
                else {
                    details.add(indent(text("After, as designed: " + s(c, "after"), false)));
                    List<String> w = new ArrayList<>();
                    for (String op : strings(c, "ops")) w.add(op.replaceFirst("^#\\d+\\s+", ""));
                    details.add(indent(text("At strength " + s(c, "strength") + " it writes: " + String.join("; ", w), false)));
                }
            }
            if (s(c, "why") != null) details.add(indent(text("Why: " + s(c, "why"), true)));
        }
    }

    static Map<String, String> single(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    static String strengthText(String v) { return "1".equals(v) ? "1 (as designed)" : "0".equals(v) ? "0 (off)" : v; }

    static String plain(double d) {
        String s = new java.math.BigDecimal(d).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s.equals("0.0") ? "0" : s;
    }

    /** Save one edit to customize.txt, then read the state again. */
    void edit(Map<String, String> edits) {
        try {
            Api.setCustomize(toolDir, appDir.resolve("patches"), edits);
        } catch (IOException e) {
            prompter.tell(frame, "Survival Rebalance", "Cannot save customize.txt: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
        }
        refresh();
    }

    JComponent heading(String t) {
        JLabel l = new JLabel(t);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() * 1.35f));
        return left(l);
    }

    static JComponent gap() { return (JComponent) Box.createVerticalStrut(10); }

    static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    static JComponent indent(JComponent c) {
        c.setBorder(new EmptyBorder(0, 24, 0, 0));
        return c;
    }

    static JComponent text(String t, boolean quiet) {
        JTextArea a = new JTextArea(t);
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setOpaque(false);
        a.setFocusable(false);
        a.setBorder(null);
        Font f = new JLabel().getFont();
        a.setFont(f);
        if (quiet) a.setForeground(UIManager.getColor("Label.disabledForeground") != null ? new Color(0x55, 0x55, 0x55) : Color.DARK_GRAY);
        a.setAlignmentX(Component.LEFT_ALIGNMENT);
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return a;
    }

    // ------------------------------------------------------------------ actions

    /** The supported mods Apply rebalances (status --json lists all of Apply's and Undo's work in apply_todo and undo_todo). */
    static final List<String> TO_APPLY = List.of("not_patched", "outdated", "customized", "replaced", "interrupted", "off_but_patched", "other_version_patched");

    /** The mods apply skips because of their version, each as "name version (tested with ...)". */
    List<String> skippedNames() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : list(status, "mods"))
            if ("other_version".equals(s(m, "state"))) out.add(s(m, "name") + " " + s(m, "installed_version") + " (tested with " + String.join(" or ", strings(m, "tested_versions")) + ")");
        return out;
    }

    List<String> namesIn(List<String> states) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : list(status, "mods")) if (states.contains(s(m, "state"))) out.add(s(m, "name"));
        return out;
    }

    boolean refuseWhileRunning(String verb) {
        if (!gameRunning.getAsBoolean()) return false;
        prompter.tell(frame, verb, wrap("Starsector is running. Close the game first, then click " + verb + " again. The game holds the mods' files open, so the tool cannot change them.", 60), JOptionPane.WARNING_MESSAGE);
        return true;
    }

    void apply() {
        if (busy || status == null || refuseWhileRunning("Apply")) return;
        List<String> todo = namesIn(TO_APPLY), failing = namesIn(List.of("fails", "modified", "damaged", "dropped_modified")), un = unsupportedEnabled();
        List<String> dropped = namesIn(List.of("dropped")), work = strings(status, "apply_todo");
        if (s(status, "newer_folder") != null) { prompter.tell(frame, "Apply", wrap(headline.getText().replaceAll("<[^>]+>", "").replace("&amp;", "&"), 60), JOptionPane.WARNING_MESSAGE); return; }
        if (!Boolean.TRUE.equals(status.get("apply_has_work"))) {
            List<String> sk = skippedNames();
            prompter.tell(frame, "Apply", wrap(sk.isEmpty() ? "Nothing to apply. Every supported mod you have is rebalanced with your current choices."
                    : "Nothing to apply. Apply skips " + String.join(", ", sk) + ", because Survival Rebalance was not tested with the version you have.", 60), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder msg = new StringBuilder();
        if (!todo.isEmpty()) {
            boolean onlyChoices = namesIn(List.of("customized")).containsAll(todo);
            msg.append(onlyChoices ? "Apply your new choices to " + count(todo.size(), "mod") + "?" : "Apply the rebalance to " + count(todo.size(), "mod") + "?");
            msg.append("\n\n").append(wrapList(todo));
            if (!dropped.isEmpty()) msg.append("\n\n").append(wrap("Apply also puts back the original files of " + String.join(", ", dropped) + ". Another version of the tool rebalanced "
                    + (dropped.size() == 1 ? "it, and this version has no changes for it." : "them, and this version has no changes for them."), 60));
        } else if (!dropped.isEmpty()) {
            msg.append("Put back the original files of ").append(count(dropped.size(), "mod")).append("?\n\n").append(wrapList(dropped));
            msg.append("\n\n").append(wrap("Another version of the tool rebalanced " + (dropped.size() == 1 ? "it, and this version has no changes for it." : "them, and this version has no changes for them."), 60));
        } else if (Boolean.TRUE.equals(status.get("monitor_missing")))
            msg.append(wrap("Put the Survival Rebalance Monitor back in your mods folder? It is missing. It warns you in the game when a mod update removes the rebalance.", 60));
        else if (Boolean.TRUE.equals(status.get("monitor_to_replace")))
            msg.append(wrap("Put in this version's copy of the Survival Rebalance Monitor? The copy in your mods folder is from another version of the tool.", 60));
        String folderTodo = s(status, "folder_todo");
        if (folderTodo != null) {
            if (msg.length() == 0) msg.append(wrap(Character.toUpperCase(folderTodo.charAt(0)) + folderTodo.substring(1) + "? No mod is changed.", 60));
            else msg.append("\n\n").append(wrap("Apply will also " + folderTodo + ".", 60));
        }
        if (!failing.isEmpty()) msg.append("\n\n").append(wrap("Left as they are, because they cannot be rebalanced: " + String.join(", ", failing) + ".", 60));
        List<String> skipped = skippedNames();
        if (!skipped.isEmpty()) msg.append("\n\n").append(wrap("Skipped, because Survival Rebalance was not tested with the version you have: " + String.join(", ", skipped) + ". "
                + (skipped.size() == 1 ? "This is not a problem in the mod, and its author cannot help with it." : "This is not a problem in those mods, and their authors cannot help with it."), 60));
        if (!un.isEmpty()) msg.append("\n\n").append(wrap((un.size() == 1 ? "1 enabled mod is not supported and stays as it is: " : un.size() + " enabled mods are not supported and stay as they are: ") + String.join(", ", un) + ".", 60));
        if (!todo.isEmpty()) msg.append("\n\n").append(wrap("This also changes some of your saved mod settings (LunaLib) and adds the Survival Rebalance Monitor mod to your mods folder. The tool backs up every file before it changes it. Undo puts everything back.", 60));
        if (!prompter.confirm(frame, "Apply", msg.toString(), "Apply")) return;
        run("Apply", "apply", Math.max(1, work.size()));
    }

    void undo() {
        if (busy || status == null || refuseWhileRunning("Undo")) return;
        List<String> todo = strings(status, "undo_todo");
        if (!Boolean.TRUE.equals(status.get("undo_has_work"))) {
            prompter.tell(frame, "Undo", "Nothing to undo. No mod is rebalanced.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String msg = "Undo the rebalance on " + count(todo.size(), "mod") + "?\n\n" + wrapList(todo)
                + "\n\n" + wrap("Every changed file is put back from its backup, the saved mod settings the tool changed get their old values back, and the Survival Rebalance Monitor mod is removed if the tool installed it.", 60);
        if (!prompter.confirm(frame, "Undo", msg, "Undo")) return;
        run("Undo", "undo", todo.size());
    }

    /** Wrap a sentence at about this many characters, for message boxes. */
    static String wrap(String s, int width) {
        StringBuilder b = new StringBuilder();
        int line = 0;
        for (String w : s.split(" ")) {
            if (line > 0 && line + 1 + w.length() > width) { b.append('\n'); line = 0; }
            else if (line > 0) { b.append(' '); line++; }
            b.append(w);
            line += w.length();
        }
        return b.toString();
    }
    static String count(int n, String what) { return n + " " + what + (n == 1 ? "" : "s"); }

    static String wrapList(List<String> names) {
        StringBuilder b = new StringBuilder();
        int line = 0;
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i) + (i + 1 < names.size() ? ", " : "");
            if (line + n.length() > 60) { b.append('\n'); line = 0; }
            b.append(n);
            line += n.length();
        }
        return b.toString();
    }

    void run(String title, String cmd, int expected) { run(title, expected, null, cmd, "--offline"); }

    /** Run one of the tool's commands in the run window; then (optionally) hand the result on, and read the state again. */
    void run(String title, int expected, java.util.function.Consumer<Child.Result> then, String... args) {
        setBusy(true, title + " is running...");
        RunDialog d = new RunDialog(frame, title, expected);
        lastRun = d;
        new SwingWorker<Child.Result, String>() {
            @Override protected Child.Result doInBackground() throws Exception {
                return Child.run(jar, game, this::publish, args);
            }
            @Override protected void process(List<String> lines) { for (String l : lines) d.line(l); }
            @Override protected void done() {
                Child.Result r;
                try { r = get(); } catch (Exception e) { r = new Child.Result(); r.code = 3; r.out = "ERROR: " + e; }
                d.finish(r);
                setBusy(false, " ");
                if (then != null) then.accept(r);
                if (afterRun != null) afterRun.run();
                refresh();
            }
        }.execute();
        if (prompter == DIALOGS) d.setVisible(true);
        else d.showWithoutBlocking();
    }

    // ------------------------------------------------------------------ updates

    /** Look for a newer version once, in the background (update --check downloads nothing). */
    void checkForUpdate() {
        if (Api.updateOff() != null) { showNotice(); return; }
        new SwingWorker<Child.Result, Void>() {
            @Override protected Child.Result doInBackground() throws Exception {
                return Child.run(jar, game, null, with(updateArgs, "update", "--check"));
            }
            @Override protected void done() {
                try {
                    Child.Result r = get();
                    if (r.code == UPDATE_STAGED) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Version (\\S+) is available").matcher(r.out);
                        newer = m.find() ? m.group(1) : "?";
                    }
                } catch (Exception ignored) {
                    // no network is a normal state: no notice
                }
                showNotice();
            }
        }.execute();
    }

    /** update's exit code when a new version is available (--check) or staged. */
    static final int UPDATE_STAGED = 10;

    void showNotice() {
        if (Api.updateOff() != null) {
            notice.setText(Api.updateOff());
            notice.setForeground(QUIET);
            update.setVisible(false);
            return;
        }
        if (updatedTo != null) {
            notice.setText("Updated to version " + updatedTo + "." + (status == null || Boolean.TRUE.equals(status.get("apply_has_work")) ? " Press Apply to bring your mods up to date with it." : " Your mods are up to date with it."));
            notice.setForeground(GOOD);
        } else if (newer != null) {
            notice.setText("Version " + newer + " is available.");
            notice.setForeground(TODO);
        } else notice.setText(" ");
        update.setVisible(newer != null);
        update.setToolTipText("Download version " + newer + ", install it and open the window again. Your customize.txt and logs are kept.");
    }

    boolean restartRequested;

    /** Extra arguments for the update command (the scripted screenshot run points it at a local server). */
    String[] updateArgs = {};

    static String[] with(String[] extra, String... args) {
        String[] r = java.util.Arrays.copyOf(args, args.length + extra.length);
        System.arraycopy(extra, 0, r, args.length, extra.length);
        return r;
    }

    void update() {
        if (busy) return;
        String msg = "Download and install version " + newer + "?\n\n"
                + wrap("The window closes, installs it and opens again. Your customize.txt, game_dir.txt and logs are kept. Your mods stay as they are until you click Apply.", 60);
        if (!prompter.confirm(frame, "Update", msg, "Update")) return;
        run("Update", 1, r -> {
            if (r.code != UPDATE_STAGED) return;
            if (!launcher) {
                prompter.tell(frame, "Update", "Version " + newer + " is downloaded. Close this window and start SurvivalRebalance.exe to install it.", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            restartRequested = true;
            prompter.tell(frame, "Update", "Version " + newer + " is downloaded. The window now closes, installs it and opens again.", JOptionPane.INFORMATION_MESSAGE);
            if (prompter == DIALOGS) System.exit(EXIT_RESTART);
        }, with(updateArgs, "update"));
    }

    void openLog() {
        Path logs = toolDir.resolve("logs");
        Path newest = null;
        if (Files.isDirectory(logs)) {
            try (Stream<Path> s = Files.list(logs)) {
                newest = s.filter(p -> p.getFileName().toString().endsWith(".log")).max(java.util.Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
            } catch (IOException ignored) {
                // falls through to the message below
            }
        }
        if (newest == null) {
            prompter.tell(frame, "Open log", "There is no log yet. Apply and Undo write one to the logs folder.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try { Desktop.getDesktop().open(newest.toFile()); }
        catch (Exception e) { prompter.tell(frame, "Open log", "Cannot open " + newest + ": " + e.getMessage(), JOptionPane.ERROR_MESSAGE); }
    }

    void changeGame() {
        Path g = Games.ask(frame, toolDir, "Choose your Starsector folder, the one that holds the mods folder.");
        if (g == null) return;
        game = g;
        selectedId = null;
        refresh();
    }

    // ------------------------------------------------------------------ the mod list

    enum Group { HAVE_HEADER, HAVE, UNSUPPORTED_HEADER, UNSUPPORTED, MISSING_HEADER, MISSING }

    static final class Row {
        Group group;
        String id, name, installed, tested, state, label;
        boolean otherVersion;
        Map<String, Object> mod;
        boolean header() { return mod == null; }
    }

    static final Map<String, String> STATE = new LinkedHashMap<>();
    static {
        STATE.put("patched", "Rebalanced");
        STATE.put("not_patched", "Not rebalanced yet");
        STATE.put("outdated", "Rebalanced by an older version");
        STATE.put("customized", "Rebalanced, choices changed");
        STATE.put("replaced", "Updated, not rebalanced");
        STATE.put("modified", "Updated over the rebalance");
        STATE.put("interrupted", "Interrupted");
        STATE.put("fails", "Would fail");
        STATE.put("off", "Switched off by you");
        STATE.put("off_but_patched", "Switched off, still rebalanced");
        STATE.put("damaged", "Record damaged");
        STATE.put("checked", "Checked, needs no change");
        STATE.put("other_version", "Skipped: another version");
        STATE.put("other_version_patched", "Another version, still rebalanced");
        STATE.put("dropped", "Rebalanced by another version");
        STATE.put("not_installed", "Not installed");
    }

    final class ModTable extends AbstractTableModel {
        final List<Row> rows = new ArrayList<>();
        final String[] cols = {"Mod", "Installed", "Tested", "Status"};

        void fill(Map<String, Object> st, boolean missing) {
            rows.clear();
            List<Row> have = new ArrayList<>(), not = new ArrayList<>(), un = new ArrayList<>();
            for (Map<String, Object> m : list(st, "mods")) {
                Row r = new Row();
                r.mod = m;
                r.id = s(m, "id");
                r.name = s(m, "name");
                r.installed = s(m, "installed_version");
                boolean any = Boolean.TRUE.equals(m.get("any_version"));
                r.tested = any ? "any version" : String.join(", ", strings(m, "tested_versions"));
                r.state = s(m, "state");
                r.label = STATE.getOrDefault(r.state, r.state) + (Boolean.FALSE.equals(m.get("enabled")) && Boolean.TRUE.equals(m.get("installed")) ? " (not enabled)" : "");
                r.otherVersion = !any && !"dropped".equals(s(m, "kind")) && Boolean.TRUE.equals(m.get("installed")) && !strings(m, "tested_versions").contains(r.installed);
                if (Boolean.TRUE.equals(m.get("installed"))) { r.group = Group.HAVE; have.add(r); }
                else { r.group = Group.MISSING; not.add(r); }
            }
            for (Map<String, Object> m : list(st, "unsupported")) {
                Row r = new Row();
                r.mod = m;
                r.group = Group.UNSUPPORTED;
                r.id = s(m, "id");
                r.name = s(m, "name");
                r.installed = s(m, "installed_version");
                r.tested = "";
                r.state = "unsupported";
                r.label = Boolean.FALSE.equals(m.get("enabled")) ? "Not supported (not enabled)" : "Not supported";
                un.add(r);
            }
            java.util.Comparator<Row> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
            // rows that need the player first: then rebalanced, then the ones that need no change
            have.sort(java.util.Comparator.comparingInt((Row r) -> r.state.equals("checked") ? 2 : r.state.equals("patched") ? 1 : 0).thenComparing(byName));
            un.sort(byName);
            not.sort(byName);
            if (!un.isEmpty()) {
                int on = 0;
                for (Row r : un) if (!Boolean.FALSE.equals(r.mod.get("enabled"))) on++;
                rows.add(header(Group.UNSUPPORTED_HEADER, "Not supported (" + on + " enabled" + (un.size() > on ? ", " + (un.size() - on) + " not enabled" : "") + ")"));
                rows.addAll(un);
            }
            rows.add(header(Group.HAVE_HEADER, "Supported mods you have (" + have.size() + ")"));
            rows.addAll(have);
            rows.add(header(Group.MISSING_HEADER, (missing ? "[-] " : "[+] ") + "Supported, not installed (" + not.size() + ")"));
            if (missing) rows.addAll(not);
            fireTableDataChanged();
        }

        Row header(Group g, String text) {
            Row r = new Row();
            r.group = g;
            r.name = text;
            return r;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row row = rows.get(r);
            if (row.header()) return c == 0 ? row.name : "";
            switch (c) {
                case 0: return row.name;
                case 1: return row.installed == null ? "" : row.installed;
                case 2: return row.tested;
                default: return row.label;
            }
        }
    }

    static final Color GOOD = new Color(0x1E, 0x7B, 0x34), BAD = new Color(0xB0, 0x24, 0x18), TODO = new Color(0x9A, 0x5B, 0x00), QUIET = new Color(0x6B, 0x6B, 0x6B);

    final class RowRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int c) {
            Row row = model.rows.get(r);
            super.getTableCellRendererComponent(t, v, sel && !row.header(), focus && !row.header(), r, c);
            setBorder(new EmptyBorder(0, c == 0 && !row.header() ? 18 : 6, 0, 6));
            Font f = t.getFont();
            if (row.header()) {
                setFont(f.deriveFont(Font.BOLD));
                setBackground(new Color(0xE8, 0xEB, 0xF0));
                setForeground(row.group == Group.UNSUPPORTED_HEADER ? BAD : Color.BLACK);
                setToolTipText(row.group == Group.MISSING_HEADER ? "Click or press Enter to show or hide them." : null);
                return this;
            }
            setFont(f);
            setToolTipText(row.mod != null ? s(row.mod, row.group == Group.UNSUPPORTED ? "id" : "state_text") : null);
            if (!sel) {
                setBackground(t.getBackground());
                Color fg = Color.BLACK;
                if (row.group == Group.MISSING) fg = QUIET;
                else if (c == 2 && row.otherVersion) { fg = TODO; setFont(f.deriveFont(Font.BOLD)); setToolTipText("Installed " + row.installed + " is not the tested version " + row.tested + "." + (row.state.startsWith("other_version") ? " Apply skips it." : "")); }
                else if (c == 3) {
                    String st = row.state;
                    if (st.equals("patched") || st.equals("checked")) fg = GOOD;
                    else if (st.equals("fails") || st.equals("modified") || st.equals("damaged") || st.equals("unsupported")) fg = BAD;
                    else if (st.equals("off") || st.equals("not_installed")) fg = QUIET;
                    else fg = TODO;
                }
                setForeground(fg);
            }
            return this;
        }
    }

    // ------------------------------------------------------------------ the run window

    final class RunDialog extends JDialog {
        final JProgressBar bar = new JProgressBar();
        final JLabel now = new JLabel(" ");
        final JTextArea result = new JTextArea(12, 64), log = new JTextArea(14, 70);
        final JScrollPane logScroll = new JScrollPane(log);
        final JButton close = new JButton("Close"), showLog = new JButton("Show log");
        final int expected;
        int seen;
        final String title;

        RunDialog(JFrame owner, String title, int expected) {
            super(owner, title, true);
            this.title = title;
            this.expected = Math.max(1, expected);
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            JPanel p = new JPanel(new BorderLayout(0, 8));
            p.setBorder(new EmptyBorder(12, 12, 12, 12));
            JPanel top = new JPanel(new BorderLayout(0, 6));
            now.setFont(now.getFont().deriveFont(Font.BOLD, now.getFont().getSize2D() * 1.3f));
            now.setText(title + " is running...");
            bar.setMaximum(this.expected);
            bar.setStringPainted(true);
            bar.setString("0 of " + count(this.expected, "mod"));
            if (title.equals("Update")) { bar.setIndeterminate(true); bar.setString("Downloading"); }
            top.add(now, BorderLayout.NORTH);
            top.add(bar, BorderLayout.CENTER);
            p.add(top, BorderLayout.NORTH);
            for (JTextArea a : new JTextArea[]{result, log}) { a.setEditable(false); a.setLineWrap(true); a.setWrapStyleWord(true); }
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, new JLabel().getFont().getSize()));
            result.setFont(new JLabel().getFont());
            result.setText("Each mod shows in the log as the tool reaches it.");
            result.setOpaque(false);
            JPanel mid = new JPanel(new BorderLayout(0, 8));
            JScrollPane rs = new JScrollPane(result);
            rs.setBorder(null);
            mid.add(rs, BorderLayout.CENTER);
            logScroll.setPreferredSize(new Dimension(760, 240));
            logScroll.setVisible(false);
            mid.add(logScroll, BorderLayout.SOUTH);
            p.add(mid, BorderLayout.CENTER);
            JPanel b = new JPanel(new BorderLayout());
            showLog.addActionListener(e -> {
                logScroll.setVisible(!logScroll.isVisible());
                showLog.setText(logScroll.isVisible() ? "Hide log" : "Show log");
                validate();
            });
            b.add(showLog, BorderLayout.WEST);
            close.setEnabled(false);
            close.addActionListener(e -> dispose());
            b.add(close, BorderLayout.EAST);
            p.add(b, BorderLayout.SOUTH);
            setContentPane(p);
            setSize(820, 640);
            setLocationRelativeTo(owner);
        }

        void showWithoutBlocking() { setModal(false); setVisible(true); }

        void line(String l) {
            log.append(l + "\n");
            if (l.startsWith("== ")) {
                seen++;
                String name = l.substring(3).replaceAll("\\s+\\(.*$", "");
                result.setText("Now: " + name);
                bar.setValue(Math.min(seen, expected));
                bar.setString(Math.min(seen, expected) + " of " + count(expected, "mod"));
            }
        }

        void finish(Child.Result r) {
            bar.setVisible(false);
            Map<String, String> names = new LinkedHashMap<>();
            for (Map<String, Object> m : Window.list(status, "mods")) names.put(Window.s(m, "id"), Window.s(m, "name"));
            if (title.equals("Update")) now.setText(r.code == UPDATE_STAGED ? "Update downloaded and checked." : r.code == 0 ? "You have the latest version." : "Update failed. Your version still works.");
            else now.setText(RunReport.headline(title, r.out, r.code));
            result.setText(RunReport.summary(title, r.out, r.code, names));
            result.setCaretPosition(0);
            log.setCaretPosition(log.getDocument().getLength());
            close.setEnabled(true);
            getRootPane().setDefaultButton(close);
            close.requestFocusInWindow();
            setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        }
    }
    /** Paint a component into an image (the scripted screenshot run uses this). */
    static BufferedImage paint(Component c) {
        BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        c.paint(g);
        g.dispose();
        return img;
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
