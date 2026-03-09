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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SmartOverlayService
 *
 * Tap the parasite logo:
 *   → Panel of chat bubbles slides in to the RIGHT of the logo.
 *   → Agent auto-starts immediately (no play button).
 *   → Retap the logo to collapse the panel (agent keeps running).
 *
 * Each bubble:
 *   → Long-press copies the text to clipboard.
 *
 * Log colours:
 *   Green  = normal / success
 *   Red    = error
 *   Yellow = warning
 *   Cyan   = AI thinking / step
 *   White  = shell output
 */
public class SmartOverlayService extends Service {

    private static final String TAG        = "SmartOverlay";
    private static final String CHANNEL_ID = "cva_smart_overlay";
    private static final int    TAP_MAX_PX = 12;
    private static final int    MAX_BUBBLES = 80;

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
    private TerminalManager terminalManager;
    private ExecutorService executor    = Executors.newSingleThreadExecutor();
    private Handler         mainHandler = new Handler(Looper.getMainLooper());

    private int screenW, screenH;
    private final SimpleDateFormat timeFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(2, buildNotification());
        resolveScreenSize();
        showBubble();
        showCursor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        // Always read the freshest key from prefs first, then let the intent override
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

        // Log a key fingerprint so it is easy to verify the right key is in use
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
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
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

        // TerminalManager: shell output → bubbles
        terminalManager = new TerminalManager(this, chunk ->
                mainHandler.post(() -> addBubble(chunk.trim(), Color.WHITE, false)));

        // BrainAgent: step callbacks → bubbles
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

    // ── Bubble overlay ────────────────────────────────────────────────────────

    private void showBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubbleView = LayoutInflater.from(this).inflate(R.layout.overlay_smart_bubble, null);

        smartPanel       = bubbleView.findViewById(R.id.layout_smart_panel);
        llBubbleContainer = bubbleView.findViewById(R.id.ll_bubble_container);
        svTerminal       = bubbleView.findViewById(R.id.sv_terminal);
        tvStatus         = bubbleView.findViewById(R.id.tv_smart_status);
        tvTask           = bubbleView.findViewById(R.id.tv_smart_task);

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

        // ── Touch: drag or tap ────────────────────────────────────────────────
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

    /**
     * Tap: if panel closed → open it + auto-run.
     *      If panel open  → close it (agent keeps running).
     */
    private void onLogoTapped() {
        if (!panelVisible) {
            showPanel();
            if (ScreenCaptureManager.isReady() && visionEngine != null) {
                runVisionTask();
            } else if (brainAgent != null && !currentTask.isEmpty()) {
                runBrainTask();
            } else {
                addBubble("[tap] type a task in the Brain keyboard first", Color.YELLOW, false);
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

        // scroll to newest bubble
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

    /**
     * Add a chat-style bubble to the panel.
     *
     * @param text    message text
     * @param color   text / accent colour
     * @param isUser  true = right-aligned (user), false = left-aligned (agent)
     *
     * Long-press on any bubble copies its text to the clipboard.
     */
    public void addBubble(final String text, final int color, final boolean isUser) {
        if (text == null || text.trim().isEmpty()) return;
        Log.d(TAG, (isUser ? "[user] " : "[agent] ") + text);

        mainHandler.post(() -> {
            if (llBubbleContainer == null) return;

            // Trim old bubbles
            while (llBubbleContainer.getChildCount() >= MAX_BUBBLES) {
                llBubbleContainer.removeViewAt(0);
            }

            String ts      = timeFmt.format(new Date());
            String display = isUser ? text : ts + "  " + text;

            // Bubble drawable
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

            // TextView
            TextView tv = new TextView(SmartOverlayService.this);
            tv.setText(display);
            tv.setTextSize(10);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(dp(8), dp(5), dp(8), dp(5));
            tv.setTextColor(isUser ? Color.parseColor("#0A0A0A") : color);
            tv.setBackground(bg);
            tv.setLineSpacing(0f, 1.2f);

            // Long-press → copy
            tv.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("cva_log", text));
                Toast.makeText(SmartOverlayService.this,
                        "Copied ✓", Toast.LENGTH_SHORT).show();
                return true;
            });

            // Wrapper for alignment
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

            // Auto-scroll
            if (svTerminal != null)
                svTerminal.post(() -> svTerminal.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    // Keep old termLog as a convenience alias
    public void termLog(String line, int color) {
        addBubble(line, color, false);
    }

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

    // ── Vision task ───────────────────────────────────────────────────────────

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

        mainHandler.postDelayed(() ->
                ScreenCaptureManager.get().capture(new ScreenCaptureManager.CaptureCallback() {
                    @Override public void onCaptured(Bitmap bitmap) {
                        addBubble("[capture] ✓ " + bitmap.getWidth() + "×"
                                + bitmap.getHeight(), Color.GREEN, false);
                        setStatus("● analyzing…", State.ANALYZING);
                        executor.submit(() ->
                                visionEngine.analyze(bitmap, currentTask,
                                        new AIVisionEngine.VisionCallback() {
                                            @Override public void onActions(
                                                    List<AIVisionEngine.ScreenAction> actions) {
                                                bitmap.recycle();
                                                addBubble("[ai] " + actions.size()
                                                        + " actions", Color.GREEN, false);
                                                executeActions(actions, 0);
                                            }
                                            @Override public void onError(String msg) {
                                                bitmap.recycle();
                                                addBubble("[ai] ❌ " + msg, Color.RED, false);
                                                setStatus("● ai error", State.ERROR);
                                            }
                                        }));
                    }
                    @Override public void onError(String reason) {
                        addBubble("[capture] ❌ " + reason, Color.RED, false);
                        setStatus("● capture failed", State.ERROR);
                    }
                }), 300);
    }

    // ── Brain task ────────────────────────────────────────────────────────────

    private void runBrainTask() {
        if (brainAgent == null) {
            addBubble("[brain] ❌ not initialised", Color.RED, false);
            return;
        }
        addBubble("[brain] → " + currentTask, Color.CYAN, true);   // user bubble on right
        setStatus("● thinking…", State.ANALYZING);
        executor.submit(() -> brainAgent.chat(currentTask));
    }

    // ── Action executor ───────────────────────────────────────────────────────

    private void executeActions(List<AIVisionEngine.ScreenAction> actions, int index) {
        if (index >= actions.size()) {
            addBubble("✅ all actions done", Color.GREEN, false);
            setStatus("● done", State.IDLE);
            hideCursor();
            return;
        }

        AIVisionEngine.ScreenAction action = actions.get(index);
        String note = (action.note != null && !action.note.isEmpty())
                ? " — " + action.note : "";
        addBubble("[" + index + "] " + action.type + note, Color.WHITE, false);
        setStatus("● " + action.type, State.EXECUTING);

        Runnable next = () -> executeActions(actions, index + 1);

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
                Toast.makeText(this, action.text, Toast.LENGTH_SHORT).show();
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

    // ── Notification ─────────────────────────────────────────────────────────

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