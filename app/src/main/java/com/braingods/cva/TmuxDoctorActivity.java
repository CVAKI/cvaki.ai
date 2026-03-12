package com.braingods.cva;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TmuxDoctorActivity  [Termux-bootstrap edition]
 * ─────────────────────────────────────────────────────────────────────────────
 * Self-healing terminal bootstrap activity.
 *
 * WHAT'S NEW vs. previous version:
 *   • step_BusyBox() now tries the FULL Termux bootstrap download via
 *     TermuxInstaller FIRST (real bash + python3 + git + vim + tmux…),
 *     then falls back to the old BusyBox-only download if that fails.
 *   • resolveBestShell() checks CVA bootstrap and real Termux before BusyBox.
 *   • step_PkgInstall() now also works with the CVA bootstrap's pkg manager.
 *   • step_BashrcFix() writes to the correct HOME ($PREFIX/home or filesDir).
 *
 * LAUNCH MODES:
 *   LAUNCH_MODE_AUTO   — called by TerminalManager on broken boot
 *   LAUNCH_MODE_MANUAL — user presses "Doctor" button in toolbar
 */
public class TmuxDoctorActivity extends Activity {

    private static final String TAG = "TmuxDoctor";

    public static final String EXTRA_MODE         = "doctor_mode";
    public static final String LAUNCH_MODE_AUTO   = "auto";
    public static final String LAUNCH_MODE_MANUAL = "manual";

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final int COL_GREEN   = Color.parseColor("#00FF41");
    private static final int COL_CYAN    = Color.parseColor("#00FFFF");
    private static final int COL_MAGENTA = Color.parseColor("#FF44FF");
    private static final int COL_YELLOW  = Color.parseColor("#FFD700");
    private static final int COL_RED     = Color.parseColor("#FF3333");
    private static final int COL_BLUE    = Color.parseColor("#4488FF");
    private static final int COL_DIM     = Color.parseColor("#555555");
    private static final int COL_WHITE   = Color.parseColor("#EEEEEE");
    private static final int COL_BG      = Color.parseColor("#000000");
    private static final int COL_BG2     = Color.parseColor("#001400");

    // ── Doctor steps ──────────────────────────────────────────────────────────
    private enum StepState { PENDING, RUNNING, OK, WARN, FAIL, SKIP }

    private static class DoctorStep {
        final String tag, label;
        StepState state = StepState.PENDING;
        DoctorStep(String tag, String label) { this.tag = tag; this.label = label; }
    }

    private final DoctorStep[] STEPS = {
            new DoctorStep("ENV_SCAN",    "Scanning shell environment"),
            new DoctorStep("BOOTSTRAP",   "Termux bootstrap / BusyBox"),
            new DoctorStep("BASH_FIX",    "Writing .bashrc + CVA prompt"),
            new DoctorStep("TMUX_CONF",   "Writing .tmux.conf"),
            new DoctorStep("TPM",         "Tmux Plugin Manager (TPM)"),
            new DoctorStep("PKG_INSTALL", "Essential packages"),
            new DoctorStep("PATH_FIX",    "PATH / TERM environment"),
            new DoctorStep("ECHO_TEST",   "ANSI / Unicode render test"),
    };

    // ── UI refs ───────────────────────────────────────────────────────────────
    private TextView     tvOutput;
    private TextView     tvStep;
    private ScrollView   svOutput;
    private ProgressBar  progressBar;
    private Button       btnRun;
    private Button       btnReset;
    private Button       btnClose;
    private LinearLayout stepsPanel;

    private final Handler          mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService  executor    = Executors.newSingleThreadExecutor();
    private final SpannableStringBuilder outputBuf = new SpannableStringBuilder();

    private TerminalBootstrap bootstrap;
    private boolean doctorRunning = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(COL_BG);

        bootstrap = new TerminalBootstrap(this);
        buildUi();
        printBanner();

        String mode = getIntent().getStringExtra(EXTRA_MODE);
        if (LAUNCH_MODE_AUTO.equals(mode)) {
            mainHandler.postDelayed(this::runDoctor, 600);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ── UI Builder ─────────────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COL_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(COL_BG2);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));

        TextView tvTitle = makeTv("⚕  CVA TmuxDoctor v3.0", 13, COL_MAGENTA, true);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(tvTitle);

        tvStep = makeTv("IDLE", 10, COL_DIM, false);
        header.addView(tvStep);
        root.addView(header);

        stepsPanel = new LinearLayout(this);
        stepsPanel.setOrientation(LinearLayout.HORIZONTAL);
        stepsPanel.setBackgroundColor(Color.parseColor("#000A00"));
        stepsPanel.setPadding(dp(4), dp(2), dp(4), dp(2));
        refreshStepsPanel();
        root.addView(stepsPanel);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(STEPS.length);
        progressBar.setProgress(0);
        progressBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(COL_GREEN));
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));
        root.addView(progressBar);

        svOutput = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        svOutput.setLayoutParams(scrollParams);
        svOutput.setBackgroundColor(COL_BG);

        tvOutput = new TextView(this);
        tvOutput.setTypeface(Typeface.MONOSPACE);
        tvOutput.setTextSize(11f);
        tvOutput.setTextColor(COL_GREEN);
        tvOutput.setBackgroundColor(COL_BG);
        tvOutput.setPadding(dp(8), dp(6), dp(8), dp(6));
        tvOutput.setTextIsSelectable(true);
        tvOutput.setText("");
        svOutput.addView(tvOutput);
        root.addView(svOutput);

        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setBackgroundColor(COL_BG2);
        btnBar.setPadding(dp(4), dp(4), dp(4), dp(4));

        btnRun = makeBtn("⚕  RUN DOCTOR", COL_GREEN, COL_BG);
        btnRun.setOnClickListener(v -> { if (!doctorRunning) runDoctor(); });
        btnBar.addView(btnRun, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2f));

        btnReset = makeBtn("↺ RESET", COL_YELLOW, COL_BG);
        btnReset.setOnClickListener(v -> resetOutput());
        btnBar.addView(btnReset, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnClose = makeBtn("✕ CLOSE", COL_RED, COL_BG);
        btnClose.setOnClickListener(v -> finish());
        btnBar.addView(btnClose, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(btnBar);
        setContentView(root);
    }

    // ── Doctor Engine ──────────────────────────────────────────────────────────

    private void runDoctor() {
        if (doctorRunning) return;
        doctorRunning = true;
        resetStepStates();
        btnRun.setEnabled(false);

        executor.execute(() -> {
            try {
                step_EnvScan();
                step_Bootstrap();   // ← renamed from step_BusyBox; tries Termux first
                step_BashrcFix();
                step_TmuxConf();
                step_TpmInstall();
                step_PkgInstall();
                step_PathFix();
                step_EchoTest();
                doctorComplete();
            } catch (Exception e) {
                log(COL_RED, "FATAL", "Unhandled error: " + e.getMessage());
                Log.e(TAG, "doctor crash", e);
            } finally {
                mainHandler.post(() -> {
                    doctorRunning = false;
                    btnRun.setEnabled(true);
                });
            }
        });
    }

    // ── Step 1: Environment Scan ──────────────────────────────────────────────

    private void step_EnvScan() {
        startStep(0, "Scanning environment…");

        log(COL_CYAN, "ENV", "Android API " + android.os.Build.VERSION.SDK_INT
                + "  ABI=" + TermuxShellUtils.getArch());
        log(COL_CYAN, "ENV", "filesDir  = " + getFilesDir().getAbsolutePath());
        log(COL_CYAN, "ENV", "PREFIX    = " + TermuxConstants.PREFIX_PATH);
        log(COL_CYAN, "ENV", "HOME      = " + TermuxConstants.HOME_PATH);
        log(COL_CYAN, "ENV", "nativeLib = " + getApplicationInfo().nativeLibraryDir);

        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            log(COL_GREEN, "ENV", "CVA Bootstrap ✓  " + TermuxConstants.BASH_PATH);
        } else if (TermuxShellUtils.isTermuxInstalled()) {
            log(COL_GREEN, "ENV", "Real Termux ✓  " + TermuxConstants.TERMUX_BASH);
        } else {
            log(COL_YELLOW, "ENV", "No Termux bootstrap — will install");
        }

        log(COL_CYAN, "ENV", "/system/bin/sh  exists=" + new File("/system/bin/sh").exists());

        if (bootstrap.hasNativeBusybox()) {
            log(COL_GREEN, "ENV", "jniLibs busybox ✓  (nativeLibraryDir)");
        } else if (bootstrap.hasDownloadedBusybox()) {
            log(COL_YELLOW, "ENV", "Downloaded busybox found (code_cache)");
        }

        doneStep(0, StepState.OK);
    }

    // ── Step 2: Bootstrap / BusyBox ───────────────────────────────────────────

    private void step_Bootstrap() {
        startStep(1, "Termux bootstrap / BusyBox…");

        // Already have full bootstrap
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            log(COL_GREEN, "BOOTSTRAP", "CVA bash ✓  " + TermuxConstants.BASH_PATH);
            doneStep(1, StepState.OK);
            return;
        }
        if (TermuxShellUtils.isTermuxInstalled()) {
            log(COL_GREEN, "BOOTSTRAP", "Real Termux ✓  " + TermuxConstants.TERMUX_BASH);
            doneStep(1, StepState.OK);
            return;
        }
        if (bootstrap.hasNativeBusybox()) {
            // FIX: hasNativeBusybox() just checks the file exists and is >500KB.
            // The .so is a shared library — it cannot be exec()'d as a shell directly.
            // hasNativeBusyboxShell() actually runs it to confirm it works.
            if (bootstrap.hasNativeBusyboxShell()) {
                log(COL_GREEN, "BOOTSTRAP", "libbusybox.so ✓ (exec-tested via linker)");
            } else {
                log(COL_YELLOW, "BOOTSTRAP", "libbusybox.so found but NOT executable as shell (ET_DYN .so) — will download standalone busybox");
            }
            doneStep(1, bootstrap.hasNativeBusyboxShell() ? StepState.OK : StepState.WARN);
            if (!bootstrap.hasNativeBusyboxShell()) {
                // Fall through to download a real standalone busybox
            } else {
                return;
            }
        }
        if (bootstrap.hasDownloadedBusybox()) {
            File f = bootstrap.getDownloadedBusybox();
            log(COL_GREEN, "BOOTSTRAP", "busybox ✓  (" + (f.length()/1024) + " KB)");
            doneStep(1, StepState.OK);
            return;
        }

        // Nothing — try Termux bootstrap first
        log(COL_YELLOW, "BOOTSTRAP", "Nothing installed — trying Termux bootstrap…");
        log(COL_CYAN,   "BOOTSTRAP", "ABI: " + TermuxShellUtils.getArch());
        log(COL_CYAN,   "BOOTSTRAP", "URL: " + TermuxShellUtils.getBootstrapUrl());

        final boolean[] success = { false };
        final Object lock = new Object();

        TermuxInstaller installer = new TermuxInstaller(this);
        installer.install(new TermuxInstaller.InstallCallback() {
            @Override public void onProgress(String msg) { log(COL_BLUE, "DL", msg); }
            @Override public void onSuccess() {
                success[0] = true;
                log(COL_GREEN, "BOOTSTRAP", "Termux bootstrap installed ✓");
                log(COL_GREEN, "BOOTSTRAP", "bash: " + TermuxConstants.BASH_PATH);
                synchronized (lock) { lock.notifyAll(); }
            }
            @Override public void onFailure(String error) {
                log(COL_YELLOW, "BOOTSTRAP", "Bootstrap failed: " + error);
                log(COL_YELLOW, "BOOTSTRAP", "Trying BusyBox fallback…");
                // Fall through to BusyBox download
                synchronized (lock) { lock.notifyAll(); }
            }
        });

        synchronized (lock) {
            try { lock.wait(120_000); } catch (InterruptedException ignored) {}
        }

        if (success[0]) {
            doneStep(1, StepState.OK);
            return;
        }

        // Termux bootstrap failed — try BusyBox
        log(COL_YELLOW, "BOOTSTRAP", "Downloading BusyBox as fallback…");
        final boolean[] bbOk = { false };
        final Object bbLock = new Object();

        bootstrap.install(new TerminalBootstrap.ProgressCallback() {
            @Override public void onProgress(String msg) { log(COL_BLUE, "DL", msg); }
            @Override public void onDone(boolean ok, String path) {
                bbOk[0] = ok;
                if (ok) log(COL_GREEN, "BOOTSTRAP", "BusyBox downloaded ✓  → " + path);
                else    log(COL_RED,   "BOOTSTRAP", "BusyBox download FAILED — using /system/bin/sh");
                synchronized (bbLock) { bbLock.notifyAll(); }
            }
        });

        synchronized (bbLock) {
            try { bbLock.wait(90_000); } catch (InterruptedException ignored) {}
        }

        doneStep(1, bbOk[0] ? StepState.WARN : StepState.FAIL);
    }

    // ── Step 3: Bashrc ────────────────────────────────────────────────────────

    private void step_BashrcFix() {
        startStep(2, "Writing .bashrc…");
        try {
            bootstrap.ensureBashrc();

            // Determine correct HOME
            File home;
            if (TermuxShellUtils.isCvaBootstrapInstalled()) {
                home = new File(TermuxConstants.HOME_PATH);
            } else if (TermuxShellUtils.isTermuxInstalled()) {
                home = new File(TermuxConstants.TERMUX_HOME);
            } else {
                home = getFilesDir();
            }

            File bashrc = new File(home, ".bashrc");
            if (bashrc.exists() && bashrc.length() > 100) {
                log(COL_GREEN, "BASHRC", "Written ✓  " + bashrc.getAbsolutePath()
                        + " (" + bashrc.length() + " bytes)");
                log(COL_CYAN,    "BASHRC", "PS1: ┌─×××××[TIME]§§§§§§[{CVA}✓]:::::::[N-wings]");
                doneStep(2, StepState.OK);
            } else {
                writeFallbackBashrc(home);
                doneStep(2, StepState.WARN);
            }
        } catch (Exception e) {
            log(COL_RED, "BASHRC", "Error: " + e.getMessage());
            doneStep(2, StepState.FAIL);
        }
    }

    private void writeFallbackBashrc(File home) throws IOException {
        home.mkdirs();
        String bb = bootstrap.isInstalled() && bootstrap.getBusyboxFile() != null
                ? bootstrap.getBusyboxFile().getAbsolutePath() : "/system/bin/sh";
        String binPath = TermuxShellUtils.isCvaBootstrapInstalled()
                ? TermuxConstants.PREFIX_BIN + ":/system/bin:/system/xbin"
                : getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin";

        File bashrc = new File(home, ".bashrc");
        try (FileOutputStream fos = new FileOutputStream(bashrc)) {
            fos.write(("# CVA .bashrc — TmuxDoctor\n"
                    + "case $- in *i*) ;; *) return;; esac\n"
                    + "export PATH=\"" + binPath + "\"\n"
                    + "export HOME=\"" + home.getAbsolutePath() + "\"\n"
                    + "export TERM=xterm-256color\n"
                    + "export BUSYBOX=\"" + bb + "\"\n"
                    + "alias ll='ls -alF --color=auto'\n"
                    + "alias cls='clear'\n").getBytes("UTF-8"));
        }
        log(COL_YELLOW, "BASHRC", "Fallback .bashrc written to " + bashrc.getAbsolutePath());
    }

    // ── Step 4: tmux.conf ─────────────────────────────────────────────────────

    private void step_TmuxConf() {
        startStep(3, "Writing .tmux.conf…");

        // Write to the correct HOME
        File home = TermuxShellUtils.isCvaBootstrapInstalled()
                ? new File(TermuxConstants.HOME_PATH)
                : TermuxShellUtils.isTermuxInstalled()
                ? new File(TermuxConstants.TERMUX_HOME)
                : getFilesDir();
        home.mkdirs();

        File tmuxConf = new File(home, ".tmux.conf");
        try (FileOutputStream fos = new FileOutputStream(tmuxConf)) {
            fos.write(buildTmuxConf().getBytes("UTF-8"));
        } catch (IOException e) {
            log(COL_RED, "TMUX.CONF", "Write failed: " + e.getMessage());
            doneStep(3, StepState.FAIL);
            return;
        }
        log(COL_GREEN, "TMUX.CONF", "Written ✓  (" + tmuxConf.length() + " bytes)");
        log(COL_CYAN,  "TMUX.CONF", "Features: mouse, 256-color, vim-keys, CVA status");
        doneStep(3, StepState.OK);
    }

    private String buildTmuxConf() {
        return "# CVA .tmux.conf — TmuxDoctor v3.0\n"
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
                + "set -g status-style 'bg=#000A00 fg=#00FF41'\n"
                + "set -g status-left  '#[fg=#FF44FF,bold]§§[#[fg=#00FF41]{CVA}#[fg=#4488FF]✓#[fg=#FF44FF]]§§  '\n"
                + "set -g status-right '#[fg=#FFD700]%H:%M  #[fg=#00FF41,bold]#H'\n"
                + "setw -g window-status-current-format '#[fg=#000000,bg=#00FF41,bold] #I:#W#F '\n"
                + "set -g pane-active-border-style 'fg=#00FF41'\n"
                + "run '~/.tmux/plugins/tpm/tpm'\n";
    }

    // ── Step 5: TPM ───────────────────────────────────────────────────────────

    private void step_TpmInstall() {
        startStep(4, "Tmux Plugin Manager…");
        if (!commandExists("git")) {
            log(COL_YELLOW, "TPM", "git not found — skipping (install via pkg/apt)");
            doneStep(4, StepState.SKIP);
            return;
        }

        File home = TermuxShellUtils.isCvaBootstrapInstalled()
                ? new File(TermuxConstants.HOME_PATH)
                : TermuxShellUtils.isTermuxInstalled()
                ? new File(TermuxConstants.TERMUX_HOME)
                : getFilesDir();
        File tpmDir = new File(home, ".tmux/plugins/tpm");

        if (tpmDir.exists()) {
            log(COL_GREEN, "TPM", "Already installed ✓");
            runShellCommand("git -C " + tpmDir.getAbsolutePath() + " pull --quiet", 30_000);
            log(COL_GREEN, "TPM", "Updated ✓");
            doneStep(4, StepState.OK);
            return;
        }

        log(COL_CYAN, "TPM", "Cloning tmux-plugins/tpm…");
        tpmDir.getParentFile().mkdirs();
        List<String> out = runShellCommand(
                "git clone --depth=1 https://github.com/tmux-plugins/tpm "
                        + tpmDir.getAbsolutePath(), 60_000);
        for (String l : out) log(COL_BLUE, "GIT", l);

        if (tpmDir.exists()) {
            log(COL_GREEN, "TPM", "Installed ✓  (prefix+I to load plugins)");
            doneStep(4, StepState.OK);
        } else {
            log(COL_RED, "TPM", "Clone failed — check internet");
            doneStep(4, StepState.FAIL);
        }
    }

    // ── Step 6: Package install ───────────────────────────────────────────────

    private void step_PkgInstall() {
        startStep(5, "Essential packages…");

        boolean hasCvaPkg  = TermuxShellUtils.isCvaBootstrapInstalled()
                && new File(TermuxConstants.PREFIX_BIN + "/pkg").exists();
        boolean hasTermux  = new File("/data/data/com.termux/files/usr/bin/pkg").exists();
        boolean hasApt     = commandExists("apt-get");
        boolean hasPkg     = hasCvaPkg || hasTermux || commandExists("pkg");

        if (!hasPkg && !hasApt) {
            log(COL_YELLOW, "PKG", "No package manager — skipping");
            log(COL_CYAN,   "PKG", "Tip: install Termux or wait for CVA bootstrap to complete");
            doneStep(5, StepState.SKIP);
            return;
        }

        // FIX: always use full path and include APT_CONFIG / DPKG_ADMINDIR in env.
        // The bootstrap wrapper scripts for pkg/apt may have stale com.termux shebangs.
        // Using the full path to the binary (not the wrapper) is safer.
        String pkgMgr;
        if (hasCvaPkg) {
            pkgMgr = TermuxConstants.PREFIX_BIN + "/pkg";
        } else if (hasTermux) {
            pkgMgr = "/data/data/com.termux/files/usr/bin/pkg";
        } else if (hasApt) {
            pkgMgr = TermuxConstants.PREFIX_BIN + "/apt-get";
        } else {
            pkgMgr = "pkg";
        }
        String[] packages = {
                "tmux", "vim", "git", "python3", "nano",
                "htop", "curl", "wget", "openssh", "tree", "zip", "unzip"
        };

        log(COL_CYAN, "PKG", "Manager: " + pkgMgr + "  updating repos…");
        runShellCommand(pkgMgr + " update -y 2>&1 | tail -3", 60_000)
                .forEach(l -> { if (!l.trim().isEmpty()) log(COL_BLUE, "PKG", l); });

        int installed = 0, failed = 0;
        for (String pkg : packages) {
            List<String> result = runShellCommand(
                    pkgMgr + " install -y " + pkg + " 2>&1 | tail -2", 60_000);
            boolean ok = commandExists(pkg)
                    || result.stream().anyMatch(l -> l.contains("installed")
                    || l.contains("upgraded") || l.contains("already"));
            log(ok ? COL_GREEN : COL_YELLOW, "PKG", pkg + (ok ? "  ✓" : "  ✗"));
            if (ok) installed++; else failed++;
        }

        log(COL_GREEN, "PKG", installed + " installed, " + failed + " skipped");
        doneStep(5, failed == 0 ? StepState.OK : StepState.WARN);
    }

    // ── Step 7: PATH fix ─────────────────────────────────────────────────────

    private void step_PathFix() {
        startStep(6, "Environment variables…");

        File home = TermuxShellUtils.isCvaBootstrapInstalled()
                ? new File(TermuxConstants.HOME_PATH)
                : TermuxShellUtils.isTermuxInstalled()
                ? new File(TermuxConstants.TERMUX_HOME)
                : getFilesDir();
        home.mkdirs();
        new File(home, "bin").mkdirs();

        File profile = new File(home, ".bash_profile");
        try (FileOutputStream fos = new FileOutputStream(profile)) {
            fos.write("# CVA .bash_profile\n[ -f ~/.bashrc ] && . ~/.bashrc\n"
                    .getBytes("UTF-8"));
            log(COL_GREEN, "PATH", ".bash_profile → sources .bashrc ✓");
        } catch (IOException e) {
            log(COL_YELLOW, "PATH", ".bash_profile: " + e.getMessage());
        }

        log(COL_GREEN, "PATH", "TERM=xterm-256color ✓");
        log(COL_GREEN, "PATH", "HOME=" + home.getAbsolutePath() + " ✓");
        if (TermuxShellUtils.isCvaBootstrapInstalled()) {
            log(COL_GREEN, "PATH", "PREFIX=" + TermuxConstants.PREFIX_PATH + " ✓");
        }
        doneStep(6, StepState.OK);
    }

    // ── Step 8: ANSI / Unicode test ───────────────────────────────────────────

    private void step_EchoTest() {
        startStep(7, "ANSI / Unicode render test…");
        sleep(200);
        log(COL_GREEN,   "TEST", "✔  Green   (\\e[92m)");
        log(COL_RED,     "TEST", "✔  Red     (\\e[91m)");
        log(COL_CYAN,    "TEST", "✔  Cyan    (\\e[96m)");
        log(COL_YELLOW,  "TEST", "✔  Yellow  (\\e[93m)");
        log(COL_MAGENTA, "TEST", "✔  Magenta (\\e[95m)");
        log(COL_BLUE,    "TEST", "✔  Blue    (\\e[94m)");
        log(COL_WHITE,   "TEST", "┌─┐└─┘│█░  Box drawing");
        log(COL_WHITE,   "TEST", "✓✗►★♞☣™≈    Symbols");
        doneStep(7, StepState.OK);
    }

    // ── Completion ────────────────────────────────────────────────────────────

    private void doctorComplete() {
        sleep(300);
        mainHandler.post(() -> {
            appendLine("");
            appendStyled("═══════════════════════════════════════\n", COL_MAGENTA, false);
            appendStyled("  CVA TmuxDoctor COMPLETE  ✓\n", COL_GREEN, true);
            appendStyled("  Terminal healed & ready\n", COL_CYAN, false);
            if (TermuxShellUtils.isCvaBootstrapInstalled()) {
                appendStyled("  Shell: bash (Termux bootstrap)\n", COL_GREEN, false);
            }
            appendStyled("═══════════════════════════════════════\n", COL_MAGENTA, false);
            appendLine("");
            appendStyled("  Restart terminal or tap ↺\n", COL_YELLOW, false);
            tvStep.setText("DONE ✓");
            tvStep.setTextColor(COL_GREEN);
            progressBar.setProgress(STEPS.length);
        });
    }

    // ── Shell utilities ───────────────────────────────────────────────────────

    private List<String> runShellCommand(String cmd, long timeoutMs) {
        List<String> lines = new ArrayList<>();
        String shell = resolveBestShell();
        try {
            ProcessBuilder pb = new ProcessBuilder(shell, "-c", cmd);
            pb.environment().put("TERM",   "xterm-256color");
            pb.environment().put("LANG",   "en_US.UTF-8");
            pb.environment().put("HOME",   resolveBestHome());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            if (TermuxShellUtils.isCvaBootstrapInstalled()) {
                String pfx = TermuxConstants.PREFIX_PATH;
                pb.environment().put("PREFIX", pfx);
                // System tools (am, pm, cmd) MUST come before PREFIX_BIN so we get
                // the real /system/bin binary, not the stale com.termux wrapper.
                pb.environment().put("PATH",
                        "/system/bin:/system/xbin:" + TermuxConstants.PREFIX_BIN);
                pb.environment().put("LD_LIBRARY_PATH", TermuxConstants.PREFIX_LIB);
                // FIX: apt.conf redirect — without this, apt reads com.termux paths → EACCES
                java.io.File aptConf = new java.io.File(pfx + "/etc/apt/apt.conf");
                if (aptConf.exists()) pb.environment().put("APT_CONFIG", aptConf.getAbsolutePath());
                pb.environment().put("DPKG_ADMINDIR",            pfx + "/var/lib/dpkg");
                pb.environment().put("DEBIAN_FRONTEND",          "noninteractive");
                pb.environment().put("APT_LISTCHANGES_FRONTEND", "none");
                // FIX: ncurses terminfo path — without this, nano/vim crash on launch
                pb.environment().put("TERMINFO",      pfx + "/share/terminfo");
                pb.environment().put("TERMINFO_DIRS", pfx + "/share/terminfo:/system/lib/terminfo");
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            long deadline = System.currentTimeMillis() + timeoutMs;
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
                if (System.currentTimeMillis() > deadline) break;
            }
            p.waitFor();
        } catch (Exception e) {
            lines.add("[cmd error: " + e.getMessage() + "]");
        }
        return lines;
    }

    private String resolveBestShell() {
        if (TermuxShellUtils.isCvaBootstrapInstalled()) return TermuxConstants.BASH_PATH;
        if (TermuxShellUtils.isTermuxInstalled())       return TermuxConstants.TERMUX_BASH;
        if (bootstrap.hasNativeBusyboxShell())          return bootstrap.getNativeBusybox().getAbsolutePath();
        if (bootstrap.hasDownloadedBusybox())           return bootstrap.getDownloadedBusybox().getAbsolutePath();
        return "/system/bin/sh";
    }

    private String resolveBestHome() {
        if (TermuxShellUtils.isCvaBootstrapInstalled()) return TermuxConstants.HOME_PATH;
        if (TermuxShellUtils.isTermuxInstalled())       return TermuxConstants.TERMUX_HOME;
        return getFilesDir().getAbsolutePath();
    }

    private boolean commandExists(String cmd) {
        List<String> r = runShellCommand("command -v " + cmd + " 2>&1", 5_000);
        return !r.isEmpty() && r.get(0).contains("/");
    }

    // ── Step helpers ──────────────────────────────────────────────────────────

    private void startStep(int idx, String label) {
        STEPS[idx].state = StepState.RUNNING;
        mainHandler.post(() -> {
            tvStep.setText("[" + STEPS[idx].tag + "] " + label);
            tvStep.setTextColor(COL_CYAN);
            progressBar.setProgress(idx);
            refreshStepsPanel();
        });
        appendLine("");
        appendStyled("── " + STEPS[idx].tag + " ── " + label + "\n", COL_CYAN, true);
    }

    private void doneStep(int idx, StepState state) {
        STEPS[idx].state = state;
        int col = state == StepState.OK   ? COL_GREEN  :
                state == StepState.WARN ? COL_YELLOW :
                        state == StepState.FAIL ? COL_RED    :
                                state == StepState.SKIP ? COL_DIM    : COL_WHITE;
        String sym = state == StepState.OK   ? "✓" :
                state == StepState.WARN ? "!" :
                        state == StepState.FAIL ? "✗" :
                                state == StepState.SKIP ? "—" : "?";
        mainHandler.post(() -> { progressBar.setProgress(idx + 1); refreshStepsPanel(); });
        appendStyled("[" + sym + "] " + STEPS[idx].tag + "  " + state.name() + "\n", col, true);
    }

    private void resetStepStates() {
        for (DoctorStep s : STEPS) s.state = StepState.PENDING;
        mainHandler.post(() -> {
            outputBuf.clear();
            tvOutput.setText("");
            progressBar.setProgress(0);
            refreshStepsPanel();
            tvStep.setText("RUNNING…");
            tvStep.setTextColor(COL_CYAN);
        });
        printBanner();
    }

    private void refreshStepsPanel() {
        stepsPanel.removeAllViews();
        for (DoctorStep s : STEPS) {
            int col = s.state == StepState.OK      ? COL_GREEN  :
                    s.state == StepState.WARN     ? COL_YELLOW :
                            s.state == StepState.FAIL     ? COL_RED    :
                                    s.state == StepState.RUNNING  ? COL_CYAN   : COL_DIM;
            String sym = s.state == StepState.OK     ? "✓" :
                    s.state == StepState.WARN   ? "!" :
                            s.state == StepState.FAIL   ? "✗" :
                                    s.state == StepState.RUNNING? "►" :
                                            s.state == StepState.SKIP   ? "—" : "·";
            TextView dot = makeTv(sym, 9, col, false);
            dot.setPadding(dp(3), 0, dp(3), 0);
            stepsPanel.addView(dot);
        }
    }

    // ── Output helpers ────────────────────────────────────────────────────────

    private void printBanner() {
        sleep(100);
        appendStyled(
                "╔══════════════════════════════════════════════╗\n" +
                        "║  CVA TmuxDoctor v3.0  ·  Terminal Healer     ║\n" +
                        "║  §§§§§§§§§§§_{GODKILLER}_§§§§§§§§§§§§        ║\n" +
                        "╚══════════════════════════════════════════════╝\n\n",
                COL_MAGENTA, true);
        appendStyled("Tap  ⚕ RUN DOCTOR  to heal your terminal\n\n", COL_YELLOW, false);
    }

    private void log(int color, String category, String message) {
        mainHandler.post(() -> {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, "[", COL_DIM,   false);
            append(sb, category, color, true);
            append(sb, "] ", COL_DIM,   false);
            append(sb, message + "\n", COL_WHITE, false);
            outputBuf.append(sb);
            tvOutput.setText(outputBuf);
            svOutput.post(() -> svOutput.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void appendLine(String text) {
        mainHandler.post(() -> { outputBuf.append(text + "\n"); tvOutput.setText(outputBuf); });
    }

    private void appendStyled(String text, int color, boolean bold) {
        mainHandler.post(() -> {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            append(sb, text, color, bold);
            outputBuf.append(sb);
            tvOutput.setText(outputBuf);
            svOutput.post(() -> svOutput.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void append(SpannableStringBuilder sb, String text, int color, boolean bold) {
        int s = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), s, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void resetOutput() {
        outputBuf.clear();
        tvOutput.setText("");
        for (DoctorStep s : STEPS) s.state = StepState.PENDING;
        progressBar.setProgress(0);
        mainHandler.post(this::refreshStepsPanel);
        printBanner();
    }

    // ── Widget factories ──────────────────────────────────────────────────────

    private TextView makeTv(String text, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return tv;
    }

    private Button makeBtn(String label, int textColor, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(textColor);
        b.setBackgroundColor(bgColor);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setTextSize(11f);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
        b.setStateListAnimator(null);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}