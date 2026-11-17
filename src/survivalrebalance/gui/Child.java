package survivalrebalance.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs one of the tool's own commands in a child process on the same Java and jar, so the
 * window holds no mod file open and a crash in a run cannot take the window down.
 */
final class Child {
    private Child() {}

    static final class Result {
        int code;
        String out;
    }

    /** The Java this window runs on; javaw on Windows, so no console window flashes up. */
    static String java() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String n : new String[]{"javaw.exe", "java.exe", "java"}) if (Files.isRegularFile(bin.resolve(n))) return bin.resolve(n).toString();
        return "java";
    }

    static List<String> command(Path jar, Path game, String... args) {
        List<String> c = new ArrayList<>();
        c.add(java());
        c.add("-Dsun.stdout.encoding=UTF-8");
        c.add("-Dsun.stderr.encoding=UTF-8");
        c.add("-Dfile.encoding=UTF-8");
        c.add("-jar");
        c.add(jar.toString());
        for (String a : args) c.add(a);
        if (game != null) { c.add("--game"); c.add(game.toString()); }
        return c;
    }

    /** Run and collect the output; each line also goes to onLine as it arrives (may be null). */
    static Result run(Path jar, Path game, Consumer<String> onLine, String... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command(jar, game, args));
        pb.redirectErrorStream(true);
        pb.directory(jar.getParent().toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                b.append(line).append('\n');
                if (onLine != null) onLine.accept(line);
            }
        }
        Result res = new Result();
        res.code = p.waitFor();
        res.out = b.toString();
        return res;
    }

    /** True when a Starsector process from this game folder is running (the tool's own processes aside). */
    static boolean gameRunning(Path game) { return survivalrebalance.GameRunning.test(game); }
}
