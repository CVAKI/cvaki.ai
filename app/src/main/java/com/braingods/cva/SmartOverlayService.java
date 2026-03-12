package com.braingods.cva;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SmartOverlayService — the "Parasite" guide-dog agent.
 *
 * ─── Architecture ───────────────────────────────────────────────────────────
 *  CVA Brain (BrainAgent) = blind man — tries terminal commands, cannot see screen.
 *  Parasite (this service)= guide dog — captures screen, finds UI, navigates.
 *
 *  When BrainAgent fails BRAIN_MAX_FAILS times it fires ParasiteEscalation.
 *  TaskAllocationActivity routes the subtask here via startService().
 *
 * ─── Grid-based screen memory ────────────────────────────────────────────────
 *  Screen is divided into 10 × 10 = 100 cells.
 *  After every visual scan, found app icon positions are saved into
 *  CvakiStorage (grid tier) so future lookups are instant.
 *
 * ─── App-finding flow ────────────────────────────────────────────────────────
 *  1. Check CvakiStorage.findGridApp()  — instant if previously found
 *  2. Take full screenshot → ask vision AI to locate the target app
 *  3. Store result in grid → animate cursor to target → click
 *  4. If not visible → go HOME → repeat up to MAX_APP_FIND_ATTEMPTS
 *  5. Report result to TaskAllocationActivity.reportParasiteResult()
 *
 * ─── Bug fixes ───────────────────────────────────────────────────────────────
 *  • Toast.makeText() on background thread → crashes.
 *    Fixed: all toasts now posted via mainHandler.
 */
public class SmartOverlayService extends Service {

    private static final String TAG        = "SmartOverlay";
    private static final String CHANNEL_ID = "cva_smart_overlay";
    private static final int    TAP_MAX_PX = 12;
    private static final int    MAX_BUBBLES = 80;

    /** Max attempts to find an app by screenshot before giving up */
    private static final int MAX_APP_FIND_ATTEMPTS = 3;

    public enum State { IDLE, CAPTURING, ANALYZING, EXECUTING, ERROR }

    // ── Views ─────────────────────────────────────────────────────────────────
    private WindowManager              wm;
    private View                       bubbleView;
    private LinearLayout               smartPanel;
    private LinearLayout               llBubbleContainer;
    private ScrollView                 svTerminal;
    private TextView                   tvStatus;
    private TextView                   tvTask;
    private View                       cursorView;
    private WindowManager.LayoutParams bubbleParams;
    private WindowManager.LayoutParams cursorParams;

    private boolean panelVisible = false;

    // ── Static instance — lets TaskAllocationActivity pipe logs into our panel ─
    /** Accessible from any component in the same process. Null when service is not running. */
    public static volatile SmartOverlayService instance;

    // ── Drag state ────────────────────────────────────────────────────────────
    private int   bubbleDragInitX, bubbleDragInitY;
    private float bubbleDragTouchX, bubbleDragTouchY;

    // ── Logic ─────────────────────────────────────────────────────────────────
    private State           currentState = State.IDLE;
    private String          currentTask  = "";
    private String          apiKey       = "";
    private String          provider     = "anthropic";
    private AIVisionEngine  visionEngine;
    private BrainAgent      brainAgent;
    public  TerminalManager terminalManager;
    private ExecutorService executor    = Executors.newSingleThreadExecutor();
    private Handler         mainHandler = new Handler(Looper.getMainLooper());

    // ── TaskAllocation bridge ─────────────────────────────────────────────────
    /**
     * Set by TaskAllocationActivity via startService() intent extras:
     *   "reportBack"   → true if result should be reported back
     *   "subtaskIndex" → index of the subtask being handled
     */
    private int  pendingSubtaskIndex  = -1;
    private boolean reportBack        = false;

    private int screenW, screenH;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;                          // ← register singleton
        createChannel();
        startForeground(2, buildNotification());
        resolveScreenSize();
        showBubble();
        showCursor();

        // Prune expired cache entries on start
        CvakiStorage.pruneCacheExpired(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        apiKey   = prefs.getString("api_key",  "");
        provider = prefs.getString("provider", "anthropic");

        if (intent.hasExtra("apiKey")) {
            String intentKey = intent.getStringExtra("apiKey");
            if (intentKey != null && !intentKey.trim().isEmpty())
                apiKey = intentKey.trim();
        }
        if (intent.hasExtra("provider") && intent.getStringExtra("provider") != null)
            provider = intent.getStringExtra("provider");
        if (intent.hasExtra("task"))
            setTask(intent.getStringExtra("task"));

        // ── TaskAllocation bridge extras ──────────────────────────────────────
        if (intent.hasExtra("reportBack")) {
            reportBack        = intent.getBooleanExtra("reportBack", false);
            pendingSubtaskIndex = intent.getIntExtra("subtaskIndex", -1);

            if (reportBack) {
                addBubble("[alloc] subtask #" + (pendingSubtaskIndex + 1)
                        + " delegated to parasite", Color.parseColor("#E040FB"), false);
            }
        }

        String keyFp = apiKey.isEmpty() ? "EMPTY ❌"
                : apiKey.substring(0, Math.min(8, apiKey.length()))
                + "…"
                + (apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "");
        addBubble("[key] provider=" + provider + "  key=" + keyFp, Color.YELLOW, false);

        // MediaProjection
        if (intent.hasExtra("projResultCode") && intent.hasExtra("projBundle")) {
            int    rc         = intent.getIntExtra("projResultCode", -1);
            Bundle projBundle = intent.getBundleExtra("projBundle");
            Intent projData   = null;
            if (projBundle != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    projData = projBundle.getParcelable("projData", Intent.class);
                else
                    //noinspection deprecation
                    projData = projBundle.getParcelable("projData");
            }

            addBubble("[proj] rc=" + rc + " data=" + (projData == null ? "NULL ❌" : "ok ✓"),
                    projData == null ? Color.RED : Color.CYAN, false);

            if (rc == android.app.Activity.RESULT_OK && projData != null) {
                try {
                    ScreenCaptureManager.init(this, rc, projData);
                    addBubble("[screen] ScreenCaptureManager ready ✓", Color.GREEN, false);
                    setStatus("● ready", State.IDLE);
                    if (visionEngine != null && !currentTask.isEmpty()) {
                        mainHandler.postDelayed(() -> {
                            addBubble("▶ auto-starting vision task…", Color.CYAN, false);
                            if (reportBack) {
                                runVisionTaskWithReport();
                            } else {
                                runVisionTask();
                            }
                        }, 500);
                    }
                } catch (Exception e) {
                    addBubble("[screen] INIT FAILED: " + e.getMessage(), Color.RED, false);
                    setStatus("● error", State.ERROR);
                }
            } else {
                addBubble("[screen] bad token — try again", Color.RED, false);
                setStatus("● bad token", State.ERROR);
            }
        }

        if (!apiKey.isEmpty()) {
            visionEngine = new AIVisionEngine(apiKey, provider);
            addBubble("[engine] AIVisionEngine ready  provider=" + provider, Color.GREEN, false);
        }

        initAgents();

        // If this is a reportBack task and screen is ready, start immediately
        if (reportBack && !currentTask.isEmpty() && ScreenCaptureManager.isReady() && visionEngine != null) {
            mainHandler.postDelayed(this::runVisionTaskWithReport, 300);
        }

        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        instance = null;                          // ← clear singleton
        if (wm != null) {
            try { if (bubbleView != null) wm.removeView(bubbleView); } catch (Exception ignored) {}
            try { if (cursorView != null) wm.removeView(cursorView); } catch (Exception ignored) {}
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    // ── Agent init ────────────────────────────────────────────────────────────

    private void initAgents() {
        if (apiKey.isEmpty()) return;

        terminalManager = new TerminalManager(this, chunk ->
                mainHandler.post(() -> addBubble(chunk.toString().trim(), Color.WHITE, false)));

        brainAgent = new BrainAgent(provider, apiKey, this);
        brainAgent.setTerminalManager(terminalManager);
        brainAgent.setAgentCallback((msg, isRunning, scrollToEnd) ->
                mainHandler.post(() -> {
                    if (msg != null && !msg.isEmpty()) {
                        addBubble(msg, isRunning ? Color.CYAN : Color.GREEN, false);
                    }
                    if (!isRunning) setStatus("● done", State.IDLE);
                }));

        addBubble("[brain] BrainAgent ready ✓", Color.GREEN, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Grid-based App Finder
    // The parasite's core spatial intelligence — knows WHERE things live on screen
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find an app/UI element by name and click it.
     *
     * STRATEGY:
     *  1. Check CvakiStorage grid (instant, no screenshot needed)
     *  2. Screenshot → vision AI locates all icons → store in grid → click
     *  3. Go home → repeat step 2 (up to MAX_APP_FIND_ATTEMPTS)
     *
     * @param appName         e.g. "Camera", "Google Chrome", "Settings"
     * @param onFound         called after successful click (on bg thread)
     * @param onNotFound      called if all attempts fail
     */
    public void findAndClickApp(String appName, Runnable onFound, Runnable onNotFound) {
        addBubble("[grid] 🔍 Looking for: " + appName, Color.parseColor("#E040FB"), false);

        // ── Step 1: Check storage ─────────────────────────────────────────────
        float[] stored = CvakiStorage.findGridApp(this, appName);
        if (stored != null) {
            addBubble("[grid] 📍 " + appName + " cached at (" +
                    Math.round(stored[0]) + "," + Math.round(stored[1]) + ")", Color.GREEN, false);
            performClickWithCursor(stored[0], stored[1], () -> {
                mainHandler.postDelayed(() -> {
                    if (onFound != null) onFound.run();
                }, 400);
            });
            return;
        }

        // ── Step 2: Screenshot scan ───────────────────────────────────────────
        addBubble("[grid] 📷 Not in memory — scanning screen…", Color.CYAN, false);
        scanScreenForApp(appName, 0, onFound, onNotFound);
    }

    /**
     * Take a screenshot and ask the vision AI to find appName.
     * Recursively retries up to MAX_APP_FIND_ATTEMPTS after pressing HOME.
     */
    private void scanScreenForApp(String appName, int attempt,
                                  Runnable onFound, Runnable onNotFound) {
        if (attempt >= MAX_APP_FIND_ATTEMPTS) {
            addBubble("[grid] ❌ " + appName + " not found after " + attempt + " attempts",
                    Color.RED, false);
            if (onNotFound != null) mainHandler.post(onNotFound);
            return;
        }

        if (attempt > 0) {
            // Go HOME before retry so we have a clean view of the launcher
            addBubble("[grid] 🏠 Going home for attempt " + (attempt + 1) + "…", Color.YELLOW, false);
            pressHome(() ->
                    mainHandler.postDelayed(() ->
                            captureAndLocateApp(appName, attempt, onFound, onNotFound), 600));
        } else {
            captureAndLocateApp(appName, attempt, onFound, onNotFound);
        }
    }

    /**
     * Capture screenshot and ask the vision AI to identify all visible app icons,
     * then store them in the grid and click the target if found.
     */
    private void captureAndLocateApp(String appName, int attempt,
                                     Runnable onFound, Runnable onNotFound) {
        if (!ScreenCaptureManager.isReady()) {
            addBubble("[grid] ❌ no screen permission for scan", Color.RED, false);
            if (onNotFound != null) mainHandler.post(onNotFound);
            return;
        }

        // Hide overlay before capture
        mainHandler.post(() -> {
            if (bubbleView != null) bubbleView.setVisibility(View.INVISIBLE);
            if (cursorView != null) cursorView.setVisibility(View.GONE);
        });

        mainHandler.postDelayed(() ->
                ScreenCaptureManager.get().capture(new ScreenCaptureManager.CaptureCallback() {
                    @Override
                    public void onCaptured(Bitmap bitmap) {
                        mainHandler.post(() -> {
                            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        });

                        // Build a specialized prompt to locate the specific app
                        String locatePrompt =
                                "TASK: Find the app icon or button named \"" + appName + "\".\n\n" +
                                        "Scan the ENTIRE screen carefully.\n" +
                                        "If you find it, return:\n" +
                                        "  [{\"type\":\"CLICK\",\"x\":<center_x>,\"y\":<center_y>," +
                                        "\"note\":\"found " + appName + "\"}]\n" +
                                        "If not found, return:\n" +
                                        "  [{\"type\":\"DONE\",\"message\":\"NOT_FOUND:" + appName + "\"}]\n\n" +
                                        "Also: for EVERY icon you see on screen, add a DONE action with message " +
                                        "\"GRID_ENTRY:name:x:y\" so we can cache them all.\n" +
                                        "Return ONLY the JSON array.";

                        int imgW = bitmap.getWidth();
                        int imgH = bitmap.getHeight();

                        executor.submit(() -> {
                            if (visionEngine == null) {
                                bitmap.recycle();
                                if (onNotFound != null) mainHandler.post(onNotFound);
                                return;
                            }

                            visionEngine.analyze(bitmap, locatePrompt, null,
                                    new AIVisionEngine.VisionCallback() {
                                        @Override
                                        public void onActions(List<AIVisionEngine.ScreenAction> actions) {
                                            bitmap.recycle();
                                            boolean targetFound = false;
                                            float foundX = 0, foundY = 0;

                                            for (AIVisionEngine.ScreenAction a : actions) {
                                                if (a.type == AIVisionEngine.ActionType.CLICK) {
                                                    // This is the target app
                                                    targetFound = true;
                                                    foundX = a.x;
                                                    foundY = a.y;
                                                    // Store in grid for future use
                                                    CvakiStorage.saveGridApp(
                                                            SmartOverlayService.this,
                                                            appName, a.x, a.y);
                                                    addBubble("[grid] ✅ Found " + appName +
                                                                    " at (" + Math.round(a.x) + "," +
                                                                    Math.round(a.y) + ") — cached",
                                                            Color.GREEN, false);

                                                    // Store in appropriate grid cell
                                                    int col = (int)(a.x / screenW * 10);
                                                    int row = (int)(a.y / screenH * 10);
                                                    String cell = CvakiStorage.getGridCell(
                                                            SmartOverlayService.this, col, row);
                                                    CvakiStorage.saveGridCell(
                                                            SmartOverlayService.this, col, row,
                                                            (cell.isEmpty() ? "" : cell + ",") + appName);

                                                } else if (a.type == AIVisionEngine.ActionType.DONE
                                                        && a.text != null
                                                        && a.text.startsWith("GRID_ENTRY:")) {
                                                    // Cache other visible icons too
                                                    cacheGridEntry(a.text);
                                                }
                                            }

                                            if (targetFound) {
                                                final float fx = foundX, fy = foundY;
                                                performClickWithCursor(fx, fy, () ->
                                                        mainHandler.postDelayed(() -> {
                                                            if (onFound != null) onFound.run();
                                                        }, 400));
                                            } else {
                                                addBubble("[grid] " + appName + " not on this screen",
                                                        Color.YELLOW, false);
                                                // Retry with home screen
                                                scanScreenForApp(appName, attempt + 1, onFound, onNotFound);
                                            }
                                        }

                                        @Override
                                        public void onError(String message) {
                                            bitmap.recycle();
                                            addBubble("[grid] vision error: " + message, Color.RED, false);
                                            scanScreenForApp(appName, attempt + 1, onFound, onNotFound);
                                        }
                                    });
                        });
                    }

                    @Override
                    public void onError(String reason) {
                        mainHandler.post(() -> {
                            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        });
                        addBubble("[grid] capture error: " + reason, Color.RED, false);
                        if (onNotFound != null) mainHandler.post(onNotFound);
                    }
                }), 300);
    }

    /**
     * Parse and cache a GRID_ENTRY message from the vision AI.
     * Format: "GRID_ENTRY:appName:x:y"
     */
    private void cacheGridEntry(String msg) {
        try {
            // Strip prefix
            String data = msg.substring("GRID_ENTRY:".length());
            String[] parts = data.split(":");
            if (parts.length >= 3) {
                String name = parts[0].trim();
                float  x    = Float.parseFloat(parts[1].trim());
                float  y    = Float.parseFloat(parts[2].trim());
                CvakiStorage.saveGridApp(this, name, x, y);
                int col = (int)(x / screenW * 10);
                int row = (int)(y / screenH * 10);
                String cell = CvakiStorage.getGridCell(this, col, row);
                CvakiStorage.saveGridCell(this, col, row,
                        (cell.isEmpty() ? "" : cell + ",") + name);
            }
        } catch (Exception e) {
            Log.w(TAG, "cacheGridEntry parse error: " + e.getMessage());
        }
    }

    // ── Press HOME ────────────────────────────────────────────────────────────

    /**
     * Press the device HOME button via the Accessibility Service.
     * Falls back to an am start intent if accessibility isn't available.
     */
    private void pressHome(Runnable onDone) {
        if (CVAAccessibilityService.isAvailable()) {
            CVAAccessibilityService.instance.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            mainHandler.postDelayed(() -> {
                if (onDone != null) onDone.run();
            }, 500);
        } else {
            // Fallback: launch home via intent
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(homeIntent);
            mainHandler.postDelayed(() -> {
                if (onDone != null) onDone.run();
            }, 800);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TaskAllocation bridge — vision task that reports back
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Run a vision task and when it finishes, report the result back to
     * TaskAllocationActivity via the static callback.
     */
    private void runVisionTaskWithReport() {
        if (currentState != State.IDLE && currentState != State.ERROR) {
            addBubble("[alloc] busy — queuing visual task", Color.YELLOW, false);
            mainHandler.postDelayed(this::runVisionTaskWithReport, 1000);
            return;
        }

        // Check if the task is to find a specific app
        String lowerTask = currentTask.toLowerCase();
        String targetApp = extractAppTarget(currentTask);

        if (targetApp != null) {
            // Use the intelligent grid-based finder
            addBubble("[alloc] 🎯 App-find task: " + targetApp, Color.parseColor("#E040FB"), false);
            findAndClickApp(targetApp,
                    () -> reportSubtaskResult(true, "Opened " + targetApp + " successfully"),
                    () -> {
                        // App not found → try full vision scan as fallback
                        addBubble("[alloc] Grid scan failed — trying full vision", Color.YELLOW, false);
                        runVisionTaskThenReport();
                    });
        } else {
            // Generic visual task — use normal vision pipeline
            runVisionTaskThenReport();
        }
    }

    /**
     * Run the standard vision task pipeline and report result when done.
     */
    private void runVisionTaskThenReport() {
        // We override the callback to intercept DONE/ERROR and fire report
        // This is done by substituting the task-run with a monitored version

        if (!ScreenCaptureManager.isReady()) {
            addBubble("[alloc] ❌ no screen permission", Color.RED, false);
            reportSubtaskResult(false, "Screen capture permission not granted");
            return;
        }
        if (visionEngine == null) {
            addBubble("[alloc] ❌ visionEngine null", Color.RED, false);
            reportSubtaskResult(false, "Vision engine not initialised");
            return;
        }

        addBubble("[alloc] 📷 capturing for subtask #" + (pendingSubtaskIndex + 1), Color.CYAN, false);
        setStatus("● capturing…", State.CAPTURING);

        mainHandler.post(() -> {
            if (bubbleView != null) bubbleView.setVisibility(View.INVISIBLE);
            if (cursorView != null) cursorView.setVisibility(View.GONE);
        });

        mainHandler.postDelayed(() ->
                ScreenCaptureManager.get().capture(new ScreenCaptureManager.CaptureCallback() {
                    @Override public void onCaptured(Bitmap bitmap) {
                        mainHandler.post(() -> {
                            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        });
                        setStatus("● 🧠 thinking…", State.ANALYZING);
                        executor.submit(() ->
                                visionEngine.analyze(bitmap, currentTask, null,
                                        new AIVisionEngine.VisionCallback() {
                                            @Override
                                            public void onActions(List<AIVisionEngine.ScreenAction> actions) {
                                                bitmap.recycle();
                                                addBubble("✅ AI done — " + actions.size() + " steps",
                                                        Color.GREEN, false);
                                                executeActionsWithReport(actions, 0);
                                            }
                                            @Override
                                            public void onError(String msg) {
                                                bitmap.recycle();
                                                addBubble("[alloc] ❌ vision error: " + msg, Color.RED, false);
                                                reportSubtaskResult(false, "Vision error: " + msg);
                                            }
                                        }));
                    }
                    @Override public void onError(String reason) {
                        mainHandler.post(() -> {
                            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        });
                        addBubble("[alloc] ❌ capture failed: " + reason, Color.RED, false);
                        reportSubtaskResult(false, "Capture failed: " + reason);
                    }
                }), 350);
    }

    /**
     * Execute actions and report the final result back to TaskAllocationActivity.
     */
    private void executeActionsWithReport(List<AIVisionEngine.ScreenAction> actions, int index) {
        if (index >= actions.size()) {
            addBubble("✅ all visual steps done", Color.GREEN, false);
            setStatus("● done", State.IDLE);
            hideCursor();
            reportSubtaskResult(true, "Visual task completed — " + actions.size() + " steps");
            return;
        }

        AIVisionEngine.ScreenAction action = actions.get(index);
        addBubble("⚡ " + (index + 1) + "/" + actions.size() + " " + action.type
                + (action.note != null ? " — " + action.note : ""), Color.CYAN, false);

        Runnable next = () -> executeActionsWithReport(actions, index + 1);

        if (action.type == AIVisionEngine.ActionType.DONE) {
            addBubble("✅ " + action.text, Color.GREEN, false);
            setStatus("● done", State.IDLE);
            hideCursor();
            reportSubtaskResult(true, action.text);
            return;
        }
        if (action.type == AIVisionEngine.ActionType.ERROR) {
            addBubble("❌ " + action.text, Color.RED, false);
            reportSubtaskResult(false, action.text);
            return;
        }

        // Delegate to standard executor for other action types
        executeActions(List.of(action), 0, next);
    }

    /**
     * Report the result of the current subtask back to TaskAllocationActivity.
     */
    private void reportSubtaskResult(boolean success, String message) {
        if (reportBack && pendingSubtaskIndex >= 0) {
            addBubble("[alloc] 📤 reporting subtask #" + (pendingSubtaskIndex + 1) +
                    " → " + (success ? "✅ success" : "❌ fail"), Color.parseColor("#E040FB"), false);
            TaskAllocationActivity.reportParasiteResult(pendingSubtaskIndex, success, message);

            // Reset bridge state
            reportBack          = false;
            pendingSubtaskIndex = -1;
        }
        setStatus(success ? "● done" : "● error", success ? State.IDLE : State.ERROR);
    }

    /**
     * Try to extract a specific app name from a task description.
     * e.g. "Open camera" → "camera"
     *      "Search Google for black cat" → "google"
     */
    private String extractAppTarget(String task) {
        if (task == null) return null;
        String lower = task.toLowerCase();
        String[] verbs = {"open ", "launch ", "start ", "find ", "go to ", "navigate to "};
        for (String verb : verbs) {
            int idx = lower.indexOf(verb);
            if (idx >= 0) {
                String rest = task.substring(idx + verb.length()).trim();
                // Take first word or up to "and"/"then"
                int end = rest.toLowerCase().indexOf(" and ");
                if (end < 0) end = rest.toLowerCase().indexOf(" then ");
                if (end < 0) end = rest.indexOf(' ', rest.indexOf(' ') + 1); // two words max
                if (end > 0) rest = rest.substring(0, end);
                if (!rest.isEmpty()) return rest.trim();
            }
        }
        return null;
    }

    // ── Bubble overlay ────────────────────────────────────────────────────────

    private void showBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_smart_bubble, null);

        smartPanel        = bubbleView.findViewById(R.id.layout_smart_panel);
        llBubbleContainer = bubbleView.findViewById(R.id.ll_bubble_container);
        svTerminal        = bubbleView.findViewById(R.id.sv_terminal);
        tvStatus          = bubbleView.findViewById(R.id.tv_smart_status);
        tvTask            = bubbleView.findViewById(R.id.tv_smart_task);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = 20;
        bubbleParams.y = 200;

        bubbleView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    bubbleDragInitX  = bubbleParams.x;
                    bubbleDragInitY  = bubbleParams.y;
                    bubbleDragTouchX = event.getRawX();
                    bubbleDragTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    bubbleParams.x = bubbleDragInitX + (int)(event.getRawX() - bubbleDragTouchX);
                    bubbleParams.y = bubbleDragInitY + (int)(event.getRawY() - bubbleDragTouchY);
                    wm.updateViewLayout(bubbleView, bubbleParams);
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getRawX() - bubbleDragTouchX);
                    float dy = Math.abs(event.getRawY() - bubbleDragTouchY);
                    if (dx < TAP_MAX_PX && dy < TAP_MAX_PX) onLogoTapped();
                    return true;
            }
            return false;
        });

        try {
            wm.addView(bubbleView, bubbleParams);
            addBubble("parasite agent online ✓", Color.GREEN, false);
            setStatus("● idle", State.IDLE);
        } catch (WindowManager.BadTokenException e) {
            Log.e(TAG, "BadTokenException", e);
            stopSelf();
        }
    }

    private void onLogoTapped() {
        if (!panelVisible) {
            showPanel();

            if (!ScreenCaptureManager.isReady() && visionEngine != null) {
                addBubble("📷 No screen permission yet — requesting…", Color.YELLOW, false);
                Intent permIntent = new Intent(this, ScreenCapturePermissionActivity.class);
                permIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                permIntent.putExtra("task",     currentTask);
                permIntent.putExtra("apiKey",   apiKey);
                permIntent.putExtra("provider", provider);
                startActivity(permIntent);
                return;
            }

            if (ScreenCaptureManager.isReady() && visionEngine != null) {
                runVisionTask();
            } else if (brainAgent != null && !currentTask.isEmpty()) {
                runBrainTask();
            } else {
                addBubble("[tap] set an API key in Settings first", Color.YELLOW, false);
            }
        } else {
            hidePanel();
        }
    }

    // ── Panel show / hide ─────────────────────────────────────────────────────

    private void showPanel() {
        if (smartPanel == null) return;
        smartPanel.setVisibility(View.VISIBLE);
        AnimationSet anim = new AnimationSet(true);
        anim.addAnimation(new AlphaAnimation(0f, 1f));
        anim.addAnimation(new TranslateAnimation(-30, 0, 0, 0));
        anim.setDuration(200);
        smartPanel.startAnimation(anim);
        panelVisible = true;
        if (svTerminal != null)
            svTerminal.post(() -> svTerminal.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void hidePanel() {
        if (smartPanel == null) return;
        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration(150);
        fade.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a)  {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                if (smartPanel != null) smartPanel.setVisibility(View.GONE);
            }
        });
        smartPanel.startAnimation(fade);
        panelVisible = false;
    }

    // ── Bubble factory ────────────────────────────────────────────────────────

    public void addBubble(final String text, final int color, final boolean isUser) {
        if (text == null || text.trim().isEmpty()) return;
        Log.d(TAG, (isUser ? "[user] " : "[agent] ") + text);

        mainHandler.post(() -> {
            if (llBubbleContainer == null) return;

            while (llBubbleContainer.getChildCount() >= MAX_BUBBLES) {
                llBubbleContainer.removeViewAt(0);
            }

            String ts      = timeFmt.format(new Date());
            String display = isUser ? text : ts + "  " + text;

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            if (isUser) {
                bg.setCornerRadii(new float[]{dp(12),dp(12), dp(3),dp(3), dp(12),dp(12), dp(12),dp(12)});
                bg.setColor(Color.parseColor("#FF6A1A"));
            } else {
                bg.setCornerRadii(new float[]{dp(3),dp(3), dp(12),dp(12), dp(12),dp(12), dp(12),dp(12)});
                bg.setColor(Color.parseColor("#0D0D0D"));
                bg.setStroke(dp(1), color);
            }

            TextView tv = new TextView(SmartOverlayService.this);
            tv.setText(display);
            tv.setTextSize(10);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(dp(8), dp(5), dp(8), dp(5));
            tv.setTextColor(isUser ? Color.parseColor("#0A0A0A") : color);
            tv.setBackground(bg);
            tv.setLineSpacing(0f, 1.2f);

            tv.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("cva_log", text));
                // FIX: Toast must always run on main thread
                mainHandler.post(() ->
                        Toast.makeText(SmartOverlayService.this, "Copied ✓", Toast.LENGTH_SHORT).show());
                return true;
            });

            FrameLayout wrapper = new FrameLayout(SmartOverlayService.this);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            wp.setMargins(0, 2, 0, 2);
            wrapper.setLayoutParams(wp);

            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            if (isUser) {
                tp.gravity = Gravity.END;
                tp.setMargins(dp(30), 0, 0, 0);
            } else {
                tp.gravity = Gravity.START;
                tp.setMargins(0, 0, dp(10), 0);
            }
            tv.setLayoutParams(tp);
            wrapper.addView(tv);
            llBubbleContainer.addView(wrapper);

            if (svTerminal != null)
                svTerminal.post(() -> svTerminal.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    public void termLog(String line, int color) { addBubble(line, color, false); }

    // ── Status label ──────────────────────────────────────────────────────────

    private void setStatus(String status, State state) {
        currentState = state;
        mainHandler.post(() -> {
            if (tvStatus == null) return;
            int col;
            switch (state) {
                case CAPTURING:  col = Color.parseColor("#FF9800"); break;
                case ANALYZING:  col = Color.parseColor("#2196F3"); break;
                case EXECUTING:  col = Color.parseColor("#4CAF50"); break;
                case ERROR:      col = Color.parseColor("#F44336"); break;
                default:         col = Color.parseColor("#00FF41"); break;
            }
            tvStatus.setTextColor(col);
            tvStatus.setText(status);
        });
    }

    // ── Task ──────────────────────────────────────────────────────────────────

    public void setTask(String task) {
        currentTask = (task != null) ? task : "";
        mainHandler.post(() -> {
            if (tvTask != null) {
                tvTask.setText("task: " + currentTask);
                tvTask.setVisibility(currentTask.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
        addBubble("[task] " + currentTask, Color.CYAN, false);
    }

    // ── Standard vision task ──────────────────────────────────────────────────

    private void runVisionTask() {
        if (currentState != State.IDLE && currentState != State.ERROR) {
            addBubble("[run] busy — state=" + currentState, Color.YELLOW, false);
            return;
        }
        if (!ScreenCaptureManager.isReady()) {
            addBubble("[run] ❌ no screen permission", Color.RED, false);
            setStatus("● no screen perm", State.ERROR);
            return;
        }
        if (visionEngine == null) {
            addBubble("[run] ❌ visionEngine null / no API key", Color.RED, false);
            setStatus("● no API key", State.ERROR);
            return;
        }

        addBubble("[run] vision task: " + currentTask, Color.CYAN, false);
        setStatus("● capturing…", State.CAPTURING);

        mainHandler.post(() -> {
            if (bubbleView != null) bubbleView.setVisibility(View.INVISIBLE);
            if (cursorView != null) cursorView.setVisibility(View.GONE);
        });

        mainHandler.postDelayed(() ->
                ScreenCaptureManager.get().capture(new ScreenCaptureManager.CaptureCallback() {
                    @Override public void onCaptured(Bitmap bitmap) {
                        mainHandler.post(() -> {
                            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        });
                        addBubble("[capture] ✓ " + bitmap.getWidth() + "×" + bitmap.getHeight(),
                                Color.GREEN, false);
                        setStatus("● 🧠 thinking…", State.ANALYZING);
                        addBubble("🧠 CVA is analyzing the screen…", Color.CYAN, false);

                        executor.submit(() ->
                                visionEngine.analyze(bitmap, currentTask,
                                        dot -> {
                                            if (dot == null) return;
                                            mainHandler.post(() ->
                                                    setStatus("● 🧠 thinking" + dot, State.ANALYZING));
                                        },
                                        new AIVisionEngine.VisionCallback() {
                                            @Override
                                            public void onActions(List<AIVisionEngine.ScreenAction> actions) {
                                                bitmap.recycle();
                                                addBubble("✅ AI done — " + actions.size()
                                                        + " steps planned", Color.GREEN, false);
                                                for (int i = 0; i < actions.size(); i++) {
                                                    AIVisionEngine.ScreenAction a = actions.get(i);
                                                    addBubble("[plan " + (i+1) + "/" + actions.size() + "] "
                                                                    + a.type + (a.note != null ? " — " + a.note : ""),
                                                            Color.parseColor("#9C27B0"), false);
                                                }
                                                addBubble("▶ executing…", Color.CYAN, false);
                                                executeActions(actions, 0);
                                            }
                                            @Override
                                            public void onError(String msg) {
                                                bitmap.recycle();
                                                addBubble("[ai] ❌ " + msg, Color.RED, false);
                                                setStatus("● ai error", State.ERROR);
                                            }
                                        }));
                    }
                    @Override public void onError(String reason) {
                        mainHandler.post(() -> {
                            if (bubbleView != null) bubbleView.setVisibility(View.VISIBLE);
                        });
                        addBubble("[capture] ❌ " + reason, Color.RED, false);
                        setStatus("● capture failed", State.ERROR);
                    }
                }), 350);
    }

    // ── Brain task ────────────────────────────────────────────────────────────

    private void runBrainTask() {
        if (brainAgent == null) { addBubble("[brain] ❌ not initialised", Color.RED, false); return; }
        addBubble("[brain] → " + currentTask, Color.CYAN, true);
        setStatus("● thinking…", State.ANALYZING);
        executor.submit(() -> brainAgent.chat(currentTask));
    }

    // ── Action executor ───────────────────────────────────────────────────────

    private void executeActions(List<AIVisionEngine.ScreenAction> actions, int index) {
        executeActions(actions, index, null);
    }

    private void executeActions(List<AIVisionEngine.ScreenAction> actions, int index,
                                Runnable onAllDone) {
        if (index >= actions.size()) {
            if (onAllDone != null) onAllDone.run();
            else {
                addBubble("✅ all actions done", Color.GREEN, false);
                setStatus("● done", State.IDLE);
                hideCursor();
            }
            return;
        }

        AIVisionEngine.ScreenAction action = actions.get(index);
        String note = (action.note != null && !action.note.isEmpty()) ? " — " + action.note : "";
        addBubble("⚡ step " + (index+1) + "/" + actions.size() + "  " + action.type + note,
                Color.CYAN, false);
        setStatus("● " + action.type + " (" + (index+1) + "/" + actions.size() + ")",
                State.EXECUTING);

        Runnable next = () -> executeActions(actions, index + 1, onAllDone);

        switch (action.type) {
            case CLICK:
                performClickWithCursor(action.x, action.y, next);
                break;
            case TYPE:
                mainHandler.post(() -> {
                    if (CVAAccessibilityService.isAvailable())
                        CVAAccessibilityService.instance.performType(action.text);
                    else fallbackType(action.text);
                    mainHandler.postDelayed(next, 200);
                });
                break;
            case TELEPORT_TYPE:
                performClickWithCursor(action.x, action.y, () ->
                        mainHandler.postDelayed(() -> {
                            if (CVAAccessibilityService.isAvailable())
                                CVAAccessibilityService.instance.performType(action.text);
                            else fallbackType(action.text);
                            mainHandler.postDelayed(next, 200);
                        }, 300));
                break;
            case SCROLL:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && CVAAccessibilityService.isAvailable())
                    CVAAccessibilityService.instance.performSwipe(
                            action.x, action.y, action.x2, action.y2, 400, next);
                else mainHandler.postDelayed(next, 100);
                break;
            case WAIT:
                mainHandler.postDelayed(next, action.delayMs);
                break;
            case DONE:
                addBubble("✅ " + action.text, Color.GREEN, false);
                setStatus("● done", State.IDLE);
                hideCursor();
                // ── BUG FIX: Toast must be shown on the main thread ──────────
                mainHandler.post(() ->
                        Toast.makeText(SmartOverlayService.this, action.text, Toast.LENGTH_SHORT).show());
                break;
            case ERROR:
                addBubble("❌ " + action.text, Color.RED, false);
                setStatus("● error", State.ERROR);
                hideCursor();
                break;
            default:
                next.run();
        }
    }

    // ── Cursor ────────────────────────────────────────────────────────────────

    private void showCursor() {
        cursorView = buildCursorView();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        cursorParams = new WindowManager.LayoutParams(
                48, 48, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        cursorParams.gravity = Gravity.TOP | Gravity.START;
        cursorParams.x = screenW / 2;
        cursorParams.y = screenH / 2;
        wm.addView(cursorView, cursorParams);
        cursorView.setVisibility(View.GONE);
    }

    private View buildCursorView() {
        Bitmap bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint  p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(180, 255, 50, 50));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        c.drawCircle(24, 24, 18, p);
        p.setStrokeWidth(1.5f);
        c.drawLine(24,  4, 24, 44, p);
        c.drawLine( 4, 24, 44, 24, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(220, 255, 50, 50));
        c.drawCircle(24, 24, 3, p);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        return iv;
    }

    private void animateCursorTo(float tx, float ty, int durationMs, Runnable onDone) {
        mainHandler.post(() -> cursorView.setVisibility(View.VISIBLE));
        float sx = cursorParams.x, sy = cursorParams.y;
        float fx = tx - 24, fy = ty - 24;
        long start = System.currentTimeMillis();
        Runnable step = new Runnable() {
            @Override public void run() {
                long e = System.currentTimeMillis() - start;
                float t = Math.min(1f, (float) e / durationMs);
                t = t < 0.5f ? 2*t*t : -1 + (4-2*t)*t;
                cursorParams.x = Math.round(sx + (fx-sx)*t);
                cursorParams.y = Math.round(sy + (fy-sy)*t);
                try { wm.updateViewLayout(cursorView, cursorParams); } catch (Exception ignored) {}
                if (t < 1f) mainHandler.postDelayed(this, 16);
                else mainHandler.postDelayed(() -> { if (onDone != null) onDone.run(); }, 80);
            }
        };
        mainHandler.post(step);
    }

    private void hideCursor() {
        mainHandler.post(() -> { if (cursorView != null) cursorView.setVisibility(View.GONE); });
    }

    private void performClickWithCursor(float x, float y, Runnable onDone) {
        float dist = (float) Math.hypot(x-(cursorParams.x+24), y-(cursorParams.y+24));
        int ms = Math.min(800, Math.max(200, (int)(dist/600f*1000)));
        animateCursorTo(x, y, ms, () -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && CVAAccessibilityService.isAvailable())
                CVAAccessibilityService.instance.performClick(x, y, () ->
                        mainHandler.postDelayed(onDone, 150));
            else mainHandler.postDelayed(onDone, 150);
        });
    }

    private void fallbackType(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("cva", text));
        } catch (Exception e) {
            addBubble("[type] fallback FAILED: " + e.getMessage(), Color.RED, false);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void resolveScreenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CVA Smart Overlay", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CVA Agent active")
                .setContentText("Tap parasite to view logs")
                .setSmallIcon(R.drawable.parasite)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}