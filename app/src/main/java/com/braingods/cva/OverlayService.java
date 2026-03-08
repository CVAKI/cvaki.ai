package com.braingods.cva;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

/**
 * Persistent floating overlay for the CVA Brain agent.
 * Shows a draggable bubble; tapping it opens the Brain activity.
 *
 * Requires  android.permission.SYSTEM_ALERT_WINDOW  (MANAGE_OVERLAY_PERMISSION).
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "cva_overlay_channel";

    private WindowManager   wm;
    private View            overlayView;
    private TextView        tvStatus;

    // Drag state
    private int   initX, initY;
    private float initTouchX, initTouchY;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, buildNotification());
        showOverlay();
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

    // ── Overlay ───────────────────────────────────────────────────────────────

    private void showOverlay() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null);
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
        params.x = 0;
        params.y = 200;

        // Drag support
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
                    if (dx < 10 && dy < 10) {
                        // Tap → open MainActivity
                        Intent i = new Intent(this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    }
                    return true;
            }
            return false;
        });

        // Close button
        ImageButton btnClose = overlayView.findViewById(R.id.btn_overlay_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> stopSelf());
        }

        wm.addView(overlayView, params);
        updateStatus("CVA active");
    }

    public void updateStatus(String status) {
        if (tvStatus != null) tvStatus.setText("🧠 " + status);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wm != null && overlayView != null) wm.removeView(overlayView);
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
                .setContentText("Tap to open CVA settings")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}