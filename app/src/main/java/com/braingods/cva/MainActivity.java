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
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS        = 100;
    private static final int REQUEST_OVERLAY_PERM   = 101;  // ← NEW: overlay permission request code

    // ── Views ─────────────────────────────────────────────────────────────────
    private EditText    etKeyAnthropic, etKeyGemini, etKeyOpenRouter, etKeyGroq;
    private RadioGroup  radioProvider;
    private RadioButton rbAnthropic, rbGemini, rbOpenRouter, rbGroq;
    private TextView    tvStatus;
    private View        viewStatusDot;

    // ── Smart Agent task input ────────────────────────────────────────────────
    private EditText etAgentTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ── Bind views ────────────────────────────────────────────────────────
        tvStatus        = findViewById(R.id.tv_status);
        viewStatusDot   = findViewById(R.id.view_status_dot);
        etKeyAnthropic  = findViewById(R.id.et_key_anthropic);
        etKeyGemini     = findViewById(R.id.et_key_gemini);
        etKeyOpenRouter = findViewById(R.id.et_key_openrouter);
        etKeyGroq       = findViewById(R.id.et_key_groq);
        radioProvider   = findViewById(R.id.radio_provider);
        rbAnthropic     = findViewById(R.id.rb_anthropic);
        rbGemini        = findViewById(R.id.rb_gemini);
        rbOpenRouter    = findViewById(R.id.rb_openrouter);
        rbGroq          = findViewById(R.id.rb_groq);
        etAgentTask     = findViewById(R.id.et_agent_task);

        // ── Load prefs ────────────────────────────────────────────────────────
        SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        etKeyAnthropic .setText(prefs.getString("key_anthropic",  ""));
        etKeyGemini    .setText(prefs.getString("key_gemini",     ""));
        etKeyOpenRouter.setText(prefs.getString("key_openrouter", ""));
        etKeyGroq      .setText(prefs.getString("key_groq",       ""));

        String savedProvider = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);
        switch (savedProvider) {
            case BrainAgent.PROVIDER_GEMINI:     rbGemini    .setChecked(true); break;
            case BrainAgent.PROVIDER_OPENROUTER: rbOpenRouter.setChecked(true); break;
            case BrainAgent.PROVIDER_GROQ:       rbGroq      .setChecked(true); break;
            default:                             rbAnthropic .setChecked(true); break;
        }

        radioProvider.setOnCheckedChangeListener((g, id) -> highlightActiveKey());
        highlightActiveKey();

        // ── Handle open_settings extra from OverlayService ───────────────────
        if (getIntent() != null && getIntent().getBooleanExtra("open_settings", false)) {
            // Scroll to / highlight the settings section if needed
            View section = findViewById(R.id.section_provider);
            if (section != null) section.requestFocus();
        }

        // ── Entrance animations ───────────────────────────────────────────────
        int[] sectionIds = {
                R.id.section_keyboard,
                R.id.section_provider,
                R.id.section_permissions,
                R.id.section_overlay,
                R.id.section_agent,
                R.id.section_memory
        };
        for (int i = 0; i < sectionIds.length; i++) {
            View section = findViewById(sectionIds[i]);
            if (section != null) {
                Animation anim = AnimationUtils.loadAnimation(this, R.anim.fade_slide_up);
                anim.setStartOffset(i * 80L + 100L);
                section.startAnimation(anim);
            }
        }
        View logo = findViewById(R.id.iv_logo);
        if (logo != null) logo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_slide_up));
        if (viewStatusDot != null)
            viewStatusDot.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse_orange));

        // ── Save button ───────────────────────────────────────────────────────
        Button btnSave = findViewById(R.id.btn_save_keys);
        btnSave.setOnClickListener(v -> {
            animPress(v);
            String provider = getSelectedProvider();
            String key      = getActiveKey();
            if (key.isEmpty()) {
                Toast.makeText(this, "Enter an API key for the selected provider", Toast.LENGTH_SHORT).show();
                return;
            }
            SharedPreferences.Editor ed = prefs.edit();
            ed.putString("provider",       provider);
            ed.putString("key_anthropic",  etKeyAnthropic .getText().toString().trim());
            ed.putString("key_gemini",     etKeyGemini    .getText().toString().trim());
            ed.putString("key_openrouter", etKeyOpenRouter.getText().toString().trim());
            ed.putString("key_groq",       etKeyGroq      .getText().toString().trim());
            ed.putString("api_key",        key);
            ed.apply();
            Toast.makeText(this, "✓  Saved — " + providerLabel(provider), Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        // ── IME buttons ───────────────────────────────────────────────────────
        Button btnEnableIme = findViewById(R.id.btn_enable_ime);
        btnEnableIme.setOnClickListener(v -> {
            animPress(v);
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        });

        Button btnSwitchIme = findViewById(R.id.btn_switch_ime);
        btnSwitchIme.setOnClickListener(v -> {
            animPress(v);
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
        });

        // ── Permission buttons ────────────────────────────────────────────────
        Button btnPerms = findViewById(R.id.btn_grant_permissions);
        btnPerms.setOnClickListener(v -> { animPress(v); requestAllPermissions(); });

        Button btnOverlay = findViewById(R.id.btn_overlay_permission);
        btnOverlay.setOnClickListener(v -> {
            animPress(v);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivityForResult(                                    // ← use startActivityForResult
                        new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())),
                        REQUEST_OVERLAY_PERM
                );
            } else {
                Toast.makeText(this, "Overlay permission already granted ✓", Toast.LENGTH_SHORT).show();
            }
        });

        Button btnAccess = findViewById(R.id.btn_accessibility);
        btnAccess.setOnClickListener(v -> {
            animPress(v);
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });

        // ── Overlay toggle ────────────────────────────────────────────────────
        Button btnToggleOverlay = findViewById(R.id.btn_toggle_overlay);
        btnToggleOverlay.setOnClickListener(v -> {
            animPress(v);
            startOverlayIfPermitted();   // ← safe launch with permission check
        });

        // ── Smart Agent launch ────────────────────────────────────────────────
        Button btnLaunchAgent = findViewById(R.id.btn_launch_agent);
        btnLaunchAgent.setOnClickListener(v -> {
            animPress(v);
            launchSmartAgent(prefs);
        });

        // Update task intent when already running (no permission re-request)
        Button btnUpdateTask = findViewById(R.id.btn_update_task);
        btnUpdateTask.setOnClickListener(v -> {
            animPress(v);
            String task = etAgentTask != null
                    ? etAgentTask.getText().toString().trim() : "";
            if (task.isEmpty()) {
                Toast.makeText(this, "Enter a task first", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, SmartOverlayService.class);
            i.putExtra("task", task);
            startService(i);
            Toast.makeText(this, "Task updated ✓", Toast.LENGTH_SHORT).show();
        });

        // ── Memory buttons ────────────────────────────────────────────────────
        Button btnMemory = findViewById(R.id.btn_view_memory);
        btnMemory.setOnClickListener(v -> {
            animPress(v);
            String[] files = CvakiStorage.listFiles(this);
            if (files.length == 0) setStatus("No .cvaki memory files found.", false);
            else {
                StringBuilder sb = new StringBuilder();
                for (String f : files) sb.append("  • ").append(f).append(".cvaki\n");
                setStatus(sb.toString().trim(), true);
            }
        });

        Button btnClearMem = findViewById(R.id.btn_clear_memory);
        btnClearMem.setOnClickListener(v -> {
            animPress(v);
            CvakiStorage.delete(this, "brain_memory");
            Toast.makeText(this, "Memory cleared", Toast.LENGTH_SHORT).show();
            setStatus("Memory cleared.", false);
        });

        updateStatus();
    }

    // ── Overlay: safe start with permission check ─────────────────────────────

    /**
     * Checks SYSTEM_ALERT_WINDOW before starting OverlayService.
     * If not granted → opens system settings and waits for result.
     * If granted     → starts the service immediately.
     *
     * This prevents the AppOps "Bad call uid -1" SecurityException.
     */
    private void startOverlayIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Please allow 'Display over other apps' then tap Start again",
                    Toast.LENGTH_LONG).show();
            startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())),
                    REQUEST_OVERLAY_PERM
            );
        } else {
            startService(new Intent(this, OverlayService.class));
            Toast.makeText(this, "CVA overlay started 🦊", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called when the user returns from the overlay permission settings screen.
     * If permission was granted, start the overlay service automatically.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERM) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Settings.canDrawOverlays(this)) {
                // Permission just granted — safe to start now
                startService(new Intent(this, OverlayService.class));
                Toast.makeText(this, "CVA overlay started 🦊", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Overlay permission denied — cannot show floating logo",
                        Toast.LENGTH_LONG).show();
            }
            updateStatus();
        }
    }

    // ── Smart Agent launcher ──────────────────────────────────────────────────

    private void launchSmartAgent(SharedPreferences prefs) {
        // 1. Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant 'Display over other apps' permission first", Toast.LENGTH_LONG).show();
            startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())),
                    REQUEST_OVERLAY_PERM
            );
            return;
        }

        // 2. Check API key
        String apiKey = prefs.getString("api_key", "").trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Save an API key first", Toast.LENGTH_SHORT).show();
            return;
        }

        // 3. Get task text (use default if empty)
        String task = (etAgentTask != null && etAgentTask.getText().length() > 0)
                ? etAgentTask.getText().toString().trim()
                : "Analyze the screen and help me complete the current task";

        // 4. Get provider
        String provider = prefs.getString("provider", BrainAgent.PROVIDER_ANTHROPIC);

        // 5. Launch — ScreenCapturePermissionActivity handles the MediaProjection dialog
        Intent i = new Intent(this, ScreenCapturePermissionActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("task",     task);
        i.putExtra("apiKey",   apiKey);
        i.putExtra("provider", provider);
        startActivity(i);

        setStatus("🤖 CVA Agent launching…", true);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void animPress(View v) {
        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(70).start())
                .start();
    }

    private String getSelectedProvider() {
        int id = radioProvider.getCheckedRadioButtonId();
        if (id == R.id.rb_gemini)     return BrainAgent.PROVIDER_GEMINI;
        if (id == R.id.rb_openrouter) return BrainAgent.PROVIDER_OPENROUTER;
        if (id == R.id.rb_groq)       return BrainAgent.PROVIDER_GROQ;
        return BrainAgent.PROVIDER_ANTHROPIC;
    }

    private String getActiveKey() {
        switch (getSelectedProvider()) {
            case BrainAgent.PROVIDER_GEMINI:     return etKeyGemini    .getText().toString().trim();
            case BrainAgent.PROVIDER_OPENROUTER: return etKeyOpenRouter.getText().toString().trim();
            case BrainAgent.PROVIDER_GROQ:       return etKeyGroq      .getText().toString().trim();
            default:                             return etKeyAnthropic .getText().toString().trim();
        }
    }

    private String providerLabel(String p) {
        switch (p) {
            case BrainAgent.PROVIDER_GEMINI:     return "Google Gemini";
            case BrainAgent.PROVIDER_OPENROUTER: return "OpenRouter (Llama 3.3 70B free)";
            case BrainAgent.PROVIDER_GROQ:       return "Groq (Llama 3.3 70B free ⚡)";
            default:                             return "Anthropic Claude";
        }
    }

    private void highlightActiveKey() {
        String  active   = getSelectedProvider();
        boolean isAnth   = active.equals(BrainAgent.PROVIDER_ANTHROPIC);
        boolean isGemini = active.equals(BrainAgent.PROVIDER_GEMINI);
        boolean isOR     = active.equals(BrainAgent.PROVIDER_OPENROUTER);
        boolean isGroq   = active.equals(BrainAgent.PROVIDER_GROQ);
        setFieldHighlight(etKeyAnthropic,  isAnth);
        setFieldHighlight(etKeyGemini,     isGemini);
        setFieldHighlight(etKeyOpenRouter, isOR);
        setFieldHighlight(etKeyGroq,       isGroq);
    }

    private void setFieldHighlight(EditText field, boolean active) {
        if (active) {
            field.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_provider_selected));
            field.setTextColor(Color.WHITE);
        } else {
            field.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_edittext));
        }
        field.animate().alpha(active ? 1f : 0.5f).setDuration(200).start();
    }

    private void setStatus(String msg, boolean highlight) {
        if (tvStatus == null) return;
        tvStatus.setText(msg);
        tvStatus.setTextColor(Color.parseColor("#FF6A1A"));
        tvStatus.setAlpha(0f);
        tvStatus.animate().alpha(1f).setDuration(300).start();
    }

    private void updateStatus() {
        SharedPreferences prefs = getSharedPreferences("cva_prefs", Context.MODE_PRIVATE);
        boolean hasOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean hasCamera  = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasAudio   = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean allGood    = hasOverlay && hasCamera && hasAudio
                && !prefs.getString("api_key", "").isEmpty();
        setStatus(allGood ? "✅ READY" : "⚠ SETUP REQUIRED", false);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

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
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
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
}