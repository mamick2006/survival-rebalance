package survivalrebalance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Reads a jar's entries and rewrites chosen entries while copying every other entry's raw
 * bytes (local header, compressed data, descriptor) unchanged and in the same order.
 */
final class Zip {
    static final class Entry {
        String name;
        int cdOffset, cdLen;        // central directory record
        int localOffset;
        int flags, method;
        long crc, csize, usize;
        int localEnd;               // end of data (and descriptor)
        int dataStart;
    }

    final byte[] b;
    final List<Entry> entries = new ArrayList<>();
    final Map<String, Entry> byName = new LinkedHashMap<>();
    int cdStart, cdEnd, eocd;

    Zip(byte[] bytes) throws Failure {
        b = bytes;
        eocd = -1;
        for (int i = b.length - 22; i >= Math.max(0, b.length - 22 - 65535); i--) {
            if (u32(i) == 0x06054b50L) { eocd = i; break; }
        }
        if (eocd < 0) throw new Failure("not a zip file (no end record)");
        int count = u16(eocd + 10);
        long cdSize = u32(eocd + 12), cdOff = u32(eocd + 16);
        if (count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) throw new Failure("zip64 jars are not supported");
        cdStart = (int) cdOff;
        cdEnd = (int) (cdOff + cdSize);
        int p = cdStart;
        for (int k = 0; k < count; k++) {
            if (u32(p) != 0x02014b50L) throw new Failure("bad central directory record");
            Entry e = new Entry();
            e.cdOffset = p;
            e.flags = u16(p + 8);
            e.method = u16(p + 10);
            e.crc = u32(p + 16);
            e.csize = u32(p + 20);
            e.usize = u32(p + 24);
            int nlen = u16(p + 28), xlen = u16(p + 30), clen = u16(p + 32);
            e.localOffset = (int) u32(p + 42);
            if (e.csize == 0xFFFFFFFFL || e.usize == 0xFFFFFFFFL || e.localOffset == -1) throw new Failure("zip64 entries are not supported");
            e.name = new String(b, p + 46, nlen, (e.flags & 0x800) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            e.cdLen = 46 + nlen + xlen + clen;
            p += e.cdLen;
            int lp = e.localOffset;
            if (u32(lp) != 0x04034b50L) throw new Failure("bad local header for " + e.name);
            int lnlen = u16(lp + 26), lxlen = u16(lp + 28);
            e.dataStart = lp + 30 + lnlen + lxlen;
            int end = (int) (e.dataStart + e.csize);
            if ((e.flags & 0x08) != 0) {
                if (end + 4 <= b.length && u32(end) == 0x08074b50L) end += 16; else end += 12;
            }
            e.localEnd = end;
            entries.add(e);
            byName.putIfAbsent(e.name, e);
        }
    }

    byte[] read(String name) throws Failure {
        Entry e = byName.get(name);
        if (e == null) return null;
        if (e.method == 0) {
            byte[] out = new byte[(int) e.usize];
            System.arraycopy(b, e.dataStart, out, 0, out.length);
            return out;
        }
        if (e.method != 8) throw new Failure("entry " + name + " uses an unknown compression method " + e.method);
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(b, e.dataStart, (int) e.csize);
            byte[] out = new byte[(int) e.usize];
            int n = 0;
            while (n < out.length) {
                int r = inf.inflate(out, n, out.length - n);
                if (r == 0 && (inf.finished() || inf.needsInput())) break;
                n += r;
            }
            if (n != out.length) throw new Failure("entry " + name + " did not inflate to its stated size");
            return out;
        } catch (java.util.zip.DataFormatException ex) {
            throw new Failure("entry " + name + " is corrupt: " + ex.getMessage());
        } finally { inf.end(); }
    }

    /** A new jar with the given entries replaced; every other byte range is copied. */
    byte[] rewrite(Map<String, byte[]> replace) throws Failure {
        for (String n : replace.keySet()) if (!byName.containsKey(n)) throw new Failure("jar has no entry " + n);
        List<Entry> order = new ArrayList<>(entries);
        order.sort(Comparator.comparingInt(e -> e.localOffset));
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 1024);
        Map<Entry, Integer> newOffset = new LinkedHashMap<>();
        Map<Entry, long[]> newMeta = new LinkedHashMap<>(); // crc, csize, usize, flags
        int cursor = 0;
        for (Entry e : order) {
            if (e.localOffset < cursor) throw new Failure("overlapping entries in jar");
            out.write(b, cursor, e.localOffset - cursor);
            newOffset.put(e, out.size());
            byte[] data = byName.get(e.name) == e ? replace.get(e.name) : null;
            if (data == null) {
                out.write(b, e.localOffset, e.localEnd - e.localOffset);
            } else {
                CRC32 crc = new CRC32();
                crc.update(data);
                byte[] comp = e.method == 0 ? data : deflate(data);
                int lp = e.localOffset;
                int lnlen = u16(lp + 26), lxlen = u16(lp + 28);
                byte[] hdr = new byte[30 + lnlen + lxlen];
                System.arraycopy(b, lp, hdr, 0, hdr.length);
                int flags = e.flags & ~0x08;
                put16(hdr, 6, flags);
                put32(hdr, 14, crc.getValue());
                put32(hdr, 18, comp.length);
                put32(hdr, 22, data.length);
                out.write(hdr, 0, hdr.length);
                out.write(comp, 0, comp.length);
                newMeta.put(e, new long[]{crc.getValue(), comp.length, data.length, flags});
            }
            cursor = e.localEnd;
        }
        if (cdStart < cursor) throw new Failure("central directory overlaps entry data");
        out.write(b, cursor, cdStart - cursor);
        int newCd = out.size();
        for (Entry e : entries) {
            byte[] rec = new byte[e.cdLen];
            System.arraycopy(b, e.cdOffset, rec, 0, e.cdLen);
            put32(rec, 42, newOffset.get(e));
            long[] m = newMeta.get(e);
            if (m != null) {
                put16(rec, 8, (int) m[3]);
                put32(rec, 16, m[0]);
                put32(rec, 20, m[1]);
                put32(rec, 24, m[2]);
            }
            out.write(rec, 0, rec.length);
        }
        int cdSize = out.size() - newCd;
        out.write(b, cdEnd, eocd - cdEnd);
        byte[] end = new byte[b.length - eocd];
        System.arraycopy(b, eocd, end, 0, end.length);
        put32(end, 12, cdSize);
        put32(end, 16, newCd);
        out.write(end, 0, end.length);
        return out.toByteArray();
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(data.length / 2 + 64);
            byte[] buf = new byte[65536];
            while (!d.finished()) { int n = d.deflate(buf); o.write(buf, 0, n); }
            return o.toByteArray();
        } finally { d.end(); }
    }

    int u16(int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8); }
    long u32(int p) { return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16) | ((b[p + 3] & 0xFFL) << 24); }
    static void put16(byte[] a, int p, int v) { a[p] = (byte) v; a[p + 1] = (byte) (v >> 8); }
    static void put32(byte[] a, int p, long v) { a[p] = (byte) v; a[p + 1] = (byte) (v >> 8); a[p + 2] = (byte) (v >> 16); a[p + 3] = (byte) (v >> 24); }
}
