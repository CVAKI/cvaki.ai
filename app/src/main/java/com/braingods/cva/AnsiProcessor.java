package com.braingods.cva;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.widget.TextView;
import android.widget.ScrollView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AnsiProcessor  [v1.0 — CVA terminal ANSI renderer]
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * ROOT CAUSE OF RAW ESCAPE CODES IN TEXTVIEW:
 *   Android's TextView silently drops the ESC character (0x1B / \u001B) when
 *   rendering text.  It's a non-printable control character — TextView leaves
 *   it in the underlying string, but the canvas renderer skips it.  The '[' that
 *   follows is printable, so it IS rendered — giving you "[1;96m" instead of a
 *   green color.
 *
 *   The solution is to NEVER put raw ANSI strings into a TextView.  Instead,
 *   pass all terminal output through AnsiProcessor before display.
 *
 * HOW TO USE — replace raw append calls with AnsiProcessor:
 *
 *   // BEFORE (shows raw escape codes):
 *   tvTerminal.append(chunk);
 *
 *   // AFTER (renders colors + styles):
 *   AnsiProcessor.append(tvTerminal, chunk, scrollView);
 *
 *   // Or build a full SpannableStringBuilder for batch updates:
 *   SpannableStringBuilder ssb = new SpannableStringBuilder();
 *   AnsiProcessor.appendTo(ssb, chunk);
 *   tvTerminal.setText(ssb);
 *
 * SUPPORTED SEQUENCES:
 *   \033[0m          — reset all attributes
 *   \033[1m          — bold
 *   \033[2m          — dim (50% alpha approximation)
 *   \033[4m          — underline
 *   \033[22m         — normal intensity
 *   \033[24m         — no underline
 *   \033[30-37m      — standard fg colors
 *   \033[38;2;R;G;Bm — 24-bit truecolor fg
 *   \033[38;5;Nm     — 256-color fg
 *   \033[40-47m      — standard bg colors
 *   \033[48;2;R;G;Bm — 24-bit truecolor bg
 *   \033[48;5;Nm     — 256-color bg
 *   \033[90-97m      — bright fg colors
 *   \033[100-107m    — bright bg colors
 *   \033[H           — cursor home (ignored — no cursor in TextView)
 *   \033[2J          — clear screen → clears the SpannableStringBuilder buffer
 *   \033[3J          — clear scrollback → same as 2J
 *   \033[K           — erase line → ignored
 *   \033[?25h/l      — show/hide cursor → ignored
 *   \033]0;...\007   — OSC title → ignored
 *   \033[s/u         — save/restore cursor → ignored
 */
public class AnsiProcessor {

    // Matches any CSI sequence: ESC [ ... (letter or ~)
    private static final Pattern CSI = Pattern.compile(
            "\u001B\\[([\\d;?]*)([A-Za-z~])");
    // Matches OSC sequences: ESC ] ... BEL  or  ESC ] ... ST
    private static final Pattern OSC = Pattern.compile(
            "\u001B\\][^\\u0007\u001B]*(?:\\u0007|\u001B\\\\)");
    // Matches a lone ESC not part of CSI/OSC (e.g. ESC M = reverse index)
    private static final Pattern LONE_ESC = Pattern.compile(
            "\u001B[^\\[\\]]");

    // Standard ANSI 16-color palette (matches xterm defaults used by bash)
    private static final int[] ANSI_COLORS = {
            // Normal (30-37, 40-47)
            Color.parseColor("#1C1C1C"), // 0 black
            Color.parseColor("#CC3333"), // 1 red
            Color.parseColor("#33CC33"), // 2 green
            Color.parseColor("#CCCC33"), // 3 yellow
            Color.parseColor("#3333CC"), // 4 blue
            Color.parseColor("#CC33CC"), // 5 magenta
            Color.parseColor("#33CCCC"), // 6 cyan
            Color.parseColor("#CCCCCC"), // 7 white
            // Bright (90-97, 100-107)
            Color.parseColor("#666666"), // 8  bright black (dark gray)
            Color.parseColor("#FF4444"), // 9  bright red
            Color.parseColor("#44FF44"), // 10 bright green
            Color.parseColor("#FFFF44"), // 11 bright yellow
            Color.parseColor("#4444FF"), // 12 bright blue
            Color.parseColor("#FF44FF"), // 13 bright magenta
            Color.parseColor("#44FFFF"), // 14 bright cyan
            Color.parseColor("#FFFFFF"), // 15 bright white
    };

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Append ANSI-encoded text to a TextView, rendering escape codes as spans.
     * Call from the main thread (or post to the main thread).
     *
     * @param tv        Terminal output TextView
     * @param ansiText  Raw terminal output chunk (may contain ANSI escape codes)
     * @param sv        Optional: if non-null, auto-scrolled to bottom after append
     */
    public static void append(TextView tv, String ansiText, ScrollView sv) {
        if (ansiText == null || ansiText.isEmpty()) return;

        SpannableStringBuilder buf;
        CharSequence existing = tv.getText();
        if (existing instanceof SpannableStringBuilder) {
            buf = (SpannableStringBuilder) existing;
        } else {
            buf = new SpannableStringBuilder(existing != null ? existing : "");
        }

        // Handle clear-screen sequences — clear the entire buffer
        if (ansiText.contains("\u001B[2J") || ansiText.contains("\u001B[3J")
                || ansiText.contains("\u001B[H\u001B[2J")) {
            buf.clear();
            // Remove the clear sequence from the text, then continue processing
            ansiText = ansiText
                    .replace("\u001B[H\u001B[2J\u001B[3J", "")
                    .replace("\u001B[2J\u001B[3J", "")
                    .replace("\u001B[2J", "")
                    .replace("\u001B[3J", "")
                    .replace("\u001B[H", "");
            if (ansiText.isEmpty()) {
                tv.setText(buf);
                return;
            }
        }

        // Trim buffer to max 50 000 chars to prevent OOM on long sessions
        final int MAX_CHARS = 50_000;
        if (buf.length() > MAX_CHARS) {
            buf.delete(0, buf.length() - MAX_CHARS / 2);
        }

        appendTo(buf, ansiText);
        tv.setText(buf);

        if (sv != null) sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /**
     * Appends ANSI-encoded text to an existing SpannableStringBuilder.
     * Creates a fresh AnsiState (colors reset to default for each call).
     * Use the overload with AnsiState if you need persistent state across chunks.
     */
    public static void appendTo(SpannableStringBuilder sb, String ansiText) {
        appendTo(sb, ansiText, new AnsiState());
    }

    /**
     * Appends ANSI-encoded text with PERSISTENT state.
     * Pass the same AnsiState instance across multiple appendTo() calls to
     * preserve color/bold state at the end of one chunk into the next.
     *
     * This is what TerminalManager uses — the shell may emit a color escape at
     * the end of one read() chunk and the colored text in the next chunk.
     */
    public static void appendTo(SpannableStringBuilder sb, String ansiText, AnsiState state) {
        if (ansiText == null || ansiText.isEmpty()) return;
        processText(sb, ansiText, state);
    }

    /**
     * Strip all ANSI escape codes and return plain text.
     * Use when you need a copy-pasteable string without color markup.
     */
    public static String strip(String ansiText) {
        if (ansiText == null) return "";
        return ansiText
                .replaceAll("\u001B\\[[\\d;?]*[A-Za-z~]", "")
                .replaceAll("\u001B\\][^\\u0007\u001B]*(?:\\u0007|\u001B\\\\)", "")
                .replaceAll("\u001B[^\\[\\]]", "")
                .replaceAll("\u001B", "");
    }

    // ── Core processor ────────────────────────────────────────────────────────

    private static void processText(SpannableStringBuilder sb, String text, AnsiState st) {
        // Walk through the string, handling ESC sequences and plain segments
        int i = 0;
        int len = text.length();
        StringBuilder plain = new StringBuilder();

        while (i < len) {
            char c = text.charAt(i);

            if (c == '\u001B' && i + 1 < len) {
                // Flush any accumulated plain text first
                if (plain.length() > 0) {
                    applyPlain(sb, plain.toString(), st);
                    plain.setLength(0);
                }

                char next = text.charAt(i + 1);

                if (next == '[') {
                    // CSI sequence — find the end
                    int end = i + 2;
                    while (end < len && !Character.isLetter(text.charAt(end))
                            && text.charAt(end) != '~') end++;
                    if (end < len) {
                        String params = text.substring(i + 2, end);
                        char cmd    = text.charAt(end);
                        handleCsi(params, cmd, st);
                        i = end + 1;
                    } else {
                        // Incomplete sequence at end of chunk — skip ESC
                        i++;
                    }

                } else if (next == ']') {
                    // OSC sequence — find BEL or ST
                    int end = text.indexOf('\u0007', i + 2);
                    if (end < 0) end = text.indexOf("\u001B\\", i + 2);
                    i = end >= 0 ? end + 1 : len; // skip entire OSC

                } else {
                    // Lone ESC (e.g. ESC M) — skip both chars
                    i += 2;
                }

            } else if (c == '\r') {
                // Carriage return — skip (bare \r without \n)
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    plain.append('\n');
                    i += 2;
                } else {
                    // \r alone — move to start of current line (hard to impl in TextView)
                    // Best effort: emit newline
                    plain.append('\n');
                    i++;
                }
            } else {
                plain.append(c);
                i++;
            }
        }

        if (plain.length() > 0) {
            applyPlain(sb, plain.toString(), st);
        }
    }

    private static void applyPlain(SpannableStringBuilder sb, String text, AnsiState st) {
        if (text.isEmpty()) return;
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        if (start == end) return; // safety — never set zero-length spans

        if (st.fgColor != Color.TRANSPARENT) {
            sb.setSpan(new ForegroundColorSpan(st.fgColor),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (st.bgColor != Color.TRANSPARENT) {
            sb.setSpan(new BackgroundColorSpan(st.bgColor),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int typefaceStyle = (st.bold ? Typeface.BOLD : 0)
                | (st.italic ? Typeface.ITALIC : 0);
        if (typefaceStyle != 0) {
            sb.setSpan(new StyleSpan(typefaceStyle),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (st.underline) {
            sb.setSpan(new UnderlineSpan(),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ── CSI handler ───────────────────────────────────────────────────────────

    private static void handleCsi(String params, char cmd, AnsiState st) {
        switch (cmd) {
            case 'm': // SGR — Select Graphic Rendition
                handleSgr(params, st);
                break;
            case 'H': // Cursor position / cursor home — ignore
            case 'f':
            case 'A': case 'B': case 'C': case 'D': // cursor move — ignore
            case 'J': // erase display — handled at higher level
            case 'K': // erase line — ignore
            case 's': case 'u': // save/restore cursor — ignore
            case 'h': case 'l': // set/reset mode (?25h show cursor etc.) — ignore
                break;
            default:
                break;
        }
    }

    private static void handleSgr(String params, AnsiState st) {
        if (params == null || params.isEmpty()) {
            st.reset(); return;
        }
        String[] parts = params.split(";");
        int idx = 0;
        while (idx < parts.length) {
            int n;
            try { n = Integer.parseInt(parts[idx].trim()); }
            catch (NumberFormatException e) { idx++; continue; }

            switch (n) {
                case 0:  st.reset();          break;
                case 1:  st.bold = true;       break;
                case 2:  st.bold = false;      break; // dim — approximate as non-bold
                case 3:  st.italic = true;     break;
                case 4:  st.underline = true;  break;
                case 22: st.bold = false;      break;
                case 23: st.italic = false;    break;
                case 24: st.underline = false; break;
                case 39: st.fgColor = Color.TRANSPARENT; break; // default fg
                case 49: st.bgColor = Color.TRANSPARENT; break; // default bg

                // Standard fg (30-37) and bright fg (90-97)
                case 30: case 31: case 32: case 33:
                case 34: case 35: case 36: case 37:
                    st.fgColor = ANSI_COLORS[n - 30]; break;
                case 90: case 91: case 92: case 93:
                case 94: case 95: case 96: case 97:
                    st.fgColor = ANSI_COLORS[n - 90 + 8]; break;

                // Standard bg (40-47) and bright bg (100-107)
                case 40: case 41: case 42: case 43:
                case 44: case 45: case 46: case 47:
                    st.bgColor = ANSI_COLORS[n - 40]; break;
                case 100: case 101: case 102: case 103:
                case 104: case 105: case 106: case 107:
                    st.bgColor = ANSI_COLORS[n - 100 + 8]; break;

                // 256-color and truecolor fg
                case 38:
                    if (idx + 1 < parts.length) {
                        int mode = parseInt(parts[idx + 1]);
                        if (mode == 5 && idx + 2 < parts.length) {
                            st.fgColor = color256(parseInt(parts[idx + 2]));
                            idx += 2;
                        } else if (mode == 2 && idx + 4 < parts.length) {
                            st.fgColor = Color.rgb(parseInt(parts[idx + 2]),
                                    parseInt(parts[idx + 3]), parseInt(parts[idx + 4]));
                            idx += 4;
                        }
                    }
                    break;

                // 256-color and truecolor bg
                case 48:
                    if (idx + 1 < parts.length) {
                        int mode = parseInt(parts[idx + 1]);
                        if (mode == 5 && idx + 2 < parts.length) {
                            st.bgColor = color256(parseInt(parts[idx + 2]));
                            idx += 2;
                        } else if (mode == 2 && idx + 4 < parts.length) {
                            st.bgColor = Color.rgb(parseInt(parts[idx + 2]),
                                    parseInt(parts[idx + 3]), parseInt(parts[idx + 4]));
                            idx += 4;
                        }
                    }
                    break;

                default: break;
            }
            idx++;
        }
    }

    // ── 256-color palette ─────────────────────────────────────────────────────

    private static int color256(int n) {
        if (n < 0) n = 0;
        if (n > 255) n = 255;
        if (n < 16) return ANSI_COLORS[n];
        if (n < 232) {
            // 6x6x6 color cube
            n -= 16;
            int b = n % 6, g = (n / 6) % 6, r = n / 36;
            return Color.rgb(r > 0 ? 55 + r * 40 : 0,
                    g > 0 ? 55 + g * 40 : 0,
                    b > 0 ? 55 + b * 40 : 0);
        }
        // Grayscale ramp (232-255)
        int gray = 8 + (n - 232) * 10;
        return Color.rgb(gray, gray, gray);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // ── State object ──────────────────────────────────────────────────────────

    /**
     * Holds current rendering attributes.
     * Create one per terminal session and pass it across append calls if you
     * want color state to persist across chunk boundaries.
     */
    public static class AnsiState {
        public int     fgColor   = Color.TRANSPARENT; // TRANSPARENT = use TextView default
        public int     bgColor   = Color.TRANSPARENT;
        public boolean bold      = false;
        public boolean italic    = false;
        public boolean underline = false;

        public void reset() {
            fgColor   = Color.TRANSPARENT;
            bgColor   = Color.TRANSPARENT;
            bold      = false;
            italic    = false;
            underline = false;
        }
    }
}