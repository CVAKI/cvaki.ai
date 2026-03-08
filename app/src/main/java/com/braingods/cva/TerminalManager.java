package com.braingods.cva;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TerminalManager {

    public interface OutputCallback {
        void onOutput(String text);
    }

    private static final String TAG         = "TerminalManager";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_USR  = "/data/data/com.termux/files/usr";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    // Ordered by preference — hush and bash confirmed present in bundled binary
    private static final String[] BUSYBOX_SHELLS = {
            "hush", "bash", "ash", "sh", "dash", "mksh", "ksh"
    };

    private Process        process;
    private OutputStream   stdin;
    private BufferedReader stdoutReader;

    private final OutputCallback    callback;
    private final ExecutorService   readExec = Executors.newSingleThreadExecutor();
    private final Context           ctx;
    private final TerminalBootstrap bootstrap;
    private final Handler           mainHandler = new Handler(Looper.getMainLooper());

    private boolean ctrlPressed = false;
    private boolean altPressed  = false;
    private String  shellPath   = null;
    private String  shellApplet = null;

    public TerminalManager(Context ctx, OutputCallback callback) {
        this.ctx       = ctx;
        this.callback  = callback;
        this.bootstrap = new TerminalBootstrap(ctx);
        bootstrap.ensureBashrc();
        resolveAndStart();
    }

    // ── Shell resolution ──────────────────────────────────────────────────────

    private void resolveAndStart() {
        // 1. Termux (best)
        if (new File(TERMUX_BASH).exists()) {
            shellPath   = TERMUX_BASH;
            shellApplet = null;
            callback.onOutput("[Termux detected ✓]\n");
            startShell();
            return;
        }

        // 2. Bundled BusyBox
        if (bootstrap.isInstalled()) {
            String bbPath = bootstrap.getShellPath();

            // First: use --list to find confirmed available shell applets
            String working = probeBusyboxShellViaList(bbPath);

            // Second: try each name directly if --list probe failed
            if (working == null) {
                working = probeBusyboxShellDirect(bbPath);
            }

            if (working != null) {
                shellPath   = bbPath;
                shellApplet = working;
                callback.onOutput("[BusyBox ready ✓  applet=" + working + "]\n");
                startShell();
                return;
            }

            // No shell found — show what IS available
            String appletList = getBusyboxAppletList(bbPath);
            Log.e(TAG, "No shell applet found. Available: " + appletList);
            callback.onOutput("[BusyBox has no shell applet!]\n");
            callback.onOutput("[Available: "
                    + appletList.substring(0, Math.min(300, appletList.length())) + "]\n\n");
        }

        // 3. System sh fallback
        callback.onOutput(
                "╔══════════════════════════════════════════════════╗\n" +
                        "║  Falling back to /system/bin/sh                  ║\n" +
                        "║  (very limited — no busybox tools)               ║\n" +
                        "╚══════════════════════════════════════════════════╝\n\n"
        );
        shellPath   = "/system/bin/sh";
        shellApplet = null;
        startShell();
    }

    // ── BusyBox probing ───────────────────────────────────────────────────────

    /** Returns full applet list as comma-separated string. */
    private String getBusyboxAppletList(String bbPath) {
        try {
            Process p = new ProcessBuilder(bbPath, "--list")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line.trim()).append(",");
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "error:" + e.getMessage();
        }
    }

    /**
     * Run busybox --list, intersect with known shell names, test each match.
     * Returns first working shell applet name, or null.
     */
    private String probeBusyboxShellViaList(String bbPath) {
        try {
            Process p = new ProcessBuilder(bbPath, "--list")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            List<String> applets = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) applets.add(line.trim().toLowerCase());
            p.waitFor();
            Log.d(TAG, "BusyBox --list: " + applets.size() + " applets");

            for (String shell : BUSYBOX_SHELLS) {
                if (applets.contains(shell) && testApplet(bbPath, shell)) {
                    Log.d(TAG, "Shell via --list: " + shell);
                    return shell;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "probeBusyboxShellViaList: " + e.getMessage());
        }
        return null;
    }

    /** Try each shell applet name directly without --list. */
    private String probeBusyboxShellDirect(String bbPath) {
        for (String applet : BUSYBOX_SHELLS) {
            if (testApplet(bbPath, applet)) {
                Log.d(TAG, "Shell via direct probe: " + applet);
                return applet;
            }
        }
        return null;
    }

    /** Test: busybox <applet> -c "echo ok" → exit 0 + output "ok". */
    private boolean testApplet(String bbPath, String applet) {
        try {
            Process p = new ProcessBuilder(bbPath, applet, "-c", "echo ok")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            boolean ok = p.exitValue() == 0 && "ok".equals(line != null ? line.trim() : "");
            Log.d(TAG, "testApplet '" + applet + "' exit=" + p.exitValue()
                    + " out=" + line + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.d(TAG, "testApplet '" + applet + "': " + e.getMessage());
            return false;
        }
    }

    // ── Shell start ───────────────────────────────────────────────────────────

    private void startShell() {
        try {
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
                process = null;
                stdin   = null;
            }

            ProcessBuilder pb;
            Map<String, String> env;
            String homeDir = ctx.getFilesDir().getAbsolutePath();
            String binDir  = ctx.getApplicationInfo().nativeLibraryDir;

            if (TERMUX_BASH.equals(shellPath)) {
                // ── Termux bash ───────────────────────────────────────────────
                pb  = new ProcessBuilder(TERMUX_BASH, "--login");
                env = pb.environment();
                env.put("SHELL",           TERMUX_BASH);
                env.put("HOME",            TERMUX_HOME);
                env.put("PREFIX",          TERMUX_USR);
                env.put("TMPDIR",          TERMUX_USR + "/tmp");
                env.put("TERM",            "xterm-256color");
                env.put("PATH",            TERMUX_USR + "/bin:" + TERMUX_USR + "/bin/applets:/system/bin:/system/xbin");
                env.put("LD_LIBRARY_PATH", TERMUX_USR + "/lib");
                env.put("LANG",            "en_US.UTF-8");
                pb.directory(new File(TERMUX_HOME));

            } else if (shellPath != null && !"/system/bin/sh".equals(shellPath)) {
                // ── BusyBox ───────────────────────────────────────────────────
                String applet = shellApplet != null ? shellApplet : "hush";
                pb  = new ProcessBuilder(shellPath, applet);
                env = pb.environment();
                env.put("PATH",    binDir + ":/system/bin:/system/xbin");
                env.put("HOME",    homeDir);
                env.put("TMPDIR",  ctx.getCacheDir().getAbsolutePath());
                env.put("TERM",    "xterm-256color");
                env.put("SHELL",   shellPath);
                env.put("BUSYBOX", shellPath);
                pb.directory(new File(homeDir));

            } else {
                // ── System sh fallback ────────────────────────────────────────
                pb  = new ProcessBuilder("/system/bin/sh");
                env = pb.environment();
                env.put("PATH", "/system/bin:/system/xbin");
                env.put("TERM", "xterm-256color");
                env.put("HOME", homeDir);
                pb.directory(new File(homeDir));
            }

            pb.redirectErrorStream(true);
            process      = pb.start();
            stdin        = process.getOutputStream();
            stdoutReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            // ── Read loop ─────────────────────────────────────────────────────
            final Process capturedProcess = process;
            readExec.execute(() -> {
                try {
                    Thread.sleep(400);

                    if (!capturedProcess.isAlive()) {
                        int exit = capturedProcess.exitValue();
                        mainHandler.post(() -> callback.onOutput(
                                "\n╔══════════════════════════════════════════╗\n" +
                                        "║  Shell died immediately!                 ║\n" +
                                        "║  Exit code : " + exit + "                              ║\n" +
                                        "║  Applet    : " + (shellApplet != null ? shellApplet : "n/a") + "\n" +
                                        "║  Device ABI: " + Build.SUPPORTED_ABIS[0] + "\n" +
                                        "╚══════════════════════════════════════════╝\n\n$ "
                        ));
                        return;
                    }

                    // Shell alive — stream output
                    char[] buf = new char[1024];
                    int n;
                    while ((n = stdoutReader.read(buf, 0, buf.length)) != -1) {
                        final String chunk = new String(buf, 0, n);
                        mainHandler.post(() -> callback.onOutput(chunk));
                    }
                } catch (IOException e) {
                    mainHandler.post(() ->
                            callback.onOutput("\n[Shell exited — tap ↺ Reset]\n"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // ── Source .bashrc once shell confirmed alive ─────────────────────
            mainHandler.postDelayed(() -> {
                if (process != null && process.isAlive()) {
                    File bashrc = new File(ctx.getFilesDir(), ".bashrc");
                    if (bashrc.exists()) {
                        sendRaw(". " + bashrc.getAbsolutePath() + "\n");
                    } else {
                        sendRaw("PS1='$ '\n");
                    }
                } else {
                    callback.onOutput("[Shell not alive — cannot source .bashrc]\n$ ");
                }
            }, 500);

        } catch (IOException e) {
            Log.e(TAG, "Failed to start shell", e);
            callback.onOutput("[ERROR starting shell: " + e.getMessage() + "]\n$ ");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void restart() {
        bootstrap.ensureBashrc();
        resolveAndStart();
    }

    public void reinstall(OutputCallback progressCb) {
        callback.onOutput("\n[BusyBox is bundled in APK — no reinstall needed]\n");
        callback.onOutput("[If missing, rebuild the APK with jniLibs included]\n\n");
        restart();
    }

    public void executeCommand(String command, OutputCallback resultCb) {
        if (process == null || !process.isAlive()) {
            resolveAndStart();
            return;
        }
        sendRaw(command + "\n");
    }

    public void sendInput(String raw)  { sendRaw(raw); }
    public void clearScreen()          { sendRaw("clear\n"); }
    public boolean isUsingTermux()     { return shellPath != null && shellPath.equals(TERMUX_BASH); }
    public boolean isBootstrapped()    { return bootstrap.isInstalled(); }
    public void setCtrl(boolean v)     { ctrlPressed = v; }
    public void setAlt(boolean v)      { altPressed  = v; }
    public boolean isCtrl()            { return ctrlPressed; }
    public boolean isCtrlPressed()     { return ctrlPressed; }

    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (process != null) process.destroy();
        readExec.shutdownNow();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private synchronized void sendRaw(String text) {
        if (stdin == null || process == null || !process.isAlive()) {
            Log.w(TAG, "sendRaw skipped — shell not running");
            return;
        }
        try {
            stdin.write(text.getBytes("UTF-8"));
            stdin.flush();
        } catch (IOException e) {
            Log.e(TAG, "stdin write failed: " + e.getMessage());
        }
    }
}