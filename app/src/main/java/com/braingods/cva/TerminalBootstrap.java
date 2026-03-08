package com.braingods.cva;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Manages the BusyBox shell environment.
 *
 * ANDROID 10+ W^X SECURITY POLICY:
 *   Apps cannot execve() from filesDir (writable directories).
 *   nativeLibraryDir is the ONLY reliably executable location.
 *
 * RESOLUTION PRIORITY:
 *   1. nativeLibraryDir/libbusybox.so   ← jniLibs bundle (BEST, always works)
 *   2. filesDir/busybox via linker64     ← downloaded, executed via system linker
 *   3. /system/bin/sh fallback           ← always available, limited
 *
 * PERMANENT FIX (do once, never worry again):
 *   1. Copy busybox binaries into your project:
 *        app/src/main/jniLibs/arm64-v8a/libbusybox.so
 *        app/src/main/jniLibs/armeabi-v7a/libbusybox.so
 *        app/src/main/jniLibs/x86_64/libbusybox.so
 *   2. In app/.gitignore add:  !src/main/jniLibs/**
 *   3. Commit. Done forever.
 */
public class TerminalBootstrap {

    public interface ProgressCallback {
        void onProgress(String message);
        void onDone(boolean success, String shellPath);
    }

    private static final String TAG = "TerminalBootstrap";

    // jsDelivr CDN — confirmed working (arm64=2729KB, has hush+bash)
    private static final String URL_ARM64  = "https://cdn.jsdelivr.net/gh/EXALAB/Busybox-static@main/busybox_arm64";
    private static final String URL_ARM    = "https://cdn.jsdelivr.net/gh/EXALAB/Busybox-static@main/busybox_arm";
    private static final String URL_X86_64 = "https://cdn.jsdelivr.net/gh/EXALAB/Busybox-static@main/busybox_amd64";

    private final Context ctx;

    public TerminalBootstrap(Context ctx) {
        this.ctx = ctx;
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    /** From jniLibs — extracted by Android at APK install to nativeLibraryDir */
    public File getNativeBusybox() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
    }

    /** Downloaded at runtime into filesDir */
    public File getDownloadedBusybox() {
        return new File(ctx.getFilesDir(), "busybox");
    }

    /** System dynamic linker — used to exec binaries from non-exec directories */
    public String getLinker() {
        return Build.SUPPORTED_ABIS[0].contains("64")
                ? "/system/bin/linker64"
                : "/system/bin/linker";
    }

    public boolean hasNativeBusybox() {
        File f = getNativeBusybox();
        return f.exists() && f.canExecute() && f.length() > 500_000;
    }

    public boolean hasDownloadedBusybox() {
        File f = getDownloadedBusybox();
        return f.exists() && f.length() > 500_000;
    }

    /** True if binary is in nativeLibraryDir (directly executable) */
    public boolean isDirectlyExecutable() { return hasNativeBusybox(); }

    /** True if any busybox binary is available */
    public boolean isInstalled()          { return hasNativeBusybox() || hasDownloadedBusybox(); }

    /** Best available busybox file, or null */
    public File getBusyboxFile() {
        if (hasNativeBusybox())     return getNativeBusybox();
        if (hasDownloadedBusybox()) return getDownloadedBusybox();
        return null;
    }

    // ── Command builders (handles linker64 wrapping automatically) ────────────

    /** Build args to run:  busybox <applet>  (or linker64 busybox <applet>) */
    public String[] getShellCommand(String applet) {
        if (hasNativeBusybox()) {
            return new String[]{ getNativeBusybox().getAbsolutePath(), applet };
        }
        if (hasDownloadedBusybox()) {
            // W^X workaround: /system/bin/linker64 /path/busybox applet
            return new String[]{ getLinker(), getDownloadedBusybox().getAbsolutePath(), applet };
        }
        return new String[]{ "/system/bin/sh" };
    }

    /** Build args to probe:  busybox <applet> [extra args] */
    public String[] getProbeCommand(String applet, String... extra) {
        String bb = hasNativeBusybox()
                ? getNativeBusybox().getAbsolutePath()
                : (hasDownloadedBusybox() ? getDownloadedBusybox().getAbsolutePath() : null);
        if (bb == null) return null;

        if (hasNativeBusybox()) {
            String[] cmd = new String[2 + extra.length];
            cmd[0] = bb; cmd[1] = applet;
            System.arraycopy(extra, 0, cmd, 2, extra.length);
            return cmd;
        } else {
            String[] cmd = new String[3 + extra.length];
            cmd[0] = getLinker(); cmd[1] = bb; cmd[2] = applet;
            System.arraycopy(extra, 0, cmd, 3, extra.length);
            return cmd;
        }
    }

    /** Build args for busybox --list */
    public String[] getListCommand() {
        if (hasNativeBusybox())
            return new String[]{ getNativeBusybox().getAbsolutePath(), "--list" };
        if (hasDownloadedBusybox())
            return new String[]{ getLinker(), getDownloadedBusybox().getAbsolutePath(), "--list" };
        return null;
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * Ensures busybox is ready. Must be called off the main thread.
     */
    public void install(ProgressCallback cb) {
        // ── Best case: jniLibs binary present ────────────────────────────────
        if (hasNativeBusybox()) {
            File bb = getNativeBusybox();
            cb.onProgress("BusyBox [nativeLib] ✓ (" + (bb.length()/1024) + " KB)");
            ensureBashrc();
            cb.onDone(true, bb.getAbsolutePath());
            return;
        }

        // ── Already downloaded ────────────────────────────────────────────────
        if (hasDownloadedBusybox()) {
            File bb = getDownloadedBusybox();
            cb.onProgress("BusyBox [downloaded] ✓ (" + (bb.length()/1024) + " KB)");
            cb.onProgress("Using linker64 execution method.");
            ensureBashrc();
            cb.onDone(true, bb.getAbsolutePath());
            return;
        }

        // ── Download ──────────────────────────────────────────────────────────
        String abi = Build.SUPPORTED_ABIS[0];
        String url = abi.contains("arm64") || abi.contains("aarch64") ? URL_ARM64
                : abi.contains("x86_64") || abi.contains("amd64")  ? URL_X86_64
                : URL_ARM;

        cb.onProgress("Downloading BusyBox for " + abi + "...");

        try {
            File tmp  = new File(ctx.getCacheDir(), "busybox.tmp");
            downloadFile(url, tmp, cb);

            if (!tmp.exists() || tmp.length() < 500_000) {
                cb.onProgress("[ERROR] Download too small (" + tmp.length() + " B)");
                cb.onDone(false, null);
                return;
            }

            File dest = getDownloadedBusybox();
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) { copyFile(tmp, dest); tmp.delete(); }

            try { Os.chmod(dest.getAbsolutePath(), 0755); }
            catch (Exception e) {
                try { Runtime.getRuntime().exec(
                        new String[]{"chmod","755",dest.getAbsolutePath()}).waitFor();
                } catch (Exception ignored) {}
            }

            cb.onProgress("Downloaded ✓ (" + (dest.length()/1024) + " KB)");
            cb.onProgress("Android 10+ blocks direct exec from filesDir.");
            cb.onProgress("Using linker64 workaround...");

            ensureBashrc();
            cb.onDone(true, dest.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            cb.onProgress("[ERROR] " + e.getMessage());
            cb.onDone(false, null);
        }
    }

    // ── Bashrc ────────────────────────────────────────────────────────────────

    public void ensureBashrc() {
        try {
            File   home = ctx.getFilesDir();
            String bb   = getBusyboxFile() != null
                    ? getBusyboxFile().getAbsolutePath() : "/system/bin/sh";
            String hp   = home.getAbsolutePath();
            String tmp  = ctx.getCacheDir().getAbsolutePath();
            String bin  = ctx.getApplicationInfo().nativeLibraryDir
                    + ":" + hp;

            File profile = new File(home, ".profile");
            try (FileOutputStream o = new FileOutputStream(profile)) {
                o.write(("export PATH=\"" + bin + ":/system/bin:/system/xbin\"\n"
                        + "export HOME=\"" + hp + "\"\n"
                        + "export TMPDIR=\"" + tmp + "\"\n"
                        + "export TERM=xterm-256color\n"
                        + "[ -f ~/.bashrc ] && . ~/.bashrc\n").getBytes("UTF-8"));
            }

            File bashrc = new File(home, ".bashrc");
            try (FileOutputStream o = new FileOutputStream(bashrc)) {
                o.write(getBashrc(bin, hp, tmp, bb).getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureBashrc: " + e.getMessage());
        }
    }

    private String getBashrc(String binPath, String homePath, String tmpPath, String bbPath) {
        return
                "case $- in\n    *i*) ;;\n      *) return;;\nesac\n" +
                        "HISTCONTROL=ignoreboth\nHISTSIZE=1000\n" +
                        "export PATH=\"" + binPath + ":/system/bin:/system/xbin\"\n" +
                        "export HOME=\"" + homePath + "\"\n" +
                        "export TMPDIR=\"" + tmpPath + "\"\n" +
                        "export TERM=xterm-256color\n" +
                        "alias ll='ls -alF'\nalias la='ls -A'\nalias l='ls -CF'\n" +
                        "alias cls='clear'\nalias grep='grep --color=auto'\n" +
                        "alias busybox='" + bbPath + "'\n" +
                        "clear\n" +
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void downloadFile(String urlStr, File dest, ProgressCallback cb) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "CVA/1.0");
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long total = conn.getContentLengthLong(), done = 0; int lastPct = -1;
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n); done += n;
                if (total > 0) {
                    int pct = (int)(done * 100 / total);
                    if (pct != lastPct && pct % 10 == 0) {
                        cb.onProgress("  " + pct + "% (" + (done/1024) + " KB)");
                        lastPct = pct;
                    }
                }
            }
        }
        cb.onProgress("Download complete (" + (done/1024) + " KB)");
    }

    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}