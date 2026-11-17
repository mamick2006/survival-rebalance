package survivalrebalance;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Whether the game is running from a game folder: the window and apply and undo refuse while it is. */
public final class GameRunning {
    private GameRunning() {}

    /** The tests replace it. */
    static java.util.function.Predicate<Path> check = GameRunning::test;

    /** One running process, as the check reads it. The tests build these by hand. */
    static final class Proc {
        final long pid;
        final String command, args, parentCommand;
        final Long parent;
        Proc(long pid, String command, String args, Long parent, String parentCommand) {
            this.pid = pid; this.command = command; this.args = args; this.parent = parent; this.parentCommand = parentCommand;
        }
    }

    /** True when a Starsector process from this game folder is running (the tool's own processes aside). */
    public static boolean test(Path game) {
        if (game == null) return false;
        long me = ProcessHandle.current().pid();
        Set<Long> mine = new java.util.HashSet<>();
        mine.add(me);
        ProcessHandle.current().descendants().forEach(h -> mine.add(h.pid()));
        String root = game.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT) + File.separator;
        List<Proc> all = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(h -> {
            String cmd = h.info().command().orElse("");
            String l = cmd.toLowerCase(Locale.ROOT);
            // only a process in this game folder, or a starsector.exe, can count: the rest are not read further
            if (!l.startsWith(root) && !l.endsWith(File.separator + "starsector.exe")) return;
            all.add(new Proc(h.pid(), cmd, String.join(" ", h.info().arguments().orElse(new String[0])),
                    h.parent().map(ProcessHandle::pid).orElse(null), h.parent().flatMap(pp -> pp.info().command()).orElse("")));
        });
        return decide(game, all, mine, me);
    }

    /**
     * The decision on a list of processes: starsector.exe under the game folder, or the game's own
     * Java (under its jre folder) running the game's classes, or with no readable arguments started
     * by starsector.exe. The tool's own processes (mine, and any whose arguments name the tool) never count.
     */
    static boolean decide(Path game, List<Proc> procs, Set<Long> mine, long me) {
        String root = game.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT) + File.separator;
        for (Proc h : procs) {
            if (mine.contains(h.pid)) continue;
            String cmd = h.command.toLowerCase(Locale.ROOT);
            if (cmd.isEmpty()) continue;
            if (cmd.endsWith(File.separator + "starsector.exe")) { if (cmd.startsWith(root)) return true; continue; }
            if (!cmd.startsWith(root)) continue;
            // the game's own Java; the tool's windows and runs use it too, so check the arguments where they can be read
            String args = h.args.toLowerCase(Locale.ROOT);
            if (args.contains("survivalrebalance")) continue;
            if (!cmd.contains(File.separator + "jre" + File.separator) || (h.parent != null && h.parent == me)) continue;
            if (args.isEmpty() ? h.parentCommand.toLowerCase(Locale.ROOT).endsWith("starsector.exe") : args.contains("starfarer") || args.contains("com.fs.")) return true;
        }
        return false;
    }
}
