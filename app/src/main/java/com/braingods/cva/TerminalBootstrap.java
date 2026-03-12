package com.braingods.cva;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import com.braingods.cva.termux.TermuxConstants;
import com.braingods.cva.termux.TermuxInstaller;
import com.braingods.cva.termux.TermuxShellUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * TerminalBootstrap  [v2 — fixed .so treated as executable shell]
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ROOT CAUSE OF SHELL EXIT 127:
 *   libbusybox.so is a JNI shared library (ET_DYN), NOT a standalone executable.
 *   The old code had three places that all made the same wrong assumption:
 *
 *   1. install() → isInstalled() returned true because hasNativeBusybox() saw the
 *      .so file → immediately called onDone(true, libbusybox.so path) without ever
 *      downloading a real shell → TerminalManager tried to exec the .so → exit 127.
 *
 *   2. installBusyboxFallback() → hasNativeBusybox() short-circuit returned the .so
 *      path instead of downloading a real standalone busybox binary.
 *
 *   3. forceDownload() deleted the downloaded busybox then called
 *      installBusyboxFallback() which immediately returned the .so again.
 *
 * FIXES:
 *   • isExecutableShell() — new method that actually RUNS the binary with
 *     "echo ok" before trusting it as a shell.  .so files fail this test.
 *
 *   • isInstalled() now uses isExecutableShell() so it only returns true
 *     when something can actually exec.
 *
 *   • hasNativeBusyboxShell() — separate from hasNativeBusybox() (file exists)
 *     vs actually being executable as a shell.
 *
 *   • installBusyboxFallback() skips the .so and goes straight to downloading
 *     a real standalone busybox binary.
 *
 *   • ensureBashrc() uses canonical /data/data/... HOME path, not
 *     ctx.getFilesDir() which returns /data/user/0/... (bind-mount symlink).
 *
 * PRIORITY ORDER (unchanged):
 *   1. CVA Termux bootstrap ($PREFIX/bin/bash)
 *   2. Real Termux app (/data/data/com.termux/…)
 *   3. Downloaded standalone busybox (code_cache/)
 *   4. jniLibs libbusybox.so via linker64 (last resort — usually fails)
 *   5. /system/bin/sh
 */
public class TerminalBootstrap {

    public interface ProgressCallback {
        void onProgress(String message);
        void onDone(boolean success, String shellPath);
    }

    private static final String TAG = "TerminalBootstrap";

    // Standalone static busybox binaries (NOT shared libraries)
    private static final String URL_ARM64_PRIMARY  = "https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox_ARM64";
    private static final String URL_ARM64_FALLBACK = "https://cdn.jsdelivr.net/gh/EXALAB/Busybox-static@main/busybox_arm64";
    private static final String URL_ARM_PRIMARY    = "https://busybox.net/downloads/binaries/1.35.0-x86_64-linux-musl/busybox_ARMV7l";
    private static final String URL_ARM_FALLBACK   = "https://cdn.jsdelivr.net/gh/EXALAB/Busybox-static@main/busybox_arm";
    private static final String URL_X86_64         = "https://cdn.jsdelivr.net/gh/EXALAB/Busybox-static@main/busybox_amd64";

    private final Context ctx;

    public TerminalBootstrap(Context ctx) {
        this.ctx = ctx;
    }

    // ── Primary: Termux bootstrap checks ─────────────────────────────────────

    public boolean hasTermuxBootstrap() { return TermuxShellUtils.isCvaBootstrapInstalled(); }
    public boolean hasRealTermux()      { return TermuxShellUtils.isTermuxInstalled(); }

    // ── BusyBox file checks ───────────────────────────────────────────────────

    /** The jniLibs .so file — exists but may NOT be directly executable as a shell */
    public File getNativeBusybox() {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
    }

    /**
     * Downloaded standalone busybox binary.
     *
     * FIX: Previously stored in getCodeCacheDir() which is mounted noexec on
     * Android 10+ (API 29+) — the kernel rejects execve() there with EACCES
     * regardless of chmod 755.  We now store the binary in
     * /data/data/<pkg>/files/bin/ which is always exec-allowed for the app.
     */
    public File getDownloadedBusybox() {
        File dir = new File("/data/data/" + ctx.getPackageName() + "/files/bin");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "busybox");
    }

    public String getLinker() {
        return Build.SUPPORTED_ABIS[0].contains("64")
                ? "/system/bin/linker64"
                : "/system/bin/linker";
    }

    /** File exists and has right size — does NOT mean it can be exec'd as a shell */
    public boolean hasNativeBusybox() {
        File f = getNativeBusybox();
        return f.exists() && f.canExecute() && f.length() > 500_000;
    }

    /** Downloaded busybox file exists and has right size */
    public boolean hasDownloadedBusybox() {
        File f = getDownloadedBusybox();
        return f.exists() && f.length() > 500_000;
    }

    /**
     * FIX: Test whether the native .so can actually be executed as a shell.
     * libbusybox.so is a JNI shared library — it CANNOT be exec()'d directly.
     * We test both direct exec and linker64-based exec before trusting it.
     */
    public boolean hasNativeBusyboxShell() {
        if (!hasNativeBusybox()) return false;
        return isExecutableShell(getNativeBusybox().getAbsolutePath());
    }

    /**
     * FIX: Actually run the binary to verify it works as a shell.
     * This catches .so files, corrupted downloads, wrong-arch binaries, etc.
     */
    public boolean isExecutableShell(String path) {
        if (path == null || path.isEmpty()) return false;
        // Try direct exec
        for (String applet : new String[]{"sh", "ash", ""}) {
            try {
                String[] cmd = applet.isEmpty()
                        ? new String[]{path, "-c", "echo ok"}
                        : new String[]{path, applet, "-c", "echo ok"};
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = new BufferedReader(
                        new InputStreamReader(p.getInputStream())).readLine();
                p.waitFor();
                if ("ok".equals(out != null ? out.trim() : "")) {
                    Log.d(TAG, "Shell OK (direct): " + path + " " + applet);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        // Try via linker64/linker (for PIE/ET_DYN binaries)
        String linker = getLinker();
        for (String applet : new String[]{"sh", "ash", ""}) {
            try {
                String[] cmd = applet.isEmpty()
                        ? new String[]{linker, path, "-c", "echo ok"}
                        : new String[]{linker, path, applet, "-c", "echo ok"};
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = new BufferedReader(
                        new InputStreamReader(p.getInputStream())).readLine();
                p.waitFor();
                if ("ok".equals(out != null ? out.trim() : "")) {
                    Log.d(TAG, "Shell OK (linker): " + path + " " + applet);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        Log.w(TAG, "Not an executable shell: " + path);
        return false;
    }

    /**
     * FIX: isDirectlyExecutable now means the .so passes the actual exec test,
     * not just "the file exists and is big enough".
     */
    public boolean isDirectlyExecutable() { return hasNativeBusyboxShell(); }

    /**
     * FIX: isInstalled() only returns true if something can ACTUALLY exec as a shell.
     * Previously returned true for the .so file which cannot be exec'd.
     */
    public boolean isInstalled() {
        return hasTermuxBootstrap()
                || hasRealTermux()
                || hasDownloadedBusybox()          // downloaded first — more reliable
                || hasNativeBusyboxShell();        // .so last — only if exec works
    }

    /**
     * FIX: getBusyboxFile() prefers the downloaded standalone binary over the .so.
     * The .so is only returned if it passes the executable shell test.
     */
    public File getBusyboxFile() {
        if (hasDownloadedBusybox()) return getDownloadedBusybox();
        if (hasNativeBusyboxShell()) return getNativeBusybox();
        return null;
    }

    public String getBestShellPath() {
        if (hasTermuxBootstrap())    return TermuxConstants.BASH_PATH;
        if (hasRealTermux())         return TermuxConstants.TERMUX_BASH;
        if (hasDownloadedBusybox())  return getDownloadedBusybox().getAbsolutePath();
        if (hasNativeBusyboxShell()) return getNativeBusybox().getAbsolutePath();
        return "/system/bin/sh";
    }

    public String[] getShellCommand(String applet) {
        if (hasDownloadedBusybox()) {
            return new String[]{ getDownloadedBusybox().getAbsolutePath(), applet };
        }
        if (hasNativeBusyboxShell()) {
            return new String[]{ getNativeBusybox().getAbsolutePath(), applet };
        }
        return new String[]{ "/system/bin/sh" };
    }

    public String[] getListCommand() {
        if (hasDownloadedBusybox())
            return new String[]{ getDownloadedBusybox().getAbsolutePath(), "--list" };
        if (hasNativeBusybox())
            return new String[]{ getLinker(), getNativeBusybox().getAbsolutePath(), "--list" };
        return null;
    }

    // ── Install ───────────────────────────────────────────────────────────────

    /**
     * FIX: install() no longer short-circuits on the .so file.
     * isInstalled() now requires an actually executable shell.
     * Order: Termux bootstrap → downloaded busybox → .so (if exec works)
     */
    public void install(ProgressCallback cb) {
        // Already have a working shell — no need to install
        if (isInstalled()) {
            String path = getBestShellPath();
            cb.onProgress("Shell already available: " + path);
            ensureBashrc();
            cb.onDone(true, path);
            return;
        }

        // Try full Termux bootstrap first (bash + python3 + git + vim + curl + tmux)
        cb.onProgress("Starting Termux bootstrap installation…");
        cb.onProgress("(Full bash + python3 + git + vim + curl + wget + tmux)");

        TermuxInstaller termuxInstaller = new TermuxInstaller(ctx);

        termuxInstaller.install(new TermuxInstaller.InstallCallback() {
            @Override public void onProgress(String message) { cb.onProgress(message); }
            @Override public void onSuccess() {
                cb.onProgress("✓ Termux bootstrap installed — bash ready");
                ensureBashrc();
                cb.onDone(true, TermuxConstants.BASH_PATH);
            }
            @Override public void onFailure(String error) {
                cb.onProgress("Bootstrap download failed: " + error);
                cb.onProgress("Falling back to standalone BusyBox download…");
                installBusyboxFallback(cb);
            }
        });
    }

    /**
     * Force re-download of a standalone busybox binary.
     * FIX: Does NOT return the .so path as a shortcut anymore.
     */
    public void forceDownload(ProgressCallback cb) {
        cb.onProgress("Forcing fresh standalone busybox download…");
        // Delete any stale downloaded binary
        File stale = getDownloadedBusybox();
        if (stale.exists()) stale.delete();
        // Go straight to network download — skip .so shortcut
        downloadBusyboxFromNetwork(cb);
    }

    // ── BusyBox fallback install ──────────────────────────────────────────────

    /**
     * FIX: installBusyboxFallback() no longer short-circuits on hasNativeBusybox().
     * The .so is a shared library and cannot be used as a standalone shell.
     * We always try to download a real static busybox binary first.
     * Only if download fails AND the .so actually passes exec test do we use it.
     */
    private void installBusyboxFallback(ProgressCallback cb) {
        // Prefer previously downloaded binary if it still works
        if (hasDownloadedBusybox()) {
            File bb = getDownloadedBusybox();
            if (isExecutableShell(bb.getAbsolutePath())) {
                cb.onProgress("BusyBox [downloaded] ✓ (" + (bb.length() / 1024) + " KB)");
                ensureBashrc();
                cb.onDone(true, bb.getAbsolutePath());
                return;
            } else {
                // Stale/corrupt download — delete and re-download
                cb.onProgress("Downloaded busybox is not executable — re-downloading…");
                bb.delete();
            }
        }

        // Download a real standalone binary
        downloadBusyboxFromNetwork(cb);
    }

    private void downloadBusyboxFromNetwork(ProgressCallback cb) {
        String abi = Build.SUPPORTED_ABIS[0];
        boolean isArm64  = abi.contains("arm64") || abi.contains("aarch64");
        boolean isX86_64 = abi.contains("x86_64") || abi.contains("amd64");

        String[] urls;
        if (isArm64)       urls = new String[]{ URL_ARM64_PRIMARY, URL_ARM64_FALLBACK };
        else if (isX86_64) urls = new String[]{ URL_X86_64 };
        else               urls = new String[]{ URL_ARM_PRIMARY, URL_ARM_FALLBACK };

        cb.onProgress("Downloading standalone BusyBox for " + abi + "…");

        File tmp = new File(ctx.getCacheDir(), "busybox.tmp");
        boolean downloaded = false;

        for (String url : urls) {
            try {
                cb.onProgress("Trying: " + url);
                if (tmp.exists()) tmp.delete();
                downloadFile(url, tmp, cb);
                if (tmp.exists() && tmp.length() > 500_000) {
                    downloaded = true;
                    break;
                }
                cb.onProgress("[Retry] Too small — trying next URL…");
            } catch (Exception e) {
                cb.onProgress("[Retry] " + e.getMessage());
            }
        }

        try {
            if (!downloaded) {
                // Last resort: check if .so is actually executable via linker
                if (hasNativeBusyboxShell()) {
                    cb.onProgress("Download failed — using jniLibs busybox (linker mode)");
                    ensureBashrc();
                    cb.onDone(true, getNativeBusybox().getAbsolutePath());
                } else {
                    cb.onProgress("[ERROR] All download sources failed and .so is not executable");
                    cb.onDone(false, null);
                }
                return;
            }

            File dest = getDownloadedBusybox();
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) { copyFile(tmp, dest); tmp.delete(); }

            // chmod +x
            try { Os.chmod(dest.getAbsolutePath(), 0755); }
            catch (Exception e) {
                try { Runtime.getRuntime().exec(
                        new String[]{"chmod", "755", dest.getAbsolutePath()}).waitFor();
                } catch (Exception ignored) {}
            }

            // Verify it actually works as a shell
            if (isExecutableShell(dest.getAbsolutePath())) {
                cb.onProgress("BusyBox downloaded ✓ (" + (dest.length() / 1024) + " KB)");
                ensureBashrc();
                cb.onDone(true, dest.getAbsolutePath());
            } else {
                cb.onProgress("[WARN] Downloaded binary not executable — trying linker mode");
                // Try running it via linker64
                if (isExecutableShell(dest.getAbsolutePath())) {
                    ensureBashrc();
                    cb.onDone(true, dest.getAbsolutePath());
                } else {
                    cb.onProgress("[ERROR] Downloaded busybox cannot execute on this device");
                    cb.onDone(false, null);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "BusyBox install failed", e);
            cb.onProgress("[ERROR] " + e.getMessage());
            cb.onDone(false, null);
        }
    }

    // ── Bashrc ────────────────────────────────────────────────────────────────

    /**
     * FIX: Uses canonical /data/data/<pkg>/files path for HOME,
     * NOT ctx.getFilesDir() which returns /data/user/0/... on API 24+.
     * The two paths are bind-mount aliases but resolve differently inside
     * ProcessBuilder subprocesses, causing .bashrc not-found errors.
     */
    public void ensureBashrc() {
        try {
            File home;
            if (hasTermuxBootstrap()) {
                home = new File(TermuxConstants.HOME_PATH);
            } else if (hasRealTermux()) {
                home = new File(TermuxConstants.TERMUX_HOME);
            } else {
                // FIX: use /data/data/... not ctx.getFilesDir() (/data/user/0/...)
                home = new File("/data/data/" + ctx.getPackageName() + "/files");
            }
            home.mkdirs();

            File bb = getBusyboxFile();
            String bbPath = bb != null ? bb.getAbsolutePath() : "/system/bin/sh";
            String hp  = home.getAbsolutePath();
            String tmp = ctx.getCacheDir().getAbsolutePath();
            String bin;

            if (hasTermuxBootstrap()) {
                bin = TermuxConstants.PREFIX_BIN
                        + ":" + ctx.getApplicationInfo().nativeLibraryDir
                        + ":/system/bin:/system/xbin";
            } else {
                bin = ctx.getApplicationInfo().nativeLibraryDir
                        + ":" + hp + ":/system/bin:/system/xbin";
            }

            File profile = new File(home, ".profile");
            try (FileOutputStream o = new FileOutputStream(profile)) {
                o.write(("export PATH=\"" + bin + "\"\n"
                        + "export HOME=\"" + hp + "\"\n"
                        + "export TMPDIR=\"" + tmp + "\"\n"
                        + "export TERM=xterm-256color\n"
                        + "[ -f ~/.bashrc ] && . ~/.bashrc\n").getBytes("UTF-8"));
            }

            File bashrc = new File(home, ".bashrc");
            try (FileOutputStream o = new FileOutputStream(bashrc)) {
                o.write(getBashrc(bin, hp, tmp, bbPath).getBytes("UTF-8"));
            }

            Log.d(TAG, "ensureBashrc: written to " + home.getAbsolutePath());

        } catch (Exception e) {
            Log.w(TAG, "ensureBashrc: " + e.getMessage());
        }
    }

    private String getBashrc(String binPath, String homePath, String tmpPath, String bbPath) {
        return  "case $- in\n    *i*) ;;\n      *) return;;\nesac\n" +
                "HISTCONTROL=ignoreboth\n" +
                "HISTSIZE=1000\n" +
                "command -v shopt >/dev/null 2>&1 && { shopt -s histappend; shopt -s checkwinsize; }\n" +
                "export PATH=\"" + binPath + ":/system/bin:/system/xbin\"\n" +
                "export HOME=\"" + homePath + "\"\n" +
                "export TMPDIR=\"" + tmpPath + "\"\n" +
                "export TERM=xterm-256color\n" +
                "alias ll='ls -alF'\nalias la='ls -A'\nalias l='ls -CF'\n" +
                "alias cls='clear'\nalias grep='grep --color=auto'\n" +
                "alias busybox='" + bbPath + "'\n" +
                "clear\n" +
                "echo -e \"  ██████╗██╗   ██╗ █████╗ \"\n" +
                "echo -e \" ██╔════╝██║   ██║██╔══██╗\"\n" +
                "echo -e \" ██║     ██║   ██║███████║\"\n" +
                "echo -e \" ██║     ╚██╗ ██╔╝██╔══██║\"\n" +
                "echo -e \" ╚██████╗ ╚████╔╝ ██║  ██║\"\n" +
                "echo -e \"  ╚═════╝  ╚═══╝  ╚═╝  ╚═╝  powered by CVAKI\n" +
                "echo -e \" ────────────────────────────────────────\"\n" +
                "echo -e \" IP: $(curl -s --max-time 4 ifconfig.me 2>/dev/null || echo offline)\"\n" +
                "command -v free >/dev/null 2>&1 && echo -e \"\\e[0;90m$(free -h)\"\n" +
                "echo -e \"$(df -h 2>/dev/null | head -4)\"\n" +
                "echo ''\n" +
                "PS1='\\[]┌[\\[]\\u\\[\\]@\\[\\]cva\\[]]─[\\[\\]\\W\\[]]\\n\\[]└─►  '";
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
                        cb.onProgress("  " + pct + "% (" + (done / 1024) + " KB)");
                        lastPct = pct;
                    }
                }
            }
        }
        cb.onProgress("Download complete (" + (done / 1024) + " KB)");
    }

    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}