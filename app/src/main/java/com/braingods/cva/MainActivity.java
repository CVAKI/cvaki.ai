package com.braingods.cva;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 100;

    // Provider key fields
    private EditText etKeyAnthropic, etKeyGemini, etKeyOpenRouter;
    private RadioGroup radioProvider;
    private RadioButton rbAnthropic, rbGemini, rbOpenRouter;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus         = findViewById(R.id.tv_status);
        etKeyAnthropic   = findViewById(R.id.et_key_anthropic);
        etKeyGemini      = findViewById(R.id.et_key_gemini);
        etKeyOpenRouter  = findViewById(R.id.et_key_openrouter);
        radioProvider    = findViewById(R.id.radio_provider);
        rbAnthropic      = findViewById(R.id.rb_anthropic);
        rbGemini         = findViewById(R.id.rb_gemini);
        rbOpenRouter     = findViewById(R.id.rb_openrouter);

        SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);

        // Load saved keys
        etKeyAnthropic .setText(prefs.getString("key_anthropic",  ""));
        etKeyGemini    .setText(prefs.getString("key_gemini",     ""));
        etKeyOpenRouter.setText(prefs.getString("key_openrouter", ""));

        // Load saved provider selection
        String savedProvider = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);
        switch (savedProvider) {
            case BrainAgent.PROVIDER_GEMINI:     rbGemini    .setChecked(true); break;
            case BrainAgent.PROVIDER_OPENROUTER: rbOpenRouter.setChecked(true); break;
            default:                             rbAnthropic .setChecked(true); break;
        }

        // Highlight active section on provider change
        radioProvider.setOnCheckedChangeListener((g, id) -> highlightActiveKey());
        highlightActiveKey();

        // ── Save button ───────────────────────────────────────────────────────
        Button btnSave = findViewById(R.id.btn_save_keys);
        btnSave.setOnClickListener(v -> {
            String provider = getSelectedProvider();
            String key      = getActiveKey();

            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter an API key for the selected provider", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences.Editor ed = prefs.edit();
            ed.putString("provider",       provider);
            ed.putString("key_anthropic",  etKeyAnthropic .getText().toString().trim());
            ed.putString("key_gemini",     etKeyGemini    .getText().toString().trim());
            ed.putString("key_openrouter", etKeyOpenRouter.getText().toString().trim());
            // Also keep legacy key field so KeyboardService can read it simply
            ed.putString("api_key",        key);
            ed.apply();

            Toast.makeText(this, "Saved — provider: " + providerLabel(provider), Toast.LENGTH_SHORT).show();
        });

        // ── IME setup ─────────────────────────────────────────────────────────
        Button btnEnableIme = findViewById(R.id.btn_enable_ime);
        btnEnableIme.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        Button btnSwitchIme = findViewById(R.id.btn_switch_ime);
        btnSwitchIme.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        });

        // ── Permissions ───────────────────────────────────────────────────────
        Button btnPerms = findViewById(R.id.btn_grant_permissions);
        btnPerms.setOnClickListener(v -> requestAllPermissions());

        Button btnOverlay = findViewById(R.id.btn_overlay_permission);
        btnOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Overlay permission already granted ✓", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnToggleOverlay = findViewById(R.id.btn_toggle_overlay);
        btnToggleOverlay.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show();
                return;
            }
            startService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "CVA overlay started", Toast.LENGTH_SHORT).show();
        });

        Button btnAccess = findViewById(R.id.btn_accessibility);
        btnAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        // ── Memory ────────────────────────────────────────────────────────────
        Button btnMemory = findViewById(R.id.btn_view_memory);
        btnMemory.setOnClickListener(v -> {
            String[] files = CvakiStorage.listFiles(this);
            if (files.length == 0) {
                tvStatus.setText("No .cvaki memory files found.");
            } else {
                StringBuilder sb = new StringBuilder("Memory files:\n");
                for (String f : files) sb.append("  • ").append(f).append(".cvaki\n");
                tvStatus.setText(sb.toString());
            }
        });

        Button btnClearMem = findViewById(R.id.btn_clear_memory);
        btnClearMem.setOnClickListener(v -> {
            CvakiStorage.delete(this, "brain_memory");
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show();
            tvStatus.setText("Memory cleared.");
        });

        updateStatus();
    }

    // ── Provider helpers ──────────────────────────────────────────────────────

    private String getSelectedProvider() {
        int id = radioProvider.getCheckedRadioButtonId();
        if (id == R.id.rb_gemini)      return BrainAgent.PROVIDER_GEMINI;
        if (id == R.id.rb_openrouter)  return BrainAgent.PROVIDER_OPENROUTER;
        return BrainAgent.PROVIDER_ANTHROPIC;
    }

    private String getActiveKey() {
        switch (getSelectedProvider()) {
            case BrainAgent.PROVIDER_GEMINI:     return etKeyGemini    .getText().toString().trim();
            case BrainAgent.PROVIDER_OPENROUTER: return etKeyOpenRouter.getText().toString().trim();
            default:                             return etKeyAnthropic .getText().toString().trim();
        }
    }

    private String providerLabel(String p) {
        switch (p) {
            case BrainAgent.PROVIDER_GEMINI:     return "Google Gemini";
            case BrainAgent.PROVIDER_OPENROUTER: return "OpenRouter (Llama free)";
            default:                             return "Anthropic Claude";
        }
    }

    /** Highlight the active key field in purple, dim the others */
    private void highlightActiveKey() {
        String active = getSelectedProvider();
        int activeColor  = Color.parseColor("#4A148C");
        int inactiveColor= Color.parseColor("#1A1A2E");

        etKeyAnthropic .setBackgroundColor(active.equals(BrainAgent.PROVIDER_ANTHROPIC)  ? activeColor : inactiveColor);
        etKeyGemini    .setBackgroundColor(active.equals(BrainAgent.PROVIDER_GEMINI)      ? activeColor : inactiveColor);
        etKeyOpenRouter.setBackgroundColor(active.equals(BrainAgent.PROVIDER_OPENROUTER)  ? activeColor : inactiveColor);
    }

    // ── Permission handling ───────────────────────────────────────────────────

    private void requestAllPermissions() {
        String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
        };
        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        }
        if (needed.isEmpty()) {
            Toast.makeText(this, "All permissions granted ✓", Toast.LENGTH_SHORT).show();
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMISSIONS) {
            int granted = 0;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) granted++;
            Toast.makeText(this, granted + "/" + results.length + " permissions granted",
                    Toast.LENGTH_SHORT).show();
            updateStatus();
        }
    }

    private void updateStatus() {
        SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        String provider = prefs.getString("provider", "none");
        boolean hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean hasCamera  = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean hasAudio   = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        tvStatus.setText("CVA Status\n"
                + "  Provider: " + providerLabel(provider) + "\n"
                + "  Overlay:  " + (hasOverlay ? "✓" : "✗") + "\n"
                + "  Camera:   " + (hasCamera  ? "✓" : "✗") + "\n"
                + "  Audio:    " + (hasAudio   ? "✓" : "✗") + "\n");
    }
}