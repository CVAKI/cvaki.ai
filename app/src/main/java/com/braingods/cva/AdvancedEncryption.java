package com.braingods.cva;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Multi-layer encryption engine used by the Hash keyboard and CVA storage.
 *
 *  Layer 1 – UTF-8 → Base64
 *  Layer 2 – XOR cipher with password
 *  Layer 3 – ISO-8859-1 → Base64
 *  Layer 4 – positional scramble (MD5-seeded)
 *  Layer 5 – character-set substitution (obscure Unicode glyphs)
 */
public class AdvancedEncryption {

    // ── Substitution alphabet ─────────────────────────────────────────────────
    private static final String[] S1 = {
            "ས","﴿","𑁍","꧌","ꡳ","᭝","ᬉ","𝆺𝅥𝅮","𒊱","꩗"
    };
    private static final String[] S3 = {
            "꜍","꜆","ꜛ","ꠂ","ꥀ","꥟","꩞","ꫵ","ꬹ","ꭍ",
            "ꯛ","ו","ֹ","𐅆","𐑓","𐘆","𐚒","𐝓","𐠎","𑀇",
            "𑃞","𑆆","𑊆","𑣿","𑩳","𑫪","𑰡","𑱗","𑶡","𒊹",
            "𖬎","𝀷","𝀽","𝄋","𝄕","𞄈","𞅏","𞥞","🜏","🜋",
            "ኗ","႞","ྊ","༏","𑲏","𞄒","𖾓","ቶ","⛦",":"
    };
    private static final String[] S2 = {
            "\u0489", "\u034b", "\u20dd", "\u0302", "\u032a", "\u034c"
    };

    private static final String KEY =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";

    private static final Map<String, String> SYS_MAP     = new HashMap<>();
    private static final Map<String, String> REV_MAP     = new HashMap<>();

    static {
        List<String> all = new ArrayList<>();
        all.addAll(Arrays.asList(S1));
        all.addAll(Arrays.asList(S3));
        all.addAll(Arrays.asList(S2));
        for (int i = 0; i < KEY.length() && i < all.size(); i++) {
            String k = String.valueOf(KEY.charAt(i));
            String v = all.get(i);
            SYS_MAP.put(k, v);
            REV_MAP.put(v, k);
        }
    }


    /**
     * Reverse-lookup: given a glyph string, return the original character(s).
     * Returns null if not found in the map.
     */
    public static String reverseLookup(String glyph) {
        return REV_MAP.getOrDefault(glyph, null);
    }

    // ── Public helper: map a printable character to its hash glyph ────────────
    /**
     * Returns the display glyph for the Hash keyboard.
     * For unmapped characters the original character is returned.
     */
    public static String getHashChar(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            String s = String.valueOf(c);
            sb.append(SYS_MAP.getOrDefault(s, s));
        }
        return sb.toString();
    }

    // ── Internal Base64 ───────────────────────────────────────────────────────
    private static class B64 {
        private static final String CHARS =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        static String encode(byte[] data) {
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < data.length; i += 3) {
                int b1 = data[i] & 0xFF;
                int b2 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
                int b3 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
                int c  = (b1 << 16) | (b2 << 8) | b3;
                r.append(CHARS.charAt((c >> 18) & 0x3F));
                r.append(CHARS.charAt((c >> 12) & 0x3F));
                r.append(i + 1 < data.length ? CHARS.charAt((c >> 6) & 0x3F) : '=');
                r.append(i + 2 < data.length ? CHARS.charAt(c & 0x3F)        : '=');
            }
            return r.toString();
        }

        static byte[] decode(String enc) {
            enc = enc.replace("=", "");
            List<Byte> out = new ArrayList<>();
            for (int i = 0; i < enc.length(); i += 4) {
                int v1 = CHARS.indexOf(enc.charAt(i));
                int v2 = i+1 < enc.length() ? CHARS.indexOf(enc.charAt(i+1)) : 0;
                int v3 = i+2 < enc.length() ? CHARS.indexOf(enc.charAt(i+2)) : 0;
                int v4 = i+3 < enc.length() ? CHARS.indexOf(enc.charAt(i+3)) : 0;
                int c  = (v1 << 18) | (v2 << 12) | (v3 << 6) | v4;
                out.add((byte)((c >> 16) & 0xFF));
                if (i+2 < enc.length()) out.add((byte)((c >> 8) & 0xFF));
                if (i+3 < enc.length()) out.add((byte)(c & 0xFF));
            }
            byte[] arr = new byte[out.size()];
            for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
            return arr;
        }
    }

    // ── XOR cipher ────────────────────────────────────────────────────────────
    private static String xor(String text, String key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
            sb.append((char)(text.charAt(i) ^ key.charAt(i % key.length())));
        return sb.toString();
    }

    // ── MD5 ───────────────────────────────────────────────────────────────────
    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : h) { String x = Integer.toHexString(0xff & b); if (x.length()==1) hex.append('0'); hex.append(x); }
            return hex.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── Scramble / Unscramble ─────────────────────────────────────────────────
    private static List<Integer> buildIndices(int len, String seed) {
        String hex = md5(seed);
        long hash = Long.parseUnsignedLong(hex.substring(0, 15), 16);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < len; i++) idx.add(i);
        final long h = hash;
        idx.sort((a, b) -> Long.compare((a * h) % len, (b * h) % len));
        return idx;
    }

    private static String scramble(String text, String seed) {
        List<Integer> idx = buildIndices(text.length(), seed);
        StringBuilder sb = new StringBuilder();
        for (int i : idx) sb.append(text.charAt(i));
        return sb.toString();
    }

    private static String unscramble(String text, String seed) {
        List<Integer> idx = buildIndices(text.length(), seed);
        char[] out = new char[text.length()];
        for (int pos = 0; pos < idx.size(); pos++) out[idx.get(pos)] = text.charAt(pos);
        return new String(out);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public static String encrypt(String plaintext, String password) {
        String l1 = B64.encode(plaintext.getBytes(StandardCharsets.UTF_8));
        String l2 = xor(l1, password);
        String l3 = B64.encode(l2.getBytes(StandardCharsets.ISO_8859_1));
        String l4 = scramble(l3, password);

        StringBuilder sb = new StringBuilder();
        for (char c : l4.toCharArray()) {
            String k = String.valueOf(c);
            sb.append(SYS_MAP.containsKey(k) ? SYS_MAP.get(k) : "[" + (int)c + "]");
        }
        return sb.toString();
    }

    public static String decrypt(String ciphertext, String password) throws Exception {
        // Layer 5 reverse
        StringBuilder l4 = new StringBuilder();
        int i = 0;
        while (i < ciphertext.length()) {
            if (ciphertext.charAt(i) == '[') {
                int end = ciphertext.indexOf(']', i);
                l4.append((char) Integer.parseInt(ciphertext.substring(i + 1, end)));
                i = end + 1;
            } else {
                boolean found = false;
                for (int len = 10; len > 0; len--) {
                    if (i + len <= ciphertext.length()) {
                        String sub = ciphertext.substring(i, i + len);
                        if (REV_MAP.containsKey(sub)) { l4.append(REV_MAP.get(sub)); i += len; found = true; break; }
                    }
                }
                if (!found) {
                    String sc = String.valueOf(ciphertext.charAt(i));
                    if (REV_MAP.containsKey(sc)) { l4.append(REV_MAP.get(sc)); i++; }
                    else throw new Exception("Unknown symbol at pos " + i);
                }
            }
        }
        String l3 = unscramble(l4.toString(), password);
        byte[] l2b = B64.decode(l3);
        String l2  = new String(l2b, StandardCharsets.ISO_8859_1);
        String l1  = xor(l2, password);
        return new String(B64.decode(l1), StandardCharsets.UTF_8);
    }
}