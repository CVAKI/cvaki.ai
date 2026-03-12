package com.braingods.cva;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TaskAllocationActivity — INVISIBLE coordinator.
 *
 * Zero UI. finish() is called immediately in onCreate() so no window ever
 * appears. All progress is sent directly to SmartOverlayService.addBubble()
 * so everything shows up in the parasite overlay panel the user is already
 * watching.
 *
 *  Brain (terminal) ──► fail x3 ──► Parasite (vision) ──► done
 *
 * All of it streams as coloured bubbles into the parasite panel.
 */
public class TaskAllocationActivity extends Activity {

    private static final String TAG = "TaskAlloc";

    // ── Colours (match SmartOverlayService palette) ───────────────────────────
    private static final int C_BRAIN    = Color.parseColor("#00BCD4");
    private static final int C_PARASITE = Color.parseColor("#E040FB");
    private static final int C_SUCCESS  = Color.parseColor("#4CAF50");
    private static final int C_ERROR    = Color.parseColor("#F44336");
    private static final int C_PENDING  = Color.parseColor("#78909C");
    private static final int C_TEXT     = Color.parseColor("#E0E0E0");
    private static final int C_ACCENT   = Color.parseColor("#FF6D00");

    private static final int BRAIN_MAX_FAILS = 3;

    // ── Static parasite callback ──────────────────────────────────────────────
    public interface ParasiteResultCallback {
        void onParasiteResult(int subtaskIndex, boolean success, String message);
    }
    private static volatile ParasiteResultCallback sParasiteCallback;

    /** Called by SmartOverlayService when a visual subtask finishes */
    public static void reportParasiteResult(int subtaskIndex, boolean success, String msg) {
        ParasiteResultCallback cb = sParasiteCallback;
        if (cb != null) cb.onParasiteResult(subtaskIndex, success, msg);
    }

    // ── SubTask model ─────────────────────────────────────────────────────────
    public enum SubTaskPhase { PENDING, BRAIN, PARASITE, DONE, ERROR }

    public static class SubTask {
        final int    index;
        final String description;
        SubTaskPhase phase = SubTaskPhase.PENDING;
        String       result = "";
        SubTask(int index, String description) {
            this.index = index;
            this.description = description;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    // Use appCtx (application context) so they're safe after finish()
    private Context          appCtx;
    private String           task;
    private String           apiKey;
    private String           provider;
    private BrainAgent       brainAgent;
    private ExecutorService  executor;
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());
    private final List<SubTask> subtasks = new ArrayList<>();
    private int              currentIndex = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        appCtx = getApplicationContext();

        task = getIntent().getStringExtra("task");
        if (task == null || task.trim().isEmpty()) {
            finish();
            return;
        }

        SharedPreferences prefs = appCtx.getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        provider = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);
        switch (provider) {
            case BrainAgent.PROVIDER_GEMINI:
                apiKey = prefs.getString("key_gemini", ""); break;
            case BrainAgent.PROVIDER_OPENROUTER:
                apiKey = prefs.getString("key_openrouter", ""); break;
            case BrainAgent.PROVIDER_GROQ:
                apiKey = prefs.getString("key_groq", ""); break;
            default:
                apiKey = prefs.getString("key_anthropic",
                        prefs.getString("api_key", "")); break;
        }

        executor = Executors.newSingleThreadExecutor();

        // ── Close the window immediately — we are completely invisible ────────
        finish();

        // ── Ensure parasite overlay is running so we have somewhere to log ────
        ensureParasiteRunning();

        // ── Register parasite result callback ─────────────────────────────────
        sParasiteCallback = (idx, ok, msg) ->
                mainHandler.post(() -> onParasiteResult(idx, ok, msg));

        // ── Init brain agent ──────────────────────────────────────────────────
        initBrainAgent();

        // ── Announce in the parasite bubble ───────────────────────────────────
        bubble("═══════════════════════════", C_ACCENT);
        bubble("⚡ TASK: " + task, C_ACCENT);
        bubble("🔑 " + provider, C_PENDING);
        bubble("🧠 Analysing subtasks…", C_BRAIN);
        Log.i(TAG, "launch task=\"" + task + "\" provider=" + provider);

        // ── Kick off breakdown in background ──────────────────────────────────
        executor.submit(this::breakdownTask);
    }

    @Override
    protected void onDestroy() {
        // finish() was called in onCreate so onDestroy fires almost immediately —
        // BEFORE any subtask has run. Do NOT clear sParasiteCallback here or the
        // parasite can never report back. Cleanup is done in onAllDone() instead.
        super.onDestroy();
    }

    // ── Ensure parasite is alive ──────────────────────────────────────────────

    private void ensureParasiteRunning() {
        if (SmartOverlayService.instance != null) {
            Log.d(TAG, "Parasite already running ✓");
            return;
        }
        Log.d(TAG, "Parasite not running — starting via ScreenCapturePermissionActivity");
        Intent i = new Intent(appCtx, ScreenCapturePermissionActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        i.putExtra("task",     task);
        i.putExtra("apiKey",   apiKey);
        i.putExtra("provider", provider);
        appCtx.startActivity(i);

        // Wait up to 5 s for the service to initialise
        for (int w = 0; w < 50; w++) {
            if (SmartOverlayService.instance != null) break;
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        if (SmartOverlayService.instance == null) {
            Log.w(TAG, "Parasite did not start in time — bubbles will be lost");
        }
    }

    // ── Task breakdown ────────────────────────────────────────────────────────

    private void breakdownTask() {
        Log.i(TAG, "breakdownTask START");
        String prompt =
                "You are a task planner for an Android automation agent.\n" +
                        "Break this task into 2-5 ordered subtasks. Each subtask must be\n" +
                        "achievable by a shell command or a visual UI tap/type on Android.\n\n" +
                        "Task: \"" + task + "\"\n\n" +
                        "Return ONLY a JSON array of subtask description strings.\n" +
                        "Example: [\"Open Google Chrome\",\"Tap the address bar\",\"Type search query\"]\n" +
                        "No extra text, no markdown, no code fences.";

        List<String> raw = new ArrayList<>();
        try {
            BrainAgent helper = new BrainAgent(provider, apiKey, appCtx);
            String resp = helper.chatOnce(prompt);
            if (resp != null) {
                resp = resp.trim();
                if (resp.startsWith("```"))
                    resp = resp.replaceAll("(?s)^```[a-z]*\\n?", "")
                            .replaceAll("```$", "").trim();
                JSONArray arr = new JSONArray(resp);
                for (int i = 0; i < arr.length(); i++) raw.add(arr.getString(i).trim());
            }
        } catch (Exception e) {
            Log.w(TAG, "Breakdown failed, single subtask: " + e.getMessage());
        }
        if (raw.isEmpty()) raw.add(task);

        for (int i = 0; i < raw.size(); i++) {
            SubTask st = new SubTask(i, raw.get(i));
            subtasks.add(st);
            bubble("  [" + (i + 1) + "] " + st.description, C_TEXT);
            Log.i(TAG, "  subtask[" + i + "]: " + st.description);
        }
        bubble("───────────────────────────", C_PENDING);

        mainHandler.post(() -> executeSubtask(0));
    }

    // ── Subtask execution ─────────────────────────────────────────────────────

    private void executeSubtask(int index) {
        if (index >= subtasks.size()) {
            onAllDone();
            return;
        }
        SubTask st = subtasks.get(index);
        currentIndex = index;
        st.phase = SubTaskPhase.BRAIN;
        Log.i(TAG, "executeSubtask[" + index + "] BRAIN: " + st.description);

        bubble("▶ [" + (st.index + 1) + "/" + subtasks.size() + "] 🧠 Brain — "
                + st.description, C_BRAIN);
        executor.submit(() -> runBrainPhase(st));
    }

    private void runBrainPhase(SubTask st) {
        try {
            brainAgent.setParasiteEscalation((failedTask, lastError, failCount) -> {
                bubble("⛕ Brain gave up (" + failCount + " fails) → parasite", C_PARASITE);
                if (lastError != null && !lastError.isEmpty())
                    bubble("  ↳ " + truncate(lastError, 100), C_ERROR);
                escalateToParasite(st);
            });

            String result = brainAgent.chat(st.description);

            if (result != null && result.contains(BrainAgent.ESCALATED_MARKER)) {
                return; // escalation callback already fired
            }

            boolean ok = isSuccess(result);
            mainHandler.post(() -> {
                if (ok) {
                    st.phase  = SubTaskPhase.DONE;
                    st.result = truncate(result, 120);
                    bubble("✅ [" + (st.index + 1) + "] done via terminal", C_SUCCESS);
                    executeSubtask(st.index + 1);
                } else {
                    bubble("⚠ Terminal result uncertain → parasite", C_PARASITE);
                    escalateToParasite(st);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Brain phase error", e);
            mainHandler.post(() -> escalateToParasite(st));
        }
    }

    private void escalateToParasite(SubTask st) {
        Log.i(TAG, "escalateToParasite[" + st.index + "]: " + st.description);
        st.phase = SubTaskPhase.PARASITE;
        bubble("▶ [" + (st.index + 1) + "/" + subtasks.size() + "] 👁 Parasite — "
                + st.description, C_PARASITE);

        Intent svc = new Intent(appCtx, SmartOverlayService.class);
        svc.putExtra("task",         st.description);
        svc.putExtra("subtaskIndex", st.index);
        svc.putExtra("reportBack",   true);
        appCtx.startService(svc);
    }

    private void onParasiteResult(int idx, boolean success, String message) {
        Log.i(TAG, "parasiteResult[" + idx + "] ok=" + success);
        if (idx < 0 || idx >= subtasks.size()) return;

        SubTask st = subtasks.get(idx);
        st.phase  = success ? SubTaskPhase.DONE : SubTaskPhase.ERROR;
        st.result = truncate(message, 120);

        bubble(success
                        ? "✅ [" + (idx + 1) + "] done via parasite"
                        : "❌ [" + (idx + 1) + "] failed: " + st.result,
                success ? C_SUCCESS : C_ERROR);

        executeSubtask(idx + 1);
    }

    private void onAllDone() {
        long done = subtasks.stream().filter(s -> s.phase == SubTaskPhase.DONE).count();
        boolean allOk = done == subtasks.size();
        String summary = allOk
                ? "✅ All " + subtasks.size() + " subtasks complete"
                : "⚠ " + done + "/" + subtasks.size() + " subtasks completed";

        bubble("═══════════════════════════", C_ACCENT);
        bubble(summary, allOk ? C_SUCCESS : C_ERROR);
        bubble("═══════════════════════════", C_ACCENT);
        Log.i(TAG, "all done: " + summary);

        CvakiStorage.saveMain(appCtx, "last_task_result",
                System.currentTimeMillis() + "|" + summary + "|" + task);

        sParasiteCallback = null;
        executor.shutdown();
    }

    // ── BrainAgent init ───────────────────────────────────────────────────────

    private void initBrainAgent() {
        brainAgent = new BrainAgent(provider, apiKey, appCtx);
        // Stream every brain step live into the parasite bubble
        brainAgent.setAgentCallback((msg, isRunning, scroll) -> {
            if (msg != null && !msg.isEmpty()) {
                bubble((isRunning ? "  ⚙ " : "  → ") + truncate(msg, 120),
                        isRunning ? C_BRAIN : C_TEXT);
            }
        });

        // Wire the parasite's TerminalManager so shell commands actually work.
        // Without this every command returns "[Error: Terminal not available]"
        // and BrainAgent immediately escalates to parasite (which is fine —
        // but wastes one AI call). With the terminal wired, brain genuinely
        // tries shell first for tasks where that's faster (get battery, ls, etc.)
        SmartOverlayService svc = SmartOverlayService.instance;
        if (svc != null && svc.terminalManager != null) {
            brainAgent.setTerminalManager(svc.terminalManager);
            bubble("  ⚡ Terminal wired ✓", C_BRAIN);
            Log.d(TAG, "terminal wired from SmartOverlayService");
        } else {
            bubble("  ⚡ No terminal — parasite handles all UI tasks", C_PENDING);
            Log.d(TAG, "no terminal available — parasite will handle all subtasks");
        }
    }

    // ── Bubble — the ONLY output channel ─────────────────────────────────────

    /**
     * Send a coloured bubble to SmartOverlayService panel.
     * This is the ONLY output path. There is no other UI.
     * Safe to call from any thread.
     */
    private void bubble(String msg, int color) {
        Log.d(TAG, msg);
        SmartOverlayService svc = SmartOverlayService.instance;
        if (svc != null) svc.addBubble(msg, color, false);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static boolean isSuccess(String result) {
        if (result == null || result.isBlank()) return false;
        String l = result.toLowerCase();
        return !l.contains("error") && !l.contains("failed") &&
                !l.contains("not found") && !l.contains("permission denied") &&
                !l.contains("exception") && !l.contains("[escalated") &&
                !l.startsWith("[error");
    }

    // ── Static launcher ───────────────────────────────────────────────────────

    public static void launch(Context ctx, String task) {
        Intent i = new Intent(ctx, TaskAllocationActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        i.putExtra("task", task);
        ctx.startActivity(i);
    }
}