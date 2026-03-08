package com.braingods.cva;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * AgentChatView
 * ─────────────────────────────────────────────────────────────────────────────
 * Drop-in chat panel for the CVA agent section.
 *
 * Features:
 *   • User bubbles   — right-aligned, cyan border
 *   • Agent bubbles  — left-aligned, orange border, CVA label
 *   • Blinking ●CVA● badge while the agent is running (isRunning=true)
 *   • Auto-scroll to latest bubble on every new message
 *   • Live terminal panel — slides in when agent types a command,
 *     shows each character appear in matrix-green, slides out when done
 *
 * ── Add to your keyboard / overlay layout ────────────────────────────────────
 *   <com.braingods.cva.AgentChatView
 *       android:id="@+id/agent_chat_view"
 *       android:layout_width="match_parent"
 *       android:layout_height="0dp"
 *       android:layout_weight="1"/>
 *
 * ── Wire up in your Service ──────────────────────────────────────────────────
 *   AgentChatView chatView = findViewById(R.id.agent_chat_view);
 *   brainAgent.setAgentCallback(chatView.buildAgentCallback());
 *   brainAgent.setLiveModeController(chatView.buildLiveModeController());
 *
 * ── Sending a message ────────────────────────────────────────────────────────
 *   chatView.addUserMessage(text);
 *   executor.execute(() -> brainAgent.chat(text));
 *   // All subsequent UI updates (bubbles, blink, scroll) happen automatically
 *   // via the callbacks — no extra posting needed.
 */
public class AgentChatView extends LinearLayout {

    // ── Brand colours ─────────────────────────────────────────────────────────
    private static final int CLR_BG        = Color.parseColor("#0D0D0D");
    private static final int CLR_USER_BG   = Color.parseColor("#1A1A2E");
    private static final int CLR_AGENT_BG  = Color.parseColor("#111111");
    private static final int CLR_ORANGE    = Color.parseColor("#FF6A1A");
    private static final int CLR_CYAN      = Color.parseColor("#00FFFF");
    private static final int CLR_WHITE     = Color.WHITE;
    private static final int CLR_DIM       = Color.parseColor("#888888");
    private static final int CLR_LIVE_BG   = Color.parseColor("#001A00");
    private static final int CLR_LIVE_TEXT = Color.parseColor("#00FF41");  // matrix green
    private static final int CLR_GREEN     = Color.parseColor("#00FF41");  // success green
    private static final int CLR_RED       = Color.parseColor("#FF3333");  // error red
    private static final int CLR_GEAR      = Color.parseColor("#00FF41");  // rotating ⚙ green

    // ── Core views ────────────────────────────────────────────────────────────
    private ScrollView   scrollView;
    private LinearLayout bubbleContainer;

    // The persistent "agent is thinking" bubble (swapped for final answer when done)
    private LinearLayout agentThinkingRow;
    private TextView     agentThinkingText;
    private TextView     blinkingLogo;      // "●CVA●" badge
    private TextView     gearView;          // rotating ⚙ gear icon
    private ObjectAnimator gearAnim;        // continuous 360° rotation

    // Live terminal panel
    private LinearLayout       livePanel;
    private TextView           livePanelLabel;
    private HorizontalScrollView liveScroll;
    private TextView           liveOutput;   // green typed-command display
    private EditText           liveInput;    // hidden state holder for the current command

    private final Handler        mainHandler = new Handler(Looper.getMainLooper());
    private       ValueAnimator  blinkAnim;

    /** Cached controller so buildLiveModeController() always returns the same instance. */
    private BrainAgent.LiveModeController cachedLiveController = null;

    /**
     * Guard that prevents the final answer bubble from being added more than once
     * per agent turn. Set to true when isRunning=true (agent starts), reset to false
     * after the final bubble is committed (isRunning=false).
     */
    private boolean agentTurnActive = false;

    // ── Constructors ──────────────────────────────────────────────────────────
    public AgentChatView(Context context)                         { super(context); init(); }
    public AgentChatView(Context context, AttributeSet a)         { super(context, a); init(); }
    public AgentChatView(Context context, AttributeSet a, int d)  { super(context, a, d); init(); }

    // ═════════════════════════════════════════════════════════════════════════
    // Build layout
    // ═════════════════════════════════════════════════════════════════════════
    private void init() {
        setOrientation(VERTICAL);
        setBackgroundColor(CLR_BG);

        // ── Live terminal panel (hidden by default) ───────────────────────────
        livePanel = new LinearLayout(getContext());
        livePanel.setOrientation(VERTICAL);
        livePanel.setBackgroundColor(CLR_LIVE_BG);
        livePanel.setPadding(dp(10), dp(6), dp(10), dp(8));
        livePanel.setVisibility(GONE);

        // Header row: "▶ LIVE TERMINAL" label + blinking dot
        LinearLayout liveHeader = new LinearLayout(getContext());
        liveHeader.setOrientation(HORIZONTAL);
        liveHeader.setGravity(Gravity.CENTER_VERTICAL);

        livePanelLabel = new TextView(getContext());
        livePanelLabel.setText("▶  LIVE  TERMINAL");
        livePanelLabel.setTextColor(CLR_ORANGE);
        livePanelLabel.setTextSize(10f);
        livePanelLabel.setTypeface(null, Typeface.BOLD);
        livePanelLabel.setPadding(0, 0, dp(8), 0);
        liveHeader.addView(livePanelLabel);

        // Thin separator line
        View sep = new View(getContext());
        sep.setBackgroundColor(Color.parseColor("#1A3300"));
        livePanel.addView(liveHeader,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        livePanel.addView(sep,
                new LayoutParams(LayoutParams.MATCH_PARENT, dp(1)));

        // Green command text display
        liveScroll = new HorizontalScrollView(getContext());
        liveOutput = new TextView(getContext());
        liveOutput.setTextColor(CLR_LIVE_TEXT);
        liveOutput.setTextSize(12f);
        liveOutput.setTypeface(Typeface.MONOSPACE);
        liveOutput.setMaxLines(5);
        liveOutput.setPadding(0, dp(4), 0, 0);
        liveScroll.addView(liveOutput);
        livePanel.addView(liveScroll,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // Hidden EditText used as state for the current command being typed
        liveInput = new EditText(getContext());
        liveInput.setVisibility(GONE);
        livePanel.addView(liveInput);

        addView(livePanel,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ── Scrollable chat bubble container ─────────────────────────────────
        scrollView = new ScrollView(getContext());
        scrollView.setBackgroundColor(CLR_BG);
        scrollView.setFillViewport(true);

        bubbleContainer = new LinearLayout(getContext());
        bubbleContainer.setOrientation(VERTICAL);
        bubbleContainer.setPadding(dp(8), dp(8), dp(8), dp(16));

        scrollView.addView(bubbleContainer,
                new ScrollView.LayoutParams(
                        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LayoutParams scrollParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0);
        scrollParams.weight = 1;
        addView(scrollView, scrollParams);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    /** Add a user message bubble (right-aligned). Always auto-scrolls. */
    public void addUserMessage(String text) {
        mainHandler.post(() -> {
            bubbleContainer.addView(makeBubble(text, false));
            scrollToBottom();
        });
    }

    /**
     * Called by BrainAgent via AgentCallback on every agentic step.
     *
     * @param msg         Status text or final reply.
     * @param isRunning   TRUE → update / show the blinking thinking bubble.
     *                    FALSE → finalise: stop blink, replace thinking bubble
     *                            with the final answer bubble.
     * @param scrollToEnd TRUE → auto-scroll to bottom after the update.
     */
    public void onAgentStep(String msg, boolean isRunning, boolean scrollToEnd) {
        mainHandler.post(() -> {
            if (isRunning) {
                agentTurnActive = true;
                if (agentThinkingRow == null) createThinkingBubble();

                // ── Colour-code the status message ────────────────────────────
                // SUCCESS  — lines starting with ✓ or 📤 or containing "done/ok"
                // ERROR    — lines starting with ✗ or [ERROR] or containing "error/fail"
                // DEFAULT  — white (plain thinking/progress steps)
                int textColor;
                String lower = msg.toLowerCase();
                if (msg.startsWith("✓") || msg.startsWith("📤") || msg.startsWith("▶")
                        || lower.contains("success") || lower.contains("done")
                        || lower.contains("complete")) {
                    textColor = CLR_GREEN;
                } else if (msg.startsWith("✗") || msg.startsWith("[error")
                        || lower.contains("error") || lower.contains("fail")
                        || lower.contains("exception")) {
                    textColor = CLR_RED;
                } else {
                    textColor = CLR_WHITE;
                }

                if (agentThinkingText != null) {
                    agentThinkingText.setText(msg);
                    agentThinkingText.setTextColor(textColor);
                }
                startBlink();
                startGear();
            } else {
                if (!agentTurnActive) return;
                agentTurnActive = false;

                stopBlink();
                stopGear();
                if (agentThinkingRow != null) {
                    bubbleContainer.removeView(agentThinkingRow);
                    agentThinkingRow  = null;
                    agentThinkingText = null;
                    blinkingLogo      = null;
                    gearView          = null;
                }
                bubbleContainer.addView(makeBubble(msg, true));
            }
            if (scrollToEnd) scrollToBottom();
        });
    }

    // ── Factory methods — pass these to BrainAgent ────────────────────────────

    /** Returns the AgentCallback to pass to {@code brainAgent.setAgentCallback()}. */
    public BrainAgent.AgentCallback buildAgentCallback() {
        return this::onAgentStep;
    }

    /**
     * Wire this directly to the {@code switch_live_import} toggle in your Service/Activity:
     *
     * <pre>
     *   Switch liveSwitch = view.findViewById(R.id.switch_live_import);
     *   liveSwitch.setOnCheckedChangeListener((btn, isChecked) ->
     *       chatView.setLiveEnabled(isChecked));
     * </pre>
     *
     * When ON  — the live terminal panel opens and BrainAgent will type commands
     *            into it character-by-character before executing them.
     * When OFF — the panel is hidden; commands still run silently in the background.
     *
     * This does NOT start/stop the shell process itself (TerminalManager keeps
     * running either way).  It only controls the visual live-typing panel.
     */
    public void setLiveEnabled(boolean enabled) {
        // Delegate to the LiveModeController that is already built into this view
        buildLiveModeController().setLiveMode(enabled);
    }

    /** Returns the LiveModeController to pass to {@code brainAgent.setLiveModeController()}. */
    public BrainAgent.LiveModeController buildLiveModeController() {
        if (cachedLiveController != null) return cachedLiveController;
        cachedLiveController = new BrainAgent.LiveModeController() {

            @Override
            public void setLiveMode(boolean enabled) {
                mainHandler.post(() -> {
                    if (enabled) {
                        // Reset display
                        liveOutput.setText("");
                        liveInput.setText("");
                        livePanel.setVisibility(VISIBLE);
                        // Slide panel down from top
                        livePanel.setTranslationY(-200f);
                        livePanel.animate()
                                .translationY(0f)
                                .setDuration(220)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .start();
                        // Pulse "▶ LIVE TERMINAL" label
                        AlphaAnimation pulse = new AlphaAnimation(0.25f, 1f);
                        pulse.setDuration(500);
                        pulse.setRepeatMode(Animation.REVERSE);
                        pulse.setRepeatCount(Animation.INFINITE);
                        livePanelLabel.startAnimation(pulse);
                    } else {
                        // Stop pulsing label
                        livePanelLabel.clearAnimation();
                        livePanelLabel.setAlpha(1f);
                        // Slide panel back up
                        livePanel.animate()
                                .translationY(-200f)
                                .setDuration(200)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() -> {
                                    livePanel.setVisibility(GONE);
                                    livePanel.setTranslationY(0f);
                                })
                                .start();
                    }
                });
            }

            @Override
            public void typeLiveChar(char c) {
                mainHandler.post(() -> {
                    liveInput.append(String.valueOf(c));
                    // Show "$ command▌" in green with blinking cursor
                    liveOutput.setText("$ " + liveInput.getText().toString() + "▌");
                    // Keep scrolled to end so cursor is always visible
                    liveScroll.post(() -> liveScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
                });
            }

            @Override
            public void submitLiveInput() {
                mainHandler.post(() -> {
                    String cmd = liveInput.getText().toString();
                    // Show command in green + executing status in white
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    SpannableString cmdSpan = new SpannableString("$ " + cmd + "\n");
                    cmdSpan.setSpan(new ForegroundColorSpan(CLR_GREEN), 0, cmdSpan.length(), 0);
                    sb.append(cmdSpan);
                    SpannableString execSpan = new SpannableString("\n  ⚙ executing…");
                    execSpan.setSpan(new ForegroundColorSpan(CLR_WHITE), 0, execSpan.length(), 0);
                    sb.append(execSpan);
                    liveOutput.setText(sb);
                });
            }

            /** Call this after command finishes to show coloured result in the live panel. */
            @Override
            public void showLiveResult(String output, boolean success) {
                mainHandler.post(() -> {
                    String cmd = liveInput.getText().toString();
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    // Command line — always green
                    SpannableString cmdSpan = new SpannableString("$ " + cmd + "\n");
                    cmdSpan.setSpan(new ForegroundColorSpan(CLR_GREEN), 0, cmdSpan.length(), 0);
                    sb.append(cmdSpan);
                    // Output — green for success, red for error
                    int outColor = success ? CLR_GREEN : CLR_RED;
                    SpannableString outSpan = new SpannableString(output.isEmpty() ? "(no output)" : output);
                    outSpan.setSpan(new ForegroundColorSpan(outColor), 0, outSpan.length(), 0);
                    sb.append(outSpan);
                    liveOutput.setText(sb);
                    liveScroll.post(() -> liveScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
                });
            }
        };
        return cachedLiveController;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Bubble factories
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * @param text    Message text.
     * @param isAgent TRUE = agent bubble (left, orange border, CVA label).
     *                FALSE = user bubble (right, cyan border).
     */
    private LinearLayout makeBubble(String text, boolean isAgent) {
        int screenW = getResources().getDisplayMetrics().widthPixels;

        // Outer row — controls left/right gravity
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        LayoutParams rowP = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowP.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(rowP);
        row.setGravity(isAgent ? Gravity.START : Gravity.END);

        // Bubble body
        LinearLayout bubble = new LinearLayout(getContext());
        bubble.setOrientation(VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));

        int maxBubbleW = (int) (screenW * 0.84f);
        LayoutParams bubP = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        bubP.width = LayoutParams.WRAP_CONTENT;
        // Clamp width by wrapping bubble in a fixed-max container via LayoutParams
        bubP.setMargins(0, 0, 0, 0);
        bubble.setLayoutParams(bubP);
        // Enforce max width: wrap inside a sized container view
        bubble.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                bubble.getViewTreeObserver().removeOnPreDrawListener(this);
                if (bubble.getWidth() > maxBubbleW) {
                    LayoutParams lp = (LayoutParams) bubble.getLayoutParams();
                    lp.width = maxBubbleW;
                    bubble.setLayoutParams(lp);
                }
                return true;
            }
        });

        if (isAgent) {
            bubble.setBackground(agentBg());
            // CVA label
            TextView lbl = new TextView(getContext());
            lbl.setText("CVA");
            lbl.setTextColor(CLR_ORANGE);
            lbl.setTextSize(9f);
            lbl.setTypeface(null, Typeface.BOLD);
            lbl.setLetterSpacing(0.18f);
            lbl.setPadding(0, 0, 0, dp(4));
            bubble.addView(lbl);
        } else {
            bubble.setBackground(userBg());
        }

        // Message text
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(isAgent ? CLR_WHITE : CLR_CYAN);
        tv.setTextSize(13f);
        tv.setLineSpacing(3f, 1f);
        bubble.addView(tv);

        // Slide-in fade
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(200);
        bubble.startAnimation(fadeIn);

        row.addView(bubble);
        return row;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Thinking bubble  (with blinking ●CVA● badge + rotating ⚙ gear)
    // ═════════════════════════════════════════════════════════════════════════
    private void createThinkingBubble() {
        int screenW = getResources().getDisplayMetrics().widthPixels;

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        LayoutParams rowP = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowP.setMargins(0, dp(5), 0, dp(5));
        row.setLayoutParams(rowP);
        row.setGravity(Gravity.START);

        int maxBubbleW = (int) (screenW * 0.84f);
        LinearLayout bubble = new LinearLayout(getContext());
        bubble.setOrientation(VERTICAL);
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(agentBg());
        bubble.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                bubble.getViewTreeObserver().removeOnPreDrawListener(this);
                if (bubble.getWidth() > maxBubbleW) {
                    LayoutParams lp = (LayoutParams) bubble.getLayoutParams();
                    if (lp != null) { lp.width = maxBubbleW; bubble.setLayoutParams(lp); }
                }
                return true;
            }
        });

        // ── ●CVA● blinking badge ──────────────────────────────────────────────
        blinkingLogo = new TextView(getContext());
        blinkingLogo.setText("●CVA●");
        blinkingLogo.setTextColor(CLR_ORANGE);
        blinkingLogo.setTextSize(12f);
        blinkingLogo.setTypeface(null, Typeface.BOLD);
        blinkingLogo.setLetterSpacing(0.2f);
        blinkingLogo.setPadding(0, 0, 0, dp(4));
        bubble.addView(blinkingLogo);

        // ── Gear + status text row ────────────────────────────────────────────
        LinearLayout statusRow = new LinearLayout(getContext());
        statusRow.setOrientation(HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        // Rotating green ⚙ gear
        gearView = new TextView(getContext());
        gearView.setText("⚙");
        gearView.setTextColor(CLR_GEAR);
        gearView.setTextSize(14f);
        gearView.setTypeface(null, Typeface.BOLD);
        // Pivot exactly at centre so rotation looks right
        gearView.setPivotX(gearView.getTextSize() / 2f);
        gearView.setPivotY(gearView.getTextSize() / 2f);
        LayoutParams gearP = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        gearP.setMargins(0, 0, dp(6), 0);
        gearView.setLayoutParams(gearP);
        statusRow.addView(gearView);

        // Status text (colour set dynamically by onAgentStep)
        agentThinkingText = new TextView(getContext());
        agentThinkingText.setText("CVA thinking…");
        agentThinkingText.setTextColor(CLR_WHITE);
        agentThinkingText.setTextSize(12f);
        agentThinkingText.setLineSpacing(3f, 1f);
        statusRow.addView(agentThinkingText,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        bubble.addView(statusRow);
        row.addView(bubble);
        bubbleContainer.addView(row);
        agentThinkingRow = row;

        // Pivot fix after first layout pass
        gearView.post(() -> {
            gearView.setPivotX(gearView.getWidth()  / 2f);
            gearView.setPivotY(gearView.getHeight() / 2f);
        });
    }

    // ── Blink control ─────────────────────────────────────────────────────────

    private void startBlink() {
        if (blinkingLogo == null || blinkAnim != null) return;
        blinkAnim = ValueAnimator.ofFloat(1f, 0.1f, 1f);
        blinkAnim.setDuration(850);
        blinkAnim.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnim.setRepeatMode(ValueAnimator.RESTART);
        blinkAnim.addUpdateListener(a -> {
            if (blinkingLogo != null) blinkingLogo.setAlpha((float) a.getAnimatedValue());
        });
        blinkAnim.start();
    }

    private void stopBlink() {
        if (blinkAnim != null) { blinkAnim.cancel(); blinkAnim = null; }
        if (blinkingLogo != null) blinkingLogo.setAlpha(1f);
    }

    // ── Gear rotation control ─────────────────────────────────────────────────

    private void startGear() {
        if (gearView == null || gearAnim != null) return;
        gearAnim = ObjectAnimator.ofFloat(gearView, "rotation", 0f, 360f);
        gearAnim.setDuration(900);
        gearAnim.setRepeatCount(ObjectAnimator.INFINITE);
        gearAnim.setRepeatMode(ObjectAnimator.RESTART);
        gearAnim.setInterpolator(new LinearInterpolator());
        gearAnim.start();
    }

    private void stopGear() {
        if (gearAnim != null) { gearAnim.cancel(); gearAnim = null; }
        if (gearView != null) gearView.setRotation(0f);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Scroll
    // ═════════════════════════════════════════════════════════════════════════
    private void scrollToBottom() {
        // Double-post: first waits for layout, second actually scrolls
        scrollView.post(() ->
                scrollView.post(() ->
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN)));
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Background drawables (no XML needed)
    // ═════════════════════════════════════════════════════════════════════════

    /** Agent bubble: dark bg + orange 2dp border, rounded except top-left */
    private android.graphics.drawable.GradientDrawable agentBg() {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        // TL, TR, BR, BL  (asymmetric — speech-bubble style)
        d.setCornerRadii(new float[]{ dp(3),dp(3), dp(14),dp(14), dp(14),dp(14), dp(3),dp(3) });
        d.setColor(CLR_AGENT_BG);
        d.setStroke(dp(2), CLR_ORANGE);
        return d;
    }

    /** User bubble: dark blue bg + cyan 1dp border, rounded except top-right */
    private android.graphics.drawable.GradientDrawable userBg() {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadii(new float[]{ dp(14),dp(14), dp(3),dp(3), dp(14),dp(14), dp(14),dp(14) });
        d.setColor(CLR_USER_BG);
        d.setStroke(dp(1), CLR_CYAN);
        return d;
    }

    // ── Utility ───────────────────────────────────────────────────────────────
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}