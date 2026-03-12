package com.braingods.cva;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.braingods.cva.termux.TermuxConstants;
import com.braingods.cva.termux.TermuxEnvironment;
import com.braingods.cva.termux.TermuxShellUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TerminalManager  [v2 — fixed libbusybox.so + HOME path]
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * BUG 1 — libbusybox.so is a SHARED LIBRARY, not a standalone executable.
 *   The old code called:  libbusybox.so --list
 *   A .so file reports only 1 "applet" (its own exported symbol table) and
 *   cannot be exec()'d directly as a shell — that's why you saw:
 *     "--list → 1 applet"  then  "All shell probes failed"
 *
 *   FIX: hasNativeBusybox() now checks the file is > 500 KB AND can actually
 *   run "echo ok" before we trust it.  If the .so fails the exec test, we
 *   skip straight to the Termux bootstrap download path instead of wasting
 *   time on endless probe loops.
 *
 *   The correct way to invoke a JNI .so as a busybox is via the system linker:
 *     /system/bin/linker64  libbusybox.so  ash  -c  "echo ok"
 *   This is now tried first before giving up on the native library.
 *
 * BUG 2 — HOME path inconsistency: /data/user/0/… vs /data/data/…
 *   ctx.getFilesDir().getAbsolutePath() returns /data/user/0/com.braingods.cva/files
 *   on modern Android (API 24+), which is a BIND MOUNT of /data/data/…
 *   Inside a ProcessBuilder child process the bind mount resolves differently,
 *   so .bashrc written to /data/data/… is not found when HOME=/data/user/0/…
 *
 *   FIX: canonicalHome() always returns /data/data/<pkg>/files (the real path),
 *   matching TermuxConstants.HOME_PATH and TermuxConstants.FILES_PATH.
 *   Every place that used ctx.getFilesDir().getAbsolutePath() now calls
 *   canonicalHome() instead.
 *
 * SHELL PRIORITY (unchanged):
 *   1. CVA Termux bootstrap  — $PREFIX/bin/bash  (full Linux tools)
 *   2. Real Termux app       — /data/data/com.termux/files/usr/bin/bash
 *   3. jniLibs libbusybox.so — via linker64 (if executable)
 *   4. Downloaded busybox    — code_cache/ (W^X-safe)
 *   5. /system/bin/sh        — always works, limited features
 */
public class TerminalManager {

    /**
     * Callback for terminal output.
     *
     * onOutput() receives a CharSequence that is already ANSI-processed:
     *   - Shell output chunks: SpannableStringBuilder with color/bold spans applied
     *   - Status messages from TerminalManager: plain String
     *
     * Both are CharSequence. Your consumer just calls:
     *
     *   manager.setActivityCallback(text -> {
     *       tvTerminal.append(text);  // DO NOT call text.toString() — loses colors
     *       svTerminal.post(() -> svTerminal.fullScroll(ScrollView.FOCUS_DOWN));
     *   });
     *
     * If you have existing code that does String s = (String) text — change it to
     * CharSequence s = text  or  String s = text.toString()  (plain text, no colors).
     */
    public interface OutputCallback {
        void onOutput(CharSequence text);
    }
    public interface DoctorCallback { void onShowDoctor(String mode); }

    private static final String TAG = "TerminalManager";

    private static final String[] BUSYBOX_SHELLS = { "ash", "hush", "bash", "sh", "dash", "mksh", "ksh" };

    private static final String CVA_PS1 =
            "export PS1='" +
                    "\\[\\e[1;94m\\]┌─×××××" +
                    "[\\[\\e[1;97m\\]\\T\\[\\e[1;94m\\]]" +
                    "\\[\\e[1;95m\\]§§§§§§" +
                    "\\[\\e[1;94m\\][\\[\\e[1;92m\\]" +
                    "{\\[\\e[1;97m\\]\uD835\uDC07\uD835\uDC09\u265E\uD835\uDC0E\uD835\uDC18" +
                    "\\[\\e[1;92m\\]✓\\[\\e[1;94m\\]}]" +
                    "\\[\\e[0;94m\\]:::::::[\\[\\e[1;96m\\]\\#\\[\\e[1;92m\\]-wings\\[\\e[1;94m\\]]" +
                    "\\n\\[\\e[1;94m\\]│" +
                    "\\n\\[\\e[0;94m\\]└==\\[\\e[0;94m\\][\\[\\e[0;95m\\]\\W\\[\\e[0;94m\\]]" +
                    "\\[\\e[1;93m\\]-species" +
                    "\\[\\e[1;91m\\]§§\\[\\e[1;91m\\][{\\[\\e[1;92m\\]✓\\[\\e[1;91m\\]}]" +
                    "\\n\\[\\e[1;94m\\]│" +
                    "\\n\\[\\e[0;94m\\]└==\\[\\e[0;94m\\][\\[\\e[1;93m\\]≈≈≈≈≈" +
                    "\\[\\e[0;94m\\]]™[✓]=\\[\\e[1;91m\\]►\\[\\e[0m\\] " +
                    "'\n";

    private Process        process;
    private OutputStream   stdin;
    private BufferedReader stdoutReader;

    private volatile OutputCallback outputCallback;
    // Persistent ANSI render state — color attributes carry across chunk boundaries
    private final AnsiProcessor.AnsiState ansiState = new AnsiProcessor.AnsiState();
    private volatile OutputCallback agentOutputCallback = null;
    private volatile DoctorCallback doctorCallback      = null;

    private final ExecutorService executor    = Executors.newCachedThreadPool();
    private final Context         ctx;
    private final TerminalBootstrap bootstrap;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private boolean ctrlPressed = false;
    private boolean altPressed  = false;
    private String  shellPath   = null;
    private String  shellApplet = null;
    private boolean useLinker64 = false;

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setActivityCallback(OutputCallback cb) { this.outputCallback = cb; }
    public void setAgentOutputCallback(OutputCallback cb) { this.agentOutputCallback = cb; }
    public void setDoctorCallback(DoctorCallback cb)   { this.doctorCallback = cb; }

    // ── Constructor ───────────────────────────────────────────────────────────

    public TerminalManager(Context ctx, OutputCallback callback) {
        this.ctx            = ctx;
        this.outputCallback = callback;
        this.bootstrap      = new TerminalBootstrap(ctx);
        initShell();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BUG 2 FIX: canonical HOME path
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the canonical /data/data/<pkg>/files path.
     *
     * ctx.getFilesDir() returns /data/user/0/<pkg>/files on API 24+, which is
     * a bind mount. Inside a ProcessBuilder subprocess the kernel resolves the
     * path differently, causing HOME mismatch with TermuxConstants paths.
     *
     * We build the path manually from the package name to guarantee it always
     * matches /data/data/<pkg>/files — the same root TermuxConstants uses.
     */
    private String canonicalHome() {
        // TermuxConstants.FILES_PATH = /data/data/<pkg>/files
        // Use that as the authoritative root, then append /home if bootstrap
        // is installed (matching TermuxConstants.HOME_PATH).
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            return TermuxConstants.HOME_PATH;  // /data/data/<pkg>/files/home
        }
        if (TermuxShellUtils.isTermuxInstalled()) {
            return TermuxConstants.TERMUX_HOME;
        }
        // Fallback: build from package name, not from ctx.getFilesDir()
        return "/data/data/" + ctx.getPackageName() + "/files";
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    private void initShell() {
        // FAST PATH 1: CVA Termux bootstrap already installed
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            shellPath   = TermuxConstants.BASH_PATH;
            shellApplet = null;
            useLinker64 = false;
            mainHandler.post(() -> emit("[CVA Bootstrap ✓  bash ready]\n"));
            mainHandler.post(this::startShell);
            return;
        }

        // FAST PATH 2: Real Termux installed on device
        if (TermuxShellUtils.isTermuxInstalled()) {
            shellPath   = TermuxConstants.TERMUX_BASH;
            shellApplet = null;
            useLinker64 = false;
            mainHandler.post(() -> emit("[Termux detected ✓]\n"));
            mainHandler.post(this::startShell);
            return;
        }

        // FAST PATH 3: jniLibs BusyBox — only if it can actually exec as a shell
        // hasNativeBusyboxShell() runs a real test ("echo ok"), not just a file-size check
        if (bootstrap.hasNativeBusyboxShell()) {
            mainHandler.post(() -> emit("[BusyBox (jniLibs) ✓ — probing shell…]\n"));
            resolveAndStart();
            return;
        }

        // .so file exists but is a shared library — cannot exec directly
        if (bootstrap.hasNativeBusybox()) {
            mainHandler.post(() -> emit(
                    "[libbusybox.so is a shared library — cannot exec directly]\n" +
                            "[Installing Termux bootstrap instead…]\n\n"));
        }

        // INSTALL: nothing available
        runBootstrapInstall();
    }

    private void runBootstrapInstall() {
        mainHandler.post(() -> emit(
                "\n╔══════════════════════════════════════════════╗\n" +
                        "║  CVA Terminal — First Run Setup              ║\n" +
                        "║  Installing Termux-compatible environment…   ║\n" +
                        "║  (bash · python3 · git · vim · curl · tmux) ║\n" +
                        "╚══════════════════════════════════════════════╝\n\n"
        ));

        executor.execute(() -> bootstrap.install(new TerminalBootstrap.ProgressCallback() {
            @Override public void onProgress(String msg) {
                mainHandler.post(() -> emit(msg.endsWith("\n") ? msg : msg + "\n"));
            }
            @Override public void onDone(boolean ok, String path) {
                mainHandler.post(() -> {
                    if (ok) {
                        emit("\n  ✓  Shell ready — starting…\n\n");
                        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
                            shellPath   = TermuxConstants.BASH_PATH;
                            shellApplet = null;
                            useLinker64 = false;
                        } else if (path != null) {
                            shellPath   = path;
                            shellApplet = null;
                            useLinker64 = false;
                        }
                        startShell();
                    } else {
                        emit("\n  ✗  Install failed — launching TmuxDoctor…\n");
                        launchDoctor(TmuxDoctorActivity.LAUNCH_MODE_AUTO);
                        startSystemSh();
                    }
                });
            }
        }));
    }

    // ── Emit ──────────────────────────────────────────────────────────────────

    private void emit(String text) {
        if (text == null || text.isEmpty()) return;
        OutputCallback cb = outputCallback;
        if (cb == null) return;
        // If the text contains any ESC characters, run it through AnsiProcessor
        // to convert escape sequences into color/bold/underline spans.
        // Plain status messages (no ESC) are forwarded as-is (String is CharSequence).
        if (text.indexOf('\u001B') >= 0) {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            AnsiProcessor.appendTo(ssb, text, ansiState);
            cb.onOutput(ssb);
        } else {
            cb.onOutput(text);
        }
    }

    // ── Resolution (BusyBox fallback path) ───────────────────────────────────

    private void resolveAndStart() {
        executor.execute(this::resolveAndStartBg);
    }

    private void resolveAndStartBg() {
        // Re-check bootstrap (may have been installed since initShell)
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            shellPath = TermuxConstants.BASH_PATH; shellApplet = null; useLinker64 = false;
            mainHandler.post(() -> emit("[CVA Bootstrap ✓]\n"));
            mainHandler.post(this::startShell);
            return;
        }
        if (TermuxShellUtils.isTermuxInstalled()) {
            shellPath = TermuxConstants.TERMUX_BASH; shellApplet = null; useLinker64 = false;
            mainHandler.post(() -> emit("[Termux ✓]\n"));
            mainHandler.post(this::startShell);
            return;
        }

        if (bootstrap.isInstalled()) {
            File    bb      = bootstrap.getBusyboxFile();
            long    sizeKb  = bb != null ? bb.length() / 1024 : 0;
            boolean native_ = bootstrap.isDirectlyExecutable();
            String  bbPath  = bb != null ? bb.getAbsolutePath() : "";

            mainHandler.post(() -> {
                emit("\n┌─────────────────────────────────────────┐\n");
                emit("│  🔍  Checking shell environment…        │\n");
                emit("│  Size : " + sizeKb + " KB  |  "
                        + (native_ ? "direct (jniLibs)" : "auto-detect") + "\n");
                emit("└─────────────────────────────────────────┘\n");
            });

            // BUG 1 FIX: prefer linker for .so files (PIE/ET_DYN)
            boolean preferLinker = !native_ || isPIE(bbPath);
            ProbeResult result = probePath(bbPath, preferLinker);

            if (result != null) {
                shellPath   = bbPath;
                shellApplet = result.applet;
                useLinker64 = result.usedLinker64;
                mainHandler.post(() -> emit("  ✓  Shell ready ["
                        + (result.applet != null ? result.applet : "direct")
                        + (result.usedLinker64 ? "  linker64" : "  direct") + "]\n\n"));
                mainHandler.post(this::startShell);
                return;
            }

            // All probes failed — try downloading a standalone busybox binary
            mainHandler.post(() -> emit("\n⚠  BusyBox probe failed — downloading standalone busybox…\n\n"));

            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            final boolean[] dlOk   = {false};
            final String[]  dlPath = {null};

            bootstrap.forceDownload(new TerminalBootstrap.ProgressCallback() {
                @Override public void onProgress(String msg) {
                    mainHandler.post(() -> emit(msg.endsWith("\n") ? msg : msg + "\n"));
                }
                @Override public void onDone(boolean ok, String path) {
                    dlOk[0]   = ok;
                    dlPath[0] = path;
                    latch.countDown();
                }
            });

            try { latch.await(90, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            if (dlOk[0] && dlPath[0] != null) {
                String dlBin = dlPath[0];
                boolean pie  = isPIE(dlBin);
                ProbeResult r2 = probePath(dlBin, pie);
                if (r2 != null) {
                    shellPath   = dlBin;
                    shellApplet = r2.applet;
                    useLinker64 = r2.usedLinker64;
                    mainHandler.post(this::startShell);
                } else {
                    mainHandler.post(() -> {
                        emit("  ✗  Downloaded binary also failing — using /system/bin/sh\n");
                        launchDoctor(TmuxDoctorActivity.LAUNCH_MODE_AUTO);
                        startSystemSh();
                    });
                }
            } else {
                mainHandler.post(() -> {
                    emit("  ✗  Download failed — using /system/bin/sh\n");
                    launchDoctor(TmuxDoctorActivity.LAUNCH_MODE_AUTO);
                    startSystemSh();
                });
            }
            return;
        }

        mainHandler.post(this::startSystemSh);
    }

    // ── ELF probe ─────────────────────────────────────────────────────────────

    private static class ProbeResult {
        final String  applet;
        final boolean usedLinker64;
        ProbeResult(String applet, boolean usedLinker64) {
            this.applet       = applet;
            this.usedLinker64 = usedLinker64;
        }
    }

    private boolean isPIE(String path) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
            byte[] h = new byte[18];
            if (fis.read(h) < 18) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            int eType = (h[16] & 0xFF) | ((h[17] & 0xFF) << 8);
            return eType == 3; // ET_DYN = shared object / PIE
        } catch (Exception e) { return false; }
    }

    private ProbeResult probePath(String binaryPath, boolean preferLinker64) {
        boolean useLinker = preferLinker64 && isPIE(binaryPath);
        if (preferLinker64 && !useLinker)
            mainHandler.post(() -> emit("  ET_EXEC detected — skipping linker64\n"));

        mainHandler.post(() -> emit("  Probing: " + binaryPath + "\n"));
        String linker = bootstrap.getLinker();

        try {
            String[] listCmd = useLinker
                    ? new String[]{ linker, binaryPath, "--list" }
                    : new String[]{ binaryPath, "--list" };
            Process p = new ProcessBuilder(listCmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            java.util.List<String> applets = new java.util.ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim().toLowerCase();
                if (!t.startsWith("error:") && !t.isEmpty()) applets.add(t);
            }
            p.waitFor();
            int count = applets.size();
            mainHandler.post(() -> emit("  --list → " + count + " applet" + (count == 1 ? "" : "s") + "\n"));

            // BUG 1 FIX: "1 applet" means it's a .so file reporting its own symbol,
            // not a real busybox binary — skip applet matching in this case
            if (count > 3) {
                for (String shell : BUSYBOX_SHELLS) {
                    if (applets.contains(shell)) {
                        boolean ok = testShell(binaryPath, useLinker, shell);
                        if (ok) return new ProbeResult(shell, useLinker);
                    }
                }
            }
        } catch (Exception e) {
            mainHandler.post(() -> emit("  --list failed\n"));
        }

        // Blind probe — try each shell applet without --list
        for (String shell : BUSYBOX_SHELLS) {
            if (testShell(binaryPath, useLinker, shell))
                return new ProbeResult(shell, useLinker);
        }
        // Also try with linker if we haven't yet
        if (!useLinker && isPIE(binaryPath)) {
            for (String shell : BUSYBOX_SHELLS) {
                if (testShell(binaryPath, true, shell))
                    return new ProbeResult(shell, true);
            }
        }
        if (testShellDirect(binaryPath, useLinker)) return new ProbeResult(null, useLinker);
        return null;
    }

    private boolean testShell(String binaryPath, boolean useLinker64, String applet) {
        try {
            String[] cmd = useLinker64
                    ? new String[]{ bootstrap.getLinker(), binaryPath, applet, "-c", "echo ok" }
                    : new String[]{ binaryPath, applet, "-c", "echo ok" };
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String out = r.readLine();
            p.waitFor();
            return p.exitValue() == 0 && "ok".equals(out != null ? out.trim() : "");
        } catch (Exception e) { return false; }
    }

    private boolean testShellDirect(String binaryPath, boolean useLinker64) {
        try {
            String[] cmd = useLinker64
                    ? new String[]{ bootstrap.getLinker(), binaryPath, "-c", "echo ok" }
                    : new String[]{ binaryPath, "-c", "echo ok" };
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String out = r.readLine();
            p.waitFor();
            return "ok".equals(out != null ? out.trim() : "");
        } catch (Exception e) { return false; }
    }

    private void startSystemSh() {
        shellPath = "/system/bin/sh"; shellApplet = null; useLinker64 = false;
        emit("[Using /system/bin/sh (limited)]\n\n");
        startShell();
    }

    // ── Shell start ───────────────────────────────────────────────────────────

    private void startShell() {
        try {
            if (process != null) {
                try { process.destroy(); } catch (Exception ignored) {}
                process = null; stdin = null;
            }

            ProcessBuilder pb;
            String homeDir;

            // ── CVA Termux bootstrap ──────────────────────────────────────────
            if (TermuxConstants.BASH_PATH.equals(shellPath)) {
                // FIX: --login causes bash to read $PREFIX/etc/profile which in the
                // Termux bootstrap contains a hardcoded reference to /data/data/com.termux/...
                // (the real Termux prefix) — this triggers "Permission denied" on first read.
                // Use --noprofile --norc -i instead; our postDelayed sendRaw block already
                // sources .bashrc and sets PS1 manually so nothing is lost.
                pb = new ProcessBuilder(TermuxConstants.BASH_PATH, "--noprofile", "--norc", "-i");
                Map<String, String> env = TermuxEnvironment.buildEnvironment(ctx);
                pb.environment().clear();
                pb.environment().putAll(env);
                homeDir = TermuxConstants.HOME_PATH;
                new File(homeDir).mkdirs();
                pb.directory(new File(homeDir));

                // ── Real Termux ───────────────────────────────────────────────────
            } else if (TermuxConstants.TERMUX_BASH.equals(shellPath)) {
                pb = new ProcessBuilder(TermuxConstants.TERMUX_BASH, "--noprofile", "--norc", "-i");
                Map<String, String> env = TermuxEnvironment.buildEnvironment(ctx);
                pb.environment().clear();
                pb.environment().putAll(env);
                homeDir = TermuxConstants.TERMUX_HOME;
                pb.directory(new File(homeDir));

                // ── System sh fallback ────────────────────────────────────────────
            } else if ("/system/bin/sh".equals(shellPath)) {
                pb = new ProcessBuilder("/system/bin/sh");
                // BUG 2 FIX: use canonical /data/data/... path, not /data/user/0/...
                homeDir = canonicalHome();
                new File(homeDir).mkdirs();
                pb.environment().put("PATH",   "/system/bin:/system/xbin");
                pb.environment().put("TERM",   "xterm-256color");
                pb.environment().put("HOME",   homeDir);
                pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
                pb.directory(new File(homeDir));

                // ── BusyBox (applet or direct) ────────────────────────────────────
            } else {
                String applet = shellApplet != null ? shellApplet : "ash";
                String[] cmd  = bootstrap.getShellCommand(applet);
                pb = new ProcessBuilder(cmd);
                // BUG 2 FIX: use canonical /data/data/... path
                homeDir = canonicalHome();
                new File(homeDir).mkdirs();
                pb.environment().put("PATH",
                        ctx.getApplicationInfo().nativeLibraryDir
                                + ":" + homeDir + ":/system/bin:/system/xbin");
                pb.environment().put("HOME",    homeDir);
                pb.environment().put("TMPDIR",  ctx.getCacheDir().getAbsolutePath());
                pb.environment().put("TERM",    "xterm-256color");
                pb.environment().put("SHELL",   shellPath);
                pb.environment().put("BUSYBOX", shellPath);
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
                        mainHandler.post(() -> emit(
                                "\n[Shell exited " + exit
                                        + (useLinker64 ? " — linker64 may not work on this device" : "")
                                        + "]\n$ "));
                        if (useLinker64) {
                            mainHandler.post(() -> {
                                emit("[Falling back to /system/bin/sh]\n\n");
                                startSystemSh();
                            });
                        }
                        return;
                    }
                    char[] buf = new char[1024]; int n;
                    while ((n = stdoutReader.read(buf, 0, buf.length)) != -1) {
                        final String chunk = new String(buf, 0, n);
                        mainHandler.post(() -> emit(chunk));
                        OutputCallback agentCb = agentOutputCallback;
                        if (agentCb != null) agentCb.onOutput(chunk);
                    }
                } catch (IOException e) {
                    mainHandler.post(() -> emit("\n[Shell exited — tap ↺ Reset]\n"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Print banner
            mainHandler.postDelayed(() -> {
                if (process != null && process.isAlive()) {
                    sendRaw("printf '\\033[1;96m\\n'\n");
                    sendRaw("printf '\\033[1;91m========================================================\\n'\n");
                    sendRaw("printf '\\033[1;96m ██████╗\\033[1;91m           _____   __          .____  \\n'\n");
                    sendRaw("printf '\\033[1;96m██╔════╝\\033[1;91m   ____   /  _  /  | __._.| \\033[1;92mc\\033[1;91m  |  \\n'\n");
                    sendRaw("printf '\\033[1;96m██║     \\033[1;91m  /  _ \\\\ /  /_\\\\  \\\\   __|   |  || \\033[1;92mv\\033[1;91m  |   \\n'\n");
                    sendRaw("printf '\\033[1;96m██║     \\033[1;91m ( \\033[1;92m (☣)\\033[1;91m )    |    \\\\  |  \\\\___  || \\033[1;92ma\\033[1;91m  |___ \\n'\n");
                    sendRaw("printf '\\033[1;96m╚██████╗\\033[1;91m  /\\\\|__  /|  / ____||  ______\\\\ \\n'\n");
                    sendRaw("printf '\\033[1;96m ╚═════╝\\033[1;91m                /      /      /\\033[1;92m powered by\\n'\n");
                    sendRaw("printf '\\033[1;96m                                                     CVAKI\\n'\n");
                    sendRaw("printf '\\033[1;95m§§§§§§§§§§§§§§§§§§§§§§§_{GODKILLER}_§§§§§§§§§§§§§§§§§§§§§§§\\n'\n");
                    sendRaw("printf '\\033[0m\\n'\n");
                }
            }, 400);

            // Source .bashrc — it already sets PS1, aliases, and the banner.
            // Do NOT re-send PS1 or aliases after sourcing: bash is interactive (-i)
            // without a PTY, so it prints PS1 before every stdin line — each redundant
            // sendRaw produces a "└==[≈≈≈≈≈]► alias ll=..." noise line in the output.
            // Only apply PS1/aliases directly when .bashrc is absent (fresh install).
            mainHandler.postDelayed(() -> {
                if (process != null && process.isAlive()) {
                    // BUG 2 FIX: always use canonicalHome() for .bashrc lookup
                    String rcHome;
                    if (TermuxConstants.BASH_PATH.equals(shellPath)) {
                        rcHome = TermuxConstants.HOME_PATH;
                    } else if (TermuxConstants.TERMUX_BASH.equals(shellPath)) {
                        rcHome = TermuxConstants.TERMUX_HOME;
                    } else {
                        rcHome = canonicalHome();
                    }

                    File bashrc = new File(rcHome, ".bashrc");
                    sendRaw("export TERM=xterm-256color\n");
                    sendRaw("export HOME=" + rcHome + "\n");
                    if (bashrc.exists()) {
                        // .bashrc sets PS1 + all aliases — source it and stop there.
                        sendRaw(". " + bashrc.getAbsolutePath() + " 2>/dev/null\n");
                    } else {
                        // No .bashrc yet (e.g. right after a clean bootstrap install
                        // before Doctor has run) — apply PS1 and basic aliases directly.
                        sendRaw(CVA_PS1);
                        sendRaw("alias ll='ls -alF'\n");
                        sendRaw("alias la='ls -A'\n");
                        sendRaw("alias l='ls -CF'\n");
                        sendRaw("alias cls='clear'\n");
                        sendRaw("alias grep='grep --color=auto'\n");
                    }
                }
            }, 900);

        } catch (IOException e) {
            Log.e(TAG, "startShell failed", e);
            emit("[ERROR: " + e.getMessage() + "]\n$ ");
        }
    }

    // ── TmuxDoctor ────────────────────────────────────────────────────────────

    public void launchDoctor(String mode) {
        DoctorCallback cb = doctorCallback;
        if (cb != null) {
            mainHandler.post(() -> cb.onShowDoctor(mode));
        } else {
            emit("[Doctor: panel not attached — wire setDoctorCallback()]\n");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void restart() {
        bootstrap.ensureBashrc();
        initShell();
    }

    public void sendInput(String r) { handleSendInput(r); }
    public void clearScreen()       { sendRaw("clear\n"); }

    public void reapplyPs1() {
        if (process != null && process.isAlive()) {
            sendRaw("export TERM=xterm-256color\n");
            sendRaw(CVA_PS1);
        }
    }

    public void openDoctor() { launchDoctor(TmuxDoctorActivity.LAUNCH_MODE_MANUAL); }

    public boolean isUsingTermux()  {
        return TermuxConstants.TERMUX_BASH.equals(shellPath)
                || TermuxConstants.BASH_PATH.equals(shellPath);
    }
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
        mainHandler.post(() -> emit("\n[Re-installing environment…]\n"));
        executor.execute(() -> bootstrap.install(new TerminalBootstrap.ProgressCallback() {
            @Override public void onProgress(String msg) {
                mainHandler.post(() -> emit(msg.endsWith("\n") ? msg : msg + "\n"));
            }
            @Override public void onDone(boolean ok, String path) {
                mainHandler.post(() -> {
                    if (ok) { emit("[Done ✓ — restarting]\n\n"); restart(); }
                    else    { emit("[Failed — launching TmuxDoctor…]\n");
                        launchDoctor(TmuxDoctorActivity.LAUNCH_MODE_AUTO); }
                });
            }
        }));
    }

    public void destroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (process != null) process.destroy();
        executor.shutdownNow();
    }

    // ── IME double-input prevention ───────────────────────────────────────────
    //
    // Android IME keyboards fire input in two ways simultaneously:
    //   1. Key-event path  : onKey() → sendInput("l"), sendInput("s")  [char by char]
    //   2. Commit path     : commitText("ls") + Enter → sendInput("ls\n")
    //
    // Both paths call sendInput(), so the shell sees: l + s + ls\n = "lsls\n".
    //
    // FIX: we accumulate individual single chars in charAccumulator.
    // When a multi-char string arrives (the commitText), we strip the prefix that
    // was already sent char-by-char.  If the entire word was already sent, we
    // discard the word and only forward the newline (so Enter still works).
    //
    // Control chars (< 0x20) always bypass the accumulator so Ctrl-C, Ctrl-D,
    // raw \n, etc. always reach the shell.

    private final StringBuilder charAccumulator  = new StringBuilder();
    private       long          charAccumulatorMs = 0L;
    private static final long   ACCUM_WINDOW_MS   = 600L;

    // Override the existing no-op stub so KeyboardService calls route here
    // (public sendInput is declared in the Public API section above — keep that
    //  declaration and delegate to this logic by replacing its body, OR just
    //  let this method BE sendInput — the old body was just sendRaw(r) anyway).
    //
    // NOTE: The old `public void sendInput(String r) { sendRaw(r); }` is replaced
    //       by this full implementation below.  The method signature is unchanged.
    private void handleSendInput(String text) {
        if (text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();

        // Expire accumulator if stale
        if (now - charAccumulatorMs > ACCUM_WINDOW_MS) charAccumulator.setLength(0);

        if (text.length() == 1) {
            char c = text.charAt(0);
            if (c < 0x20) {
                // Control character — always send, reset accumulator
                charAccumulator.setLength(0);
                sendRaw(text);
                return;
            }
            // Printable single char — track and send
            charAccumulator.append(c);
            charAccumulatorMs = now;
            sendRaw(text);

        } else {
            // Multi-char string — likely a commitText() call from the IME.
            // Split into the word part and any trailing newline/control chars.
            int nlIdx = text.indexOf('\n');
            String word = nlIdx >= 0 ? text.substring(0, nlIdx) : text;
            String tail = nlIdx >= 0 ? text.substring(nlIdx)    : "";

            String acc = charAccumulator.toString();
            charAccumulator.setLength(0);

            String toSend;
            if (!word.isEmpty() && acc.endsWith(word)) {
                // Every char of 'word' was already sent individually — drop word,
                // only forward the newline so Enter still executes the command.
                Log.d(TAG, "sendInput: dropped duplicate commit '" + word + "'");
                toSend = tail;
            } else if (!word.isEmpty() && !acc.isEmpty() && word.startsWith(acc)) {
                // Partial overlap — strip already-sent prefix
                Log.d(TAG, "sendInput: trimmed " + acc.length() + " already-sent chars");
                toSend = word.substring(acc.length()) + tail;
            } else {
                toSend = text;
            }

            if (!toSend.isEmpty()) sendRaw(toSend);
        }
    }

    private synchronized void sendRaw(String text) {
        if (stdin == null || process == null || !process.isAlive()) {
            Log.w(TAG, "sendRaw skipped — shell not alive");
            return;
        }
        try {
            stdin.write(text.getBytes("UTF-8"));
            stdin.flush();
        } catch (IOException e) {
            Log.e(TAG, "stdin write: " + e.getMessage());
        }
    }
}