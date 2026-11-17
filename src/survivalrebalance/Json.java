package survivalrebalance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A JSON parser that accepts the game's loose JSON (comments, unquoted keys and values,
 * trailing commas, '=' separators) and remembers where every value sits in the text, so an
 * edit can rewrite one value's characters and nothing else. Strict JSON is a subset, so the
 * patch files go through the same parser.
 */
final class Json {
    private Json() {}

    abstract static class Node {
        int start, end; // character span of the value's token(s) in the source text
    }

    static final class Obj extends Node {
        final List<String> keys = new ArrayList<>();
        final List<Node> values = new ArrayList<>();

        Node get(String k) {
            Node found = null;
            for (int i = 0; i < keys.size(); i++) if (keys.get(i).equals(k)) found = values.get(i);
            return found;
        }

        int count(String k) {
            int n = 0;
            for (String s : keys) if (s.equals(k)) n++;
            return n;
        }

        String str(String k) {
            Node n = get(k);
            if (n == null) return null;
            if (n instanceof Str) return ((Str) n).value;
            if (n instanceof Num) return ((Num) n).raw;
            if (n instanceof Bool) return String.valueOf(((Bool) n).value);
            return null;
        }

        Obj obj(String k) {
            Node n = get(k);
            return n instanceof Obj ? (Obj) n : null;
        }

        Arr arr(String k) {
            Node n = get(k);
            return n instanceof Arr ? (Arr) n : null;
        }
    }

    static final class Arr extends Node {
        final List<Node> items = new ArrayList<>();
    }

    static final class Str extends Node {
        final String value;
        final boolean quoted;
        Str(String v, boolean q) { value = v; quoted = q; }
    }

    static final class Num extends Node {
        final String raw;
        final BigDecimal value;
        Num(String raw, BigDecimal v) { this.raw = raw; this.value = v; }
    }

    static final class Bool extends Node {
        final boolean value;
        Bool(boolean v) { value = v; }
    }

    static final class Null extends Node {}

    static final class ParseException extends Exception {
        ParseException(String m) { super(m); }
    }

    static Node parse(String text) throws ParseException {
        P p = new P(text);
        p.ws();
        Node n = p.value();
        p.ws();
        if (p.i < text.length()) throw new ParseException("unexpected text at character " + p.i);
        return n;
    }

    private static final class P {
        final String s;
        int i;
        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '﻿') { i++; continue; }
                if (c == '#') { while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++; continue; }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++; continue;
                }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int e = s.indexOf("*/", i + 2);
                    i = e < 0 ? s.length() : e + 2; continue;
                }
                break;
            }
        }

        ParseException err(String m) {
            int line = 1;
            for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
            return new ParseException(m + " (line " + line + ")");
        }

        Node value() throws ParseException {
            if (i >= s.length()) throw err("unexpected end of text");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"' || c == '\'') {
                int st = i;
                String v = quoted();
                Str n = new Str(v, true);
                n.start = st; n.end = i;
                return n;
            }
            int st = i;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == ',' || d == ']' || d == '}' || d == '\n' || d == '\r' || d == ';') break;
                if (d == '#' ) break;
                i++;
            }
            int en = i;
            while (en > st && Character.isWhitespace(s.charAt(en - 1))) en--;
            if (en == st) throw err("expected a value");
            String tok = s.substring(st, en);
            Node n = scalar(tok);
            n.start = st; n.end = en;
            return n;
        }

        Node scalar(String tok) {
            if (tok.equals("true")) return new Bool(true);
            if (tok.equals("false")) return new Bool(false);
            if (tok.equals("null")) return new Null();
            BigDecimal d = number(tok);
            if (d != null) return new Num(tok, d);
            return new Str(tok, false);
        }

        String quoted() throws ParseException {
            char q = s.charAt(i++);
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("unterminated string");
                char c = s.charAt(i++);
                if (c == q) break;
                if (c == '\\') {
                    if (i >= s.length()) throw err("unterminated string");
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw err("bad \\u escape");
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4; break;
                        default: b.append(e);
                    }
                } else b.append(c);
            }
            return b.toString();
        }

        Obj obj() throws ParseException {
            Obj o = new Obj();
            o.start = i;
            i++;
            while (true) {
                ws();
                if (i >= s.length()) throw err("unterminated object");
                char c = s.charAt(i);
                if (c == '}') { i++; break; }
                if (c == ',' || c == ';') { i++; continue; }
                String key;
                if (c == '"' || c == '\'') key = quoted();
                else {
                    int st = i;
                    while (i < s.length() && s.charAt(i) != ':' && s.charAt(i) != '=' && s.charAt(i) != '\n') i++;
                    key = s.substring(st, i).trim();
                }
                ws();
                if (i < s.length() && (s.charAt(i) == ':' || s.charAt(i) == '=')) {
                    i++;
                    if (i < s.length() && s.charAt(i) == '>') i++;
                } else throw err("expected ':' after key " + key);
                ws();
                Node v = value();
                o.keys.add(key);
                o.values.add(v);
            }
            o.end = i;
            return o;
        }

        Arr arr() throws ParseException {
            Arr a = new Arr();
            a.start = i;
            i++;
            while (true) {
                ws();
                if (i >= s.length()) throw err("unterminated array");
                char c = s.charAt(i);
                if (c == ']') { i++; break; }
                if (c == ',' || c == ';') { i++; continue; }
                a.items.add(value());
            }
            a.end = i;
            return a;
        }
    }

    static BigDecimal number(String tok) {
        if (!tok.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return null;
        try { return new BigDecimal(tok.startsWith("+") ? tok.substring(1) : tok); } catch (NumberFormatException e) { return null; }
    }

    /** Resolve a path of keys (strings) and indexes (integers). Throws with a reason when it does not resolve to one node. */
    static Node resolve(Node root, List<Node> path) throws ParseException {
        Node cur = root;
        StringBuilder where = new StringBuilder();
        for (Node el : path) {
            if (el instanceof Num) {
                int idx = ((Num) el).value.intValueExact();
                where.append('[').append(idx).append(']');
                if (!(cur instanceof Arr)) throw new ParseException(where + " is not inside an array");
                Arr a = (Arr) cur;
                if (idx < 0 || idx >= a.items.size()) throw new ParseException(where + " is past the end of the array (" + a.items.size() + " items)");
                cur = a.items.get(idx);
            } else if (el instanceof Str) {
                String k = ((Str) el).value;
                where.append('.').append(k);
                if (!(cur instanceof Obj)) throw new ParseException(where + " is not inside an object");
                Obj o = (Obj) cur;
                int c = o.count(k);
                if (c == 0) throw new ParseException("key " + where + " not found");
                if (c > 1) throw new ParseException("key " + where + " appears " + c + " times");
                cur = o.get(k);
            } else throw new ParseException("path elements must be strings or integers");
        }
        return cur;
    }

    /** Every scalar in document order, as "path=token", for the after-edit self-check. */
    static List<String> flatten(String text, Node root) {
        List<String> out = new ArrayList<>();
        flatten(text, root, "", out);
        return out;
    }

    private static void flatten(String text, Node n, String path, List<String> out) {
        if (n instanceof Obj) {
            Obj o = (Obj) n;
            for (int i = 0; i < o.keys.size(); i++) flatten(text, o.values.get(i), path + "." + o.keys.get(i), out);
        } else if (n instanceof Arr) {
            Arr a = (Arr) n;
            for (int i = 0; i < a.items.size(); i++) flatten(text, a.items.get(i), path + "[" + i + "]", out);
        } else out.add(path + "=" + text.substring(n.start, n.end));
    }

    static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Strict JSON text for maps, lists, strings, numbers, booleans and null (the tool's own files). */
    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(o, b, "");
        return b.append('\n').toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder b, String ind) {
        if (o instanceof java.util.Map) {
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            int i = 0;
            for (java.util.Map.Entry<String, Object> e : m.entrySet()) {
                b.append(ind).append("  ").append(quote(e.getKey())).append(": ");
                write(e.getValue(), b, ind + "  ");
                if (++i < m.size()) b.append(',');
                b.append('\n');
            }
            b.append(ind).append('}');
        } else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                b.append(ind).append("  ");
                write(l.get(i), b, ind + "  ");
                if (i + 1 < l.size()) b.append(',');
                b.append('\n');
            }
            b.append(ind).append(']');
        } else if (o instanceof String) b.append(quote((String) o));
        else if (o == null) b.append("null");
        else b.append(o);
    }

    /** Plain Java form of a parsed node (maps, lists, strings, BigDecimal, Boolean). */
    static Object toJava(Node n) {
        if (n instanceof Obj) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            Obj o = (Obj) n;
            for (int i = 0; i < o.keys.size(); i++) m.put(o.keys.get(i), toJava(o.values.get(i)));
            return m;
        }
        if (n instanceof Arr) {
            List<Object> l = new ArrayList<>();
            for (Node x : ((Arr) n).items) l.add(toJava(x));
            return l;
        }
        if (n instanceof Str) return ((Str) n).value;
        if (n instanceof Num) return new RawNum(((Num) n).raw);
        if (n instanceof Bool) return ((Bool) n).value;
        return null;
    }

    /** A number that is written back exactly as it was read. */
    static final class RawNum {
        final String raw;
        RawNum(String r) { raw = r; }
        @Override public String toString() { return raw; }
    }

    /** Scalar as display text: strings bare, numbers raw. */
    static String display(Node n) {
        if (n instanceof Str) return ((Str) n).value;
        if (n instanceof Num) return ((Num) n).raw;
        if (n instanceof Bool) return String.valueOf(((Bool) n).value);
        if (n instanceof Null) return "null";
        if (n instanceof Obj) return "{object}";
        return "[array]";
    }

    /** True when two scalars are equal: numbers by value, others exactly. */
    static boolean scalarEquals(Node a, Node b) {
        if (a instanceof Num && b instanceof Num) return ((Num) a).value.compareTo(((Num) b).value) == 0;
        if (a instanceof Num && b instanceof Str) {
            BigDecimal d = number(((Str) b).value);
            return d != null && d.compareTo(((Num) a).value) == 0;
        }
        if (a instanceof Str && b instanceof Num) return scalarEquals(b, a);
        if (a instanceof Str && b instanceof Str) return Text.lf(((Str) a).value).equals(Text.lf(((Str) b).value));
        if (a instanceof Bool && b instanceof Bool) return ((Bool) a).value == ((Bool) b).value;
        if (a instanceof Bool && b instanceof Str) return String.valueOf(((Bool) a).value).equals(((Str) b).value);
        if (a instanceof Str && b instanceof Bool) return scalarEquals(b, a);
        return a instanceof Null && b instanceof Null;
    }
}
