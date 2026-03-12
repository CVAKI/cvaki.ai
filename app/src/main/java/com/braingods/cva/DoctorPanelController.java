package com.braingods.cva;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.braingods.cva.termux.TermuxConstants;
import com.braingods.cva.termux.TermuxInstaller;
import com.braingods.cva.termux.TermuxShellUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * DoctorPanelController  [v4.1 — fixed stateful shell + HOME/PREFIX consistency]
 *
 * KEY FIXES vs v4.0:
 *   1. shell() now passes a fully-resolved environment with PREFIX/HOME/PATH
 *      pre-expanded as literal strings — no more "$PREFIX" expanding to "" or "/".
 *
 *   2. bestHome() is consistent: always /data/data/com.braingods.cva/files/home
 *      when our bootstrap is installed. Previously the AI was overriding it with
 *      /data/user/0/... which is a different symlink path inside ProcessBuilder.
 *
 *   3. AI doctor prompt is now Android-aware: tells the model that each <cmd>
 *      runs in an isolated subprocess (no state), so commands must be self-contained.
 *      Dangerous/impossible commands (mkdir /lib, cp from /system/bin/vi, etc.)
 *      are filtered before execution.
 *
 *   4. Retry logic: on retries, only re-runs steps that WARN or FAIL — skips
 *      already-OK steps to avoid spamming identical output.
 *
 *   5. executeAiCommands() uses a single long-lived Process for the whole
 *      AI-generated script, so exports actually persist between commands.
 */
public class DoctorPanelController {

    private static final String TAG = "DoctorPanel";

    // ── Panel colors ──────────────────────────────────────────────────────────
    private static final int COL_PRIMARY = Color.parseColor("#00FF6A");
    private static final int COL_CYAN    = Color.parseColor("#00E5FF");
    private static final int COL_MAGENTA = Color.parseColor("#FF2EFF");
    private static final int COL_GOLD    = Color.parseColor("#FFD600");
    private static final int COL_RED     = Color.parseColor("#FF3B3B");
    private static final int COL_BLUE    = Color.parseColor("#2979FF");
    private static final int COL_DIM     = Color.parseColor("#2A4030");
    private static final int COL_MID     = Color.parseColor("#446650");
    private static final int COL_TEXT    = Color.parseColor("#C8F0D5");

    private static final int STC_PENDING = Color.parseColor("#1A2E20");
    private static final int STC_RUNNING = Color.parseColor("#003320");
    private static final int STC_OK      = Color.parseColor("#003A15");
    private static final int STC_WARN    = Color.parseColor("#2A1E00");
    private static final int STC_FAIL    = Color.parseColor("#2A0808");
    private static final int STC_SKIP    = Color.parseColor("#141414");

    // ── ANSI codes (for terminal mirror) ─────────────────────────────────────
    private static final String RST   = "\033[0m";
    private static final String GRN   = "\033[1;92m";
    private static final String CYN   = "\033[1;96m";
    private static final String YEL   = "\033[1;93m";
    private static final String RED   = "\033[1;91m";
    private static final String MAG   = "\033[1;95m";
    private static final String BLU   = "\033[1;94m";
    private static final String DIM   = "\033[2;37m";
    private static final String WHT   = "\033[0;97m";
    private static final String BLD   = "\033[1m";

    // ── Step model ────────────────────────────────────────────────────────────
    private enum StepState { PENDING, RUNNING, OK, WARN, FAIL, SKIP }

    private static class Step {
        final String tag, icon, label;
        StepState state = StepState.PENDING;
        TextView  chip;
        Step(String t, String i, String l) { tag=t; icon=i; label=l; }
    }

    private final Step[] STEPS = {
            new Step("ENV",       "⬡", "Environment"),
            new Step("BOOTSTRAP", "◈", "Bootstrap"),
            new Step("BASH",      "❯", "Bashrc"),
            new Step("TMUX",      "▣", "Tmux.conf"),
            new Step("TPM",       "⊞", "Plugins"),
            new Step("PKG",       "⬡", "Packages"),
            new Step("PATH",      "≫", "Path/Env"),
            new Step("TEST",      "◉", "ANSI test"),
    };

    // ── View refs ─────────────────────────────────────────────────────────────
    private final View         panel;
    private final LinearLayout chipsRow;
    private final ProgressBar  progressBar;
    private final ScrollView   svLog;
    private final TextView     tvLog;
    private final TextView     tvStatus;
    private final TextView     tvStepLabel;
    private final TextView     tvCursor;
    private final TextView     tvBytes;
    private final Button       btnRun;
    private final Button       btnReset;
    private final Button       btnClose;

    // ── State ─────────────────────────────────────────────────────────────────
    private final Context           ctx;
    private final TerminalBootstrap bootstrap;

    private volatile TerminalManager.OutputCallback terminalOut = null;

    private BrainAgent            brainAgent = null;
    private final Handler         main       = new Handler(Looper.getMainLooper());
    private final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private final SpannableStringBuilder logBuf = new SpannableStringBuilder();
    private Switch  doctorSwitch = null;
    private boolean running      = false;
    private boolean panelVisible = false;
    private int     totalChars   = 0;
    private int     runAttempt   = 0;
    private static final int MAX_ATTEMPTS = 3;

    // ── Constructor ───────────────────────────────────────────────────────────

    public DoctorPanelController(Context ctx, View rootView, TerminalBootstrap bootstrap) {
        this.ctx       = ctx;
        this.bootstrap = bootstrap;

        panel       = rootView.findViewById(R.id.doctor_section_panel);
        chipsRow    = rootView.findViewById(R.id.doctor_chips_row);
        progressBar = rootView.findViewById(R.id.doctor_progress);
        svLog       = rootView.findViewById(R.id.sv_doctor_log);
        tvLog       = rootView.findViewById(R.id.tv_doctor_log);
        tvStatus    = rootView.findViewById(R.id.tv_doctor_status);
        tvStepLabel = rootView.findViewById(R.id.tv_doctor_step_label);
        tvCursor    = rootView.findViewById(R.id.tv_doctor_cursor);
        tvBytes     = rootView.findViewById(R.id.tv_doctor_bytes);
        btnRun      = rootView.findViewById(R.id.btn_doctor_run);
        btnReset    = rootView.findViewById(R.id.btn_doctor_reset);
        btnClose    = rootView.findViewById(R.id.btn_doctor_close);

        btnRun.setOnClickListener(v -> { if (!running) runDoctor(); });
        btnReset.setOnClickListener(v -> resetPanel());
        btnClose.setOnClickListener(v -> hide());

        doctorSwitch = rootView.findViewById(R.id.btn_doctor_toggle);
        if (doctorSwitch != null)
            doctorSwitch.setOnCheckedChangeListener((sw, c) -> { if (c) show(); else hide(); });

        buildChips();
        startCursorBlink();
        printBanner();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setTerminalOutputCallback(TerminalManager.OutputCallback cb) {
        this.terminalOut = cb;
    }

    public void setBrainAgent(BrainAgent agent) { this.brainAgent = agent; }

    public void show() {
        if (panelVisible) return;
        panelVisible = true;
        panel.setVisibility(View.VISIBLE);
        TranslateAnimation a = new TranslateAnimation(0,0,-panel.getHeight(),0);
        a.setDuration(220); a.setInterpolator(new DecelerateInterpolator());
        panel.startAnimation(a);
    }

    public void hide() {
        if (!panelVisible) return;
        panelVisible = false;
        panel.setVisibility(View.GONE);
        if (doctorSwitch != null && doctorSwitch.isChecked()) doctorSwitch.setChecked(false);
    }

    public void toggle()                 { if (panelVisible) hide(); else show(); }
    public boolean isVisible()           { return panelVisible; }

    public void showAndRun(String mode) {
        show();
        if (!running) main.postDelayed(this::runDoctor,
                TmuxDoctorActivity.LAUNCH_MODE_AUTO.equals(mode) ? 400 : 0);
    }

    // ── Doctor engine ─────────────────────────────────────────────────────────

    private void runDoctor() { if (running) return; runAttempt = 0; runDoctorAttempt(); }

    private void runDoctorAttempt() {
        if (running) return;
        runAttempt++;
        final int attempt = runAttempt;
        running = true;

        main.post(() -> {
            if (attempt == 1) { logBuf.clear(); tvLog.setText(""); totalChars = 0; }
            tvBytes.setText(totalChars + " B");
            progressBar.setProgress(0);
            // On retries, only reset steps that didn't pass
            for (Step s : STEPS) {
                if (attempt == 1 || s.state != StepState.OK) s.state = StepState.PENDING;
            }
            refreshChips();
            btnRun.setEnabled(false);
            btnRun.setAlpha(0.45f);
            setStatus("● RUN " + attempt + "/" + MAX_ATTEMPTS, COL_CYAN);
            tvStepLabel.setText("ATTEMPT " + attempt + "/" + MAX_ATTEMPTS + " …");
            tvStepLabel.setTextColor(COL_CYAN);
        });

        if (attempt == 1) printBanner();
        else              techo(YEL, "\n  ↺ RETRY  attempt " + attempt + "/" + MAX_ATTEMPTS + " ──────────\n");

        if (brainAgent != null) runAiDoctor(); else runLegacyDoctor();
    }

    // ── AI doctor ─────────────────────────────────────────────────────────────

    private void runAiDoctor() {
        executor.execute(() -> {
            try {
                tlog(CYN, "AI", "CVA Brain Doctor initialising…");
                tlog(MAG, "AI", "Asking AI to diagnose & fix environment…");
                pause(300);
                main.post(() -> setStatus("● AI THINKING", COL_MAGENTA));

                // FIX: Android-aware prompt — tells model each <cmd> runs isolated,
                // and gives pre-expanded literal paths (no shell variables to mis-expand).
                String reply = brainAgent.chatOnce(buildAiPrompt());

                if (reply == null || reply.isEmpty()) {
                    tlog(RED, "AI", "No response — falling back to legacy doctor");
                    runLegacySteps(); return;
                }
                tlog(GRN, "AI", "Brain responded — executing fix commands…");
                main.post(() -> setStatus("● AI FIXING", COL_CYAN));
                executeAiScript(reply);

            } catch (Exception e) {
                tlog(RED, "FATAL", "AI crash: " + e.getMessage());
                runLegacySteps();
            } finally {
                main.post(() -> { running = false; btnRun.setEnabled(true); btnRun.setAlpha(1f); });
            }
        });
    }

    /**
     * Build an Android-aware prompt with all paths pre-expanded as literals.
     * This prevents the AI from emitting commands like "mkdir -p $PREFIX/lib"
     * which fail when $PREFIX isn't set in the subprocess environment.
     */
    private String buildAiPrompt() {
        String prefix  = TermuxConstants.PREFIX_PATH;   // /data/data/com.braingods.cva/files/usr
        String home    = bestHome();                     // /data/data/com.braingods.cva/files/home
        String nlib    = ctx.getApplicationInfo().nativeLibraryDir;

        return "You are CVA Terminal Doctor running on Android (non-root).\n"
                + "IMPORTANT ANDROID RULES — you MUST follow these:\n"
                + "  • /system is READ-ONLY. Never write to /system, /lib, /bin, /etc.\n"
                + "  • Each <cmd> runs in a SEPARATE isolated shell subprocess.\n"
                + "    Exports from one command do NOT carry to the next.\n"
                + "    Use full literal paths — never shell variables like $PREFIX.\n"
                + "  • Android system commands (am, pm, input, cmd, settings, dumpsys,\n"
                + "    wm, service, appops, content, monkey) MUST be invoked using their\n"
                + "    full path: /system/bin/am, /system/bin/pm, /system/bin/cmd etc.\n"
                + "    NEVER use bare 'am' or 'pm' — the Termux bootstrap ships wrapper\n"
                + "    scripts for these that have a wrong shebang referencing com.termux,\n"
                + "    causing 'bad interpreter: Permission denied' at runtime.\n"
                + "  • Only write to these writable paths:\n"
                + "    PREFIX = " + prefix + "\n"
                + "    HOME   = " + home + "\n"
                + "    TMPDIR = " + ctx.getCacheDir().getAbsolutePath() + "\n"
                + "\n"
                + "STATE:\n"
                + "  api="   + android.os.Build.VERSION.SDK_INT + "\n"
                + "  abi="   + TermuxShellUtils.getArch() + "\n"
                + "  cvaBootstrap=" + TermuxShellUtils.isCvaBootstrapInstalled() + "\n"
                + "  realTermux="   + TermuxShellUtils.isTermuxInstalled() + "\n"
                + "  nativeBB="     + (bootstrap != null && bootstrap.hasNativeBusybox()) + "\n"
                + "\n"
                + "Output each shell command inside <cmd>...</cmd> tags.\n"
                + "Use full literal paths in every command — no $VARIABLES.\n"
                + "At the end output: <done>DOCTOR COMPLETE</done>\n";
    }

    private String buildDeviceCtx() {
        return "api="   + android.os.Build.VERSION.SDK_INT
                + "\nabi=" + TermuxShellUtils.getArch()
                + "\ncvaBootstrap=" + TermuxShellUtils.isCvaBootstrapInstalled()
                + "\nrealTermux="   + TermuxShellUtils.isTermuxInstalled()
                + "\nPREFIX="       + TermuxConstants.PREFIX_PATH
                + "\nHOME="         + bestHome()
                + "\nnativeBB="     + (bootstrap != null && bootstrap.hasNativeBusybox())
                + "\ndownloadBB="   + (bootstrap != null && bootstrap.hasDownloadedBusybox())
                + "\nsystemSh="     + new File("/system/bin/sh").exists() + "\n";
    }

    /**
     * FIX: Execute AI commands as a SINGLE shell script via one long-lived process,
     * so that exports persist across commands (instead of spawning a new subprocess
     * per command, which made every export a no-op).
     */
    private void executeAiScript(String reply) {
        // Build a single shell script from all <cmd> tags
        StringBuilder script = new StringBuilder();
        // Pre-set known-good environment at the top of the script
        script.append("export PREFIX='").append(TermuxConstants.PREFIX_PATH).append("'\n");
        script.append("export HOME='").append(bestHome()).append("'\n");
        script.append("export TMPDIR='").append(ctx.getCacheDir().getAbsolutePath()).append("'\n");
        script.append("export TERM=xterm-256color\n");
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            // /system/bin FIRST — bootstrap wrapper scripts for am/pm/cmd/input
            // may have stale com.termux shebangs; real system binaries are always safe.
            script.append("export PATH='/system/bin:/system/xbin:")
                    .append(TermuxConstants.PREFIX_BIN).append("'\n");
            script.append("export LD_LIBRARY_PATH='").append(TermuxConstants.PREFIX_LIB).append("'\n");
        } else {
            script.append("export PATH='/system/bin:/system/xbin'\n");
        }
        script.append("set -e\n"); // stop on first error so we can detect failures

        List<String> cmds = new ArrayList<>();
        boolean hasDone = false;

        for (String line : reply.split("\n")) {
            String t = line.trim();
            if (t.contains("<done>")) { hasDone = true; break; }
            if (t.contains("<cmd>") && t.contains("</cmd>")) {
                int s = t.indexOf("<cmd>") + 5, e = t.indexOf("</cmd>");
                if (s >= e) continue;
                String cmd = t.substring(s, e).trim();
                if (isSafeCommand(cmd)) {
                    cmds.add(cmd);
                    // Echo marker so we can track progress in output
                    script.append("echo '__CVA_CMD_START__: ").append(cmd.replace("'", "\\'")).append("'\n");
                    script.append(cmd).append("\n");
                    script.append("echo '__CVA_CMD_END__'\n");
                } else {
                    tlog(YEL, "SKIP", "Unsafe cmd skipped: " + cmd);
                }
            } else if (!t.isEmpty() && !t.startsWith("<") && t.length() > 3) {
                tlog(YEL, "AI", t);
            }
        }

        if (cmds.isEmpty()) {
            tlog(YEL, "AI", "No executable commands found — running legacy doctor");
            runLegacySteps();
            return;
        }

        tlog(CYN, "AI", "Running " + cmds.size() + " commands as unified script…");

        // Run the whole script in one process
        List<String> output = shellScript(script.toString(), 120_000);

        // Parse output and update step UI
        int si = 0;
        String currentCmd = null;
        List<String> cmdOutput = new ArrayList<>();

        for (String line : output) {
            if (line.startsWith("__CVA_CMD_START__: ")) {
                currentCmd = line.substring("__CVA_CMD_START__: ".length());
                cmdOutput.clear();
                if (si < STEPS.length) {
                    final int fi = si;
                    final String cmdSnapshot = currentCmd;
                    STEPS[fi].state = StepState.RUNNING;
                    main.post(() -> { refreshChips(); progressBar.setProgress(fi + 1);
                        tvStepLabel.setText("[CMD " + (fi + 1) + "] " + cmdSnapshot);
                        tvStepLabel.setTextColor(COL_CYAN); });
                }
                tlog(CYN, "CMD", "$ " + currentCmd);
            } else if (line.equals("__CVA_CMD_END__")) {
                if (si < STEPS.length) { STEPS[si].state = StepState.OK; main.post(this::refreshChips); si++; }
            } else {
                if (!line.trim().isEmpty()) tlog(WHT, "OUT", line);
                cmdOutput.add(line);
            }
        }

        doctorComplete();
    }

    /**
     * Filter out commands that are known to fail on non-root Android
     * to avoid confusing the user with red-herring errors.
     */
    private static final String[] ANDROID_SYSTEM_CMDS = {
            "am", "pm", "input", "cmd", "settings", "dumpsys",
            "monkey", "wm", "service", "appops", "content"
    };

    private boolean isSafeCommand(String cmd) {
        // Block bare Android system commands (without a leading /).
        // The Termux bootstrap ships shell wrappers for am, pm, cmd, input, etc.
        // whose shebangs reference #!/data/data/com.termux/... — executing them from
        // CVA's process yields "bad interpreter: Permission denied".
        // Force the AI to use /system/bin/am, /system/bin/pm, etc. explicitly.
        String firstToken = cmd.trim().split("\\s+")[0];
        if (!firstToken.startsWith("/")) {
            for (String sc : ANDROID_SYSTEM_CMDS) {
                if (firstToken.equals(sc)) {
                    tlog(YEL, "SKIP",
                            "Use /system/bin/" + sc + " not bare '" + sc + "' — "
                                    + "bootstrap wrapper has wrong shebang: " + cmd);
                    return false;
                }
            }
        }
        // Block writes to read-only paths
        if (cmd.matches(".*(mkdir|cp|mv|ln|chmod|chown|touch|echo|cat).*/(system|proc|sys|dev|lib|bin|etc|sbin|vendor).*")) {
            // Allow only if the target starts with our writable prefix or home
            String prefix = TermuxConstants.PREFIX_PATH;
            String home   = bestHome();
            // If the command's target is within our writable dirs, allow it
            if (cmd.contains(prefix) || cmd.contains(home)
                    || cmd.contains(ctx.getCacheDir().getAbsolutePath())) {
                return true;
            }
            return false;
        }
        // Block su/sudo
        if (cmd.startsWith("su ") || cmd.startsWith("sudo ")) return false;
        // Block direct writes to /system, /proc, /dev
        if (cmd.matches(".*>\\s*/system/.*") || cmd.matches(".*>\\s*/proc/.*")) return false;
        return true;
    }

    // ── Legacy doctor ─────────────────────────────────────────────────────────

    private void runLegacyDoctor() {
        executor.execute(() -> {
            try { runLegacySteps(); }
            catch (Exception e) { tlog(RED, "FATAL", "Crash: " + e.getMessage()); }
            finally { main.post(() -> { running=false; btnRun.setEnabled(true); btnRun.setAlpha(1f); }); }
        });
    }

    private void runLegacySteps() {
        // On retry, skip steps that already passed
        if (STEPS[0].state != StepState.OK) step_Env();
        if (STEPS[1].state != StepState.OK) step_Bootstrap();
        if (STEPS[2].state != StepState.OK) step_Bashrc();
        if (STEPS[3].state != StepState.OK) step_TmuxConf();
        if (STEPS[4].state != StepState.OK) step_Tpm();
        if (STEPS[5].state != StepState.OK) step_Packages();
        if (STEPS[6].state != StepState.OK) step_Path();
        step_EchoTest(); // always run the test
        doctorComplete();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // INDIVIDUAL STEPS
    // ─────────────────────────────────────────────────────────────────────────

    private void step_Env() {
        begin(0);
        tlog(CYN, "ENV", "Android API " + android.os.Build.VERSION.SDK_INT + "  ABI=" + TermuxShellUtils.getArch());
        tlog(CYN, "ENV", "filesDir  = " + ctx.getFilesDir());
        tlog(CYN, "ENV", "PREFIX    = " + TermuxConstants.PREFIX_PATH);
        tlog(CYN, "ENV", "HOME      = " + bestHome());
        tlog(CYN, "ENV", "nativeLib = " + ctx.getApplicationInfo().nativeLibraryDir);

        if      (TermuxShellUtils.isCvaBootstrapInstalled()) tlog(GRN, "ENV", "CVA Bootstrap ✓  " + TermuxConstants.BASH_PATH);
        else if (TermuxShellUtils.isTermuxInstalled())        tlog(GRN, "ENV", "Real Termux ✓  "   + TermuxConstants.TERMUX_BASH);
        else                                                  tlog(YEL, "ENV", "No bootstrap — will install in next step");

        if (bootstrap != null && bootstrap.hasNativeBusybox())
            tlog(GRN, "ENV", "jniLibs busybox ✓  (no download needed)");
        else if (bootstrap != null && bootstrap.hasDownloadedBusybox())
            tlog(YEL, "ENV", "Downloaded busybox found");

        finish(0, StepState.OK);
    }

    private void step_Bootstrap() {
        begin(1);

        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            tlog(GRN, "BOOTSTRAP", "CVA bash ✓  " + TermuxConstants.BASH_PATH);
            finish(1, StepState.OK); return;
        }
        if (TermuxShellUtils.isTermuxInstalled()) {
            tlog(GRN, "BOOTSTRAP", "Real Termux ✓  " + TermuxConstants.TERMUX_BASH);
            finish(1, StepState.OK); return;
        }
        if (bootstrap != null && bootstrap.hasNativeBusybox()) {
            tlog(GRN, "BOOTSTRAP", "libbusybox.so ✓  (jniLibs, no download needed)");
            finish(1, StepState.OK); return;
        }
        if (bootstrap != null && bootstrap.hasDownloadedBusybox()) {
            tlog(GRN, "BOOTSTRAP", "busybox ✓  (" + (bootstrap.getDownloadedBusybox().length()/1024) + " KB)");
            finish(1, StepState.OK); return;
        }

        tlog(YEL, "BOOTSTRAP", "Nothing found — trying Termux bootstrap download…");
        tlog(CYN, "BOOTSTRAP", "ABI: " + TermuxShellUtils.getArch());
        tlog(CYN, "BOOTSTRAP", "URL: " + TermuxShellUtils.getBootstrapUrl());

        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};

        new TermuxInstaller(ctx).install(new TermuxInstaller.InstallCallback() {
            @Override public void onProgress(String msg) { tlog(BLU, "DL", msg); }
            @Override public void onSuccess() {
                ok[0] = true;
                tlog(GRN, "BOOTSTRAP", "Termux bootstrap installed ✓");
                tlog(GRN, "BOOTSTRAP", "bash → " + TermuxConstants.BASH_PATH);
                latch.countDown();
            }
            @Override public void onFailure(String err) {
                tlog(YEL, "BOOTSTRAP", "Bootstrap failed: " + err);
                tlog(YEL, "BOOTSTRAP", "Trying BusyBox fallback…");
                installBusyboxFallback(ok, latch);
            }
        });

        try { latch.await(180, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        if (!ok[0] && bootstrap != null && bootstrap.hasNativeBusybox()) {
            tlog(YEL, "BOOTSTRAP", "Using jniLibs busybox as shell fallback ✓");
            ok[0] = true;
        }

        finish(1, ok[0]
                ? (TermuxShellUtils.isCvaBootstrapInstalled() ? StepState.OK : StepState.WARN)
                : StepState.FAIL);
    }

    private void installBusyboxFallback(boolean[] ok, CountDownLatch latch) {
        if (bootstrap == null) { latch.countDown(); return; }
        bootstrap.install(new TerminalBootstrap.ProgressCallback() {
            @Override public void onProgress(String msg) { tlog(BLU, "DL", msg); }
            @Override public void onDone(boolean success, String path) {
                ok[0] = success;
                tlog(success ? GRN : RED, "BOOTSTRAP",
                        success ? "BusyBox ✓  → " + path : "BusyBox download FAILED");
                latch.countDown();
            }
        });
    }

    private void step_Bashrc() {
        begin(2);
        // FIX: use bestHome() consistently — always the same resolved path
        File home = new File(bestHome()); home.mkdirs();

        String nlib    = ctx.getApplicationInfo().nativeLibraryDir;
        String binPath = TermuxShellUtils.isCvaBootstrapInstalled()
                ? TermuxConstants.PREFIX_BIN + ":" + nlib + ":/system/bin:/system/xbin"
                : nlib + ":/system/bin:/system/xbin";
        String bbPath  = (bootstrap != null && bootstrap.getBusyboxFile() != null)
                ? bootstrap.getBusyboxFile().getAbsolutePath() : "/system/bin/sh";
        String hp      = home.getAbsolutePath();
        String tmp     = ctx.getCacheDir().getAbsolutePath();

        File bashrc = new File(home, ".bashrc");
        try (FileOutputStream fos = new FileOutputStream(bashrc)) {
            fos.write(buildBashrc(binPath, hp, tmp, bbPath).getBytes("UTF-8"));
        } catch (IOException e) {
            tlog(RED, "BASH", "Write .bashrc failed: " + e.getMessage());
            finish(2, StepState.FAIL); return;
        }

        try (FileOutputStream fos = new FileOutputStream(new File(home, ".profile"))) {
            fos.write(("export PATH=\""+binPath+"\"\n"
                    + "export HOME=\""+hp+"\"\nexport TMPDIR=\""+tmp+"\"\n"
                    + "export TERM=xterm-256color\n[ -f ~/.bashrc ] && . ~/.bashrc\n")
                    .getBytes("UTF-8"));
        } catch (IOException ignored) {}

        tlog(GRN, "BASH", "✓  " + bashrc.getAbsolutePath() + " (" + bashrc.length() + " B)");
        tlog(CYN, "BASH", "PATH=" + binPath);
        techo(GRN, "⚕ BASH ✓  " + bashrc.getAbsolutePath());
        finish(2, StepState.OK);
    }

    private String buildBashrc(String binPath, String hp, String tmp, String bb) {
        return "# CVA .bashrc — TmuxDoctor v4.1\n"
                + "case $- in *i*) ;; *) return;; esac\n"
                + "HISTCONTROL=ignoreboth\nHISTSIZE=2000\n"
                + "command -v shopt >/dev/null 2>&1 && shopt -s histappend checkwinsize\n"
                + "export PATH=\"" + binPath + "\"\n"
                + "export HOME=\"" + hp + "\"\n"
                + "export TMPDIR=\"" + tmp + "\"\n"
                + "export TERM=xterm-256color\nexport COLORTERM=truecolor\n"
                + "export BUSYBOX=\"" + bb + "\"\n"
                + "alias ll='ls -alF --color=auto'\nalias la='ls -A'\nalias l='ls -CF'\n"
                + "alias cls='clear'\nalias grep='grep --color=auto'\n"
                + "PS1='\\[\\e[94m\\]┌─×××××[\\[\\e[97m\\]\\T\\[\\e[94m\\]]§§§§§§"
                + "\\e[1;94m[\\e[1;92m{C0A†YL}✓\\e[1;94m]\\e[0;94m::::::::"
                + "[\\e[1;96m\\#\\e[1;92m-wings\\e[1;94m]\\n|\\n"
                + "\\e[0;94m┟==[\\[\\e[94m\\]\\e[0;95m\\W\\[\\e[94m\\]]\\e[1;93m-species"
                + "\\[\\e[94m\\]\\e[1;91m\\[\\e[91m\\]§§\\e[1;91m[\\e[1;92m{✓}\\e[1;91m]"
                + "\\e[0;94m\\n|\\n└==[\\[\\e[93m\\]≈≈≈≈≈\\e[94m\\]]™[✓]=►\\e[1;91m '\n";
    }

    private void step_TmuxConf() {
        begin(3);
        File home = new File(bestHome()); home.mkdirs();
        File f = new File(home, ".tmux.conf");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(buildTmuxConf().getBytes("UTF-8"));
        } catch (IOException e) {
            tlog(RED, "TMUX", "Write failed: " + e.getMessage());
            finish(3, StepState.FAIL); return;
        }
        tlog(GRN, "TMUX", "✓  " + f.getAbsolutePath() + " (" + f.length() + " B)");
        tlog(CYN, "TMUX", "mouse · 256-color · vim-keys · CVA status");

        List<String> rl = shell("tmux source-file " + f.getAbsolutePath() + " 2>&1", 5_000);
        if (!rl.isEmpty() && !rl.get(0).contains("error") && !rl.get(0).contains("no server"))
            tlog(GRN, "TMUX", "Live reloaded ✓");

        techo(GRN, "⚕ TMUX.CONF ✓  " + f.getAbsolutePath());
        finish(3, StepState.OK);
    }

    private String buildTmuxConf() {
        return "# CVA .tmux.conf v4.1\n"
                + "set -g default-terminal \"screen-256color\"\n"
                + "set -ga terminal-overrides \",xterm-256color:Tc\"\n"
                + "set -g history-limit 50000\nset -g base-index 1\n"
                + "setw -g pane-base-index 1\nset -g renumber-windows on\n"
                + "set -sg escape-time 10\nset -g focus-events on\n"
                + "unbind C-b\nset -g prefix C-a\nbind C-a send-prefix\n"
                + "set -g mouse on\nsetw -g mode-keys vi\n"
                + "bind | split-window -h -c \"#{pane_current_path}\"\n"
                + "bind - split-window -v -c \"#{pane_current_path}\"\n"
                + "bind h select-pane -L\nbind j select-pane -D\n"
                + "bind k select-pane -U\nbind l select-pane -R\n"
                + "bind r source-file ~/.tmux.conf \\; display '.tmux.conf reloaded ✓'\n"
                + "set -g status-style 'bg=#020805 fg=#00FF6A'\n"
                + "set -g status-left  '#[fg=#FF2EFF,bold]§§[#[fg=#00FF6A]{CVA}#[fg=#2979FF]✓#[fg=#FF2EFF]]§§  '\n"
                + "set -g status-right '#[fg=#FFD600]%H:%M #[fg=#00FF6A,bold]#H'\n"
                + "setw -g window-status-current-format '#[fg=#020805,bg=#00FF6A,bold] #I:#W#F '\n"
                + "set -g pane-active-border-style 'fg=#00FF6A'\n"
                + "run -b '~/.tmux/plugins/tpm/tpm 2>/dev/null || true'\n";
    }

    private void step_Tpm() {
        begin(4);
        File home   = new File(bestHome());
        File tpmDir = new File(home, ".tmux/plugins/tpm");

        boolean hasGit = cmdExists("git");
        if (!hasGit) {
            tlog(YEL, "TPM", "git not found — trying: pkg install -y git");
            List<String> pg = shell("pkg install -y git 2>&1 | tail -4", 90_000);
            for (String l : pg) if (!l.trim().isEmpty()) tlog(BLU, "TPM", l);
            hasGit = cmdExists("git");
        }
        tlog(hasGit ? GRN : YEL, "TPM", "git: " + (hasGit ? "✓" : "✗ still not found"));

        if (!hasGit) {
            tlog(YEL, "TPM", "Skipping TPM — run: pkg install git  then retry");
            techo(YEL, "⚕ TPM ⊘  git not available — run: pkg install git");
            finish(4, StepState.SKIP); return;
        }

        if (tpmDir.exists() && new File(tpmDir, "tpm").exists()) {
            tlog(GRN, "TPM", "Already installed ✓  " + tpmDir);
            List<String> pull = shell("git -C '" + tpmDir + "' pull --ff-only 2>&1 | tail -2", 30_000);
            for (String l : pull) if (!l.trim().isEmpty()) tlog(BLU, "TPM", l);
            techo(GRN, "⚕ TPM ✓  (updated)");
            finish(4, StepState.OK); return;
        }

        tpmDir.getParentFile().mkdirs();
        tlog(CYN, "TPM", "Cloning → " + tpmDir);
        List<String> clone = shell("git clone --depth=1 https://github.com/tmux-plugins/tpm '"
                + tpmDir + "' 2>&1", 90_000);
        for (String l : clone) if (!l.trim().isEmpty()) tlog(BLU, "GIT", l);

        boolean cloned = tpmDir.exists() && new File(tpmDir, "tpm").exists();
        tlog(cloned ? GRN : RED, "TPM", cloned ? "Installed ✓  (prefix+I in tmux to load)" : "Clone failed");
        techo(cloned ? GRN : RED, "⚕ TPM " + (cloned ? "✓" : "✗ clone failed"));
        finish(4, cloned ? StepState.OK : StepState.FAIL);
    }

    private void step_Packages() {
        begin(5);

        String mgr = null;
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            if (new File(TermuxConstants.PREFIX_BIN + "/pkg").exists())
                mgr = TermuxConstants.PREFIX_BIN + "/pkg";
        }
        if (mgr == null && new File("/data/data/com.termux/files/usr/bin/pkg").exists())
            mgr = "/data/data/com.termux/files/usr/bin/pkg";
        // FIX: prefer full path to avoid stale shebang wrapper scripts
        if (mgr == null && new File(TermuxConstants.PREFIX_BIN + "/apt-get").exists())
            mgr = TermuxConstants.PREFIX_BIN + "/apt-get";
        if (mgr == null && cmdExists("pkg"))     mgr = "pkg";
        if (mgr == null && cmdExists("apt-get")) mgr = "apt-get";
        if (mgr == null && cmdExists("apt"))     mgr = "apt";

        if (mgr == null) {
            tlog(YEL, "PKG", "No package manager found");
            tlog(CYN, "PKG", "Bootstrap installs pkg at: " + TermuxConstants.PREFIX_BIN + "/pkg");
            techo(YEL, "⚕ PKG ⊘  no package manager (bootstrap must finish first)");
            finish(5, StepState.SKIP); return;
        }

        final String pm = mgr;
        tlog(CYN, "PKG", "Manager: " + pm + "  → updating repos…");

        List<String> upd = shell(pm + " update -y 2>&1 | tail -4", 90_000);
        for (String l : upd) if (!l.trim().isEmpty()) tlog(BLU, "PKG", l);

        String[] pkgs = {"tmux","vim","git","python3","nano","curl","wget","openssh","htop","zip","unzip"};
        int ok = 0, fail = 0;
        StringBuilder sb = new StringBuilder();

        for (String pkg : pkgs) {
            List<String> res = shell(pm + " install -y " + pkg + " 2>&1 | tail -3", 90_000);
            boolean done = cmdExists(pkg)
                    || res.stream().anyMatch(l -> l.contains("installed") || l.contains("upgraded")
                    || l.contains("already") || l.contains("Setting up"));
            tlog(done ? GRN : YEL, "PKG", pkg + "  " + (done ? "✓" : "✗"));
            if (done) { ok++; sb.append(pkg).append("✓ "); }
            else       { fail++; sb.append(pkg).append("✗ "); }
        }

        tlog(GRN, "PKG", ok + " installed  " + fail + " skipped");
        techo(ok > 0 ? GRN : YEL, "⚕ PKG  " + ok + "✓ " + fail + "✗  " + sb);
        finish(5, fail == 0 ? StepState.OK : StepState.WARN);
    }

    private void step_Path() {
        begin(6);
        File home = new File(bestHome()); home.mkdirs();
        new File(home, "bin").mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(home, ".bash_profile"))) {
            fos.write("# CVA .bash_profile\n[ -f ~/.bashrc ] && . ~/.bashrc\n".getBytes("UTF-8"));
        } catch (IOException e) { tlog(YEL, "PATH", e.getMessage()); }
        tlog(GRN, "PATH", ".bash_profile → .bashrc ✓");
        tlog(GRN, "PATH", "TERM=xterm-256color ✓");
        tlog(GRN, "PATH", "HOME=" + home);
        if (TermuxShellUtils.isCvaBootstrapInstalled())
            tlog(GRN, "PATH", "PREFIX=" + TermuxConstants.PREFIX_PATH + " ✓");

        techo(CYN, "⚕ ENV SNAPSHOT (copy-pasteable):");
        techo(WHT, "  HOME=" + home);
        techo(WHT, "  TERM=xterm-256color");
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            techo(WHT, "  PREFIX=" + TermuxConstants.PREFIX_PATH);
            techo(WHT, "  PATH=" + TermuxConstants.PREFIX_BIN + ":...");
        }
        finish(6, StepState.OK);
    }

    private void step_EchoTest() {
        begin(7); pause(100);

        List<String> r = shell(
                "printf '\\033[1;92m✔ Green\\033[0m  \\033[1;96mCyan\\033[0m  "
                        + "\\033[1;91mRed\\033[0m  \\033[1;93mYellow\\033[0m  \\033[1;95mMagenta\\033[0m\\n'"
                        + " && printf '\\033[1;94m┌─┐└─┘│█░ Box-drawing\\033[0m\\n'"
                        + " && printf '\\033[1;97m✓✗►★♞☣™≈ Symbols\\033[0m\\n'"
                        + " && echo ANSI_TEST_OK", 10_000);
        boolean passed = r.stream().anyMatch(l -> l.contains("ANSI_TEST_OK"));

        tlog(GRN,  "TEST", "✔  Green   (#00FF6A)");
        tlog(RED,  "TEST", "✔  Red     (#FF3B3B)");
        tlog(CYN,  "TEST", "✔  Cyan    (#00E5FF)");
        tlog(YEL,  "TEST", "✔  Yellow  (#FFD600)");
        tlog(MAG,  "TEST", "✔  Magenta (#FF2EFF)");
        tlog(BLU,  "TEST", "✔  Blue    (#2979FF)");
        tlog(WHT,  "TEST", "┌─┐└─┘│█░  Box drawing");
        tlog(WHT,  "TEST", "✓✗►★♞☣™≈   Symbols");
        tlog(passed ? GRN : YEL, "TEST", "Shell ANSI test: " + (passed ? "PASS ✓" : "WARN (shell may not support colors)"));

        TerminalManager.OutputCallback tout = terminalOut;
        if (tout != null) {
            StringBuilder rep = new StringBuilder();
            rep.append("\n");
            rep.append(MAG).append("══════════════════════════════════════════════════\n").append(RST);
            rep.append(GRN).append(BLD).append("  ⚕  CVA DOCTOR REPORT  —  copy-paste below:\n").append(RST);
            rep.append(MAG).append("══════════════════════════════════════════════════\n").append(RST);
            for (Step s : STEPS) {
                String c = s.state==StepState.OK ? GRN : s.state==StepState.WARN ? YEL
                        : s.state==StepState.FAIL ? RED : s.state==StepState.SKIP ? DIM : WHT;
                String sym = s.state==StepState.OK ? "✓" : s.state==StepState.WARN ? "!"
                        : s.state==StepState.FAIL ? "✗" : s.state==StepState.SKIP ? "—" : "?";
                rep.append("  ").append(c).append(sym).append("  ").append(s.tag)
                        .append("\t").append(s.label)
                        .append("  [").append(s.state.name()).append("]").append(RST).append("\n");
            }
            rep.append(MAG).append("══════════════════════════════════════════════════\n").append(RST);
            rep.append(YEL).append("  Restart terminal or type: source ~/.bashrc\n").append(RST);
            rep.append("\n");
            main.post(() -> tout.onOutput(rep.toString()));
        }

        finish(7, passed ? StepState.OK : StepState.WARN);
    }

    // ── Completion ────────────────────────────────────────────────────────────

    private void doctorComplete() {
        pause(150);
        int fails=0, warns=0;
        for (Step s : STEPS) { if (s.state==StepState.FAIL) fails++; else if (s.state==StepState.WARN) warns++; }
        final int f=fails, w=warns;
        final boolean allOk    = (f==0 && w==0);
        final boolean canRetry = !allOk && runAttempt < MAX_ATTEMPTS;

        main.post(() -> {
            if (allOk) {
                appendStyled("┌────────────────────────────────────────┐\n", COL_PRIMARY, true);
                appendStyled("│   CVA Terminal Doctor  COMPLETE  ✓     │\n", COL_PRIMARY, true);
                if (TermuxShellUtils.isCvaBootstrapInstalled())
                    appendStyled("│   Shell: bash (Termux bootstrap)       │\n", COL_PRIMARY, false);
                appendStyled("└────────────────────────────────────────┘\n", COL_PRIMARY, true);
                setStatus("● DONE ✓", COL_PRIMARY);
                tvStepLabel.setText("ALL STEPS COMPLETE  ✓"); tvStepLabel.setTextColor(COL_PRIMARY);
            } else if (canRetry) {
                appendStyled("┌────────────────────────────────────────┐\n", COL_GOLD, true);
                appendStyled("│  " + f + " FAIL  " + w + " WARN — retrying…              │\n", COL_GOLD, true);
                appendStyled("└────────────────────────────────────────┘\n", COL_GOLD, true);
                setStatus("↺ RETRY " + (runAttempt+1) + "/" + MAX_ATTEMPTS, COL_GOLD);
            } else {
                appendStyled("┌────────────────────────────────────────┐\n", COL_RED, true);
                appendStyled("│  " + f + " FAIL  " + w + " WARN after " + MAX_ATTEMPTS + " tries │\n", COL_RED, true);
                appendStyled("└────────────────────────────────────────┘\n", COL_RED, true);
                setStatus("● DONE — issues remain", COL_GOLD);
            }
            progressBar.setProgress(STEPS.length);
        });

        if (canRetry) { pause(800); running=false; runDoctorAttempt(); }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STEP HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private void begin(int i) {
        STEPS[i].state = StepState.RUNNING;
        main.post(() -> {
            tvStepLabel.setText("["+STEPS[i].tag+"]  "+STEPS[i].label+"…");
            tvStepLabel.setTextColor(COL_CYAN);
            progressBar.setProgress(i);
            setStatus("● " + STEPS[i].tag, COL_CYAN);
            refreshChips();
        });
        appendStyled("\n  ── " + STEPS[i].icon + " " + STEPS[i].tag + "  "
                + STEPS[i].label.toUpperCase() + " ──\n", COL_CYAN, true);
        techo(CYN, "⚕ ── " + STEPS[i].tag + " " + STEPS[i].label + " ──");
    }

    private void finish(int i, StepState state) {
        STEPS[i].state = state;
        main.post(() -> { progressBar.setProgress(i+1); refreshChips(); });
        appendStyled("  " + stateSymbol(state) + "  " + STEPS[i].tag + "  "
                + state.name() + "\n", stateColor(state), state==StepState.OK);
    }

    private void resetPanel() {
        for (Step s : STEPS) s.state = StepState.PENDING;
        main.post(() -> {
            logBuf.clear(); tvLog.setText(""); totalChars=0; tvBytes.setText("0 B");
            progressBar.setProgress(0); refreshChips();
            setStatus("● IDLE", COL_MID);
            tvStepLabel.setText("STANDBY — tap ⚕ RUN DOCTOR"); tvStepLabel.setTextColor(COL_MID);
        });
        printBanner();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHIPS
    // ─────────────────────────────────────────────────────────────────────────

    private void buildChips() {
        main.post(() -> {
            chipsRow.removeAllViews();
            for (int i = 0; i < STEPS.length; i++) {
                Step s = STEPS[i]; s.chip = makeChip(s); chipsRow.addView(s.chip);
                if (i < STEPS.length - 1) {
                    TextView sep = new TextView(ctx); sep.setText(" › ");
                    sep.setTextSize(8.5f); sep.setTextColor(COL_DIM);
                    sep.setTypeface(Typeface.MONOSPACE); chipsRow.addView(sep);
                }
            }
        });
    }

    private TextView makeChip(Step s) {
        TextView tv = new TextView(ctx);
        tv.setText(s.icon + " " + s.tag); tv.setTextSize(8.5f);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); tv.setTextColor(COL_DIM);
        tv.setPadding(dp(6), dp(1), dp(6), dp(1)); tv.setBackground(pill(STC_PENDING, COL_DIM));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(1)); tv.setLayoutParams(lp); return tv;
    }

    private void refreshChips() {
        for (Step s : STEPS) {
            if (s.chip == null) continue;
            String lbl; int bg, txt;
            switch (s.state) {
                case RUNNING: lbl="► "+s.tag; bg=STC_RUNNING; txt=COL_CYAN;    break;
                case OK:      lbl="✓ "+s.tag; bg=STC_OK;      txt=COL_PRIMARY; break;
                case WARN:    lbl="! "+s.tag; bg=STC_WARN;    txt=COL_GOLD;    break;
                case FAIL:    lbl="✗ "+s.tag; bg=STC_FAIL;    txt=COL_RED;     break;
                case SKIP:    lbl="— "+s.tag; bg=STC_SKIP;    txt=COL_MID;     break;
                default:      lbl=s.icon+" "+s.tag; bg=STC_PENDING; txt=COL_DIM; break;
            }
            s.chip.setText(lbl); s.chip.setTextColor(txt); s.chip.setBackground(pill(bg, txt));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OUTPUT  — panel + terminal mirror
    // ─────────────────────────────────────────────────────────────────────────

    private void tlog(String ansi, String cat, String msg) {
        int panelCol = ansiToColor(ansi);
        main.post(() -> {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            span(sb, "  [", COL_DIM, false); span(sb, cat, panelCol, true);
            span(sb, "] ", COL_DIM, false);  span(sb, msg + "\n", COL_TEXT, false);
            logBuf.append(sb);
            totalChars += msg.length();
            tvLog.setText(logBuf); tvBytes.setText(fmtBytes(totalChars));
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });

        TerminalManager.OutputCallback tout = terminalOut;
        if (tout != null) {
            String line = ansi + "[" + cat + "] " + RST + WHT + msg + RST + "\n";
            main.post(() -> tout.onOutput(line));
        }
    }

    private void techo(String ansi, String msg) {
        TerminalManager.OutputCallback tout = terminalOut;
        if (tout != null) main.post(() -> tout.onOutput(ansi + msg + RST + "\n"));
    }

    private void techo(String msg) { techo(RST, msg); }

    private void printBanner() {
        pause(30);
        appendStyled(
                "╔══════════════════════════════════════════╗\n"
                        + "║  CVA Terminal Doctor  v4.1               ║\n"
                        + "║  §§§§§_{GODKILLER}_§§§§§                 ║\n"
                        + "╚══════════════════════════════════════════╝\n\n", COL_MAGENTA, true);
        appendStyled("  Tap  ⚕ RUN DOCTOR  to heal the terminal\n\n", COL_GOLD, false);

        TerminalManager.OutputCallback tout = terminalOut;
        if (tout != null) {
            String banner = "\n"
                    + MAG + "╔══════════════════════════════════════════╗\n" + RST
                    + MAG + "║  " + GRN + BLD + "CVA Terminal Doctor  v4.1" + RST + MAG + "               ║\n" + RST
                    + MAG + "║  §§§§§_{GODKILLER}_§§§§§                 ║\n" + RST
                    + MAG + "╚══════════════════════════════════════════╝\n" + RST
                    + YEL + "  Logs mirrored here — fully copy-pasteable\n" + RST + "\n";
            main.post(() -> tout.onOutput(banner));
        }
    }

    private void appendStyled(String text, int color, boolean bold) {
        main.post(() -> {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            span(sb, text, color, bold);
            logBuf.append(sb); totalChars += text.length();
            tvLog.setText(logBuf); tvBytes.setText(fmtBytes(totalChars));
            svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void span(SpannableStringBuilder sb, String text, int color, boolean bold) {
        // FIX: SpannableStringBuilder crashes with SPAN_EXCLUSIVE_EXCLUSIVE on zero-length spans.
        // This happens when tlog() or appendStyled() is called with an empty string.
        if (text == null || text.isEmpty()) return;
        int s = sb.length(); sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void setStatus(String text, int color) { tvStatus.setText(text); tvStatus.setTextColor(color); }

    // ─────────────────────────────────────────────────────────────────────────
    // CURSOR BLINK
    // ─────────────────────────────────────────────────────────────────────────

    private void startCursorBlink() {
        main.post(new Runnable() {
            boolean vis = true;
            @Override public void run() {
                if (tvCursor != null)
                    tvCursor.setVisibility(vis ? View.VISIBLE : View.INVISIBLE);
                vis = !vis;
                main.postDelayed(this, 600);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SHELL + PATH HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run a single shell command in an isolated subprocess.
     * All paths are passed as pre-expanded literals in the environment.
     */
    private List<String> shell(String cmd, long timeoutMs) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(bestShell(), "-c", cmd);
            applyEnv(pb);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long deadline = System.currentTimeMillis() + timeoutMs;
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (System.currentTimeMillis() > deadline) { tlog(YEL,"SHELL","timeout: "+cmd); break; }
            }
            p.waitFor();
        } catch (Exception e) { lines.add("[err: " + e.getMessage() + "]"); }
        return lines;
    }

    /**
     * FIX: Run a multi-command script in ONE process so exports persist.
     * Used by executeAiScript() for the AI doctor flow.
     */
    private List<String> shellScript(String script, long timeoutMs) {
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(bestShell());
            applyEnv(pb);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            // Write the script to stdin
            try (OutputStream os = p.getOutputStream()) {
                os.write(script.getBytes("UTF-8"));
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long deadline = System.currentTimeMillis() + timeoutMs;
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
                if (System.currentTimeMillis() > deadline) {
                    tlog(YEL, "SHELL", "script timeout");
                    p.destroy();
                    break;
                }
            }
            p.waitFor();
        } catch (Exception e) { lines.add("[err: " + e.getMessage() + "]"); }
        return lines;
    }

    /**
     * FIX: Apply fully-resolved environment to a ProcessBuilder.
     * All values are literal strings — no shell variables that could mis-expand.
     */
    private void applyEnv(ProcessBuilder pb) {
        // Start with a clean slate to avoid inheriting broken system env
        pb.environment().put("TERM",   "xterm-256color");
        pb.environment().put("HOME",   bestHome());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
        pb.environment().put("LANG",   "en_US.UTF-8");

        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            pb.environment().put("PREFIX", TermuxConstants.PREFIX_PATH);
            // System tools (am, pm, cmd, input…) MUST come before PREFIX_BIN.
            // The Termux bootstrap ships wrapper scripts for those tools whose
            // shebangs may reference com.termux paths — putting /system/bin first
            // ensures we get the real system binary, not the wrapper.
            pb.environment().put("PATH",
                    "/system/bin:/system/xbin:"
                            + TermuxConstants.PREFIX_BIN + ":"
                            + ctx.getApplicationInfo().nativeLibraryDir);
            pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.PREFIX_LIB);
            pb.environment().put("SHELL", TermuxConstants.BASH_PATH);
            // FIX: apt/dpkg path redirect — without these, apt reads com.termux paths → EACCES
            File aptConf = new File(TermuxConstants.PREFIX_PATH + "/etc/apt/apt.conf");
            if (aptConf.exists()) pb.environment().put("APT_CONFIG", aptConf.getAbsolutePath());
            pb.environment().put("DPKG_ADMINDIR",            TermuxConstants.PREFIX_LIB.replace("/lib", "/var/lib/dpkg"));
            pb.environment().put("DEBIAN_FRONTEND",          "noninteractive");
            pb.environment().put("APT_LISTCHANGES_FRONTEND", "none");
            // FIX: ncurses terminfo path — without this, nano/vim/tmux crash on launch
            pb.environment().put("TERMINFO",      TermuxConstants.PREFIX_PATH + "/share/terminfo");
            pb.environment().put("TERMINFO_DIRS", TermuxConstants.PREFIX_PATH + "/share/terminfo:/system/lib/terminfo");
        } else if (TermuxShellUtils.isTermuxInstalled()) {
            pb.environment().put("PATH",
                    "/data/data/com.termux/files/usr/bin:"
                            + ctx.getApplicationInfo().nativeLibraryDir
                            + ":/system/bin:/system/xbin");
            pb.environment().put("LD_LIBRARY_PATH", "/data/data/com.termux/files/usr/lib");
        } else {
            pb.environment().put("PATH",
                    ctx.getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin");
        }
    }

    private String bestShell() {
        if (TermuxShellUtils.isCvaBootstrapInstalled()) return TermuxConstants.BASH_PATH;
        if (TermuxShellUtils.isTermuxInstalled())       return TermuxConstants.TERMUX_BASH;
        if (bootstrap != null && bootstrap.hasNativeBusybox())
            return bootstrap.getNativeBusybox().getAbsolutePath();
        if (bootstrap != null && bootstrap.hasDownloadedBusybox())
            return bootstrap.getDownloadedBusybox().getAbsolutePath();
        return "/system/bin/sh";
    }

    /**
     * FIX: Always resolve to the correct, consistent HOME path.
     * /data/data/... and /data/user/0/... are different paths inside ProcessBuilder
     * even though they resolve to the same inode — using the wrong one causes
     * file-not-found errors when the shell reads .bashrc.
     */
    private String bestHome() {
        if (TermuxShellUtils.isCvaBootstrapInstalled()) return TermuxConstants.HOME_PATH;
        if (TermuxShellUtils.isTermuxInstalled())       return TermuxConstants.TERMUX_HOME;
        // FIX: Use getFilesDir() → getAbsolutePath() which returns /data/data/...
        // NOT /data/user/0/... (the symlink), to stay consistent with TermuxConstants.
        return ctx.getFilesDir().getAbsolutePath();
    }

    private boolean cmdExists(String cmd) {
        List<String> r = shell("command -v " + cmd + " 2>&1 || which " + cmd + " 2>&1", 5_000);
        return !r.isEmpty() && !r.get(0).startsWith("[err") && r.get(0).contains("/");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTIL
    // ─────────────────────────────────────────────────────────────────────────

    private String stateSymbol(StepState s) {
        switch (s) { case OK: return "✓"; case WARN: return "!"; case FAIL: return "✗";
            case SKIP: return "—"; default: return "?"; }
    }

    private int stateColor(StepState s) {
        switch (s) { case OK: return COL_PRIMARY; case WARN: return COL_GOLD;
            case FAIL: return COL_RED; case SKIP: return COL_MID; default: return COL_TEXT; }
    }

    private int ansiToColor(String a) {
        if (GRN.equals(a)) return COL_PRIMARY; if (CYN.equals(a)) return COL_CYAN;
        if (YEL.equals(a)) return COL_GOLD;    if (RED.equals(a)) return COL_RED;
        if (MAG.equals(a)) return COL_MAGENTA; if (BLU.equals(a)) return COL_BLUE;
        if (DIM.equals(a)) return COL_DIM;     return COL_TEXT;
    }

    private android.graphics.drawable.GradientDrawable pill(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(20)); d.setColor(fill); d.setStroke(dp(1), stroke); return d;
    }

    private String fmtBytes(int n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format("%.1f KB", n/1024f);
        return String.format("%.2f MB", n/1048576f);
    }

    private int dp(int v) { return Math.round(v * ctx.getResources().getDisplayMetrics().density); }
    private void pause(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}