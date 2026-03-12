package com.braingods.cva.termux;

/**
 * TermuxConstants
 * ─────────────────────────────────────────────────────────────────────────────
 * Single source of truth for all file paths used by the Termux-compatible
 * environment inside com.braingods.cva.
 *
 * The layout mirrors real Termux exactly:
 *
 *   /data/data/com.braingods.cva/files/
 *     usr/          ← PREFIX  (all binaries, libs, headers live here)
 *       bin/        ← bash, busybox, python3, vim …
 *       lib/        ← shared .so files
 *       include/
 *       share/
 *       tmp/        ← TMPDIR (writable temp)
 *     home/         ← HOME  (.bashrc, .tmux.conf, projects …)
 *
 * WHY THIS WORKS on Android 10+ (W^X policy):
 *   Extracted binaries in getFilesDir() are NOT directly exec-able on Android 10+
 *   for non-root processes, BUT the Termux bootstrap uses its own dynamic linker
 *   (ld-linux / ld.so) at PREFIX/lib/ld-android.so which bypasses the system W^X
 *   restriction by setting the correct ELF interpreter.  All binaries in the
 *   bootstrap ZIP are compiled as PIE (ET_DYN) and linked against Termux's own
 *   libc (bionic fork), so they can be executed by the app process.
 */
public final class TermuxConstants {

    // App package — update if you rename the app
    public static final String CVA_PACKAGE = "com.braingods.cva";

    // Real Termux package — used for cross-app detection
    public static final String TERMUX_PACKAGE = "com.termux";

    // ── Termux-compatible prefix paths ────────────────────────────────────────

    /** Root of our Termux-compatible installation under getFilesDir(). */
    public static final String FILES_PATH  = "/data/data/" + CVA_PACKAGE + "/files";

    /** $PREFIX — equivalent to Termux's /data/data/com.termux/files/usr */
    public static final String PREFIX_PATH = FILES_PATH + "/usr";

    /** $HOME — user's home directory */
    public static final String HOME_PATH   = FILES_PATH + "/home";

    /** $TMPDIR */
    public static final String TMP_PATH    = PREFIX_PATH + "/tmp";

    // ── Subdirectory shortcuts ────────────────────────────────────────────────

    public static final String PREFIX_BIN     = PREFIX_PATH + "/bin";
    public static final String PREFIX_LIB     = PREFIX_PATH + "/lib";
    public static final String PREFIX_INCLUDE = PREFIX_PATH + "/include";
    public static final String PREFIX_SHARE   = PREFIX_PATH + "/share";
    public static final String PREFIX_ETC     = PREFIX_PATH + "/etc";
    public static final String PREFIX_LIBEXEC = PREFIX_PATH + "/libexec";

    // ── Key binaries (post-bootstrap) ────────────────────────────────────────

    public static final String BASH_PATH   = PREFIX_BIN + "/bash";
    public static final String SH_PATH     = PREFIX_BIN + "/sh";    // symlink → bash
    public static final String LOGIN_PATH  = PREFIX_BIN + "/login";

    // Real Termux paths — used to detect if user has Termux installed
    public static final String TERMUX_PREFIX   = "/data/data/com.termux/files/usr";
    public static final String TERMUX_BASH     = TERMUX_PREFIX + "/bin/bash";
    public static final String TERMUX_HOME     = "/data/data/com.termux/files/home";

    // Fallback shell always available on Android
    public static final String SYSTEM_SH = "/system/bin/sh";

    // ── Bootstrap ZIP URLs (from Termux's official GitHub releases) ───────────
    //
    // FIX: The old pinned tag "bootstrap-2024.09.09" no longer exists on GitHub
    // (Termux rotates bootstrap releases).  Using the "latest" redirect ensures
    // we always resolve to the current published release without needing to
    // update this constant every time Termux cuts a new bootstrap.

    private static final String BOOTSTRAP_BASE =
            "https://github.com/termux/termux-packages/releases/latest/download/";

    public static final String BOOTSTRAP_URL_ARM64  = BOOTSTRAP_BASE + "bootstrap-aarch64.zip";
    public static final String BOOTSTRAP_URL_ARM    = BOOTSTRAP_BASE + "bootstrap-arm.zip";
    public static final String BOOTSTRAP_URL_X86_64 = BOOTSTRAP_BASE + "bootstrap-x86_64.zip";
    public static final String BOOTSTRAP_URL_X86    = BOOTSTRAP_BASE + "bootstrap-i686.zip";

    // CDN mirror — also updated to use latest redirect
    private static final String BOOTSTRAP_CDN =
            "https://github.com/termux/termux-packages/releases/latest/download/";

    public static final String BOOTSTRAP_CDN_ARM64  = BOOTSTRAP_CDN + "bootstrap-aarch64.zip";
    public static final String BOOTSTRAP_CDN_ARM    = BOOTSTRAP_CDN + "bootstrap-arm.zip";
    public static final String BOOTSTRAP_CDN_X86_64 = BOOTSTRAP_CDN + "bootstrap-x86_64.zip";

    // Minimum acceptable size for a valid bootstrap ZIP (bytes)
    public static final long BOOTSTRAP_MIN_SIZE = 1_000_000L; // 1 MB

    // ── Staging paths ─────────────────────────────────────────────────────────

    /** Staging dir — bootstrap is extracted here first, then moved to PREFIX */
    public static final String STAGING_PREFIX_PATH = FILES_PATH + "/usr-staging";

    // ── Shell ENV keys ────────────────────────────────────────────────────────

    public static final String ENV_PREFIX  = "PREFIX";
    public static final String ENV_HOME    = "HOME";
    public static final String ENV_TMPDIR  = "TMPDIR";
    public static final String ENV_SHELL   = "SHELL";
    public static final String ENV_TERM    = "TERM";
    public static final String ENV_LANG    = "LANG";
    public static final String ENV_PATH    = "PATH";
    public static final String ENV_LD_LIB  = "LD_LIBRARY_PATH";

    // ── AndroidManifest entry reminder ───────────────────────────────────────
    /*
     * Add these to AndroidManifest.xml:
     *
     * <uses-permission android:name="android.permission.INTERNET"/>
     * <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
     * <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
     */

    private TermuxConstants() {} // static-only
}