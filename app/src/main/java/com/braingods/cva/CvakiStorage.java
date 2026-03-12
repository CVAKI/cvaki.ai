package com.braingods.cva;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * CvakiStorage — three-tier encrypted key-value persistence.
 *
 * ┌──────────────────────────────────────────────────────────────┐
 * │  TIER 1 — MAIN MEMORY  (prefix: "main_")                    │
 * │  Permanent. Survives restarts. Important findings, learned   │
 * │  app locations, user preferences.                            │
 * │                                                              │
 * │  TIER 2 — CACHE MEMORY  (prefix: "cache_")                  │
 * │  Temporary. Auto-expires after 24 hours. Used for current    │
 * │  session state, intermediate results, live context.          │
 * │                                                              │
 * │  TIER 3 — GRID INDEX  (prefix: "grid_")                     │
 * │  Screen divided into 10×10 = 100 cells. Stores which UI      │
 * │  elements / app icons live at each coordinate region.        │
 * │  Also stores app-name → pixel coordinates for fast lookup.   │
 * └──────────────────────────────────────────────────────────────┘
 *
 * Each file on disk:  <prefix><key>.cvaki
 * Content encrypted with AdvancedEncryption using a device-specific salt.
 */
public class CvakiStorage {

    private static final String TAG       = "CvakiStorage";
    private static final String EXT       = ".cvaki";
    private static final String SECRET    = "CVA_BRAIN_KEY_2024";

    // Cache TTL: 24 hours in milliseconds
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    // ─── Key prefixes ──────────────────────────────────────────────────────────
    private static final String PREFIX_MAIN  = "main_";
    private static final String PREFIX_CACHE = "cache_";
    private static final String PREFIX_GRID  = "grid_";

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 1 — MAIN MEMORY (permanent, important facts)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Save a value to permanent main memory. */
    public static void saveMain(Context ctx, String key, String value) {
        rawSave(ctx, PREFIX_MAIN + key, value);
    }

    /** Load from main memory. Returns "" if not found. */
    public static String loadMain(Context ctx, String key) {
        return rawLoad(ctx, PREFIX_MAIN + key);
    }

    public static boolean existsMain(Context ctx, String key) {
        return fileFor(ctx, PREFIX_MAIN + key).exists();
    }

    public static void deleteMain(Context ctx, String key) {
        rawDelete(ctx, PREFIX_MAIN + key);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 2 — CACHE MEMORY (auto-expires after 24 hours)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Save a value to cache. It will be automatically ignored after 24 hours.
     */
    public static void saveCache(Context ctx, String key, String value) {
        // Store as "timestamp|value" so we can check expiry on load
        String wrapped = System.currentTimeMillis() + "|" + value;
        rawSave(ctx, PREFIX_CACHE + key, wrapped);
    }

    /**
     * Load from cache. Returns null if the entry doesn't exist OR has expired.
     */
    public static String loadCache(Context ctx, String key) {
        String wrapped = rawLoad(ctx, PREFIX_CACHE + key);
        if (wrapped == null || wrapped.isEmpty()) return null;

        int sep = wrapped.indexOf('|');
        if (sep < 0) return null; // malformed

        try {
            long savedAt = Long.parseLong(wrapped.substring(0, sep));
            if (System.currentTimeMillis() - savedAt > CACHE_TTL_MS) {
                // Expired — clean up silently
                rawDelete(ctx, PREFIX_CACHE + key);
                Log.d(TAG, "Cache expired: " + key);
                return null;
            }
            return wrapped.substring(sep + 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean existsCache(Context ctx, String key) {
        return loadCache(ctx, key) != null;
    }

    /**
     * Delete all cache entries older than 24 hours.
     * Call this on app startup or periodically.
     */
    public static void pruneCacheExpired(Context ctx) {
        File dir = ctx.getFilesDir();
        String[] files = dir.list((d, n) -> n.startsWith(PREFIX_CACHE) && n.endsWith(EXT));
        if (files == null) return;
        int pruned = 0;
        for (String fname : files) {
            String rawKey = fname.replace(EXT, "").substring(PREFIX_CACHE.length());
            if (loadCache(ctx, rawKey) == null) pruned++; // loadCache auto-deletes expired
        }
        if (pruned > 0) Log.d(TAG, "Pruned " + pruned + " expired cache entries");
    }

    /** Wipe ALL cache entries immediately (e.g. on explicit user clear). */
    public static void clearAllCache(Context ctx) {
        File dir = ctx.getFilesDir();
        String[] files = dir.list((d, n) -> n.startsWith(PREFIX_CACHE) && n.endsWith(EXT));
        if (files == null) return;
        for (String f : files) new File(dir, f).delete();
        Log.d(TAG, "Cleared all cache entries");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 3 — GRID INDEX (100-cell screen map, 10 cols × 10 rows)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Record an app / UI element location by name.
     *
     * @param appName  e.g. "camera", "chrome", "settings"  (case-insensitive)
     * @param x        screen pixel x
     * @param y        screen pixel y
     */
    public static void saveGridApp(Context ctx, String appName, float x, float y) {
        String key = PREFIX_GRID + "app_" + appName.toLowerCase().trim().replace(" ", "_");
        String value = x + ":" + y;
        rawSave(ctx, key, value);
        Log.d(TAG, "Grid: saved " + appName + " → (" + x + "," + y + ")");
    }

    /**
     * Look up a previously recorded app location.
     *
     * @return float[]{x, y} if found, or null if not in storage.
     */
    public static float[] findGridApp(Context ctx, String appName) {
        String key = PREFIX_GRID + "app_" + appName.toLowerCase().trim().replace(" ", "_");
        String val = rawLoad(ctx, key);
        if (val == null || val.isEmpty()) return null;
        try {
            String[] parts = val.split(":");
            return new float[]{ Float.parseFloat(parts[0]), Float.parseFloat(parts[1]) };
        } catch (Exception e) {
            Log.w(TAG, "Corrupt grid entry for " + appName + ": " + val);
            rawDelete(ctx, key);
            return null;
        }
    }

    /** Remove a specific app from the grid index (e.g. after it was uninstalled). */
    public static void removeGridApp(Context ctx, String appName) {
        String key = PREFIX_GRID + "app_" + appName.toLowerCase().trim().replace(" ", "_");
        rawDelete(ctx, key);
    }

    /**
     * Store the contents of a specific grid cell.
     *
     * Grid layout:
     *   col 0-9  (left → right, each = screenWidth/10 wide)
     *   row 0-9  (top  → bottom, each = screenHeight/10 tall)
     *
     * @param content  Comma-separated list of detected element names, e.g.
     *                 "Camera,Clock,Calculator"
     */
    public static void saveGridCell(Context ctx, int col, int row, String content) {
        String key = PREFIX_GRID + "cell_" + col + "_" + row;
        rawSave(ctx, key, content);
    }

    /** Retrieve stored cell content. Returns "" if the cell has never been scanned. */
    public static String getGridCell(Context ctx, int col, int row) {
        String key = PREFIX_GRID + "cell_" + col + "_" + row;
        return rawLoad(ctx, key);
    }

    /**
     * Search ALL grid cells for any cell that mentions appName.
     * Returns the cell's (col, row) as int[]{col, row}, or null if not found.
     */
    public static int[] findAppInGrid(Context ctx, String appName) {
        String needle = appName.toLowerCase().trim();
        for (int row = 0; row < 10; row++) {
            for (int col = 0; col < 10; col++) {
                String content = getGridCell(ctx, col, row);
                if (content != null && content.toLowerCase().contains(needle)) {
                    return new int[]{col, row};
                }
            }
        }
        return null;
    }

    /**
     * Get the pixel center of a grid cell.
     *
     * @param col       0-9
     * @param row       0-9
     * @param screenW   screen width in pixels
     * @param screenH   screen height in pixels
     * @return float[]{centerX, centerY}
     */
    public static float[] cellCenter(int col, int row, int screenW, int screenH) {
        float cellW = (float) screenW / 10f;
        float cellH = (float) screenH / 10f;
        return new float[]{
                cellW * col + cellW / 2f,
                cellH * row + cellH / 2f
        };
    }

    /** Wipe entire grid index (forces re-scan on next use). */
    public static void clearGrid(Context ctx) {
        File dir = ctx.getFilesDir();
        String[] files = dir.list((d, n) -> n.startsWith(PREFIX_GRID) && n.endsWith(EXT));
        if (files == null) return;
        for (String f : files) new File(dir, f).delete();
        Log.d(TAG, "Grid index cleared");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY API — preserved for backward compatibility with existing callers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Legacy save — equivalent to {@link #saveMain}.
     * Old callers that used the plain save() are treated as main-memory saves.
     */
    public static void save(Context ctx, String name, String plaintext) {
        saveMain(ctx, name, plaintext);
    }

    /** Legacy load — reads from main memory. */
    public static String load(Context ctx, String name) throws Exception {
        String val = loadMain(ctx, name);
        return val != null ? val : "";
    }

    public static boolean exists(Context ctx, String name) {
        return existsMain(ctx, name);
    }

    public static void delete(Context ctx, String name) {
        deleteMain(ctx, name);
    }

    /** List all stored keys (all tiers, without extensions). */
    public static String[] listFiles(Context ctx) {
        File dir = ctx.getFilesDir();
        String[] all = dir.list((d, n) -> n.endsWith(EXT));
        if (all == null) return new String[0];
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++)
            names[i] = all[i].replace(EXT, "");
        return names;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static void rawSave(Context ctx, String key, String plaintext) {
        try {
            String encrypted = AdvancedEncryption.encrypt(plaintext, SECRET);
            try (FileOutputStream fos = new FileOutputStream(fileFor(ctx, key))) {
                fos.write(encrypted.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "rawSave failed for key=" + key, e);
        }
    }

    private static String rawLoad(Context ctx, String key) {
        File file = fileFor(ctx, key);
        if (!file.exists()) return null;
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                //noinspection ResultOfMethodCallIgnored
                fis.read(bytes);
            }
            String encrypted = new String(bytes, StandardCharsets.UTF_8);
            if (encrypted.isEmpty()) return "";
            return AdvancedEncryption.decrypt(encrypted, SECRET);
        } catch (Exception e) {
            Log.e(TAG, "rawLoad failed for key=" + key, e);
            return null;
        }
    }

    private static void rawDelete(Context ctx, String key) {
        File f = fileFor(ctx, key);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static File fileFor(Context ctx, String key) {
        // Sanitise key so it's a valid filename (replace slashes, spaces)
        String safe = key.replace("/", "_").replace(" ", "_");
        return new File(ctx.getFilesDir(), safe + EXT);
    }
}