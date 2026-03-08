package com.braingods.cva;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
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
    private TextView  tvBrainOut;
    private ScrollView svBrainOut;
    private View       brainSlideKb;
    private boolean    brainKbVisible = false;
    private StringBuilder brainBuf = new StringBuilder();

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
        int active   = Color.parseColor("#536DFE");
        int inactive = Color.parseColor("#263238");
        btnNavNormal  .setBackgroundColor(s == SEC_NORMAL   ? active : inactive);
        btnNavHash    .setBackgroundColor(s == SEC_HASH     ? active : inactive);
        btnNavTerminal.setBackgroundColor(s == SEC_TERMINAL ? active : inactive);
        btnNavBrain   .setBackgroundColor(s == SEC_BRAIN    ? active : inactive);
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

    private void buildKeyboardRows(View parent, boolean hash) {
        LinearLayout container = parent.findViewById(R.id.keyboard_rows_container);
        container.removeAllViews();
        for (String[] row : QWERTY) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            rl.setPadding(dp(2), dp(2), dp(2), dp(2));
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
            case " " -> 4f;
            case "SHF", "DEL", "↵" -> 1.5f;
            default -> 1f;
        };
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), weight);
        p.setMargins(1,1,1,1);
        b.setLayoutParams(p);
        b.setPadding(0,0,0,0);

        // Keys always display normal letters — hash mode only changes the OUTPUT
        String display = switch (key) {
            case "SHF" -> "⇧";
            case "DEL" -> "⌫";
            case "↵"  -> "↵";
            default    -> isShiftOn ? key.toUpperCase() : key;
        };

        boolean isHashLetter = hash && key.length() == 1 && Character.isLetter(key.charAt(0));
        if (isHashLetter) {
            // Show  letter + tiny hash-glyph subtitle
            String glyph = AdvancedEncryption.getHashChar(isShiftOn ? key.toUpperCase() : key.toLowerCase());
            b.setText(display + "\n" + glyph);
            b.setTextSize(8);
        } else {
            b.setText(display);
            b.setTextSize(display.length() > 2 ? 9 : 13);
        }
        b.setTextColor(isHashLetter ? Color.parseColor("#90CAF9") : Color.WHITE);
        b.setBackgroundColor(keyColor(key));

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
            case "DEL"  -> Color.parseColor("#B71C1C");
            case "SHF"  -> Color.parseColor("#283593");
            case "↵"    -> Color.parseColor("#004D40");
            case "?123" -> Color.parseColor("#37474F");
            case " "    -> Color.parseColor("#455A64");
            default     -> Color.parseColor("#263238");
        };
    }

    // =========================================================================
    // Normal / Hash key handler
    // =========================================================================

    private void handleKey(String key, boolean hash) {
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
            case " " -> ic.commitText(hash ? AdvancedEncryption.getHashChar(" ") : " ", 1);
            case "?123" -> Toast.makeText(this, "Symbols panel – coming soon", Toast.LENGTH_SHORT).show();
            default -> {
                String ch = isShiftOn ? key.toUpperCase() : key.toLowerCase();
                ic.commitText(hash ? AdvancedEncryption.getHashChar(ch) : ch, 1);
                if (isShiftOn && !isCapsLock) {
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

        tvTermOut.setTextColor(Color.parseColor("#00FF41"));
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

        // Auto-import toggle — when ON, every new output line is sent to the cursor
        Button importLeft  = sectionTerminal.findViewById(R.id.btn_import_left);
        Button importRight = sectionTerminal.findViewById(R.id.btn_import_right);
        if (importLeft != null) {
            importLeft.setOnClickListener(v -> toggleAutoImport(importLeft));
        }
        if (importRight != null) {
            importRight.setOnClickListener(v -> toggleAutoImport(importRight));
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
            {" ","↵"}
    };

    private void buildTerminalKeyboard() {
        LinearLayout container = sectionTerminal.findViewById(R.id.terminal_keyboard_container);
        container.removeAllViews();

        for (String[] row : TERM_ROWS) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            rl.setPadding(dp(2), dp(1), dp(2), dp(1));
            for (String k : row) {
                Button b = new Button(this);
                float w = switch (k) {
                    case " " -> 4f;
                    case "↵","DEL","SHF" -> 1.5f;
                    default -> 1f;
                };
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), w);
                p.setMargins(1,1,1,1);
                b.setLayoutParams(p);
                b.setPadding(0,0,0,0);
                b.setText(k.equals("SHF") ? "⇧" : k.equals("DEL") ? "⌫" : k);
                b.setTextSize(k.length() > 2 ? 10 : 12);
                b.setTextColor(Color.WHITE);
                b.setBackgroundColor(switch (k) {
                    case "CTRL","ALT","ESC","TAB" -> Color.parseColor("#1A237E");
                    case "↑","↓","←","→"          -> Color.parseColor("#006064");
                    default -> keyColor(k);
                });
                final String fk = k;
                b.setOnClickListener(v -> handleTermKey(fk));
                rl.addView(b);
            }
            container.addView(rl);
        }
    }

    private void handleTermKey(String key) {
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
            case "SHF" -> { isShiftOn = !isShiftOn; }
            case " "   -> { termBuf.append(" "); updateTermInputLine(); }
            case "CTRL"-> terminalManager.setCtrl(true);
            case "ALT" -> terminalManager.setAlt(true);
            case "ESC" -> terminalManager.sendInput("\u001b");
            case "TAB" -> { termBuf.append("\t"); terminalManager.sendInput("\t"); }
            case "↑"  -> terminalManager.sendInput("\u001b[A");
            case "↓"  -> terminalManager.sendInput("\u001b[B");
            case "←"  -> terminalManager.sendInput("\u001b[D");
            case "→"  -> terminalManager.sendInput("\u001b[C");
            default -> {
                String c = isShiftOn ? key.toUpperCase() : key.toLowerCase();
                if (terminalManager.isCtrl()) {
                    int code = c.charAt(0) - 'a' + 1;
                    terminalManager.sendInput(String.valueOf((char) code));
                    terminalManager.setCtrl(false);
                } else {
                    termBuf.append(c);
                    updateTermInputLine();
                    if (isShiftOn) isShiftOn = false;
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

    /** Toggle continuous auto-import mode on/off */
    private void toggleAutoImport(android.widget.Button btn) {
        autoImportOn = !autoImportOn;
        // Update both buttons — no Toast (IME toasts are suppressed by Android)
        int[] ids = {R.id.btn_import_left, R.id.btn_import_right};
        for (int id : ids) {
            Button b = sectionTerminal.findViewById(id);
            if (b != null) {
                b.setText(autoImportOn ? "⏹ LIVE" : "←In");
                b.setBackgroundColor(autoImportOn
                        ? Color.parseColor("#00C853")
                        : Color.parseColor("#0A1A0A"));
            }
        }
        // Show status in the input line instead of Toast
        if (tvTermInputLine != null) {
            tvTermInputLine.setText(autoImportOn
                    ? "[ LIVE: output → cursor ON ]"
                    : "[ LIVE OFF ] $ " + termBuf + "█");
        }
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

        tvBrainOut.setTextColor(Color.parseColor("#E0E0E0"));

        buildBrainKeyboard();

        Button btnToggle = sectionBrain.findViewById(R.id.btn_show_brain_keyboard);
        if (btnToggle != null) btnToggle.setOnClickListener(v -> toggleBrainKb());

        View il = sectionBrain.findViewById(R.id.btn_brain_import_left);
        View ir = sectionBrain.findViewById(R.id.btn_brain_import_right);
        if (il != null) il.setOnClickListener(v -> importBrainToCursor());
        if (ir != null) ir.setOnClickListener(v -> importBrainToCursor());

        Button btnKey = sectionBrain.findViewById(R.id.btn_api_key);
        if (btnKey != null) btnKey.setOnClickListener(v ->
                Toast.makeText(this, "Open CVA Settings app to set API key", Toast.LENGTH_LONG).show());

        tvBrainOut.setText("🧠 CVA Brain ready.\nType a message below and tap Send.\n\n");
    }

    private static final String[][] BRAIN_ROWS = {
            {"q","w","e","r","t","y","u","i","o","p"},
            {"a","s","d","f","g","h","j","k","l"},
            {"SHF","z","x","c","v","b","n","m","DEL"},
            {" ","SEND"}
    };

    private void buildBrainKeyboard() {
        LinearLayout container = sectionBrain.findViewById(R.id.brain_keyboard_container);
        container.removeAllViews();
        for (String[] row : BRAIN_ROWS) {
            LinearLayout rl = new LinearLayout(this);
            rl.setOrientation(LinearLayout.HORIZONTAL);
            rl.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            rl.setPadding(dp(2),dp(1),dp(2),dp(1));
            for (String k : row) {
                Button b = new Button(this);
                float w = switch (k) {
                    case " " -> 3f;
                    case "SEND" -> 2f;
                    case "SHF","DEL" -> 1.5f;
                    default -> 1f;
                };
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), w);
                p.setMargins(1,1,1,1);
                b.setLayoutParams(p);
                b.setPadding(0,0,0,0);
                b.setText(k.equals("SHF") ? "⇧" : k.equals("DEL") ? "⌫" : k.equals(" ") ? "⎵" : k);
                b.setTextSize(k.equals("SEND") ? 11 : 13);
                b.setTextColor(Color.WHITE);
                b.setBackgroundColor(k.equals("SEND") ? Color.parseColor("#6A1B9A") : keyColor(k));
                final String fk = k;
                b.setOnClickListener(v -> handleBrainKey(fk));
                rl.addView(b);
            }
            container.addView(rl);
        }
    }

    private void handleBrainKey(String key) {
        switch (key) {
            case "DEL" -> {
                if (brainBuf.length() > 0) {
                    brainBuf.deleteCharAt(brainBuf.length() - 1);
                    updateBrainInput();
                }
            }
            case "SEND" -> sendBrainMsg();
            case "SHF"  -> { isShiftOn = !isShiftOn; }
            case " "    -> { brainBuf.append(" "); updateBrainInput(); }
            default -> {
                brainBuf.append(isShiftOn ? key.toUpperCase() : key.toLowerCase());
                updateBrainInput();
                if (isShiftOn) isShiftOn = false;
            }
        }
    }

    private void updateBrainInput() {
        TextView inp = sectionBrain.findViewById(R.id.tv_brain_input);
        if (inp != null) inp.setText("> " + brainBuf + "█");
    }

    private void sendBrainMsg() {
        String msg = brainBuf.toString().trim();
        if (msg.isEmpty()) return;
        tvBrainOut.append("You: " + msg + "\n");
        brainBuf.setLength(0);
        updateBrainInput();
        tvBrainOut.append("CVA: thinking…\n");
        svBrainOut.post(() -> svBrainOut.fullScroll(ScrollView.FOCUS_DOWN));

        exec.execute(() -> {
            String resp = brainAgent.chat(msg);
            ui.post(() -> {
                String cur = tvBrainOut.getText().toString();
                tvBrainOut.setText(cur.replace("CVA: thinking…\n",
                        "CVA: " + resp + "\n\n"));
                svBrainOut.post(() -> svBrainOut.fullScroll(ScrollView.FOCUS_DOWN));
            });
        });
    }

    private void importBrainToCursor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        String[] lines = tvBrainOut.getText().toString().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].startsWith("CVA: ") && !lines[i].contains("thinking")) {
                ic.commitText(lines[i].substring(5).trim(), 1);
                Toast.makeText(this, "CVA response imported ✓", Toast.LENGTH_SHORT).show();
                return;
            }
        }
    }

    private void toggleBrainKb() {
        brainKbVisible = !brainKbVisible;
        if (brainKbVisible) {
            svBrainOut  .setVisibility(View.GONE);
            brainSlideKb.setVisibility(View.VISIBLE);
            slideUp(brainSlideKb);
        } else {
            brainSlideKb.setVisibility(View.GONE);
            svBrainOut  .setVisibility(View.VISIBLE);
        }
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

    private void slideUp(View v) {
        v.post(() -> {
            TranslateAnimation anim = new TranslateAnimation(0, 0, v.getHeight(), 0);
            anim.setDuration(250);
            anim.setFillAfter(true);
            v.startAnimation(anim);
        });
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}