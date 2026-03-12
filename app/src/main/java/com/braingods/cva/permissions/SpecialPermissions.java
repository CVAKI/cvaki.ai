package com.braingods.cva.permissions;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * SpecialPermissions
 * ─────────────────────────────────────────────────────────────────────────────
 * Handles permissions that are NOT granted via ActivityCompat.requestPermissions()
 * but instead require the user to be sent to a system Settings screen.
 *
 * COVERS:
 *  1. SYSTEM_ALERT_WINDOW        (overlay bubble, SmartOverlayService)
 *  2. MANAGE_EXTERNAL_STORAGE    (full storage access, Android 11+)
 *  3. WRITE_SETTINGS             (system settings write)
 *  4. REQUEST_INSTALL_PACKAGES   (APK sideload)
 *  5. Accessibility Service      (gesture automation)
 *  6. POST_NOTIFICATIONS         (Android 13 — redirects to settings if denied)
 *
 * CRASH FIX — MediaProjection
 * ────────────────────────────
 * Log shows:
 *   SecurityException: Media projections require a foreground service of type
 *   ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *
 * Root cause: SmartOverlayService must be started as a foreground service with
 *   android:foregroundServiceType="mediaProjection"  BEFORE
 * getMediaProjection() is called in ScreenCaptureManager.
 *
 * The manifest already has  foregroundServiceType="mediaProjection"  on
 * SmartOverlayService.  The missing piece was calling startForeground() with
 * the correct type flag inside SmartOverlayService.onCreate() and ensuring the
 * service is running BEFORE the permission result is delivered.
 *
 * Call {@link #ensureMediaProjectionService(Activity)} from
 * ScreenCapturePermissionActivity BEFORE launching the media projection intent.
 */
public final class SpecialPermissions {

    private static final String TAG = "SpecialPermissions";

    // ── 1. SYSTEM_ALERT_WINDOW (overlay) ─────────────────────────────────────

    /** Returns true if the app can draw over other apps. */
    public static boolean hasOverlay(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(ctx);
        }
        return true; // Always granted below API 23
    }

    /**
     * Opens the Settings screen that lets the user grant SYSTEM_ALERT_WINDOW.
     * Returns immediately; check {@link #hasOverlay(Context)} in onResume().
     */
    public static void requestOverlay(Activity activity) {
        if (hasOverlay(activity)) return;
        Log.d(TAG, "Requesting SYSTEM_ALERT_WINDOW");
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            // Some OEMs don't handle the URI; fall back to generic overlay settings
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    // ── 2. MANAGE_EXTERNAL_STORAGE (Android 11+) ─────────────────────────────

    /** Returns true if the app has MANAGE_EXTERNAL_STORAGE (Android 11+). */
    public static boolean hasManageExternalStorage(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true; // Below Android 11 this permission doesn't exist
    }

    /**
     * Opens the "All files access" Settings screen.
     * Returns immediately; check {@link #hasManageExternalStorage(Context)} in onResume().
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public static void requestManageExternalStorage(Activity activity) {
        if (hasManageExternalStorage(activity)) return;
        Log.d(TAG, "Requesting MANAGE_EXTERNAL_STORAGE");
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            activity.startActivity(
                    new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ── 3. WRITE_SETTINGS ─────────────────────────────────────────────────────

    /** Returns true if the app can write system settings. */
    public static boolean hasWriteSettings(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.System.canWrite(ctx);
        }
        return true;
    }

    /** Opens Settings to request WRITE_SETTINGS. */
    public static void requestWriteSettings(Activity activity) {
        if (hasWriteSettings(activity)) return;
        Log.d(TAG, "Requesting WRITE_SETTINGS");
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    // ── 4. REQUEST_INSTALL_PACKAGES ───────────────────────────────────────────

    /** Returns true if the app can install APKs from unknown sources. */
    public static boolean hasInstallPackages(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** Opens Settings to request "Install unknown apps" permission. */
    public static void requestInstallPackages(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || hasInstallPackages(activity)) return;
        Log.d(TAG, "Requesting REQUEST_INSTALL_PACKAGES");
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    // ── 5. Accessibility Service ──────────────────────────────────────────────

    /** Returns true if CVAAccessibilityService is enabled. */
    public static boolean hasAccessibility(Context ctx) {
        String setting = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(setting)) return false;

        String target = ctx.getPackageName() + "/.CVAAccessibilityService";
        return setting.toLowerCase().contains(target.toLowerCase())
                || setting.contains("CVAAccessibilityService");
    }

    /** Opens the Accessibility Settings screen. */
    public static void requestAccessibility(Activity activity) {
        if (hasAccessibility(activity)) return;
        Log.d(TAG, "Requesting Accessibility Service enable");
        activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    // ── 6. MediaProjection foreground service (crash fix) ────────────────────

    /**
     * CRASH FIX: ensures SmartOverlayService is running as a foreground service
     * with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION BEFORE the media projection
     * permission result is delivered and MediaProjectionManager.getMediaProjection()
     * is called.
     *
     * CALL THIS from ScreenCapturePermissionActivity.onActivityResult() BEFORE
     * constructing ScreenCaptureManager:
     *
     *   SpecialPermissions.ensureMediaProjectionService(this);
     *   // small delay then:
     *   ScreenCaptureManager.init(resultCode, data);
     *
     * The service's onStartCommand must call:
     *   startForeground(NOTIF_ID, notification,
     *       ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);   // API 29+
     */
    public static void ensureMediaProjectionService(Activity activity) {
        Log.d(TAG, "ensureMediaProjectionService — starting SmartOverlayService");
        Intent intent = new Intent(activity,
                getSmartOverlayServiceClass(activity));
        intent.setAction("ACTION_START_MEDIA_PROJECTION_FOREGROUND");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent);
            } else {
                activity.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureMediaProjectionService failed: " + e.getMessage());
        }
    }

    /** Returns SmartOverlayService.class via reflection to avoid hard dependency. */
    private static Class<?> getSmartOverlayServiceClass(Context ctx) {
        try {
            return Class.forName(ctx.getPackageName() + ".SmartOverlayService");
        } catch (ClassNotFoundException e) {
            // Should never happen — included in the same APK
            throw new RuntimeException("SmartOverlayService not found", e);
        }
    }

    // ── 7. POST_NOTIFICATIONS (Android 13+) ──────────────────────────────────

    /**
     * Returns true if POST_NOTIFICATIONS is granted (or not required).
     * Runtime permission check is done via PermissionGateway.has().
     */
    public static boolean hasNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return PermissionGateway.has(ctx, android.Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * Opens the App Notification Settings if the permission was permanently denied.
     * If it's just not-yet-requested, use PermissionGateway.requestAll() instead.
     */
    public static void openNotificationSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        activity.startActivity(intent);
    }

    // ── Convenience: request ALL special permissions at once ─────────────────

    /**
     * Opens all required Settings screens in sequence.
     * Each screen returns to the Activity via onResume() — the user must
     * manually navigate back after granting each one.
     *
     * Typical call site: a "Grant All Permissions" button in MainActivity.
     */
    public static void requestAllSpecial(Activity activity) {
        if (!hasOverlay(activity))                requestOverlay(activity);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !hasManageExternalStorage(activity))
            requestManageExternalStorage(activity);
        if (!hasAccessibility(activity))          requestAccessibility(activity);
        if (!hasInstallPackages(activity))        requestInstallPackages(activity);
    }

    /**
     * Returns a bitmask summary of which special permissions are granted.
     * Useful for logging / Doctor panel.
     */
    public static String getSpecialStatusReport(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Special Permissions]\n");
        sb.append(hasOverlay(ctx)               ? "  ✓" : "  ✗").append(" SYSTEM_ALERT_WINDOW\n");
        sb.append(hasManageExternalStorage(ctx) ? "  ✓" : "  ✗").append(" MANAGE_EXTERNAL_STORAGE\n");
        sb.append(hasWriteSettings(ctx)         ? "  ✓" : "  ✗").append(" WRITE_SETTINGS\n");
        sb.append(hasInstallPackages(ctx)       ? "  ✓" : "  ✗").append(" REQUEST_INSTALL_PACKAGES\n");
        sb.append(hasAccessibility(ctx)         ? "  ✓" : "  ✗").append(" ACCESSIBILITY_SERVICE\n");
        sb.append(hasNotifications(ctx)         ? "  ✓" : "  ✗").append(" POST_NOTIFICATIONS\n");
        return sb.toString();
    }

    private SpecialPermissions() {}
}