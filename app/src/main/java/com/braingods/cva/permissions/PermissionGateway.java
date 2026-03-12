package com.braingods.cva.permissions;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PermissionGateway
 * ─────────────────────────────────────────────────────────────────────────────
 * Single entry point for ALL permission management in CVA.
 *
 * COVERS:
 *  • Runtime permissions (CAMERA, RECORD_AUDIO, STORAGE, LOCATION, SMS, …)
 *  • Special permissions (SYSTEM_ALERT_WINDOW, MANAGE_EXTERNAL_STORAGE,
 *                         WRITE_SETTINGS, PACKAGE_INSTALL)
 *  • Foreground service types (ensureMediaProjectionService)
 *  • POST_NOTIFICATIONS (Android 13+)
 *  • Accessibility service detection
 *
 * USAGE:
 *   // Check a single permission:
 *   boolean ok = PermissionGateway.has(this, Manifest.permission.CAMERA);
 *
 *   // Request runtime permissions:
 *   PermissionGateway.request(activity,
 *       PermissionRequest.builder()
 *           .add(Manifest.permission.CAMERA)
 *           .add(Manifest.permission.RECORD_AUDIO)
 *           .requestCode(REQ_AV)
 *           .build());
 *
 *   // Check + request ALL permissions CVA needs in one shot:
 *   PermissionGateway.requestAll(activity, callback);
 *
 *   // Handle in onRequestPermissionsResult:
 *   PermissionGateway.onRequestPermissionsResult(requestCode, permissions,
 *       grantResults, callback);
 */
public final class PermissionGateway {

    private static final String TAG = "PermissionGateway";

    // ── Public request codes (use these in onRequestPermissionsResult) ────────
    public static final int RC_CORE         = 1100;
    public static final int RC_STORAGE      = 1101;
    public static final int RC_MEDIA        = 1102;
    public static final int RC_LOCATION     = 1103;
    public static final int RC_CONTACTS     = 1104;
    public static final int RC_NOTIFICATIONS= 1105;
    public static final int RC_ALL          = 1199;

    // ── Permission groups used by CVA ─────────────────────────────────────────

    /** Permissions required for the core terminal + AI overlay to function. */
    public static final String[] CORE_PERMISSIONS = {
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.VIBRATE,
    };

    /** Storage permissions — version-adaptive. */
    public static String[] storagePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {  // API 33
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {  // API 30
            // MANAGE_EXTERNAL_STORAGE is a special permission — handled separately
            // via SpecialPermissions.requestManageExternalStorage(activity).
            // The normal READ/WRITE still needed for scoped storage fallback.
            return new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
            };
        } else {
            return new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
        }
    }

    /** Audio / camera for screen-capture AI feature. */
    public static final String[] MEDIA_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };

    /** Location (used by CVA automation features). */
    public static final String[] LOCATION_PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
    };

    /** Contacts / communication data. */
    public static final String[] CONTACTS_PERMISSIONS = {
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
    };

    // ── Basic check ───────────────────────────────────────────────────────────

    /** Returns true if the given Manifest.permission is granted. */
    public static boolean has(Context ctx, String permission) {
        return ContextCompat.checkSelfPermission(ctx, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Returns true if ALL permissions in the array are granted. */
    public static boolean hasAll(Context ctx, String... permissions) {
        for (String p : permissions) {
            if (!has(ctx, p)) return false;
        }
        return true;
    }

    /** Returns which permissions from the array are NOT yet granted. */
    public static List<String> missing(Context ctx, String... permissions) {
        List<String> missing = new ArrayList<>();
        for (String p : permissions) {
            if (!has(ctx, p)) missing.add(p);
        }
        return missing;
    }

    // ── Runtime permission request ────────────────────────────────────────────

    /**
     * Request a single permission group.
     * @param activity  calling Activity
     * @param request   built via {@link PermissionRequest#builder()}
     */
    public static void request(Activity activity, PermissionRequest request) {
        List<String> needed = missing(activity, request.permissions);
        if (needed.isEmpty()) {
            if (request.callback != null) {
                request.callback.onAllGranted();
            }
            return;
        }
        Log.d(TAG, "Requesting " + needed.size() + " permission(s): " + needed);
        ActivityCompat.requestPermissions(
                activity,
                needed.toArray(new String[0]),
                request.requestCode);
    }

    /**
     * Request ALL CVA runtime permissions in one shot.
     * Special permissions (overlay, storage, notifications) are handled
     * by {@link SpecialPermissions} — call that separately if needed.
     */
    public static void requestAll(Activity activity, PermissionResult.Callback callback) {
        List<String> all = new ArrayList<>();
        all.addAll(Arrays.asList(CORE_PERMISSIONS));
        all.addAll(Arrays.asList(storagePermissions()));
        all.addAll(Arrays.asList(MEDIA_PERMISSIONS));
        all.addAll(Arrays.asList(LOCATION_PERMISSIONS));
        all.addAll(Arrays.asList(CONTACTS_PERMISSIONS));

        // POST_NOTIFICATIONS — Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            all.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        request(activity, PermissionRequest.builder()
                .addAll(all.toArray(new String[0]))
                .requestCode(RC_ALL)
                .callback(callback)
                .build());
    }

    // ── onRequestPermissionsResult bridge ────────────────────────────────────

    /**
     * Call from your Activity's onRequestPermissionsResult().
     * Dispatches the result to the appropriate callback.
     *
     * @param requestCode  as received from Android
     * @param permissions  as received from Android
     * @param grantResults as received from Android
     * @param callback     your result callback
     */
    public static void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults,
            PermissionResult.Callback callback) {

        if (callback == null) return;

        List<String> granted = new ArrayList<>();
        List<String> denied  = new ArrayList<>();

        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                granted.add(permissions[i]);
            } else {
                denied.add(permissions[i]);
                Log.w(TAG, "DENIED: " + permissions[i]);
            }
        }

        PermissionResult result = new PermissionResult(requestCode, granted, denied);

        if (denied.isEmpty()) {
            callback.onAllGranted();
        } else if (granted.isEmpty()) {
            callback.onAllDenied(result);
        } else {
            callback.onPartiallyGranted(result);
        }
    }

    // ── Convenience status ────────────────────────────────────────────────────

    /**
     * Returns a human-readable summary of CVA's permission state.
     * Useful for the Doctor / settings screen.
     */
    public static String getStatusReport(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CVA Permission Status ===\n");

        sb.append("\n[Core]\n");
        for (String p : CORE_PERMISSIONS) {
            sb.append(has(ctx, p) ? "  ✓ " : "  ✗ ").append(shortName(p)).append("\n");
        }

        sb.append("\n[Storage]\n");
        for (String p : storagePermissions()) {
            sb.append(has(ctx, p) ? "  ✓ " : "  ✗ ").append(shortName(p)).append("\n");
        }
        sb.append(SpecialPermissions.hasManageExternalStorage(ctx)
                ? "  ✓ " : "  ✗ ").append("MANAGE_EXTERNAL_STORAGE\n");

        sb.append("\n[Media / Screen Capture]\n");
        for (String p : MEDIA_PERMISSIONS) {
            sb.append(has(ctx, p) ? "  ✓ " : "  ✗ ").append(shortName(p)).append("\n");
        }
        sb.append(SpecialPermissions.hasOverlay(ctx)
                ? "  ✓ " : "  ✗ ").append("SYSTEM_ALERT_WINDOW\n");

        sb.append("\n[Location]\n");
        for (String p : LOCATION_PERMISSIONS) {
            sb.append(has(ctx, p) ? "  ✓ " : "  ✗ ").append(shortName(p)).append("\n");
        }

        sb.append("\n[Contacts / SMS]\n");
        for (String p : CONTACTS_PERMISSIONS) {
            sb.append(has(ctx, p) ? "  ✓ " : "  ✗ ").append(shortName(p)).append("\n");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.append("\n[Notifications]\n");
            sb.append(has(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    ? "  ✓ " : "  ✗ ").append("POST_NOTIFICATIONS\n");
        }

        sb.append("\n[Special]\n");
        sb.append(SpecialPermissions.hasAccessibility(ctx)
                ? "  ✓ " : "  ✗ ").append("Accessibility Service\n");

        return sb.toString();
    }

    private static String shortName(String permission) {
        int dot = permission.lastIndexOf('.');
        return dot >= 0 ? permission.substring(dot + 1) : permission;
    }

    private PermissionGateway() {}
}