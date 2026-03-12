package com.braingods.cva;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * ScreenCapturePermissionActivity
 *
 * Shows the "Start recording?" system dialog.
 * On success: packages the raw projection token inside a Bundle and passes it
 * to SmartOverlayService via startForegroundService().
 *
 * WHY A BUNDLE instead of putting the Intent directly as a Parcelable extra:
 *   On Android 13+ (API 33), putting an Intent as a Parcelable extra and reading
 *   it back with getParcelableExtra(String) sometimes returns null due to class
 *   loader issues when the receiving component is a Service. Wrapping it in a
 *   Bundle with putParcelable / getBundle is consistently reliable.
 *
 * SmartOverlayService calls getMediaProjection() from inside onStartCommand,
 * which already runs as a declared foreground service of type "mediaProjection".
 */
public class ScreenCapturePermissionActivity extends Activity {

    private static final String TAG      = "ScreenCapturePerm";
    private static final int    REQ_CODE = 1001;

    private String task;
    private String apiKey;
    private String provider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Suppress enter animation — this activity has no UI of its own; we
        // don't want a flash of the window background before the system dialog appears.
        overridePendingTransition(0, 0);

        task     = getIntent().getStringExtra("task");
        apiKey   = getIntent().getStringExtra("apiKey");
        provider = getIntent().getStringExtra("provider");

        if (task == null)     task     = "Analyze screen and complete the current task";
        if (provider == null) provider = "anthropic";

        Log.i(TAG, "Requesting screen capture permission");
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CODE) return;

        if (resultCode == RESULT_OK && data != null) {
            Log.i(TAG, "Permission granted ✓  resultCode=" + resultCode);

            // ── Wrap token in a Bundle to avoid class-loader issues on API 33+ ──
            Bundle projBundle = new Bundle();
            projBundle.putParcelable("projData", data);  // Intent is Parcelable

            Intent svc = new Intent(this, SmartOverlayService.class);
            svc.putExtra("task",           task);
            svc.putExtra("apiKey",         apiKey);
            svc.putExtra("provider",       provider);
            svc.putExtra("projResultCode", resultCode);
            svc.putExtra("projBundle",     projBundle);  // ← Bundle wrapper

            startForegroundService(svc);
            Toast.makeText(this, "CVA screen agent started 🧠", Toast.LENGTH_SHORT).show();

        } else {
            Log.w(TAG, "Screen capture permission denied (resultCode=" + resultCode + ")");
            Toast.makeText(this,
                    "Screen capture denied — CVA needs this to read your screen",
                    Toast.LENGTH_LONG).show();
        }

        // moveTaskToBack() keeps whatever app was in the foreground visible
        // instead of letting Android surface MainActivity when we finish().
        // This is the key fix: the permission dialog closed → user stays exactly
        // where they were (e.g. in their browser / chat app).
        moveTaskToBack(true);
        finish();
        // Suppress the exit animation so there is no visible transition to MainActivity.
        overridePendingTransition(0, 0);
    }
}