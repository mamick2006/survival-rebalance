package survivalrebalance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

final class Files2 {
    private Files2() {}

    /** Write through a temp file beside the target and an atomic move, keeping the old modified time if asked. */
    static void atomicWrite(Path target, byte[] data) throws Failure {
        atomicWrite(target, data, null);
    }

    static void atomicWrite(Path target, byte[] data, FileTime mtime) throws Failure {
        Path tmp = target.resolveSibling(target.getFileName() + ".srtmp");
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.write(tmp, data);
            if (mtime != null) Files.setLastModifiedTime(tmp, mtime);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // a write that failed leaves no temp file beside the target
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw new Failure("cannot write " + target + ": " + e.getMessage());
        }
    }

    static String sha(Path p) throws Failure {
        try { return Text.sha256(Files.readAllBytes(p)); } catch (IOException e) { throw new Failure("cannot read " + p + ": " + e.getMessage()); }
    }

    static FileTime mtime(Path p) {
        try { return Files.getLastModifiedTime(p); } catch (IOException e) { return null; }
    }

    /**
     * The open writable() makes on Windows: write access, sharing nothing with any other handle.
     * Null where those options do not exist or do nothing (Linux and macOS run the tool through
     * the .sh, and a jre without jdk.unsupported has no such class).
     */
    private static final OpenOption[] ALONE = alone();

    private static OpenOption[] alone() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        try {
            return new OpenOption[]{StandardOpenOption.WRITE, com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE};
        } catch (LinkageError e) {
            return null;
        }
    }

    /** How many times writable() waited for a file to come free; the tests read it. */
    static volatile int waits;

    /**
     * Set once a file stayed in use through the whole wait. From then on in this run a check
     * tries once: the game is most likely running, and waiting a second for each of its mods
     * would only slow the refusal down. Main.run clears it at the start of every run.
     */
    static volatile boolean sawBusy;

    /**
     * True when the file can be replaced now. It moves, renames and writes nothing, so status
     * may call it as often as it likes. On Windows the file is opened for writing with no read,
     * write or delete sharing and closed at once: that open fails while any other program holds
     * the file at all, the running game's class loader included (it shares read and write but
     * not delete, so the replace, a rename, would fail). Elsewhere it falls back to a plain
     * write open with a lock, the check the tool used before 1.3.3. A file held for a moment
     * (a virus scanner reading it) is not in use: the open is tried again for about a second
     * before the answer is no. A missing or read-only file is no at once.
     */
    static boolean writable(Path p) {
        // no Files.isWritable in front: on a share whose rights Windows cannot work out it can say no
        // for a file that can be written. The open decides; a refused open (read-only, no rights) is
        // no at once, without the wait.
        if (!Files.isRegularFile(p)) return false;
        for (int i = 0; ; i++) {
            int r = openAlone(p);
            if (r == FREE) return true;
            if (r == DENIED || sawBusy || !Files.isRegularFile(p)) return false;
            if (i >= 10) { sawBusy = true; return false; }
            waits++;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    static final int FREE = 0, BUSY = 1, DENIED = 2;

    /**
     * True when the file cannot be written for a reason no wait cures: it is marked read-only,
     * or the open is refused for want of rights. False for a file another program holds.
     */
    static boolean denied(Path p) {
        try {
            Object ro = Files.getAttribute(p, "dos:readonly");
            if (Boolean.TRUE.equals(ro)) return true;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            // not a DOS file system: the open below decides
        }
        return Files.isRegularFile(p) && openAlone(p) == DENIED;
    }

    private static int openAlone(Path p) {
        if (ALONE != null) {
            try (FileChannel c = FileChannel.open(p, ALONE)) {
                return FREE;
            } catch (java.nio.file.AccessDeniedException e) {
                return DENIED;
            } catch (IOException e) {
                return BUSY;
            } catch (UnsupportedOperationException e) {
                // this jre does not take the options after all: the plain open below decides
            }
        }
        try (FileChannel c = FileChannel.open(p, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            java.nio.channels.FileLock l = c.tryLock();
            if (l == null) return BUSY;
            l.release();
            return FREE;
        } catch (java.nio.file.AccessDeniedException e) {
            return DENIED;
        } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
            return BUSY;
        }
    }

    static void deleteTree(Path p) throws Failure {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                try { Files.setAttribute(x, "dos:readonly", false); } catch (IOException | UnsupportedOperationException ignored) {}
                Files.delete(x);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw new Failure("cannot remove " + p + ": " + e.getMessage());
        }
    }
}
