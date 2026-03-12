package com.braingods.cva;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.braingods.cva.termux.TermuxApplication;

import java.util.ArrayDeque;

/**
 * TerminalService
 * ─────────────────────────────────────────────────────────────────────────────
 * Foreground service that keeps the CVA shell process alive even when the UI
 * Activity is paused, backgrounded, or destroyed.
 *
 * WHAT'S NEW vs. previous version:
 *   • onCreate() calls TermuxApplication.onAppCreate() as a safety net — this
 *     is idempotent (no-op if already called from CvaApplication).  Ensures
 *     $PREFIX/$HOME dirs exist even if the service is started without the app
 *     class (e.g., system restart of a sticky service).
 *   • Everything else is identical to the previous version.
 *
 * LIFECYCLE INTEGRATION (in your terminal Activity):
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  private TerminalService terminalService;                               │
 * │  private final TerminalServiceConnection conn = new TerminalServiceConnection(); │
 * │                                                                         │
 * │  // onStart():                                                          │
 * │  Intent svc = new Intent(this, TerminalService.class);                  │
 * │  startService(svc);                                                     │
 * │  bindService(svc, conn, BIND_AUTO_CREATE);                              │
 * │                                                                         │
 * │  // onStop():                                                           │
 * │  if (terminalService != null) terminalService.detachCallback();         │
 * │  unbindService(conn);  terminalService = null;                          │
 * │                                                                         │
 * │  class TerminalServiceConnection implements ServiceConnection {         │
 * │    public void onServiceConnected(ComponentName n, IBinder b) {         │
 * │      terminalService = ((TerminalService.LocalBinder) b).getService();  │
 * │      TerminalManager mgr = terminalService.getOrCreateManager(          │
 * │          text -> runOnUiThread(() -> appendToTermView(text)));          │
 * │      terminalService.drainReplayBuffer(                                 │
 * │          text -> runOnUiThread(() -> appendToTermView(text)));          │
 * │    }                                                                    │
 * │    public void onServiceDisconnected(ComponentName n) {                 │
 * │      terminalService = null;                                            │
 * │    }                                                                    │
 * │  }                                                                      │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
public class TerminalService extends Service {

    // ── Intent actions ────────────────────────────────────────────────────────
    public static final String ACTION_STOP_SERVICE = "com.braingods.cva.ACTION_STOP_SERVICE";
    public static final String ACTION_WAKE_LOCK    = "com.braingods.cva.ACTION_WAKE_LOCK";
    public static final String ACTION_WAKE_UNLOCK  = "com.braingods.cva.ACTION_WAKE_UNLOCK";

    // ── Notification ──────────────────────────────────────────────────────────
    private static final String NOTIF_CHANNEL_ID   = "cva_terminal_channel";
    private static final String NOTIF_CHANNEL_NAME = "CVA Terminal";
    private static final int    NOTIF_ID           = 0xC0A_1337;

    // ── Replay ring-buffer ────────────────────────────────────────────────────
    private static final int REPLAY_BUFFER_LINES = 200;
    private final ArrayDeque<CharSequence> replayBuffer = new ArrayDeque<>();

    private static final String TAG = "TerminalService";

    // ── Binder ────────────────────────────────────────────────────────────────

    public class LocalBinder extends Binder {
        public TerminalService getService() { return TerminalService.this; }
    }

    private final IBinder binder = new LocalBinder();

    // ── State ─────────────────────────────────────────────────────────────────

    private TerminalManager            manager;
    private TerminalManager.OutputCallback liveCallback;
    private PowerManager.WakeLock      wakeLock;
    private WifiManager.WifiLock       wifiLock;
    private boolean wantsToStop = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        // Safety net: ensure bootstrap dirs exist even if CvaApplication didn't run
        // (e.g., system-restarted service after process kill).  This is a no-op if
        // CvaApplication.onCreate() already ran.
        TermuxApplication.onAppCreate(this);

        setupNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (action == null) return START_NOT_STICKY;

        switch (action) {
            case ACTION_STOP_SERVICE:
                Log.d(TAG, "ACTION_STOP_SERVICE");
                wantsToStop = true;
                if (manager != null) { manager.destroy(); manager = null; }
                stopForeground(true);
                stopSelf();
                break;
            case ACTION_WAKE_LOCK:
                Log.d(TAG, "ACTION_WAKE_LOCK");
                acquireWakeLocks();
                break;
            case ACTION_WAKE_UNLOCK:
                Log.d(TAG, "ACTION_WAKE_UNLOCK");
                releaseWakeLocks();
                break;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy — wantsToStop=" + wantsToStop);
        releaseWakeLocks();
        if (manager != null) { manager.destroy(); manager = null; }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ── Activity ↔ Service API ────────────────────────────────────────────────

    /**
     * Called by the Activity after binding.  Returns the live TerminalManager,
     * creating it if this is the first launch.
     */
    public TerminalManager getOrCreateManager(TerminalManager.OutputCallback cb) {
        liveCallback = cb;
        if (manager == null) {
            manager = new TerminalManager(getApplicationContext(), bufferedCallback(cb));
        } else {
            manager.setActivityCallback(bufferedCallback(cb));
        }
        return manager;
    }

    /**
     * Called when the Activity goes to background.
     * The shell keeps running; output is recorded in the replay buffer.
     */
    public void detachCallback() {
        liveCallback = null;
        if (manager != null) {
            manager.setActivityCallback(bufferedCallback(null));
        }
    }

    public @Nullable TerminalManager getManager() { return manager; }

    /**
     * Drains buffered output into {@code cb} so the Activity can show
     * whatever happened while it was in background.
     */
    public void drainReplayBuffer(TerminalManager.OutputCallback cb) {
        synchronized (replayBuffer) {
            for (CharSequence chunk : replayBuffer) cb.onOutput(chunk);
            replayBuffer.clear();
        }
    }

    // ── Buffered callback ─────────────────────────────────────────────────────

    private TerminalManager.OutputCallback bufferedCallback(
            final TerminalManager.OutputCallback delegate) {
        return text -> {
            synchronized (replayBuffer) {
                replayBuffer.addLast(text);
                while (replayBuffer.size() > REPLAY_BUFFER_LINES) replayBuffer.pollFirst();
            }
            if (delegate != null) delegate.onOutput(text);
        };
    }

    // ── Wake locks ────────────────────────────────────────────────────────────

    @SuppressWarnings({"WakelockTimeout", "deprecation"})
    public void acquireWakeLocks() {
        if (wakeLock != null && wakeLock.isHeld()) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cva:terminal");
            wakeLock.acquire();
            Log.d(TAG, "CPU WakeLock acquired");
        }

        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cva:terminal");
            wifiLock.acquire();
            Log.d(TAG, "Wi-Fi WifiLock acquired");
        }

        updateNotification();
    }

    public void releaseWakeLocks() {
        if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
        if (wifiLock != null) { if (wifiLock.isHeld()) wifiLock.release(); wifiLock = null; }
        updateNotification();
        Log.d(TAG, "Wake locks released");
    }

    public boolean isWakeLockHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("CVA Terminal — background shell session");
            ch.setShowBadge(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        boolean wakeHeld = isWakeLockHeld();

        Intent openIntent = getLaunchIntent();
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 0, openIntent, pendingIntentFlags());

        Intent stopIntent = new Intent(this, TerminalService.class).setAction(ACTION_STOP_SERVICE);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, pendingIntentFlags());

        Intent wakeIntent = new Intent(this, TerminalService.class)
                .setAction(wakeHeld ? ACTION_WAKE_UNLOCK : ACTION_WAKE_LOCK);
        PendingIntent wakePi = PendingIntent.getService(this, 2, wakeIntent, pendingIntentFlags());

        String subText = wakeHeld
                ? "CVA · Wake lock ON · tap to open"
                : "CVA shell running · tap to open";

        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("CVA Terminal")
                .setContentText(subText)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setColor(0xFF00FF41)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
                .addAction(
                        wakeHeld ? android.R.drawable.ic_lock_idle_lock
                                : android.R.drawable.ic_lock_lock,
                        wakeHeld ? "Wake: ON" : "Wake: OFF",
                        wakePi)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Intent getLaunchIntent() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        return i != null ? i : new Intent();
    }

    private static int pendingIntentFlags() {
        int base = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) base |= PendingIntent.FLAG_IMMUTABLE;
        return base;
    }
}