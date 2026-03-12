package com.braingods.cva.termux;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * TermuxEnvironment  [v2 — apt/pkg + ncurses fixes]
 * ─────────────────────────────────────────────────────────────────────────────
 * Builds the correct shell environment variables for a Termux-compatible
 * session.  Every ProcessBuilder that launches bash/sh should call
 * {@link #buildEnvironment(Context)} and merge the result into its env map.
 *
 * WHAT'S FIXED vs v1:
 *
 *  FIX 1 — apt/pkg "Unable to read …com.termux…apt.conf.d/ Permission denied"
 *   The Termux bootstrap binaries (apt, dpkg) have /data/data/com.termux/files/usr
 *   compiled in as their default prefix.  Running them from a different app's data
 *   directory means they try to open the real Termux app's files — which Android
 *   blocks with EACCES.
 *
 *   Solution: apt respects the APT_CONFIG environment variable.  When set, apt reads
 *   its main config from that file before applying any compiled-in defaults.  We write
 *   a minimal apt.conf at $PREFIX/etc/apt/apt.conf that sets Dir to our own prefix,
 *   then point APT_CONFIG at it.  dpkg respects DPKG_ADMINDIR for its status DB.
 *
 *   The actual apt.conf and directory structure are written by
 *   {@link BootstrapPostInstall#run(Context)} which is called from TermuxInstaller
 *   after a successful bootstrap extraction.
 *
 *  FIX 2 — ncurses "cannot initialize terminal type" (nano, vim, tmux, htop…)
 *   ncurses looks for the compiled-in terminfo database path first, which again points
 *   at /data/data/com.termux/…  We add TERMINFO pointing to the CVA prefix so ncurses
 *   finds the terminfo trees that are part of the bootstrap.
 *
 *  FIX 3 — pkg wrapper
 *   Termux users type `pkg install git` not `apt install git`.  BootstrapPostInstall
 *   writes a thin pkg wrapper script so the familiar workflow just works.
 */
public final class TermuxEnvironment {

    private static final String TAG = "TermuxEnvironment";

    /**
     * Returns a map of environment variables for a full Termux-compatible shell.
     * Automatically detects which environment is available (our bootstrap,
     * real Termux, or system fallback) and builds the correct PATH.
     */
    public static Map<String, String> buildEnvironment(Context ctx) {
        Map<String, String> env = new HashMap<>();

        boolean hasCvaBootstrap = TermuxShellUtils.isCvaBootstrapInstalled();
        boolean hasRealTermux   = TermuxShellUtils.isTermuxInstalled();

        if (hasRealTermux) {
            buildTermuxEnv(env);
            Log.d(TAG, "Using real Termux environment");
        } else if (hasCvaBootstrap) {
            buildCvaEnv(ctx, env);
            Log.d(TAG, "Using CVA bootstrap environment");
        } else {
            buildSystemEnv(ctx, env);
            Log.d(TAG, "Using system /bin/sh environment (bootstrap not installed)");
        }

        return env;
    }

    // ── CVA bootstrap environment (preferred) ────────────────────────────────

    private static void buildCvaEnv(Context ctx, Map<String, String> env) {
        String prefix    = TermuxConstants.PREFIX_PATH;
        String home      = TermuxConstants.HOME_PATH;
        String tmpdir    = TermuxConstants.TMP_PATH;
        String nativeLib = ctx.getApplicationInfo().nativeLibraryDir;

        // ── Core paths ────────────────────────────────────────────────────────
        env.put("PREFIX",    prefix);
        env.put("HOME",      home);
        env.put("TMPDIR",    tmpdir);
        env.put("SHELL",     TermuxConstants.BASH_PATH);
        env.put("TERM",      "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG",      "en_US.UTF-8");
        env.put("LC_ALL",    "en_US.UTF-8");

        // PATH: prefix/bin first, then system bins
        env.put("PATH",
                prefix + "/bin" + ":" +
                        prefix + "/bin/applets" + ":" +
                        nativeLib + ":" +
                        "/system/bin" + ":" +
                        "/system/xbin");

        // Dynamic linker path
        env.put("LD_LIBRARY_PATH",
                prefix + "/lib" + ":" +
                        prefix + "/lib/perl5/5.38.2/arm-linux-androideabi/CORE" + ":" +
                        nativeLib);

        // ── FIX 1: apt / dpkg prefix override ────────────────────────────────
        //
        // APT_CONFIG — apt reads this file BEFORE applying compiled-in defaults.
        // The file contains  Dir "/data/data/com.braingods.cva/files/usr/";
        // which redirects every apt sub-path (etc/apt/, var/lib/dpkg/, …) to our
        // own prefix instead of the hardcoded com.termux path.
        //
        // DPKG_ADMINDIR — dpkg uses this for its status database.  Without it,
        // dpkg falls back to the compiled-in /data/data/com.termux/… path and
        // "Unable to determine a suitable packaging system type" follows.
        //
        // APT_LISTCHANGES_FRONTEND — suppress interactive changelogs during install.

        // FIX: Always set APT_CONFIG to the intended path — do NOT gate on
        // File.exists().  BootstrapPostInstall.run() (called from CvaApplication)
        // creates the file on every startup.  If we only set APT_CONFIG when the
        // file already exists, the very first run after a clean bootstrap install
        // has no APT_CONFIG → apt falls back to its compiled-in com.termux path → EACCES.
        String aptConf = prefix + "/etc/apt/apt.conf";
        env.put("APT_CONFIG", aptConf);
        env.put("DPKG_ADMINDIR",            prefix + "/var/lib/dpkg");
        env.put("APT_LISTCHANGES_FRONTEND", "none");
        env.put("DEBIAN_FRONTEND",          "noninteractive");

        // ── FIX 2: ncurses / terminfo ─────────────────────────────────────────
        //
        // ncurses searches TERMINFO first, then TERMINFO_DIRS, then its compiled-in
        // default (which points at com.termux).  Setting TERMINFO to our share/terminfo
        // lets nano, vim, tmux, htop etc. find their terminal definitions.
        //
        // We list both possible locations separated by ":" in TERMINFO_DIRS so any
        // variant ncurses build finds them.
        env.put("TERMINFO",      prefix + "/share/terminfo");
        env.put("TERMINFO_DIRS", prefix + "/share/terminfo:/system/lib/terminfo");

        // ── Python support ────────────────────────────────────────────────────
        File python3 = new File(prefix + "/bin/python3");
        if (python3.exists()) {
            env.put("PYTHONHOME", prefix);
            env.put("PYTHONPATH", prefix + "/lib/python3/dist-packages");
        }

        // ── Termux-style package manager vars ─────────────────────────────────
        env.put("TERMUX_APP_PACKAGE", TermuxConstants.CVA_PACKAGE);
        env.put("TERMUX_VERSION",     "0.118.0");
        env.put("TERMUX_PREFIX",      prefix);   // used by some pkg scripts

        // ── Android metadata ──────────────────────────────────────────────────
        env.put("ANDROID_API", String.valueOf(Build.VERSION.SDK_INT));
        env.put("ANDROID_ABI", Build.SUPPORTED_ABIS[0]);
        env.put("ANDROID_DATA", "/data");
    }

    // ── Real Termux environment ───────────────────────────────────────────────

    private static void buildTermuxEnv(Map<String, String> env) {
        String prefix = TermuxConstants.TERMUX_PREFIX;
        String home   = TermuxConstants.TERMUX_HOME;

        env.put("PREFIX",          prefix);
        env.put("HOME",            home);
        env.put("TMPDIR",          prefix + "/tmp");
        env.put("SHELL",           prefix + "/bin/bash");
        env.put("TERM",            "xterm-256color");
        env.put("COLORTERM",       "truecolor");
        env.put("LANG",            "en_US.UTF-8");
        env.put("LC_ALL",          "en_US.UTF-8");
        env.put("PATH",            prefix + "/bin:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", prefix + "/lib");
        env.put("TERMINFO",        prefix + "/share/terminfo");
        env.put("TERMINFO_DIRS",   prefix + "/share/terminfo:/system/lib/terminfo");
    }

    // ── System fallback environment ───────────────────────────────────────────

    private static void buildSystemEnv(Context ctx, Map<String, String> env) {
        String home = ctx.getFilesDir().getAbsolutePath();
        env.put("HOME",   home);
        env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        env.put("SHELL",  "/system/bin/sh");
        env.put("TERM",   "xterm-256color");
        env.put("LANG",   "en_US.UTF-8");
        env.put("PATH",   ctx.getApplicationInfo().nativeLibraryDir
                + ":" + home + "/bin"
                + ":/system/bin:/system/xbin");
    }

    // ── Shell startup command builder ─────────────────────────────────────────

    /**
     * Returns the command array to start an interactive login shell.
     * Picks the best available shell in order: CVA bash → Termux bash → system sh.
     */
    public static String[] buildShellCommand(Context ctx) {
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            return new String[]{ TermuxConstants.BASH_PATH, "--login" };
        }
        if (TermuxShellUtils.isTermuxInstalled()) {
            return new String[]{ TermuxConstants.TERMUX_BASH, "--login" };
        }
        File nativeBb = new File(ctx.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
        if (nativeBb.exists() && nativeBb.canExecute()) {
            return new String[]{ nativeBb.getAbsolutePath(), "ash" };
        }
        return new String[]{ "/system/bin/sh" };
    }

    private TermuxEnvironment() {}
}