package com.braingods.cva;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class KeyboardService extends InputMethodService {

    // ── Section indices ──────────────────────────────────────────────────────
    private static final int SEC_NORMAL   = 0;
    private static final int SEC_HASH     = 1;
    private static final int SEC_TERMINAL = 2;
    private static final int SEC_BRAIN    = 3;

    private int currentSection = SEC_NORMAL;
    private View mainView;

    // section root views
    private View sectionNormal, sectionHash, sectionTerminal, sectionBrain;

    // nav buttons
    private Button btnNavNormal, btnNavHash, btnNavTerminal, btnNavBrain;

    // shift / caps
    private boolean isShiftOn  = false;
    private boolean isCapsLock = false;
    private long    lastShift  = 0;

    // numbers / symbols mode  (false = letters, true = nums+symbols)
    private boolean isNumSymMode      = false;   // normal & hash keyboards
    private boolean isTermNumSymMode  = false;   // terminal keyboard
    private boolean isSymPage2        = false;   // second symbols page (normal kb)
    private boolean isTermSymPage2    = false;   // second symbols page (terminal kb)

    // ── Terminal ─────────────────────────────────────────────────────────────
    private TerminalManager terminalManager;
    private TextView  tvTermOut;
    private ScrollView svTermOut;
    private View      termSlideKb;
    private boolean   termKbVisible = false;
    private StringBuilder termBuf = new StringBuilder();
    private TextView tvTermInputLine;
    private boolean termOutputVisible = true;
    private boolean autoImportOn = false;

    // ── Brain / CVA ──────────────────────────────────────────────────────────
    private BrainAgent brainAgent;
    private LinearLayout tvBrainOut;  // bubble container
    private String lastCvaReply = "";
    private ScrollView svBrainOut;
    private View       brainSlideKb;
    private boolean    brainKbVisible = false;
    private StringBuilder brainBuf = new StringBuilder();
    private boolean isBrainNumSymMode = false;
    private boolean isBrainSymPage2   = false;

    /** TRUE = Screen-Agent mode ON — brain can see the screen and act autonomously */
    private boolean brainSwitchOn = false;

    // Background executor
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // =========================================================================
    // IME lifecycle
    // =========================================================================

    @Override
    public View onCreateInputView() {
        mainView = getLayoutInflater().inflate(R.layout.keyboard_main, null);

        sectionNormal   = mainView.findViewById(R.id.section_normal);
        sectionHash     = mainView.findViewById(R.id.section_hash);
        sectionTerminal = mainView.findViewById(R.id.section_terminal);
        sectionBrain    = mainView.findViewById(R.id.section_brain);

        setupNav();
        buildKeyboardRows(sectionNormal,  false);
        buildKeyboardRows(sectionHash,    true);
        setupHashSection();
        setupTerminalSection();
        setupBrainSection();

        // Terminal manager pipes shell output → TextView
        terminalManager = new TerminalManager(this, chunk -> ui.post(() -> {
            tvTermOut.append(chunk);
            svTermOut.post(() -> svTermOut.fullScroll(ScrollView.FOCUS_DOWN));
            if (autoImportOn) pushChunkToCursor(chunk);
        }));

        SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        String provider = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);
        // Use per-provider key; fall back to legacy "api_key" field
        String activeKey;
        switch (provider) {
            case BrainAgent.PROVIDER_GEMINI:     activeKey = prefs.getString("key_gemini",     ""); break;
            case BrainAgent.PROVIDER_OPENROUTER: activeKey = prefs.getString("key_openrouter", ""); break;
            default:                             activeKey = prefs.getString("key_anthropic",  prefs.getString("api_key", "")); break;
        }
        brainAgent = new BrainAgent(provider, activeKey, this);
        brainAgent.setTerminalManager(terminalManager);
        // agentCallback: while running → update the status ticker only.
        // Final answer (isRunning=false) → add the reply bubble exactly ONCE here.
        // sendBrainMsg() adds the user bubble + thinking bubble only.
        brainAgent.setAgentCallback((msg, isRunning, scrollToEnd) -> ui.post(() -> {
            if (currentSection != SEC_BRAIN) return;
            updateAgentStatusTicker(msg, isRunning);
            if (!isRunning && msg != null && !msg.isEmpty()) {
                // Final answer: store for import button and add exactly one bubble
                lastCvaReply = msg;
                addBubble(msg, false);
                if (svBrainOut != null)
                    svBrainOut.post(() -> svBrainOut.fullScroll(ScrollView.FOCUS_DOWN));
            }
        }));

        showSection(SEC_NORMAL);
        return mainView;
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private void setupNav() {
        btnNavNormal   = mainView.findViewById(R.id.btn_nav_normal);
        btnNavHash     = mainView.findViewById(R.id.btn_nav_hash);
        btnNavTerminal = mainView.findViewById(R.id.btn_nav_terminal);
        btnNavBrain    = mainView.findViewById(R.id.btn_nav_brain);

        btnNavNormal  .setOnClickListener(v -> showSection(SEC_NORMAL));
        btnNavHash    .setOnClickListener(v -> showSection(SEC_HASH));
        btnNavTerminal.setOnClickListener(v -> showSection(SEC_TERMINAL));
        btnNavBrain   .setOnClickListener(v -> showSection(SEC_BRAIN));
    }

    private void showSection(int s) {
        currentSection = s;
        sectionNormal  .setVisibility(s == SEC_NORMAL   ? View.VISIBLE : View.GONE);
        sectionHash    .setVisibility(s == SEC_HASH     ? View.VISIBLE : View.GONE);
        sectionTerminal.setVisibility(s == SEC_TERMINAL ? View.VISIBLE : View.GONE);
        sectionBrain   .setVisibility(s == SEC_BRAIN    ? View.VISIBLE : View.GONE);
        highlightNav(s);
    }

    private void highlightNav(int s) {
        // Active tab: fox orange fill with dark text; inactive: dark surface with muted text
        int activeBg   = Color.parseColor("#FF6A1A");
        int inactiveBg = Color.parseColor("#1A1A1A");
        int activeText   = Color.parseColor("#0A0A0A");
        int inactiveText = Color.parseColor("#888888");

        btnNavNormal  .setBackgroundColor(s == SEC_NORMAL   ? activeBg : inactiveBg);
        btnNavHash    .setBackgroundColor(s == SEC_HASH     ? activeBg : inactiveBg);
        btnNavTerminal.setBackgroundColor(s == SEC_TERMINAL ? activeBg : inactiveBg);
        btnNavBrain   .setBackgroundColor(s == SEC_BRAIN    ? activeBg : inactiveBg);

        btnNavNormal  .setTextColor(s == SEC_NORMAL   ? activeText : inactiveText);
        btnNavHash    .setTextColor(s == SEC_HASH     ? activeText : inactiveText);
        btnNavTerminal.setTextColor(s == SEC_TERMINAL ? activeText : inactiveText);
        btnNavBrain   .setTextColor(s == SEC_BRAIN    ? activeText : inactiveText);
    }

    // =========================================================================
    // Normal & Hash keyboard builder
    // =========================================================================

    private static final String[][] QWERTY = {
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"SHF","z","x","c","v","b","n","m","DEL"},
            {"?123"," ","↵"}
    };

    // Numbers + symbols page 1
    private static final String[][] NUM_SYM_1 = {
            {"1","2","3","4","5","6","7","8","9","0"},
            {"!","@","#","$","%","^","&","*","(",")",},
            {"=\\<","-","_","+","=","[","]","{","}","DEL"},
            {"ABC","SYM2"," ","↵"}
    };

    // Symbols page 2
    private static final String[][] NUM_SYM_2 = {
            {"~","`","\\","|","/","<",">","?",":",";"},
            {"'","\"",",",".","…","•","€","£","¥","°"},
            {"©","®","™","←","→","↑","↓","×","÷","DEL"},
            {"ABC","SYM1"," ","↵"}
    };

    private void buildKeyboardRows(View parent, boolean hash) {
        LinearLayout container = parent.findViewById(R.id.keyboard_rows_container);
        container.removeAllViews();
        String[][] layout = isNumSymMode
                ? (isSymPage2 ? NUM_SYM_2 : NUM_SYM_1)
                : QWERTY;
        for (String[] row : layout) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            rl.setPadding(dp(3), dp(2), dp(3), dp(2));
            rl.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            for (String k : row) {
                rl.addView(makeKey(k, hash, false));
            }
            container.addView(rl);
        }
    }

    // =========================================================================
    // Key factory
    // =========================================================================

    private Button makeKey(String key, boolean hash, boolean terminal) {
        Button b = new Button(this);
        float weight = switch (key) {
            case " "               -> 4f;
            case "SHF","DEL","↵"  -> 1.5f;
            case "?123","ABC","SYM1","SYM2","=\\<" -> 1.5f;
            default -> 1f;
        };
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), weight);
        p.setMargins(4,3,4,3);
        b.setLayoutParams(p);
        b.setPadding(0,0,0,0);

        // Keys always display normal letters — hash mode only changes the OUTPUT
        String display = switch (key) {
            case "SHF"   -> "⇧";
            case "DEL"   -> "⌫";
            case "↵"    -> "↵";
            case "?123"  -> "?123";
            case "ABC"   -> "ABC";
            case "SYM1"  -> "SYM";
            case "SYM2"  -> "SYM2";
            case "=\\<"  -> "=\\<";
            case " "     -> "𝗖𝗩♞𝗞𝗜";
            default      -> isShiftOn ? key.toUpperCase() : key;
        };

        b.setText(display);
        b.setTextSize(display.length() > 2 ? 18 : 26);
        b.setTextColor(key.equals(" ") ? Color.parseColor("#FF6A1A") : Color.WHITE);
        b.setBackground(roundedKey(keyColor(key)));

        b.setOnClickListener(v -> {
            if (terminal) handleTermKey(key);
            else          handleKey(key, hash);
        });
        if (key.equals("DEL")) {
            b.setOnLongClickListener(v -> { deleteLine(); return true; });
        }
        return b;
    }

    private int keyColor(String k) {
        return switch (k) {
            // Action keys — fox orange theme
            case "↵"   -> Color.parseColor("#FF6A1A");   // Enter: full orange CTA
            case "DEL" -> Color.parseColor("#CC3300");   // Delete: burnt orange-red
            case "SHF" -> Color.parseColor("#2A2A2A");   // Shift: dark elevated
            case "?123","ABC","SYM1","SYM2","=\\<" -> Color.parseColor("#222222");
            case " "   -> Color.parseColor("#1C1C1C");   // Space: subtly lighter
            default -> {
                if (k.length() == 1 && Character.isDigit(k.charAt(0)))
                    yield Color.parseColor("#2C1800");    // Digits: warm dark tint
                yield Color.parseColor("#161616");        // Letters: pure dark
            }
        };
    }

    // =========================================================================
    // Rounded key background helper
    // =========================================================================

    private android.graphics.drawable.GradientDrawable roundedKey(int color) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(10));
        gd.setColor(color);
        return gd;
    }

    // =========================================================================
    // Normal / Hash key handler
    // =========================================================================

    private void handleKey(String key, boolean hash) {
        vibrate();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        switch (key) {
            case "DEL" -> ic.deleteSurroundingText(1, 0);
            case "SHF" -> {
                long now = System.currentTimeMillis();
                if (now - lastShift < 400) { isCapsLock = !isCapsLock; isShiftOn = isCapsLock; }
                else                       { isShiftOn = !isShiftOn; }
                lastShift = now;
                buildKeyboardRows(currentSection == SEC_NORMAL ? sectionNormal : sectionHash,
                        currentSection == SEC_HASH);
            }
            case "↵" -> {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_ENTER));
            }
            case " " -> ic.commitText(hash && !isNumSymMode ? AdvancedEncryption.getHashChar(" ") : " ", 1);
            case "?123" -> {
                isNumSymMode = true; isSymPage2 = false;
                buildKeyboardRows(currentSection == SEC_NORMAL ? sectionNormal : sectionHash,
                        currentSection == SEC_HASH);
            }
            case "ABC" -> {
                isNumSymMode = false; isSymPage2 = false;
                buildKeyboardRows(currentSection == SEC_NORMAL ? sectionNormal : sectionHash,
                        currentSection == SEC_HASH);
            }
            case "SYM2", "=\\<" -> {
                isNumSymMode = true; isSymPage2 = true;
                buildKeyboardRows(currentSection == SEC_NORMAL ? sectionNormal : sectionHash,
                        currentSection == SEC_HASH);
            }
            case "SYM1" -> {
                isNumSymMode = true; isSymPage2 = false;
                buildKeyboardRows(currentSection == SEC_NORMAL ? sectionNormal : sectionHash,
                        currentSection == SEC_HASH);
            }
            default -> {
                String ch = (isShiftOn && !isNumSymMode) ? key.toUpperCase() : key;
                ic.commitText(hash && !isNumSymMode ? AdvancedEncryption.getHashChar(ch) : ch, 1);
                if (isShiftOn && !isCapsLock && !isNumSymMode) {
                    isShiftOn = false;
                    buildKeyboardRows(currentSection == SEC_NORMAL ? sectionNormal : sectionHash,
                            currentSection == SEC_HASH);
                }
            }
        }
    }

    private void deleteLine() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1);
        else ic.deleteSurroundingText(80, 0);
    }


    // =========================================================================
    // Hash / Translator section
    // =========================================================================

    private void setupHashSection() {
        View toggleBtn = sectionHash.findViewById(R.id.btn_toggle_translator);
        View panel     = sectionHash.findViewById(R.id.translator_panel);

        if (toggleBtn == null || panel == null) return;

        toggleBtn.setOnClickListener(v -> {
            boolean showing = panel.getVisibility() == View.VISIBLE;
            panel.setVisibility(showing ? View.GONE : View.VISIBLE);
        });

        android.widget.Button btnHashToPlain  = sectionHash.findViewById(R.id.btn_hash_to_plain);
        android.widget.Button btnPlainToHash  = sectionHash.findViewById(R.id.btn_plain_to_hash);
        android.widget.Button btnCopy         = sectionHash.findViewById(R.id.btn_copy_translation);
        EditText etInput                      = sectionHash.findViewById(R.id.et_translate_input);
        TextView tvOutput                     = sectionHash.findViewById(R.id.tv_translate_output);

        if (btnHashToPlain == null || btnPlainToHash == null || etInput == null || tvOutput == null) return;

        // Hash → Plain: reverse-map each glyph back to its letter
        btnHashToPlain.setOnClickListener(v -> {
            String input = etInput.getText().toString();
            if (input.isEmpty()) { tvOutput.setText("—"); return; }
            StringBuilder plain = new StringBuilder();
            int i = 0;
            while (i < input.length()) {
                boolean found = false;
                // Try multi-char glyphs first (up to 10 code-units)
                for (int len = 10; len > 0; len--) {
                    if (i + len <= input.length()) {
                        String sub = input.substring(i, i + len);
                        String mapped = AdvancedEncryption.reverseLookup(sub);
                        if (mapped != null) {
                            plain.append(mapped);
                            i += len;
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    plain.append(input.charAt(i)); // pass through unmapped char
                    i++;
                }
            }
            tvOutput.setText(plain.toString());
        });

        // Plain → Hash: map each letter to its glyph
        btnPlainToHash.setOnClickListener(v -> {
            String input = etInput.getText().toString();
            if (input.isEmpty()) { tvOutput.setText("—"); return; }
            tvOutput.setText(AdvancedEncryption.getHashChar(input));
        });

        // Copy result to clipboard
        btnCopy.setOnClickListener(v -> {
            CharSequence result = tvOutput.getText();
            if (result == null || result.toString().equals("—")) {
                Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("CVA Hash", result));
            Toast.makeText(this, "Copied ✓", Toast.LENGTH_SHORT).show();
        });
    }

    // =========================================================================
    // Terminal section
    // =========================================================================

    private void setupTerminalSection() {
        tvTermOut      = sectionTerminal.findViewById(R.id.tv_terminal_output);
        svTermOut      = sectionTerminal.findViewById(R.id.sv_terminal_output);
        tvTermInputLine= sectionTerminal.findViewById(R.id.tv_term_input_line);
        termSlideKb    = sectionTerminal.findViewById(R.id.terminal_slide_keyboard);

        tvTermOut.setTextColor(Color.parseColor("#FF8C42"));  // orange terminal output
        tvTermOut.setBackgroundColor(Color.BLACK);
        tvTermOut.setTypeface(android.graphics.Typeface.MONOSPACE);

        // Build keyboard immediately — it is always visible
        buildTerminalKeyboard();

        // Toggle output panel height (collapse/expand)
        Button btnToggleOut = sectionTerminal.findViewById(R.id.btn_toggle_output);
        if (btnToggleOut != null) {
            btnToggleOut.setOnClickListener(v -> {
                termOutputVisible = !termOutputVisible;
                android.view.ViewGroup.LayoutParams lp = svTermOut.getLayoutParams();
                lp.height = termOutputVisible ? dp(110) : dp(0);
                svTermOut.setLayoutParams(lp);
                btnToggleOut.setText(termOutputVisible ? "▲ Output" : "▼ Output");
            });
        }

        // LIVE toggle switch — single Switch widget on the top-bar right side
        android.widget.Switch liveSwitch = sectionTerminal.findViewById(R.id.switch_live_import);
        if (liveSwitch != null) {
            liveSwitch.setChecked(false);
            liveSwitch.setOnCheckedChangeListener((sw, isChecked) -> {
                autoImportOn = isChecked;
                // Cast to Switch to access thumb/track tint APIs
                android.widget.Switch s = (android.widget.Switch) sw;
                int thumbOn  = android.graphics.Color.parseColor("#00FF41");
                int thumbOff = android.graphics.Color.parseColor("#444444");
                int trackOn  = android.graphics.Color.parseColor("#004D14");
                int trackOff = android.graphics.Color.parseColor("#1A1A1A");
                s.setThumbTintList(android.content.res.ColorStateList.valueOf(isChecked ? thumbOn : thumbOff));
                s.setTrackTintList(android.content.res.ColorStateList.valueOf(isChecked ? trackOn : trackOff));
                // Update label next to switch — find the LIVE TextView in the same parent
                android.view.ViewGroup bar = (android.view.ViewGroup) sw.getParent();
                if (bar != null) {
                    for (int i = 0; i < bar.getChildCount(); i++) {
                        android.view.View child = bar.getChildAt(i);
                        if (child instanceof TextView) {
                            ((TextView) child).setTextColor(isChecked
                                    ? android.graphics.Color.parseColor("#00FF41")
                                    : android.graphics.Color.parseColor("#555555"));
                        }
                    }
                }
                if (tvTermInputLine != null) {
                    tvTermInputLine.setText(isChecked
                            ? "[ LIVE: output → cursor ON ]"
                            : "$ " + termBuf + "█");
                }
            });
        }

        // Show Termux status in label
        TextView termLabel = sectionTerminal.findViewById(R.id.tv_term_shell_label);
        if (termLabel != null) {
            boolean hasTmx = new java.io.File("/data/data/com.termux/files/usr/bin/bash").exists();
            boolean hasBB  = new java.io.File(getFilesDir(), "cva_bin/busybox").exists();
            if (hasTmx) {
                termLabel.setText(">_ Termux ✓");
                termLabel.setTextColor(Color.parseColor("#00FF41"));
            } else if (hasBB) {
                termLabel.setText(">_ BusyBox ✓");
                termLabel.setTextColor(Color.parseColor("#69F0AE"));
            } else {
                termLabel.setText(">_ Downloading...");
                termLabel.setTextColor(Color.parseColor("#FFD740"));
            }
        }

        // Restart / reinstall button
        Button btnRestart = sectionTerminal.findViewById(R.id.btn_term_restart);
        if (btnRestart != null) {
            btnRestart.setOnClickListener(v -> {
                tvTermOut.setText("[Restarting shell...] ");
                termBuf.setLength(0);
                updateTermInputLine();
                terminalManager.restart();
            });
            btnRestart.setOnLongClickListener(v -> {
                tvTermOut.setText("[Re-downloading BusyBox...] ");
                termBuf.setLength(0);
                updateTermInputLine();
                terminalManager.reinstall(null);
                return true;
            });
        }

        tvTermOut.setText("CVA Terminal v1.0\n$ ");
        updateTermInputLine();
    }

    private static final String[][] TERM_ROWS = {
            {"CTRL","ALT","TAB","ESC","↑","↓","←","→"},
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"SHF","z","x","c","v","b","n","m","DEL"},
            {"?123"," ","↵"}
    };

    // Terminal num/sym page 1
    private static final String[][] TERM_NUM_SYM_1 = {
            {"CTRL","ALT","TAB","ESC","↑","↓","←","→"},
            {"1","2","3","4","5","6","7","8","9","0"},
            {"!","@","#","$","%","^","&","*","(",")",},
            {"=\\<","-","_","+","=","[","]","{","}","DEL"},
            {"ABC","SYM2"," ","↵"}
    };

    // Terminal num/sym page 2
    private static final String[][] TERM_NUM_SYM_2 = {
            {"CTRL","ALT","TAB","ESC","↑","↓","←","→"},
            {"~","`","\\","|","/","<",">","?",":",";"},
            {"'","\"",",",".","~","-","_","=","+","DEL"},
            {"ABC","SYM1"," ","↵"}
    };

    private void buildTerminalKeyboard() {
        LinearLayout container = sectionTerminal.findViewById(R.id.terminal_keyboard_container);
        container.removeAllViews();

        String[][] layout = isTermNumSymMode
                ? (isTermSymPage2 ? TERM_NUM_SYM_2 : TERM_NUM_SYM_1)
                : TERM_ROWS;

        for (String[] row : layout) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            rl.setPadding(dp(3), dp(2), dp(3), dp(2));
            rl.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            for (String k : row) {
                Button b = new Button(this);
                float w = switch (k) {
                    case " " -> 4f;
                    case "↵","DEL","SHF","?123","ABC","SYM1","SYM2","=\\<" -> 1.5f;
                    default -> 1f;
                };
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), w);
                p.setMargins(4,3,4,3);
                b.setLayoutParams(p);
                b.setPadding(0,0,0,0);
                String disp = switch (k) {
                    case "SHF"  -> "⇧";
                    case "DEL"  -> "⌫";
                    case " "    -> "𝗖𝗩♞𝗞𝗜";
                    default     -> k;
                };
                b.setText(disp);
                b.setTextSize(disp.length() > 2 ? 18 : 24);
                b.setTextColor(k.equals(" ") ? Color.parseColor("#FF6A1A") : Color.WHITE);
                b.setBackground(roundedKey(switch (k) {
                    case "CTRL","ALT","ESC","TAB"    -> Color.parseColor("#2C1800");
                    case "↑","↓","←","→"             -> Color.parseColor("#CC3300");
                    case "?123","ABC","SYM1","SYM2","=\\<" -> Color.parseColor("#222222");
                    default -> {
                        if (k.length() == 1 && Character.isDigit(k.charAt(0)))
                            yield Color.parseColor("#2C1800");
                        yield keyColor(k);
                    }
                }));
                final String fk = k;
                b.setOnClickListener(v -> handleTermKey(fk));
                rl.addView(b);
            }
            container.addView(rl);
        }
    }

    private void handleTermKey(String key) {
        vibrate();
        switch (key) {
            case "DEL" -> {
                if (termBuf.length() > 0) {
                    termBuf.deleteCharAt(termBuf.length() - 1);
                    terminalManager.sendInput("\b \b");
                    updateTermInputLine();
                }
            }
            case "↵" -> {
                String cmd = termBuf.toString().trim();
                tvTermOut.append(cmd + "\n");
                termBuf.setLength(0);
                updateTermInputLine();
                if (cmd.equalsIgnoreCase("clear")) {
                    clearTermAndCursor();
                } else if (!cmd.isEmpty()) {
                    terminalManager.executeCommand(cmd, output -> ui.post(() -> {
                        tvTermOut.append(output + "\n$ ");
                        updateTermInputLine();
                        importTermToCursor(); // auto-import if toggle is ON
                    }));
                } else {
                    tvTermOut.append("$ ");
                    updateTermInputLine();
                }
            }
            case "SHF"  -> { isShiftOn = !isShiftOn; }
            case " "    -> { termBuf.append(" "); terminalManager.sendInput(" "); updateTermInputLine(); }
            case "CTRL" -> terminalManager.setCtrl(true);
            case "ALT"  -> terminalManager.setAlt(true);
            case "ESC"  -> terminalManager.sendInput("\u001b");
            case "TAB"  -> { termBuf.append("\t"); terminalManager.sendInput("\t"); }
            case "↑"   -> terminalManager.sendInput("\u001b[A");
            case "↓"   -> terminalManager.sendInput("\u001b[B");
            case "←"   -> terminalManager.sendInput("\u001b[D");
            case "→"   -> terminalManager.sendInput("\u001b[C");
            case "?123" -> { isTermNumSymMode = true;  isTermSymPage2 = false; buildTerminalKeyboard(); }
            case "ABC"  -> { isTermNumSymMode = false; isTermSymPage2 = false; buildTerminalKeyboard(); }
            case "SYM2","=\\<" -> { isTermNumSymMode = true; isTermSymPage2 = true;  buildTerminalKeyboard(); }
            case "SYM1" -> { isTermNumSymMode = true; isTermSymPage2 = false; buildTerminalKeyboard(); }
            default -> {
                String c = (isShiftOn && !isTermNumSymMode) ? key.toUpperCase() : key;
                if (terminalManager.isCtrl() && c.length() == 1 && Character.isLetter(c.charAt(0))) {
                    int code = Character.toLowerCase(c.charAt(0)) - 'a' + 1;
                    terminalManager.sendInput(String.valueOf((char) code));
                    terminalManager.setCtrl(false);
                } else {
                    termBuf.append(c);
                    terminalManager.sendInput(c);
                    updateTermInputLine();
                    if (isShiftOn && !isTermNumSymMode) isShiftOn = false;
                }
            }
        }
    }

    private void clearTermAndCursor() {
        tvTermOut.setText("CVA Terminal v1.0\n$ ");
        termBuf.setLength(0);
        // Backspace-clear the active cursor field too
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.deleteSurroundingText(9999, 0);
        terminalManager.clearScreen();
    }

    /** Push the last meaningful output line to the active cursor (if auto-import is on) */
    private void importTermToCursor() {
        if (!autoImportOn) return;
        String full = tvTermOut.getText().toString();
        String[] lines = full.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String ln = lines[i].replaceAll("^\\$\\s*", "").trim();
            if (!ln.isEmpty()) {
                pushChunkToCursor(ln);
                return;
            }
        }
    }

    /** Directly commit text to whatever field currently has focus */
    private void pushChunkToCursor(String text) {
        // Strip shell prompt noise
        String clean = text.replaceAll("^\\$\\s*", "")
                .replaceAll("\\x1B\\[[0-9;]*[mK]", "") // strip ANSI escapes
                .trim();
        if (clean.isEmpty()) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(clean, 1);
        }
    }

    private void toggleTermKb() {
        // Keyboard is always visible — nothing to toggle
    }

    // =========================================================================
    // Brain / CVA section
    // =========================================================================

    private void setupBrainSection() {
        tvBrainOut   = sectionBrain.findViewById(R.id.tv_brain_output);
        svBrainOut   = sectionBrain.findViewById(R.id.sv_brain_output);
        brainSlideKb = sectionBrain.findViewById(R.id.brain_slide_keyboard);

        // Keyboard always visible — no slide/toggle needed
        if (brainSlideKb != null) brainSlideKb.setVisibility(View.VISIBLE);

        buildBrainKeyboard();

        Button btnToggle = sectionBrain.findViewById(R.id.btn_show_brain_keyboard);
        if (btnToggle != null) btnToggle.setVisibility(View.GONE);

        View il = sectionBrain.findViewById(R.id.btn_brain_import_left);
        View ir = sectionBrain.findViewById(R.id.btn_brain_import_right);
        if (il != null) il.setOnClickListener(v -> importBrainToCursor());
        if (ir != null) ir.setOnClickListener(v -> importBrainToCursor());

        Button btnKey = sectionBrain.findViewById(R.id.btn_api_key);
        if (btnKey != null) btnKey.setOnClickListener(v -> {
            animPress(v);
            // Clear history and reload API key from prefs
            brainAgent.clearHistory();
            SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
            String prov = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);
            String key;
            switch (prov) {
                case BrainAgent.PROVIDER_GEMINI:     key = prefs.getString("key_gemini", ""); break;
                case BrainAgent.PROVIDER_OPENROUTER: key = prefs.getString("key_openrouter", ""); break;
                default:                             key = prefs.getString("key_anthropic", prefs.getString("api_key", "")); break;
            }
            brainAgent.setProvider(prov);
            brainAgent.setApiKey(key);
            if (tvBrainOut != null) tvBrainOut.removeAllViews();
            addBubble("🔄 Session refreshed. Provider: " + prov, false);
        });

        // Add welcome bubble
        addBubble("🦊 CVA Brain ready. Tap 🧠 to toggle Screen-Agent ON/OFF — when ON I can see & control your screen!", false);
    }

    private static final String[][] BRAIN_ROWS = {
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"SHF","z","x","c","v","b","n","m","DEL"},
            {"?123"," ","PARASITE","SEND"}
    };

    private static final String[][] BRAIN_NUM_SYM_1 = {
            {"1","2","3","4","5","6","7","8","9","0"},
            {"!","@","#","$","%","^","&","*","(",")",},
            {"=\\<","-","_","+","=","[","]","{","}","DEL"},
            {"ABC","SYM2"," ","PARASITE","SEND"}
    };

    private static final String[][] BRAIN_NUM_SYM_2 = {
            {"~","`","\\","|","/","<",">","?",":",";"},
            {"'","\"",",",".","…","•","€","£","¥","°"},
            {"©","®","™","←","→","↑","↓","×","÷","DEL"},
            {"ABC","SYM1"," ","PARASITE","SEND"}
    };

    private void buildBrainKeyboard() {
        LinearLayout container = sectionBrain.findViewById(R.id.brain_keyboard_container);
        container.removeAllViews();
        String[][] layout = isBrainNumSymMode
                ? (isBrainSymPage2 ? BRAIN_NUM_SYM_2 : BRAIN_NUM_SYM_1)
                : BRAIN_ROWS;
        for (String[] row : layout) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            rl.setPadding(dp(3),dp(2),dp(3),dp(2));
            rl.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            for (String k : row) {
                // ── PARASITE key: ImageButton with res/drawable/parasite.png ──────────────
                if (k.equals("PARASITE")) {
                    android.widget.ImageButton ib = new android.widget.ImageButton(this);
                    // weight 1.5 — same as modifier keys, keeps row balanced
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1.0f);
                    p.setMargins(4, 3, 4, 3);
                    ib.setLayoutParams(p);
                    // tight padding so image fills the key face
                    ib.setPadding(dp(3), dp(3), dp(3), dp(3));
                    ib.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

                    ib.setImageResource(R.drawable.parasite);
                    // OFF = orange tint  |  ON = green glow
                    ib.setColorFilter(
                            brainSwitchOn
                                    ? android.graphics.Color.parseColor("#00FF44")
                                    : android.graphics.Color.parseColor("#FF6A1A"),
                            android.graphics.PorterDuff.Mode.SRC_ATOP
                    );
                    // Background stays the same default key colour regardless of state
                    ib.setBackground(roundedKey(keyColor("PARASITE")));
                    ib.setTag("parasite_btn");
                    ib.setOnClickListener(v -> handleBrainKey("PARASITE"));
                    rl.addView(ib);
                    continue;
                }

                // ── All other keys ────────────────────────────────────────────────────────
                Button b = new Button(this);
                float w = switch (k) {
                    case " " -> 3.0f;
                    case "SEND" -> 1.5f;
                    case "SHF","DEL","?123","ABC","SYM1","SYM2","=\\<" -> 1.5f;
                    default -> 1f;
                };
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), w);
                p.setMargins(4,3,4,3);
                b.setLayoutParams(p);
                b.setPadding(0,0,0,0);
                String bDisp = switch (k) {
                    case "SHF"  -> "⇧";
                    case "DEL"  -> "⌫";
                    case " "    -> "𝗖𝗩♞𝗞𝗜";
                    case "SYM2","=\\<" -> "SYM2";
                    case "SYM1" -> "SYM";
                    default     -> isBrainNumSymMode ? k : (isShiftOn ? k.toUpperCase() : k);
                };
                b.setText(bDisp);
                b.setTextSize(k.equals("SEND") ? 22 : bDisp.length() > 2 ? 18 : 26);
                b.setBackground(roundedKey(switch (k) {
                    case "SEND"  -> Color.parseColor("#FF6A1A");
                    case "?123","ABC","SYM1","SYM2","=\\<" -> Color.parseColor("#222222");
                    default -> {
                        if (k.length() == 1 && Character.isDigit(k.charAt(0)))
                            yield Color.parseColor("#2C1800");
                        yield keyColor(k);
                    }
                }));
                b.setTextColor(k.equals("SEND") ? Color.parseColor("#0A0A0A") : k.equals(" ") ? Color.parseColor("#FF6A1A") : Color.WHITE);
                final String fk = k;
                b.setOnClickListener(v -> handleBrainKey(fk));
                rl.addView(b);
            }
            container.addView(rl);
        }
    }

    private void handleBrainKey(String key) {
        vibrate();
        switch (key) {
            case "DEL" -> {
                if (brainBuf.length() > 0) {
                    brainBuf.deleteCharAt(brainBuf.length() - 1);
                    updateBrainInput();
                }
            }
            case "SEND" -> sendBrainMsg();
            case "SHF"  -> { isShiftOn = !isShiftOn; buildBrainKeyboard(); }
            case " "    -> { brainBuf.append(" "); updateBrainInput(); }
            case "?123" -> { isBrainNumSymMode = true;  isBrainSymPage2 = false; buildBrainKeyboard(); }
            case "ABC"  -> { isBrainNumSymMode = false; isBrainSymPage2 = false; buildBrainKeyboard(); }
            case "SYM2","=\\<" -> { isBrainNumSymMode = true; isBrainSymPage2 = true;  buildBrainKeyboard(); }
            case "SYM1" -> { isBrainNumSymMode = true;  isBrainSymPage2 = false; buildBrainKeyboard(); }
            case "PARASITE" -> toggleBrainSwitch();
            default -> {
                brainBuf.append(isShiftOn && !isBrainNumSymMode ? key.toUpperCase() : key);
                updateBrainInput();
                if (isShiftOn && !isBrainNumSymMode) { isShiftOn = false; buildBrainKeyboard(); }
            }
        }
    }

    private void updateBrainInput() {
        TextView inp = sectionBrain.findViewById(R.id.tv_brain_input);
        if (inp != null) inp.setText("> " + brainBuf + "█");
    }

    /**
     * Toggle the Brain Screen-Agent switch ON/OFF.
     *
     * ON  → launches ScreenCapturePermissionActivity which starts SmartOverlayService.
     *        The overlay service reads the screen and executes the task autonomously.
     *        If there is text in the input bar it is used as the first task.
     *
     * OFF → stops SmartOverlayService and refreshes the keyboard button colours.
     */
    private void toggleBrainSwitch() {
        brainSwitchOn = !brainSwitchOn;
        buildBrainKeyboard();   // redraw so button colour flips immediately

        if (brainSwitchOn) {
            // ── Determine the task (use typed text or fallback) ──────────────
            String task = brainBuf.toString().trim();
            if (task.isEmpty()) task = "Analyze the screen and complete the current task";

            // Reload API key + provider from prefs
            android.content.SharedPreferences prefs =
                    getSharedPreferences("cva_prefs", android.content.Context.MODE_PRIVATE);
            String provider = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);
            String apiKey;
            switch (provider) {
                case BrainAgent.PROVIDER_GEMINI:     apiKey = prefs.getString("key_gemini",     ""); break;
                case BrainAgent.PROVIDER_OPENROUTER: apiKey = prefs.getString("key_openrouter", ""); break;
                default:                             apiKey = prefs.getString("key_anthropic",  prefs.getString("api_key", "")); break;
            }

            // Show feedback bubble
            addBubble("[🧠]::Parasite deamon On…", false);

            // Launch the screen-capture permission activity (starts SmartOverlayService)
            android.content.Intent intent = new android.content.Intent(
                    this, ScreenCapturePermissionActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("task",     task);
            intent.putExtra("apiKey",   apiKey);
            intent.putExtra("provider", provider);
            startActivity(intent);

        } else {
            // ── Stop the overlay service ─────────────────────────────────────
            stopService(new android.content.Intent(this, SmartOverlayService.class));
            addBubble("[🧠]:: Parasite Killed..", false);
        }
    }

    private void sendBrainMsg() {
        String msg = brainBuf.toString().trim();
        if (msg.isEmpty()) return;
        addBubble(msg, true);   // ← user bubble (right-aligned orange)
        brainBuf.setLength(0);
        updateBrainInput();
        svBrainOut.post(() -> svBrainOut.fullScroll(ScrollView.FOCUS_DOWN));

        // ── If Screen-Agent is ON, also update the overlay task ────────────────
        if (brainSwitchOn) {
            android.content.Intent update = new android.content.Intent(
                    this, SmartOverlayService.class);
            update.putExtra("task", msg);
            startService(update);  // onStartCommand just calls setTask() for re-sends
        }

        // brainAgent.chat() fires agentCallback on every step.
        // The final bubble is added by agentCallback (isRunning=false) above.
        // chat() returns null when agentCallback is set — do NOT call addBubble(resp).
        exec.execute(() -> brainAgent.chat(msg));
    }

    /** Add a chat bubble. isUser=true → right/orange. isUser=false → left/dark.
     *  Long-press any bubble → copies its text to clipboard. */
    private android.view.View addBubble(String text, boolean isUser) {
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.setMargins(0, 2, 0, 2);
        wrapper.setLayoutParams(wp);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setLineSpacing(2, 1);
        tv.setPadding(dp(10), dp(7), dp(10), dp(7));

        android.graphics.drawable.GradientDrawable bubble =
                new android.graphics.drawable.GradientDrawable();
        bubble.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);

        android.widget.FrameLayout.LayoutParams tp;
        if (isUser) {
            // User: right-aligned orange bubble
            bubble.setCornerRadii(new float[]{dp(14),dp(14), dp(4),dp(4), dp(14),dp(14), dp(14),dp(14)});
            bubble.setColor(Color.parseColor("#FF6A1A"));
            tv.setTextColor(Color.parseColor("#0A0A0A"));
            tp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            tp.gravity = android.view.Gravity.END;
            tp.setMargins(dp(40), 0, 0, 0);
        } else {
            // CVA: left-aligned dark bubble with orange border
            bubble.setCornerRadii(new float[]{dp(4),dp(4), dp(14),dp(14), dp(14),dp(14), dp(14),dp(14)});
            bubble.setColor(Color.parseColor("#1A1A1A"));
            bubble.setStroke(dp(1), Color.parseColor("#FF6A1A"));
            tv.setTextColor(Color.parseColor("#E0E0E0"));
            tp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            tp.gravity = android.view.Gravity.START;
            tp.setMargins(0, 0, dp(40), 0);
        }
        tv.setBackground(bubble);
        tv.setLayoutParams(tp);

        // ── Long-press → copy bubble text to clipboard ────────────────────────
        tv.setOnLongClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("cva_bubble", text));
            Toast.makeText(this, "Copied ✓", Toast.LENGTH_SHORT).show();
            return true;
        });

        wrapper.addView(tv);
        tvBrainOut.addView(wrapper);
        return wrapper;
    }

    private void importBrainToCursor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!lastCvaReply.isEmpty()) {
            ic.commitText(lastCvaReply.trim(), 1);
            Toast.makeText(this, "CVA response imported ✓", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No CVA response yet", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleBrainKb() {
        // Keyboard is always visible — nothing to toggle
    }

    // =========================================================================
    // Helpers
    // =========================================================================


    private void updateTermInputLine() {
        if (tvTermInputLine == null) return;
        String display = "$ " + termBuf.toString() + "█";
        tvTermInputLine.setText(display);
        // Also auto-scroll output
        if (svTermOut != null)
            svTermOut.post(() -> svTermOut.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void animPress(View v) {
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(70).start())
                .start();
    }

    private void slideUp(View v) {
        v.post(() -> {
            TranslateAnimation anim = new TranslateAnimation(0, 0, v.getHeight(), 0);
            anim.setDuration(250);
            anim.setFillAfter(true);
            v.startAnimation(anim);
        });
    }

    // ── Agent status ticker ───────────────────────────────────────────────────

    /**
     * Updates the scrolling status ticker at the top of the brain section.
     * NEVER adds a bubble — bubble management is handled exclusively by
     * sendBrainMsg() (user bubble) and BrainAgent.agentCallback final step.
     *
     * @param msg       Status text to show in the ticker.
     * @param isRunning TRUE = agent busy (show ticker); FALSE = done (hide ticker).
     */
    private void updateAgentStatusTicker(String msg, boolean isRunning) {
        if (sectionBrain == null) return;
        android.widget.TextView tv = sectionBrain.findViewById(R.id.tv_agent_status);
        if (tv == null) return;
        if (isRunning) {
            tv.setVisibility(View.VISIBLE);
            tv.setText(msg);
            tv.setSelected(true); // enable marquee scroll
            tv.removeCallbacks(null);
            // Auto-hide 4 s after the last running update
            tv.postDelayed(() -> tv.setVisibility(View.GONE), 4000);
        } else {
            // Agent finished — hide the ticker immediately
            tv.setVisibility(View.GONE);
        }
    }

    /** @deprecated use updateAgentStatusTicker — kept so any existing XML/reflection refs compile */
    private void showAgentStatus(String msg) {
        updateAgentStatusTicker(msg, true);
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ── Haptic feedback ───────────────────────────────────────────────────────
    private void vibrate() {
        Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(45, 200));
        } else {
            vib.vibrate(45);
        }
    }
}