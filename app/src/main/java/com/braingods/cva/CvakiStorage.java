package com.braingods.cva;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encrypted key-value persistence.
 * Each entry is saved as  <key>.cvaki  in the app's private files directory.
 * Content is encrypted with AdvancedEncryption using a device-specific salt.
 */
public class CvakiStorage {

    private static final String TAG        = "CvakiStorage";
    private static final String EXTENSION  = ".cvaki";
    private static final String SECRET_KEY = "CVA_BRAIN_KEY_2024";   // internal key

    // ── Public API ────────────────────────────────────────────────────────────

    public static void save(Context ctx, String name, String plaintext) {
        try {
            String encrypted = AdvancedEncryption.encrypt(plaintext, SECRET_KEY);
            File   file      = fileFor(ctx, name);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(encrypted.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "save() failed for: " + name, e);
        }
    }

    public static String load(Context ctx, String name) throws Exception {
        File file = fileFor(ctx, name);
        if (!file.exists()) return "";
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            //noinspection ResultOfMethodCallIgnored
            fis.read(bytes);
        }
        String encrypted = new String(bytes, StandardCharsets.UTF_8);
        if (encrypted.isEmpty()) return "";
        return AdvancedEncryption.decrypt(encrypted, SECRET_KEY);
    }

    public static boolean exists(Context ctx, String name) {
        return fileFor(ctx, name).exists();
    }

    public static void delete(Context ctx, String name) {
        File f = fileFor(ctx, name);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** List all .cvaki file names (without extension) */
    public static String[] listFiles(Context ctx) {
        File dir = ctx.getFilesDir();
        String[] all = dir.list((d, n) -> n.endsWith(EXTENSION));
        if (all == null) return new String[0];
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++)
            names[i] = all[i].replace(EXTENSION, "");
        return names;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static File fileFor(Context ctx, String name) {
        return new File(ctx.getFilesDir(), name + EXTENSION);
    }
}