package survivalrebalance;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Puts the tool folder right when apply or undo runs (status, check and a dry run only say what
 * apply would do): the files of this version that are missing or differ are written from the
 * bundle, and the files another version left there are removed. A file whose bytes no version
 * shipped (the player edited it) is copied to <name>.edited before it is replaced. The tool
 * writes nothing in a folder its files were put loose into (see loose()), not even its log.
 * A file is removed only when it is not the player's (the updater's rule), it is not the jar
 * that runs, and a version of the tool shipped exactly that file: history.txt lists its path with
 * the sha256 of its bytes. A file no version shipped, or a shipped name with other bytes, is never
 * touched; it is named once as not used. Only the paths history.txt names are looked at, and the
 * patches folders are listed (not walked) for patch files nobody shipped.
 *
 * It acts only on the release layout: the jar in a folder named app. Files above app\ are
 * touched only when that folder holds shipped.txt, so a tool unzipped into another folder
 * never touches that folder's own files. Nothing here fails a run: the bundle rules either way.
 */
final class ToolFolder {
    private ToolFolder() {}

    /** An installer runs for seconds; one older than this is left over (it may still be running the apply it starts). */
    static final long HELPER_AGE_MS = 10 * 60 * 1000L;

    static final class Result {
        final List<String> removed = new ArrayList<>(), unused = new ArrayList<>(), written = new ArrayList<>(), mixed = new ArrayList<>(), failed = new ArrayList<>(), saved = new ArrayList<>();
        /** Without write: what apply would do in the folder, as words after "Apply will", or null for nothing. */
        String todo;
    }

    /** The release folder the jar's folder belongs to, or null when this is not the release layout. */
    static Path root(Path appDir) {
        if (appDir == null || appDir.getFileName() == null || appDir.getParent() == null) return null;
        return appDir.getFileName().toString().equalsIgnoreCase("app") ? appDir.getParent() : null;
    }

    // ---------------------------------------------------------------- the version file

    /** Every release from 1.3.4 on ships app\version-<its version>.txt, with this one line in it. */
    static String versionFile(String v) { return "version-" + v + ".txt"; }

    /** The one line of a version file, without its line end. Every release keeps these words. */
    static String versionLine(String v) { return "The files in this folder are from Survival Rebalance " + v + "."; }

    static String versionText(String v) { return versionLine(v) + "\r\n"; }

    static final java.util.regex.Pattern VERSION_FILE = java.util.regex.Pattern.compile("(?i)version-([0-9][0-9A-Za-z.\\-]{0,40})\\.txt");

    /**
     * The version a file in app\ names when it is a version file a release shipped, else null. The
     * version comes from the file's name. Its first line must be versionLine(v); a BOM, LF or CRLF
     * line ends and blank lines or spaces after it are accepted, since an editor may have saved it.
     */
    static String versionOf(Path p) {
        java.util.regex.Matcher m = VERSION_FILE.matcher(String.valueOf(p.getFileName()));
        if (!m.matches() || !Files.isRegularFile(p)) return null;
        try {
            if (Files.size(p) > 200) return null;
            String s = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) s = s.substring(1);
            int nl = s.indexOf('\n');
            String first = (nl < 0 ? s : s.substring(0, nl)).stripTrailing(), rest = nl < 0 ? "" : s.substring(nl + 1);
            return first.equals(versionLine(m.group(1))) && rest.isBlank() ? m.group(1) : null;
        } catch (IOException e) { return null; }
    }

    /** The version files in app\, by version. */
    static Map<String, Path> versionFiles(Path appDir) {
        Map<String, Path> m = new java.util.TreeMap<>();
        if (appDir == null || !Files.isDirectory(appDir)) return m;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(appDir, "version-*.txt")) {
            for (Path p : ds) { String v = versionOf(p); if (v != null) m.put(v, p); }
        } catch (IOException | java.nio.file.DirectoryIteratorException e) { /* nothing to compare */ }
        return m;
    }

    /**
     * The newest version whose files sit in app\ when it is newer than the jar that runs, else null.
     * That happens when a newer version was unzipped over this one and the unzip kept the files
     * that were there: the newer version's new files are in, its jar is not.
     */
    static String newerFolder(Path appDir, String running) {
        String best = null;
        for (String v : versionFiles(appDir).keySet())
            if (Checks.compareVersions(v, running) > 0 && (best == null || Checks.compareVersions(v, best) > 0)) best = v;
        return best;
    }

    /**
     * The one line every command says first when newerFolder() found a newer version. The tool
     * cannot tell which of two unzips left the folder so, so the line gives both and what to do for each.
     */
    static String newerText(String folder, String running) {
        return "This folder holds the files of Survival Rebalance " + folder + ", but the program that runs is version " + running + "."
                + " If you unzipped " + folder + " over " + running + " and kept the old files, unzip " + folder + " again and choose to replace the files."
                + " If you unzipped " + running + " over " + folder + " to go back, copy customize.txt out, delete the SurvivalRebalance folder, unzip " + running + " again and put customize.txt back."
                + " Until then Apply changes no mod. Undo still works.";
    }

    // ---------------------------------------------------------------- the loose layout

    /** Names at the top of a tool folder that belong to the player or to the updater. */
    static final java.util.Set<String> PLAYER_TOP = java.util.Set.of("customize.txt", "game_dir.txt", "disabled_changes.txt", "logs", "update");

    /** The tool's own copy of a file the player changed: README.txt.edited, shipped.txt.edited2. */
    static final java.util.regex.Pattern EDITED = java.util.regex.Pattern.compile("(?i).+\\.edited[0-9]*");

    /**
     * True when the folder above app\ is not a folder of the tool's own: the zip's contents were
     * put loose into the Starsector folder (it holds mods or starsector-core), or into a folder of
     * another name that holds more than three things no version of the tool shipped (Downloads,
     * say). A folder whose name starts with SurvivalRebalance is the tool's own whatever the
     * player keeps in it. The tool's .edited copies never count.
     */
    static boolean loose(Path root, Bundle b, History h) {
        if (Files.isDirectory(root.resolve("mods")) || Files.isDirectory(root.resolve("starsector-core"))) return true;
        Path name = root.getFileName();
        if (name != null && name.toString().toLowerCase(Locale.ROOT).startsWith("survivalrebalance")) return false;
        java.util.Set<String> ours = new java.util.HashSet<>(PLAYER_TOP);
        if (b != null) for (String rel : b.files.keySet()) ours.add(rel.split("/")[0].toLowerCase(Locale.ROOT));
        for (String rel : h.files.keySet()) ours.add(rel.split("/")[0]);
        ours.add("app");
        int foreign = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String n = String.valueOf(p.getFileName());
                if (!ours.contains(n.toLowerCase(Locale.ROOT)) && !EDITED.matcher(n).matches() && ++foreign > 3) return true;
            }
        } catch (IOException | java.nio.file.DirectoryIteratorException e) {
            return true; // a folder it cannot list is not one to write in
        }
        return false;
    }

    /** loose() for the folder above this jar folder; false when the jar is not in a folder named app. */
    static boolean loose(Path appDir) {
        Path root = root(appDir);
        return root != null && Files.isDirectory(root) && loose(root, Bundle.current(), History.get());
    }

    /**
     * Put the folder right. With write false (status, check, a dry run) nothing is written or
     * removed: the notes say what apply would do.
     */
    static Result putRight(Path appDir, Path runningJar, Bundle b, Warnings w, boolean write) {
        Result r = new Result();
        Path root = root(appDir);
        if (b == null || root == null || !Files.isDirectory(appDir)) return r;
        boolean top = Files.isRegularFile(root.resolve(Updater.SHIPPED));
        History h = History.get();
        if (loose(root, b, h)) {
            w.warn("Survival Rebalance", "its files are loose in " + root + ", not in a folder of their own, so the tool writes nothing there.",
                    "Unzip SurvivalRebalance.zip into the Starsector folder as it is, so it makes a SurvivalRebalance folder, and run the tool from that folder.");
            return r;
        }
        String self = runningJar != null && runningJar.startsWith(root) ? rel(root, runningJar) : Bundle.APP + Updater.JAR;

        for (String rel : b.files.keySet()) {
            if (!top && !rel.startsWith(Bundle.APP)) continue;
            Path p = root.resolve(rel);
            String had;
            try { had = Files.isRegularFile(p) ? Files2.sha(p) : null; } catch (Failure f) { r.failed.add(rel); continue; }
            if (b.sha.get(rel).equals(had)) continue;
            // cmd and Windows hold a running .bat or .exe, so one of another version is reported, not replaced
            if (Files.exists(p) && running(rel)) { r.mixed.add(rel); continue; }
            // bytes no version shipped are the player's own edit: a copy is kept beside the file
            boolean edited = had != null && !h.shipped(rel, had);
            if (!write) { r.written.add(rel); if (edited) r.saved.add(rel); continue; }
            try {
                if (edited) {
                    Path keep = p.resolveSibling(p.getFileName() + ".edited");
                    for (int i = 2; Files.exists(keep); i++) keep = p.resolveSibling(p.getFileName() + ".edited" + i);
                    Files.copy(p, keep);
                    r.saved.add(rel(root, keep));
                }
                Files2.atomicWrite(p, b.files.get(rel));
                r.written.add(rel);
            } catch (Failure | IOException f) { r.failed.add(rel); }
        }

        // the paths history.txt names, one by one (never a walk: the tool folder may be a big folder)
        TreeSet<Path> dirs = new TreeSet<>();
        for (Map.Entry<String, String> e : h.spelling.entrySet()) {
            String rel = e.getValue();
            if ((!top && !e.getKey().startsWith(Bundle.APP)) || Updater.userOwned(rel) || b.find(rel) != null || rel.equalsIgnoreCase(self)) continue;
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) continue;
            String sha;
            try { sha = Files2.sha(p); } catch (Failure f) { r.failed.add(rel); continue; }
            // a file is another version's only when its path and its bytes are one a version shipped
            if (!h.shipped(rel, sha)) { r.unused.add(rel); continue; }
            remove(r, root, p, rel, dirs, write);
        }
        // an older version's version file: its text says which version shipped it, so history need not list it
        for (Map.Entry<String, Path> e : versionFiles(appDir).entrySet()) {
            String rel = rel(root, e.getValue());
            if (Checks.compareVersions(e.getKey(), Main.VERSION) < 0 && b.find(rel) == null && !r.removed.contains(rel)) remove(r, root, e.getValue(), rel, dirs, write);
        }
        // a patch file no version shipped is not read, and the player hears so
        for (String pd : top ? new String[]{Bundle.PATCHES, "patches/"} : new String[]{Bundle.PATCHES}) {
            Path d = root.resolve(pd);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.json")) {
                for (Path p : ds) {
                    String rel = rel(root, p);
                    if (Files.isRegularFile(p) && b.find(rel) == null && !h.named(rel)) r.unused.add(rel);
                }
            } catch (IOException | java.nio.file.DirectoryIteratorException ex) {
                r.failed.add(pd);
            }
        }
        // the installer an update left behind (it does not delete itself): only that one file, only when its
        // bytes are the tool's own installer, and only when no update is under way (no marker, nothing staged,
        // no download) and it was written long ago
        Path ud = root.resolve(Updater.DIR), helper = ud.resolve(Updater.HELPER);
        if (top && Files.isRegularFile(helper, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.exists(ud.resolve(Updater.MARKER))
                && !Files.exists(ud.resolve(Updater.STAGED)) && !Files.exists(ud.resolve(Updater.ZIP))) {
            java.nio.file.attribute.FileTime t = Files2.mtime(helper);
            boolean own;
            try { own = Files.size(helper) < 16384 && Updater.isHelper(Files.readAllBytes(helper)); } catch (IOException e) { own = false; }
            if (own && t != null && System.currentTimeMillis() - t.toMillis() > HELPER_AGE_MS) remove(r, root, helper, rel(root, helper), dirs, write);
        }
        if (write) for (Path d : dirs.descendingSet()) {
            try (Stream<Path> s = Files.list(d)) { if (s.findAny().isEmpty()) Files.delete(d); } catch (IOException | java.io.UncheckedIOException ignored) {}
        }

        if (write) {
            if (!r.removed.isEmpty()) w.note("Removed the files another version of the tool left in its folder: " + list(r.removed) + ".");
            if (!r.written.isEmpty()) w.note("Put back the files of this version that were missing or changed in the tool folder: " + list(r.written) + "."
                    + (r.saved.isEmpty() ? "" : " Your own copy is kept as " + list(r.saved) + "."));
        } else if (!r.removed.isEmpty() || !r.written.isEmpty()) {
            // one line, and nothing removed: apply does the tidying
            String rm = r.removed.isEmpty() ? null : "remove the files another version of the tool left in its folder (" + list(r.removed) + ")";
            String wr = r.written.isEmpty() ? null : "put back the files of this version that are missing or changed (" + list(r.written) + ")";
            r.todo = rm == null ? wr : wr == null ? rm : rm + " and " + wr;
            w.note("Apply will " + r.todo + "."
                    + (r.saved.isEmpty() ? "" : " It keeps a copy of your own " + list(r.saved) + " beside it."));
        }
        if (!r.unused.isEmpty()) w.note(r.unused.size() == 1
                ? list(r.unused) + " is in the tool folder, but this version does not use it. The tool leaves it where it is."
                : "These files are in the tool folder, but this version does not use them: " + list(r.unused) + ". The tool leaves them where they are.");
        if (!r.mixed.isEmpty()) w.warn("Survival Rebalance", "this folder mixes two versions (" + list(r.mixed) + (r.mixed.size() == 1 ? " is" : " are") + " from another version).", "Delete the SurvivalRebalance folder and unzip the tool again. Copy customize.txt out first if you changed it, and put it back after.");
        if (!r.failed.isEmpty()) w.note("The tool could not tidy " + list(r.failed) + " in its folder. It uses its own changes anyway.");
        return r;
    }

    /** Remove one file another version left (or, without write, only count it). */
    static void remove(Result r, Path root, Path p, String rel, TreeSet<Path> dirs, boolean write) {
        if (!write) { r.removed.add(rel); return; }
        try {
            Files.delete(p);
            r.removed.add(rel);
            for (Path d = p.getParent(); d != null && !d.equals(root) && d.startsWith(root); d = d.getParent()) dirs.add(d);
        } catch (IOException ex) {
            r.failed.add(rel);
        }
    }

    static boolean running(String rel) {
        String l = rel.toLowerCase(Locale.ROOT);
        return l.endsWith(".bat") || l.endsWith(".exe") || l.endsWith(".sh");
    }

    static String rel(Path root, Path p) { return root.relativize(p).toString().replace('\\', '/'); }

    /** The paths as the player reads them, with backslashes; a folder with more than 10 of them is one item. */
    static String list(List<String> rels) {
        java.util.Map<String, Integer> per = new java.util.LinkedHashMap<>();
        for (String r : rels) per.merge(group(r), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (String r : rels) {
            String g = group(r);
            if (g != null && per.get(g) > 10) { if (done.add(g)) out.add(g.replace('/', '\\') + " (" + per.get(g) + " files)"); }
            else out.add(r.replace('/', '\\'));
        }
        return String.join(", ", out);
    }

    /** The folder a path is counted under: its first folder, or app\ and the one below it. Null for a file at the top. */
    static String group(String rel) {
        String[] s = rel.split("/");
        if (s.length < 2) return null;
        if (s[0].equalsIgnoreCase("app")) return s.length < 3 ? null : s[0] + "/" + s[1] + "/";
        return s[0] + "/";
    }
}
