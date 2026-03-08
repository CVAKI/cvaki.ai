package com.braingods.cva;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TerminalManager
 * ─────────────────────────────────────────────────────────────────────────────
 * Manages the persistent shell process (Termux bash → BusyBox applet → /system/bin/sh).
 *
 * Key additions for agentic live mode:
 *   • setAgentOutputCallback(cb) — BrainAgent taps this to capture command output.
 *   • sendInput(raw)             — BrainAgent sends commands (also used by live typing).
 */
public class TerminalManager {

    public interface OutputCallback { void onOutput(String text); }

    private static final String TAG         = "TerminalManager";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_USR  = "/data/data/com.termux/files/usr";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private static final String[] BUSYBOX_SHELLS = { "ash", "hush", "bash", "sh", "dash", "mksh", "ksh" };

    private Process        process;
    private OutputStream   stdin;
    private BufferedReader stdoutReader;

    private final OutputCallback  callback;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Context         ctx;
    private final TerminalBootstrap bootstrap;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private boolean ctrlPressed = false;
    private boolean altPressed  = false;
    private String  shellPath   = null;
    private String  shellApplet = null;
    private boolean useLinker64 = false;

    /** Tapped by BrainAgent during agentic command execution to capture output. */
    private volatile OutputCallback agentOutputCallback = null;

    public void setAgentOutputCallback(OutputCallback cb) { this.agentOutputCallback = cb; }

    public TerminalManager(Context ctx, OutputCallback callback) {
        this.ctx       = ctx;
        this.callback  = callback;
        this.bootstrap = new TerminalBootstrap(ctx);
        initShell();
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void initShell() {
        if (bootstrap.isInstalled()) {
            resolveAndStart();
        } else {
            mainHandler.post(() -> callback.onOutput(
                    "╔══════════════════════════════════════════╗\n" +
                            "║  CVA Terminal — First Run Setup          ║\n" +
                            "║  Downloading BusyBox shell...            ║\n" +
                            "╚══════════════════════════════════════════╝\n\n"
            ));
            executor.execute(() -> bootstrap.install(new TerminalBootstrap.ProgressCallback() {
                @Override public void onProgress(String msg) {
                    mainHandler.post(() -> callback.onOutput(msg + "\n"));
                }
                @Override public void onDone(boolean ok, String path) {
                    mainHandler.post(() -> {
                        if (ok) { callback.onOutput("\n[BusyBox ready — starting shell...]\n\n"); resolveAndStart(); }
                        else    { callback.onOutput("\n[Download failed — using /system/bin/sh]\n\n$ "); startSystemSh(); }
                    });
                }
            }));
        }
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    private void resolveAndStart() {
        if (new File(TERMUX_BASH).exists()) {
            shellPath = TERMUX_BASH; shellApplet = null; useLinker64 = false;
            callback.onOutput("[Termux detected ✓]\n");
            startShell(); return;
        }
        if (bootstrap.isInstalled()) {
            useLinker64 = !bootstrap.isDirectlyExecutable();
            String applet = probeBusyboxShell();
            if (applet != null) {
                shellPath   = bootstrap.getBusyboxFile().getAbsolutePath();
                shellApplet = applet.equals("__direct__") ? null : applet;
                callback.onOutput("[BusyBox ✓  applet=" + (shellApplet != null ? shellApplet : "direct")
                        + (useLinker64 ? "  via=linker64" : "  via=direct") + "]\n");
                startShell(); return;
            }
            callback.onOutput("[BusyBox found but no shell applet — falling back]\n");
        }
        startSystemSh();
    }

    private void startSystemSh() {
        shellPath = "/system/bin/sh"; shellApplet = null; useLinker64 = false;
        callback.onOutput("[Using /system/bin/sh (limited)]\n\n");
        startShell();
    }

    // ── BusyBox probing ───────────────────────────────────────────────────────

    private String probeBusyboxShell() {
        String[] listCmd = bootstrap.getListCommand();
        if (listCmd != null) {
            try {
                Process p = new ProcessBuilder(listCmd).redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                java.util.List<String> applets = new java.util.ArrayList<>();
                String line;
                while ((line = r.readLine()) != null) applets.add(line.trim().toLowerCase());
                p.waitFor();
                Log.d(TAG, "--list returned " + applets.size() + " applets");
                if (applets.size() > 3) {
                    for (String shell : BUSYBOX_SHELLS) {
                        if (applets.contains(shell) && testApplet(shell)) return shell;
                    }
                } else {
                    Log.d(TAG, "Too few applets — probing directly");
                }
            } catch (Exception e) { Log.d(TAG, "--list failed: " + e.getMessage()); }
        }
        for (String shell : BUSYBOX_SHELLS) {
            if (testApplet(shell)) return shell;
        }
        if (testBinaryDirectly()) return "__direct__";
        return null;
    }

    private boolean testBinaryDirectly() {
        File bb = bootstrap.getBusyboxFile();
        if (bb == null) return false;
        try {
            String[] cmd = bootstrap.isDirectlyExecutable()
                    ? new String[]{ bb.getAbsolutePath(), "-c", "echo ok" }
                    : new String[]{ bootstrap.getLinker(), bb.getAbsolutePath(), "-c", "echo ok" };
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            boolean ok = "ok".equals(line != null ? line.trim() : "");
            Log.d(TAG, "testBinaryDirectly -> " + ok);
            return ok;
        } catch (Exception e) { Log.d(TAG, "testBinaryDirectly exception: " + e.getMessage()); return false; }
    }

    private boolean testApplet(String applet) {
        String[] cmd = bootstrap.getProbeCommand(applet, "-c", "echo ok");
        if (cmd == null) return false;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor();
            boolean ok = p.exitValue() == 0 && "ok".equals(line != null ? line.trim() : "");
            Log.d(TAG, "testApplet '" + applet + "' -> " + ok);
            return ok;
        } catch (Exception e) { Log.d(TAG, "testApplet '" + applet + "' exception: " + e.getMessage()); return false; }
    }

    // ── Shell start ───────────────────────────────────────────────────────────

    private void startShell() {
        try {
            if (process != null) { try { process.destroy(); } catch (Exception ignored) {} process = null; stdin = null; }

            ProcessBuilder pb;
            Map<String, String> env;
            String homeDir = ctx.getFilesDir().getAbsolutePath();

            if (TERMUX_BASH.equals(shellPath)) {
                pb  = new ProcessBuilder(TERMUX_BASH, "--login");
                env = pb.environment();
                env.put("SHELL", TERMUX_BASH); env.put("HOME", TERMUX_HOME);
                env.put("PREFIX", TERMUX_USR); env.put("TMPDIR", TERMUX_USR + "/tmp");
                env.put("TERM", "xterm-256color");
                env.put("PATH", TERMUX_USR + "/bin:/system/bin:/system/xbin");
                env.put("LD_LIBRARY_PATH", TERMUX_USR + "/lib");
                env.put("LANG", "en_US.UTF-8");
                pb.directory(new File(TERMUX_HOME));

            } else if ("/system/bin/sh".equals(shellPath)) {
                pb  = new ProcessBuilder("/system/bin/sh");
                env = pb.environment();
                env.put("PATH", "/system/bin:/system/xbin");
                env.put("TERM", "xterm-256color");
                env.put("HOME", homeDir);
                pb.directory(new File(homeDir));

            } else {
                String applet = shellApplet != null ? shellApplet : "ash";
                String[] cmd  = bootstrap.getShellCommand(applet);
                pb  = new ProcessBuilder(cmd);
                env = pb.environment();
                env.put("PATH",    ctx.getApplicationInfo().nativeLibraryDir + ":" + homeDir + ":/system/bin:/system/xbin");
                env.put("HOME",    homeDir);
                env.put("TMPDIR",  ctx.getCacheDir().getAbsolutePath());
                env.put("TERM",    "xterm-256color");
                env.put("SHELL",   shellPath);
                env.put("BUSYBOX", shellPath);
                pb.directory(new File(homeDir));
            }

            pb.redirectErrorStream(true);
            process      = pb.start();
            stdin        = process.getOutputStream();
            stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            final Process capturedProcess = process;
            executor.execute(() -> {
                try {
                    Thread.sleep(400);
                    if (!capturedProcess.isAlive()) {
                        int exit = capturedProcess.exitValue();
                        mainHandler.post(() -> callback.onOutput(
                                "\n[Shell exited " + exit + (useLinker64 ? " — linker64 may not work on this device" : "") + "]\n$ "));
                        if (useLinker64) mainHandler.post(() -> {
                            callback.onOutput("[Falling back to /system/bin/sh]\n\n");
                            startSystemSh();
                        });
                        return;
                    }
                    char[] buf = new char[1024]; int n;
                    while ((n = stdoutReader.read(buf, 0, buf.length)) != -1) {
                        final String chunk = new String(buf, 0, n);
                        mainHandler.post(() -> callback.onOutput(chunk));
                        // Tap output to BrainAgent if it is waiting for a command result
                        OutputCallback agentCb = agentOutputCallback;
                        if (agentCb != null) agentCb.onOutput(chunk);
                    }
                } catch (IOException e) {
                    mainHandler.post(() -> callback.onOutput("\n[Shell exited — tap ↺ Reset]\n"));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });

            // Source .bashrc once shell is alive
            mainHandler.postDelayed(() -> {
                if (process != null && process.isAlive()) {
                    File bashrc = new File(ctx.getFilesDir(), ".bashrc");
                    if (bashrc.exists()) sendRaw(". " + bashrc.getAbsolutePath() + "\n");
                    else                 sendRaw("PS1='$ '\n");
                }
            }, 600);

        } catch (IOException e) {
            Log.e(TAG, "startShell failed", e);
            callback.onOutput("[ERROR: " + e.getMessage() + "]\n$ ");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void restart()           { bootstrap.ensureBashrc(); resolveAndStart(); }
    public void sendInput(String r) { sendRaw(r); }
    public void clearScreen()       { sendRaw("clear\n"); }
    public boolean isUsingTermux()  { return TERMUX_BASH.equals(shellPath); }
    public boolean isBootstrapped() { return bootstrap.isInstalled(); }
    public void setCtrl(boolean v)  { ctrlPressed = v; }
    public void setAlt(boolean v)   { altPressed  = v; }
    public boolean isCtrl()         { return ctrlPressed; }
    public boolean isCtrlPressed()  { return ctrlPressed; }

    public void executeCommand(String command, OutputCallback resultCb) {
        if (process == null || !process.isAlive()) { resolveAndStart(); return; }
        sendRaw(command + "\n");
    }

    public void reinstall(OutputCallback progressCb) {
        File bb = bootstrap.getDownloadedBusybox();
        if (bb.exists()) bb.delete();
        mainHandler.post(() -> callback.onOutput("\n[Re-downloading BusyBox...]\n"));
        executor.execute(() -> bootstrap.install(new TerminalBootstrap.ProgressCallback() {
            @Override public void onProgress(String msg) { mainHandler.post(() -> callback.onOutput(msg + "\n")); }
            @Override public void onDone(boolean ok, String path) {
                mainHandler.post(() -> {
                    if (ok) { callback.onOutput("[Done ✓ — restarting]\n\n"); resolveAndStart(); }
                    else    { callback.onOutput("[Failed — check internet]\n$ "); }
                });
            }
        }));
    }

    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (process != null) process.destroy();
        executor.shutdownNow();
    }

    private synchronized void sendRaw(String text) {
        if (stdin == null || process == null || !process.isAlive()) { Log.w(TAG, "sendRaw skipped"); return; }
        try { stdin.write(text.getBytes("UTF-8")); stdin.flush(); }
        catch (IOException e) { Log.e(TAG, "stdin: " + e.getMessage()); }
    }
}