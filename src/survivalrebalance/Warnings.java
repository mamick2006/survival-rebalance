package survivalrebalance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that might go wrong, each with one plain sentence on what to do. Printed at the
 * end of every apply, undo, status and check run, and at the top of the log file.
 */
final class Warnings {
    static final class W {
        final String subject, text, advice;
        W(String s, String t, String a) { subject = s; text = t; advice = a; }
    }

    final List<W> warnings = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    private final Set<String> seen = new LinkedHashSet<>();

    /** A warning: subject (a mod name, or "customize.txt"), what is wrong, what to do. */
    void warn(String subject, String text, String advice) {
        if (!seen.add("w\n" + subject + "\n" + text)) return;
        warnings.add(new W(subject, text, advice));
    }

    /** Information that needs no action, one line. */
    void note(String text) {
        if (seen.add("n\n" + text)) notes.add(text);
    }

    int count() { return warnings.size(); }

    /** True when a warning's subject or text contains this (for tests). */
    boolean has(String part) {
        for (W w : warnings) if (w.text.contains(part) || w.subject.contains(part)) return true;
        return false;
    }

    String render() {
        StringBuilder b = new StringBuilder();
        if (warnings.isEmpty()) b.append("No warnings\n");
        else {
            b.append("Warnings (").append(warnings.size()).append(")\n");
            int i = 1;
            for (W w : warnings) {
                b.append(String.format("  %2d. ", i++)).append(w.subject).append(": ").append(w.text).append('\n');
                if (w.advice != null && !w.advice.isEmpty()) b.append("      What to do: ").append(w.advice).append('\n');
            }
        }
        for (String n : notes) b.append("  Note: ").append(n).append('\n');
        return b.toString();
    }
}
