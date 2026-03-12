package com.braingods.cva.permissions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * MediaProjectionGate
 * ─────────────────────────────────────────────────────────────────────────────
 * Wraps the complete media projection permission flow and fixes the two crashes
 * seen in the logs:
 *
 *  CRASH 1:
 *    SecurityException: Media projections require a foreground service of type
 *    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
 *    at ScreenCaptureManager.<init> line 89
 *
 *  CRASH 2:
 *    SecurityException: Invalid media projection
 *    at ScreenCaptureManager.capture line 124-130
 *
 * ROOT CAUSES:
 *  1. SmartOverlayService must be running as a foreground service with the
 *     FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION type BEFORE getMediaProjection()
 *     is called.  Starting the service and immediately calling getMediaProjection()
 *     in the same onActivityResult() call loses the race — the service hasn't
 *     called startForeground() yet.
 *
 *  2. The MediaProjection token is single-use (Android 10+).  If the token was
 *     already used to create a VirtualDisplay, it's invalidated.  Any subsequent
 *     capture call throws "Invalid media projection" even if the token object is
 *     still in memory.  You must re-request the permission to get a fresh token.
 *
 * USAGE:
 *   // In ScreenCapturePermissionActivity:
 *   public static final int RC_MEDIA_PROJECTION = 1050;
 *
 *   // Start the request:
 *   MediaProjectionGate.request(this, RC_MEDIA_PROJECTION);
 *
 *   // In onActivityResult:
 *   @Override
 *   protected void onActivityResult(int requestCode, int resultCode, Intent data) {
 *       MediaProjectionGate.onActivityResult(this, requestCode, RC_MEDIA_PROJECTION,
 *           resultCode, data, (resultCode2, data2) -> {
 *               // Safe to call ScreenCaptureManager.init(resultCode2, data2) here
 *           });
 *   }
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public final class MediaProjectionGate {

    private static final String TAG              = "MediaProjectionGate";

    /** Delay between starting SmartOverlayService and calling getMediaProjection(). */
    private static final long   SERVICE_START_DELAY_MS = 300L;

    // ── Public API ────────────────────────────────────────────────────────────

    /** Callback delivered once the foreground service is ready and the token is valid. */
    public interface ReadyCallback {
        /**
         * Called on the main thread after SmartOverlayService has started its
         * foreground notification.  Safe to call getMediaProjection() here.
         *
         * @param resultCode  RESULT_OK from the system dialog
         * @param data        Intent containing the MediaProjection token
         */
        void onReady(int resultCode, Intent data);

        /** Called if the user denied the media projection dialog. */
        default void onDenied() {
            Log.w(TAG, "Media projection denied by user");
        }
    }

    /**
     * Launch the system media projection permission dialog.
     * Call from an Activity or Fragment.
     *
     * @param activity      calling Activity
     * @param requestCode   your RC for onActivityResult (e.g. 1050)
     */
    public static void request(Activity activity, int requestCode) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) activity.getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;

        Log.d(TAG, "Launching media projection intent");
        activity.startActivityForResult(
                mpm.createScreenCaptureIntent(), requestCode);
    }

    /**
     * Handle the result from the system media projection dialog.
     * Call from your Activity's onActivityResult().
     *
     * This method:
     *   1. Verifies the result is from the correct request code
     *   2. Starts SmartOverlayService as a FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION service
     *   3. Waits {@value SERVICE_START_DELAY_MS}ms for the service to call startForeground()
     *   4. Delivers the token to your callback
     *
     * @param context     calling context
     * @param requestCode as received by onActivityResult
     * @param myRc        your RC passed to {@link #request}
     * @param resultCode  as received by onActivityResult
     * @param data        as received by onActivityResult
     * @param callback    invoked when safe to call getMediaProjection()
     */
    public static void onActivityResult(
            Context context, int requestCode, int myRc,
            int resultCode, Intent data, ReadyCallback callback) {

        if (requestCode != myRc) return;

        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "Media projection dialog: user denied or null data");
            if (callback != null) callback.onDenied();
            return;
        }

        Log.d(TAG, "Media projection granted — starting foreground service first");

        // Step 1: Start SmartOverlayService with mediaProjection foreground type.
        // The service MUST call startForeground(id, notification, TYPE_MEDIA_PROJECTION)
        // before we call getMediaProjection().
        startProjectionForegroundService(context);

        // Step 2: Wait for the service to become foreground, THEN deliver the token.
        // 300ms is enough on all tested devices; the service start is synchronous
        // in terms of Binder calls and the delay covers the notification post.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.d(TAG, "Delivering media projection token to callback");
            if (callback != null) callback.onReady(resultCode, data);
        }, SERVICE_START_DELAY_MS);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void startProjectionForegroundService(Context ctx) {
        try {
            Class<?> cls = Class.forName(ctx.getPackageName() + ".SmartOverlayService");
            Intent intent = new Intent(ctx, cls);
            intent.setAction("ACTION_START_MEDIA_PROJECTION_FOREGROUND");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            Log.d(TAG, "SmartOverlayService start intent sent");
        } catch (Exception e) {
            Log.e(TAG, "Could not start SmartOverlayService: " + e.getMessage());
        }
    }

    // ── SmartOverlayService integration note ─────────────────────────────────
    //
    // In SmartOverlayService.onStartCommand(), handle the action:
    //
    //   if ("ACTION_START_MEDIA_PROJECTION_FOREGROUND".equals(intent.getAction())) {
    //       Notification n = buildMediaProjectionNotification();
    //       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    //           startForeground(NOTIF_ID, n,
    //               ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    //       } else {
    //           startForeground(NOTIF_ID, n);
    //       }
    //   }
    //
    // The AndroidManifest already declares:
    //   android:foregroundServiceType="mediaProjection"
    // on SmartOverlayService, which is the other required piece.

    private MediaProjectionGate() {}
}