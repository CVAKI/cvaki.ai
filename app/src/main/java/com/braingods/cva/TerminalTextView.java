package com.braingods.cva;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.util.AttributeSet;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * TerminalTextView  [v1.0]
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Drop-in replacement for any raw TextView used to display terminal output.
 * Internally pipes all text through AnsiProcessor so ANSI escape sequences
 * are rendered as colors/bold/underline instead of showing as raw "[1;96m".
 *
 * HOW TO USE — two options:
 *
 * OPTION A: Use this class in your XML layout (no code changes needed):
 *
 *   <!-- In your keyboard/terminal layout XML, replace: -->
 *   <TextView android:id="@+id/tv_terminal" ... />
 *
 *   <!-- With: -->
 *   <com.braingods.cva.TerminalTextView android:id="@+id/tv_terminal" ... />
 *
 * OPTION B: In your existing code, replace raw append calls:
 *
 *   // BEFORE (shows raw ANSI codes):
 *   tvTerminal.append(outputChunk);
 *
 *   // AFTER (renders colors):
 *   AnsiProcessor.append(tvTerminal, outputChunk, svScroll);
 *
 * HOOKING INTO TerminalManager OUTPUT:
 *
 *   In KeyboardService / MainActivity wherever you wire up the OutputCallback:
 *
 *   terminalManager.setOutputCallback(text -> {
 *       runOnUiThread(() ->                          // or mainHandler.post()
 *           AnsiProcessor.append(tvTerminal, text, svTerminal)
 *       );
 *   });
 *
 *   If the callback is already on the main thread (TerminalManager.emit() posts
 *   to mainHandler), just call AnsiProcessor.append directly without post().
 */
public class TerminalTextView extends androidx.appcompat.widget.AppCompatTextView {

    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private ScrollView scrollView = null;

    public TerminalTextView(Context context) {
        super(context); init();
    }

    public TerminalTextView(Context context, AttributeSet attrs) {
        super(context, attrs); init();
    }

    public TerminalTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle); init();
    }

    private void init() {
        setTypeface(Typeface.MONOSPACE);
        setTextIsSelectable(true);
    }

    /**
     * Attach an optional ScrollView so the terminal auto-scrolls to the bottom
     * on each new output chunk.
     */
    public void setScrollView(ScrollView sv) {
        this.scrollView = sv;
    }

    /**
     * Append raw terminal output (may contain ANSI escape sequences).
     * Replaces the plain TextView.append() call.
     */
    public void appendAnsi(CharSequence ansiText) {
        AnsiProcessor.append(this, ansiText.toString(), scrollView);
    }

    /**
     * Append raw terminal output — implements TerminalManager.OutputCallback so
     * you can pass this::appendAnsi as the callback lambda.
     */
    public TerminalManager.OutputCallback asCallback() {
        return this::appendAnsi;
    }

    /**
     * Clear the terminal buffer.
     */
    public void clearTerminal() {
        buffer.clear();
        setText(buffer);
    }

    /**
     * Get the plain-text content of the terminal (ANSI codes stripped).
     * Useful for copy-to-clipboard.
     */
    public String getPlainText() {
        return AnsiProcessor.strip(buffer.toString());
    }
}