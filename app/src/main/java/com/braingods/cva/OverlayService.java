package com.braingods.cva;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

/**
 * Floating transparent overlay using the "parasite" logo.
 *
 * FIX: Guards showOverlay() with Settings.canDrawOverlays() to prevent
 *      the AppOps "uid -1" SecurityException that occurs when
 *      TYPE_APPLICATION_OVERLAY is used without the permission being granted.
 *
 * How to trigger permission grant from your Activity before starting this service:
 *
 *   if (!Settings.canDrawOverlays(this)) {
 *       Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
 *           Uri.parse("package:" + getPackageName()));
 *       startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
 *   } else {
 *       startService(new Intent(this, OverlayService.class));
 *   }
 */
public class OverlayService extends Service {

    private static final String TAG          = "OverlayService";
    private static final String CHANNEL_ID   = "cva_overlay_channel";
    private static final int    TAP_MAX_PX   = 10;

    private WindowManager wm;
    private View          overlayView;
    private LinearLayout  detailPanel;
    private TextView      tvStatus;
    private boolean       panelVisible = false;

    // Drag state
    private int   initX, initY;
    private float initTouchX, initTouchY;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());

        // ✅ KEY FIX: only attempt to draw overlay if permission is actually granted.
        // Without this guard Android's AppOps rejects the call with uid -1 crash.
        if (hasOverlayPermission()) {
            showOverlay();
        } else {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — requesting permission.");
            requestOverlayPermission();
            stopSelf(); // stop the service; user must grant & restart
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("status")) {
            updateStatus(intent.getStringExtra("status"));
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wm != null && overlayView != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay view", e);
            }
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    /**
     * Returns true only if the overlay permission is properly granted.
     * Handles both modern (API 23+) and legacy checks.
     */
    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        // Pre-M: permission is granted at install time
        return true;
    }

    /**
     * Opens the system overlay-permission screen so the user can grant it.
     * Called when the service starts without permission — this launches the
     * settings UI so the user can enable "Display over other apps".
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    // ── Overlay setup ─────────────────────────────────────────────────────────

    private void showOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
        detailPanel = overlayView.findViewById(R.id.layout_detail_panel);
        tvStatus    = overlayView.findViewById(R.id.tv_overlay_status);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20;
        params.y = 200;

        // ── Touch: drag + tap-to-toggle panel ─────────────────────────────
        overlayView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {

                case MotionEvent.ACTION_DOWN:
                    initX      = params.x;
                    initY      = params.y;
                    initTouchX = event.getRawX();
                    initTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    params.x = initX + (int)(event.getRawX() - initTouchX);
                    params.y = initY + (int)(event.getRawY() - initTouchY);
                    wm.updateViewLayout(overlayView, params);
                    return true;

                case MotionEvent.ACTION_UP:
                    float dx = Math.abs(event.getRawX() - initTouchX);
                    float dy = Math.abs(event.getRawY() - initTouchY);
                    if (dx < TAP_MAX_PX && dy < TAP_MAX_PX) {
                        togglePanel();
                    }
                    return true;
            }
            return false;
        });

        // ── Panel buttons ──────────────────────────────────────────────────

        TextView btnOpenBrain = overlayView.findViewById(R.id.btn_open_brain);
        if (btnOpenBrain != null) {
            btnOpenBrain.setOnClickListener(v -> {
                hidePanel();
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            });
        }

        TextView btnSettings = overlayView.findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                hidePanel();
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra("open_settings", true);
                startActivity(i);
            });
        }

        TextView btnClose = overlayView.findViewById(R.id.btn_overlay_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> stopSelf());
        }

        try {
            wm.addView(overlayView, params);
            updateStatus("CVA active");
        } catch (WindowManager.BadTokenException e) {
            // Rare: permission was revoked between the check and addView
            Log.e(TAG, "BadTokenException — overlay permission revoked mid-start", e);
            stopSelf();
        }
    }

    // ── Panel toggle ──────────────────────────────────────────────────────────

    private void togglePanel() {
        if (panelVisible) hidePanel(); else showPanel();
    }

    private void showPanel() {
        if (detailPanel == null) return;
        detailPanel.setVisibility(View.VISIBLE);

        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new AlphaAnimation(0f, 1f));
        set.addAnimation(new TranslateAnimation(0, 0, -20, 0));
        set.setDuration(200);
        detailPanel.startAnimation(set);
        panelVisible = true;
    }

    private void hidePanel() {
        if (detailPanel == null) return;

        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration(150);
        fade.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a)  {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                detailPanel.setVisibility(View.GONE);
            }
        });
        detailPanel.startAnimation(fade);
        panelVisible = false;
    }

    // ── Status update ─────────────────────────────────────────────────────────

    public void updateStatus(String status) {
        if (tvStatus != null) tvStatus.setText(status);
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "CVA Brain", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("CVA overlay service");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("CVA Brain active")
                .setContentText("Tap to open CVA")
                .setSmallIcon(R.drawable.parasite)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}