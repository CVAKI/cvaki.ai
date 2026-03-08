package com.braingods.cva;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Manages the BusyBox shell environment.
 *
 * BusyBox is bundled as jniLibs/arm64-v8a/libbusybox.so inside the APK.
 * Android extracts it to nativeLibraryDir which IS executable (proper SELinux context).
 * This is the ONLY reliable way to run executables on Android 10+ without root.
 */
public class TerminalBootstrap {

    public interface ProgressCallback {
        void onProgress(String message);
        void onDone(boolean success, String shellPath);
    }

    private static final String TAG = "TerminalBootstrap";

    private final Context ctx;

    public TerminalBootstrap(Context ctx) {
        this.ctx = ctx;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the busybox binary from nativeLibraryDir (bundled in APK) */
    public File getBusyboxFile() {
        // nativeLibraryDir = /data/app/<pkg>/lib/arm64/ — always executable
        String nativeDir = ctx.getApplicationInfo().nativeLibraryDir;
        return new File(nativeDir, "libbusybox.so");
    }

    public File getBinDir() {
        // Writable home for symlinks/scripts — use nativeLibraryDir parent
        // Actually just return the nativeLibraryDir itself since busybox is there
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    public boolean isInstalled() {
        File bb = getBusyboxFile();
        return bb.exists() && bb.canExecute();
    }

    public String getShellPath() {
        // We run busybox directly as the shell (busybox sh)
        File bb = getBusyboxFile();
        return bb.exists() ? bb.getAbsolutePath() : null;
    }

    /**
     * "Install" = just verify the bundled binary exists and write .bashrc.
     * No download needed — binary is extracted from APK by Android at install time.
     */
    public void install(ProgressCallback cb) {
        File bb = getBusyboxFile();

        cb.onProgress("Checking bundled BusyBox...");
        cb.onProgress("Path: " + bb.getAbsolutePath());

        if (!bb.exists()) {
            cb.onProgress("[ERROR] libbusybox.so not found in nativeLibraryDir!");
            cb.onProgress("Make sure jniLibs/arm64-v8a/libbusybox.so is in your project.");
            cb.onDone(false, null);
            return;
        }

        if (!bb.canExecute()) {
            // Try to chmod — usually already executable from APK extraction
            try {
                Os.chmod(bb.getAbsolutePath(), 0755);
            } catch (Exception e) {
                Log.w(TAG, "chmod failed: " + e.getMessage());
            }
        }

        if (!bb.canExecute()) {
            cb.onProgress("[ERROR] Cannot execute BusyBox — SELinux may be blocking it.");
            cb.onDone(false, null);
            return;
        }

        cb.onProgress("BusyBox found ✓ (" + (bb.length() / 1024) + " KB)");
        ensureBashrc();
        cb.onProgress("Shell environment ready.");
        cb.onDone(true, bb.getAbsolutePath());
    }

    /** Write .bashrc and .profile to a writable home dir */
    public void ensureBashrc() {
        try {
            // Use filesDir for writable scripts (not executed, just sourced)
            File homeDir = ctx.getFilesDir();
            String bbPath = getBusyboxFile().getAbsolutePath();
            String homePath = homeDir.getAbsolutePath();
            String tmpPath = ctx.getCacheDir().getAbsolutePath();

            // .profile
            File profile = new File(homeDir, ".profile");
            String profileScript =
                    "export PATH=\"" + ctx.getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin\"\n" +
                            "export HOME=\"" + homePath + "\"\n" +
                            "export TMPDIR=\"" + tmpPath + "\"\n" +
                            "export TERM=xterm-256color\n" +
                            "[ -f ~/.bashrc ] && . ~/.bashrc\n";
            try (FileOutputStream fos = new FileOutputStream(profile)) {
                fos.write(profileScript.getBytes("UTF-8"));
            }

            // .bashrc — full CVA custom config
            File bashrc = new File(homeDir, ".bashrc");
            String bashrcScript = getBashrc(
                    ctx.getApplicationInfo().nativeLibraryDir, homePath, tmpPath);
            try (FileOutputStream fos = new FileOutputStream(bashrc)) {
                fos.write(bashrcScript.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureBashrc failed: " + e.getMessage());
        }
    }

    private String getBashrc(String binPath, String homePath, String tmpPath) {
        return
                "echo -e \"\\e[1;96m  Starting cvaki daemon....\"\n" +
                        "case $- in\n" +
                        "    *i*) ;;\n" +
                        "      *) return;;\n" +
                        "esac\n" +
                        "HISTCONTROL=ignoreboth\n" +
                        "HISTSIZE=1000\n" +
                        "HISTFILESIZE=2000\n" +
                        "export PATH=\"" + binPath + ":/system/bin:/system/xbin\"\n" +
                        "export HOME=\"" + homePath + "\"\n" +
                        "export TMPDIR=\"" + tmpPath + "\"\n" +
                        "export TERM=xterm-256color\n" +
                        "alias ll='ls -alF'\n" +
                        "alias la='ls -A'\n" +
                        "alias l='ls -CF'\n" +
                        "alias cls='clear'\n" +
                        "alias grep='grep --color=auto'\n" +
                        "alias busybox='busybox'\n" +
                        "clear\n" +
                        "echo \"\"\n" +
                        "echo -e \"========================================================\"\n" +
                        "echo -e \"\\e[1;96m ██████╗\\e[1;91m           _____   __          .____  \"\n" +
                        "echo -e \"\\e[1;96m██╔════╝\\e[1;91m   ____   /  _  \\/  | __._.| \\e[1;92mc\\e[1;91m  |  \"\n" +
                        "echo -e \"\\e[1;96m██║     \\e[1;91m  /  _ \\ /  /_\\  \\   __|   |  || \\e[1;92mv\\e[1;91m  |   \"\n" +
                        "echo -e \"\\e[1;96m██║     \\e[1;91m ( \\e[1;92m (\\u2623)\\e[1;91m )    |    \\  |  \\___  || \\e[1;92ma\\e[1;91m  |___ \"\n" +
                        "echo -e \"\\e[1;96m╚██████╗\\e[1;91m  \\/\\|__  /|  / ____|  |\\/| \\\\___  |\"\n" +
                        "echo -e \"\\e[1;96m ╚═════╝\\e[1;91m                \\/      \\/      \\/\\e[1;92m powered by\"\n" +
                        "echo -e \"\\e[1;96m                                                     CVAKI\"\n" +
                        "echo -e \"\\e[1;95m§§§§§§§§§§§§§§§§§§§§§§§_{GODKILLER}_§§§§§§§§§§§§§§§§§§§§§§§\"\n" +
                        "echo -e \"\\e[1;93m MY-IP: { $(wget -qO- --timeout=3 ifconfig.me 2>/dev/null || echo 'offline') }\"\n" +
                        "echo -e \"\\e[0m\"\n" +
                        "PS1='\\[\\e[94m\\]\\u2514\\u2500×××××[\\[\\e[97m\\]\\T\\[\\e[94m\\]]§§§§§§\\e[1;94m[\\e[1;92m{C0A†YL}✓\\e[1;94m]\\e[0;94m:::::::[\\e[1;96m\\#\\e[1;92m-wings\\e[1;94m]\\n|\\n\\e[0;94m\\u253f==[\\[\\e[94m\\]\\e[0;95m\\W\\[\\e[94m\\]]\\e[1;93m-species\\[\\e[94m\\]\\e[1;91m\\[\\e[91m\\]§§\\e[1;91m[\\e[1;92m{✓}\\e[1;91m]\\e[0;94m\\n|\\n\\u2514==[\\[\\e[93m\\]≈≈≈≈≈\\e[94m\\]]™[✓]=►\\e[1;91m '\n";
    }
}