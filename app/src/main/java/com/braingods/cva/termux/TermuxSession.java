package com.braingods.cva.termux;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TermuxSession
 * ─────────────────────────────────────────────────────────────────────────────
 * Manages a single interactive shell process, wired to the CVA terminal view.
 * Mirrors TermuxSession from the real Termux app but works with our
 * ProcessBuilder-based approach (no native PTY required).
 *
 * USAGE in TerminalManager:
 *
 *   TermuxSession session = new TermuxSession(context,
 *       text -> runOnUiThread(() -> termView.append(text)));
 *   session.start();
 *   // later:
 *   session.sendInput("ls -la\n");
 *   session.finish();
 *
 * RELATIONSHIP TO EXISTING CODE:
 *   This class is designed to be used INSIDE your existing TerminalManager.java
 *   as a drop-in replacement for the raw Process management.  Instead of:
 *       ProcessBuilder pb = new ProcessBuilder(shellPath);
 *   Use:
 *       TermuxSession session = new TermuxSession(ctx, outputCb);
 *       session.start();
 *   Everything else (sendInput, restart, destroy) stays the same.
 */
public class TermuxSession {

    private static final String TAG = "TermuxSession";

    /** Callback for terminal output — called on the main thread. */
    public interface OutputCallback {
        void onOutput(String text);
    }

    /** Callback for session lifecycle events. */
    public interface SessionCallback {
        void onSessionStarted();
        void onSessionFinished(int exitCode);
    }

    private final Context         ctx;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor    = Executors.newCachedThreadPool();

    /** Primary output callback — set by Activity/Fragment. Hot-swappable. */
    private volatile OutputCallback outputCallback;

    /** Agent callback — taps output without replacing the primary callback. */
    private volatile OutputCallback agentCallback;

    /** Lifecycle events. */
    private volatile SessionCallback sessionCallback;

    private Process        process;
    private OutputStream   stdin;
    private BufferedReader stdoutReader;

    private final AtomicBoolean alive = new AtomicBoolean(false);

    // Session metadata
    private String shellPath;
    private String sessionId;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TermuxSession(Context ctx, OutputCallback outputCallback) {
        this.ctx            = ctx.getApplicationContext();
        this.outputCallback = outputCallback;
        this.sessionId      = "cva-session-" + System.currentTimeMillis();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Start the shell session.  Determines the best available shell automatically.
     * Safe to call from any thread.
     */
    public void start() {
        executor.execute(this::startInternal);
    }

    /**
     * Restart the session (kills current shell and starts a new one).
     */
    public void restart() {
        killProcess();
        start();
    }

    /**
     * Kill the shell process.
     */
    public void finish() {
        killProcess();
        alive.set(false);
    }

    /**
     * Destroy the session completely (also shuts down the executor).
     * Call this in onDestroy() of your Activity/Service.
     */
    public void destroy() {
        killProcess();
        executor.shutdownNow();
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    /**
     * Send text to the shell's stdin.
     * Use "\n" to execute a command: sendInput("ls -la\n")
     */
    public synchronized void sendInput(String text) {
        if (stdin == null || !alive.get()) {
            Log.w(TAG, "sendInput skipped — session not alive");
            return;
        }
        try {
            stdin.write(text.getBytes("UTF-8"));
            stdin.flush();
        } catch (IOException e) {
            Log.e(TAG, "sendInput failed: " + e.getMessage());
        }
    }

    /**
     * Send a Ctrl+C interrupt signal.
     */
    public void sendCtrlC() {
        sendInput("\u0003");
    }

    /**
     * Send a Ctrl+D EOF.
     */
    public void sendCtrlD() {
        sendInput("\u0004");
    }

    // ── Callback management ───────────────────────────────────────────────────

    /** Hot-swap the output callback (thread-safe). */
    public void setOutputCallback(OutputCallback cb) {
        this.outputCallback = cb;
    }

    /** Tap output without replacing the primary callback. */
    public void setAgentCallback(OutputCallback cb) {
        this.agentCallback = cb;
    }

    public void setSessionCallback(SessionCallback cb) {
        this.sessionCallback = cb;
    }

    // ── Status ────────────────────────────────────────────────────────────────

    public boolean isAlive() {
        return alive.get() && process != null && process.isAlive();
    }

    public String getShellPath() { return shellPath; }
    public String getSessionId() { return sessionId; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void startInternal() {
        try {
            // Pick the best shell and build environment
            String[] cmd = TermuxEnvironment.buildShellCommand(ctx);
            this.shellPath = cmd[0];

            Map<String, String> env = TermuxEnvironment.buildEnvironment(ctx);

            // Working directory
            String workDir = env.containsKey("HOME")
                    ? env.get("HOME") : ctx.getFilesDir().getAbsolutePath();
            new java.io.File(workDir).mkdirs();

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().clear();
            pb.environment().putAll(env);
            pb.directory(new java.io.File(workDir));
            pb.redirectErrorStream(true);

            process      = pb.start();
            stdin        = process.getOutputStream();
            stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            alive.set(true);

            emit("[Shell: " + shellPath + "]\n");

            SessionCallback sc = sessionCallback;
            if (sc != null) mainHandler.post(sc::onSessionStarted);

            // Apply CVA prompt after a brief startup delay
            mainHandler.postDelayed(this::applyPrompt, 600);

            // Read output loop
            readOutputLoop();

        } catch (IOException e) {
            Log.e(TAG, "startInternal failed", e);
            emit("[ERROR starting shell: " + e.getMessage() + "]\n");
            alive.set(false);
        }
    }

    private void readOutputLoop() {
        try {
            // Quick liveness check — give shell 400ms to start
            Thread.sleep(400);
            if (!process.isAlive()) {
                int exit = process.exitValue();
                emit("\n[Shell exited immediately (code " + exit + ")]\n");
                emit("[Hint: if bootstrap is missing, tap ⚕ Doctor to install it]\n$ ");
                alive.set(false);
                SessionCallback sc = sessionCallback;
                if (sc != null) mainHandler.post(() -> sc.onSessionFinished(exit));
                return;
            }

            // Stream output
            char[] buf = new char[4096]; int n;
            while ((n = stdoutReader.read(buf, 0, buf.length)) != -1) {
                final String chunk = new String(buf, 0, n);
                mainHandler.post(() -> emit(chunk));
                // Agent tap
                OutputCallback agent = agentCallback;
                if (agent != null) agent.onOutput(chunk);
            }
        } catch (IOException e) {
            emit("\n[Shell exited — tap ↺ to restart]\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            alive.set(false);
            int exitCode = -1;
            try { if (process != null) exitCode = process.waitFor(); }
            catch (Exception ignored) {}
            final int code = exitCode;
            SessionCallback sc = sessionCallback;
            if (sc != null) mainHandler.post(() -> sc.onSessionFinished(code));
        }
    }

    private void applyPrompt() {
        if (!isAlive()) return;
        // Source .bashrc so the CVA PS1 gets applied
        java.io.File bashrc = new java.io.File(TermuxConstants.HOME_PATH, ".bashrc");
        sendInput("export TERM=xterm-256color\n");
        if (bashrc.exists()) {
            sendInput(". " + bashrc.getAbsolutePath() + " 2>/dev/null\n");
        }
    }

    private void emit(String text) {
        OutputCallback cb = outputCallback;
        if (cb != null) cb.onOutput(text);
    }

    private void killProcess() {
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
            process = null;
            stdin   = null;
        }
        alive.set(false);
    }
}