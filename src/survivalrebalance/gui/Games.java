package survivalrebalance.gui;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Finding the Starsector folder: beside the tool, then game_dir.txt, then the player's choice. */
final class Games {
    private Games() {}

    static final String GAME_DIR_FILE = "game_dir.txt";

    static boolean isGame(Path g) { return g != null && Files.isDirectory(g.resolve("mods")); }

    static Path find(Path toolDir) {
        Path parent = toolDir.getParent();
        if (isGame(parent)) return parent;
        Path f = toolDir.resolve(GAME_DIR_FILE);
        if (Files.isRegularFile(f)) {
            try {
                String s = Files.readString(f, StandardCharsets.UTF_8).replace("﻿", "").replace("\"", "").trim();
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(0, nl).trim();
                if (!s.isEmpty() && isGame(Path.of(s))) return Path.of(s).toAbsolutePath().normalize();
            } catch (IOException | RuntimeException ignored) {
                // asked below
            }
        }
        return null;
    }

    /** Ask with a folder picker until the player picks a game folder or cancels; saves the pick in game_dir.txt. */
    static Path ask(Component parent, Path toolDir, String why) {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(why);
        fc.setApproveButtonText("Use this folder");
        while (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            Path g = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
            if (!isGame(g) && isGame(g.getParent())) g = g.getParent(); // picked the mods folder itself
            if (isGame(g)) {
                try { Files.writeString(toolDir.resolve(GAME_DIR_FILE), g + "\r\n", StandardCharsets.UTF_8); }
                catch (IOException e) { JOptionPane.showMessageDialog(parent, "Cannot save the choice in " + GAME_DIR_FILE + ": " + e.getMessage()); }
                return g;
            }
            JOptionPane.showMessageDialog(parent, g + " has no mods folder. Choose your Starsector folder, the one that holds the mods folder.",
                    "Not a Starsector folder", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }
}
