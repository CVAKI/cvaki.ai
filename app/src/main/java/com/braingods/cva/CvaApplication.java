package com.braingods.cva;

import android.app.Application;
import android.util.Log;

import com.braingods.cva.termux.BootstrapPostInstall;
import com.braingods.cva.termux.TermuxApplication;
import com.braingods.cva.termux.TermuxShellUtils;

/**
 * CvaApplication
 * ─────────────────────────────────────────────────────────────────────────────
 * App entry point.  Registered in AndroidManifest.xml as android:name=".CvaApplication"
 *
 * Responsibilities:
 *   1. Calls TermuxApplication.onAppCreate() — creates PREFIX/HOME/TMPDIR dirs,
 *      writes .profile so any shell sources the right PATH on login.
 *   2. Add any other app-wide init here (crash handlers, logging, etc.)
 *
 * WHY THIS IS NEEDED:
 *   Without an Application class, directory creation was racing with Service/Activity
 *   startup.  Running it in onCreate() guarantees dirs exist before TerminalService
 *   or TerminalManager ever touches the filesystem.
 */
public class CvaApplication extends Application {

    private static final String TAG = "CvaApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "CVA Application starting…");

        // ── Bootstrap environment setup (Termux-compatible) ──────────────────
        // Creates:  /data/data/com.braingods.cva/files/usr/   ($PREFIX)
        //           /data/data/com.braingods.cva/files/home/  ($HOME)
        //           /data/data/com.braingods.cva/files/usr/tmp/
        // Writes:   $HOME/.profile  (sourced by any POSIX login shell)
        TermuxApplication.onAppCreate(this);

        // ── BootstrapPostInstall: run on every startup ────────────────────────
        // This is idempotent — it only writes files that are missing/stale.
        // Running it here (not just inside TermuxInstaller) ensures that:
        //   1. Existing installs from before the fix get apt.conf + dpkg dirs written
        //   2. apt.conf exists by the time any shell session starts, so APT_CONFIG works
        //   3. terminfo config is always present so nano/vim/tmux don't crash
        //
        // We run it on a background thread so it never blocks the UI.
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            new Thread(() -> {
                try {
                    Log.d(TAG, "Running BootstrapPostInstall…");
                    BootstrapPostInstall.run(this, msg -> Log.d(TAG, "PostInstall: " + msg));
                    Log.d(TAG, "BootstrapPostInstall complete");
                } catch (Exception e) {
                    Log.w(TAG, "BootstrapPostInstall failed: " + e.getMessage());
                }
            }, "cva-post-install").start();
        }

        Log.d(TAG, "CVA Application ready");
    }
}