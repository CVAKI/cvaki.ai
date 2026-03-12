package com.braingods.cva.termux;

import android.os.Build;
import android.util.Log;

import java.io.File;

/**
 * TermuxShellUtils
 * ─────────────────────────────────────────────────────────────────────────────
 * Static helpers for detecting shell availability, ABI, and bootstrap state.
 */
public final class TermuxShellUtils {

    private static final String TAG = "TermuxShellUtils";

    // ── Bootstrap detection ───────────────────────────────────────────────────

    /**
     * Returns true if our own CVA bootstrap has been successfully installed.
     * We check for bash (the primary shell) in PREFIX/bin.
     */
    public static boolean isCvaBootstrapInstalled() {
        File bash = new File(TermuxConstants.BASH_PATH);
        return bash.exists() && bash.length() > 1024;
    }

    /**
     * Returns true if the real Termux app is installed and its bash is present.
     */
    public static boolean isTermuxInstalled() {
        return new File(TermuxConstants.TERMUX_BASH).exists();
    }

    /**
     * Returns true if any working shell is available on this device.
     */
    public static boolean hasAnyShell() {
        return isCvaBootstrapInstalled()
                || isTermuxInstalled()
                || new File("/system/bin/sh").exists();
    }

    // ── ABI detection ─────────────────────────────────────────────────────────

    /** CPU architecture string: "aarch64", "arm", "x86_64", or "i686". */
    public static String getArch() {
        String abi = Build.SUPPORTED_ABIS[0];
        if (abi.contains("arm64") || abi.contains("aarch64")) return "aarch64";
        if (abi.contains("arm"))                               return "arm";
        if (abi.contains("x86_64") || abi.contains("amd64"))  return "x86_64";
        if (abi.contains("x86")   || abi.contains("i686"))    return "i686";
        return "aarch64"; // safe default
    }

    /** Returns the correct Termux bootstrap ZIP download URL for this device. */
    public static String getBootstrapUrl() {
        switch (getArch()) {
            case "aarch64": return TermuxConstants.BOOTSTRAP_URL_ARM64;
            case "arm":     return TermuxConstants.BOOTSTRAP_URL_ARM;
            case "x86_64":  return TermuxConstants.BOOTSTRAP_URL_X86_64;
            default:        return TermuxConstants.BOOTSTRAP_URL_ARM64;
        }
    }

    /** Returns the CDN mirror URL for faster downloads in some regions. */
    public static String getBootstrapCdnUrl() {
        switch (getArch()) {
            case "aarch64": return TermuxConstants.BOOTSTRAP_CDN_ARM64;
            case "arm":     return TermuxConstants.BOOTSTRAP_CDN_ARM;
            case "x86_64":  return TermuxConstants.BOOTSTRAP_CDN_X86_64;
            default:        return TermuxConstants.BOOTSTRAP_CDN_ARM64;
        }
    }

    // ── Prefix directory helpers ──────────────────────────────────────────────

    /** Create PREFIX and HOME directories if they don't exist. */
    public static void ensureDirectories() {
        mkdirs(TermuxConstants.PREFIX_PATH);
        mkdirs(TermuxConstants.PREFIX_BIN);
        mkdirs(TermuxConstants.PREFIX_LIB);
        mkdirs(TermuxConstants.PREFIX_INCLUDE);
        mkdirs(TermuxConstants.PREFIX_SHARE);
        mkdirs(TermuxConstants.PREFIX_ETC);
        mkdirs(TermuxConstants.TMP_PATH);
        mkdirs(TermuxConstants.HOME_PATH);
        mkdirs(TermuxConstants.STAGING_PREFIX_PATH);
    }

    private static void mkdirs(String path) {
        File f = new File(path);
        if (!f.exists()) {
            boolean created = f.mkdirs();
            Log.d(TAG, "mkdirs " + path + " → " + created);
        }
    }

    /** Delete the staging prefix (called after a successful install). */
    public static void deleteStagingPrefix() {
        deleteRecursive(new File(TermuxConstants.STAGING_PREFIX_PATH));
    }

    /** Delete the entire PREFIX (used for clean reinstall). */
    public static void deletePrefix() {
        deleteRecursive(new File(TermuxConstants.PREFIX_PATH));
        Log.d(TAG, "PREFIX deleted");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    // ── ELF type detection ────────────────────────────────────────────────────

    /** Returns true if the file at {@code path} is a PIE (ET_DYN) ELF binary. */
    public static boolean isPIE(String path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
            byte[] h = new byte[18];
            if (fis.read(h) < 18) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            int eType = (h[16] & 0xFF) | ((h[17] & 0xFF) << 8);
            return eType == 3; // ET_DYN = PIE
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns true if the file looks like a valid ELF binary. */
    public static boolean isELF(File f) {
        if (!f.exists() || f.length() < 4) return false;
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] magic = new byte[4];
            return fis.read(magic) == 4
                    && magic[0] == 0x7f && magic[1] == 'E'
                    && magic[2] == 'L'  && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private TermuxShellUtils() {}
}