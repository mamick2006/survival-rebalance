package survivalrebalance;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A text file decoded so that encoding it again gives back the same bytes. */
final class Text {
    final boolean bom;
    final Charset charset;
    String body; // without the BOM

    private Text(boolean bom, Charset cs, String body) { this.bom = bom; this.charset = cs; this.body = body; }

    static Text decode(byte[] b) {
        boolean bom = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
        int off = bom ? 3 : 0;
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, b.length - off)).toString();
            return new Text(bom, StandardCharsets.UTF_8, s);
        } catch (CharacterCodingException e) {
            return new Text(bom, StandardCharsets.ISO_8859_1, new String(b, off, b.length - off, StandardCharsets.ISO_8859_1));
        }
    }

    byte[] encode() {
        byte[] body = this.body.getBytes(charset);
        if (!bom) return body;
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF; out[1] = (byte) 0xBB; out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    Text withBody(String b) { return new Text(bom, charset, b); }

    static String lf(String s) { return s.replace("\r\n", "\n").replace('\r', '\n'); }

    static String sha256(byte[] b) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xFF));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** The hash a sealed anchor carries: SHA-256 of the UTF-8 bytes of the LF-normalized text. */
    static String anchorHash(String s) { return sha256(lf(s).getBytes(StandardCharsets.UTF_8)); }

    static int countOf(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    /** Short display form of a text for messages. */
    static String clip(String s) {
        String t = lf(s).replace("\n", "\\n");
        return t.length() > 70 ? t.substring(0, 67) + "..." : t;
    }
}
