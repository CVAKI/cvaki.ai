package com.braingods.cva;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.nio.ByteBuffer;

/**
 * ScreenCaptureManager
 *
 * Wraps MediaProjection to take a single-frame screenshot as a Bitmap.
 *
 * USAGE:
 *   // 1. In your Activity, request permission:
 *   MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
 *   startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CODE_SCREEN);
 *
 *   // 2. In onActivityResult:
 *   ScreenCaptureManager.init(context, resultCode, data);
 *
 *   // 3. Anywhere (off main thread):
 *   ScreenCaptureManager.get().capture(bitmap -> { ... });
 *
 * The singleton holds the MediaProjection token for repeated captures.
 * Call release() when done to free projection resources.
 */
public class ScreenCaptureManager {

    private static final String TAG = "ScreenCapture";

    public interface CaptureCallback {
        void onCaptured(Bitmap bitmap);
        void onError(String reason);
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static ScreenCaptureManager sInstance;

    public static synchronized void init(Context ctx, int resultCode, Intent data) {
        if (sInstance != null) sInstance.release();
        sInstance = new ScreenCaptureManager(ctx.getApplicationContext(), resultCode, data);
    }

    public static synchronized ScreenCaptureManager get() {
        return sInstance;
    }

    public static boolean isReady() {
        return sInstance != null && sInstance.projection != null;
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private final Context            ctx;
    private final MediaProjection    projection;
    private final int                screenWidth;
    private final int                screenHeight;
    private final int                screenDpi;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ScreenCaptureManager(Context ctx, int resultCode, Intent data) {
        this.ctx = ctx;

        // Resolve display metrics
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRealMetrics(dm);
        screenWidth  = dm.widthPixels;
        screenHeight = dm.heightPixels;
        screenDpi    = dm.densityDpi;

        MediaProjectionManager mpm =
                (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);

        if (projection == null) {
            Log.e(TAG, "getMediaProjection() returned NULL — check foregroundServiceType=mediaProjection");
        } else {
            Log.i(TAG, "MediaProjection obtained ✓  screen=" + screenWidth + "×" + screenHeight);
        }
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    /**
     * Capture one frame. Callback fires on the main thread.
     *
     * WHY THE RETRY LOOP:
     *   acquireLatestImage() returns null on the FIRST onImageAvailable call
     *   because the VirtualDisplay has not rendered a frame yet. The listener
     *   fires as soon as the Surface is connected, before the GPU has produced
     *   pixels. We retry up to 10 times with 50 ms gaps (500 ms max).
     */
    public void capture(CaptureCallback cb) {
        if (projection == null) {
            fireCb(cb, null, "No MediaProjection available");
            return;
        }

        // Background thread so we never block the main thread during retries
        android.os.HandlerThread ht = new android.os.HandlerThread("CVACapture");
        ht.start();
        Handler bgHandler = new Handler(ht.getLooper());

        ImageReader reader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);

        VirtualDisplay[] vdRef = new VirtualDisplay[1];
        vdRef[0] = projection.createVirtualDisplay(
                "CVACaptureDisplay",
                screenWidth, screenHeight, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        int[] attempts = {0};
        final int MAX_ATTEMPTS = 10;
        // Guard: ensures cleanup + callback fire exactly once even if ImageReader
        // delivers a second queued frame AFTER doCleanup() has quit the HandlerThread,
        // which would otherwise cause "sending message to a Handler on a dead thread".
        java.util.concurrent.atomic.AtomicBoolean done =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                // Ignore stale callbacks fired after we already cleaned up
                if (done.get()) {
                    Image stale = null;
                    try { stale = r.acquireLatestImage(); } catch (Exception ignored) {}
                    finally { if (stale != null) try { stale.close(); } catch (Exception ignored) {} }
                    return;
                }
                attempts[0]++;
                Image image = null;
                try {
                    image = r.acquireLatestImage();

                    if (image == null) {
                        if (attempts[0] < MAX_ATTEMPTS) {
                            Log.d(TAG, "acquireLatestImage null, retry " + attempts[0]);
                            r.setOnImageAvailableListener(null, null);
                            bgHandler.postDelayed(() ->
                                    r.setOnImageAvailableListener(this, bgHandler), 50);
                        } else {
                            if (done.compareAndSet(false, true)) {
                                doCleanup(vdRef, r, ht);
                                fireCb(cb, null, "No frame after " + MAX_ATTEMPTS + " attempts");
                            }
                        }
                        return;
                    }

                    // Got a real frame — convert to Bitmap
                    Image.Plane plane     = image.getPlanes()[0];
                    ByteBuffer  buffer    = plane.getBuffer();
                    int         rowStride = plane.getRowStride();
                    int         pixStride = plane.getPixelStride();
                    int         rowPad    = rowStride - pixStride * screenWidth;

                    Bitmap bmp = Bitmap.createBitmap(
                            screenWidth + rowPad / pixStride,
                            screenHeight,
                            Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buffer);

                    if (bmp.getWidth() != screenWidth) {
                        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight);
                        bmp.recycle();
                        bmp = cropped;
                    }

                    Log.i(TAG, "Frame captured after " + attempts[0] + " attempt(s)");
                    final Bitmap finalBmp = bmp;
                    if (done.compareAndSet(false, true)) {
                        doCleanup(vdRef, r, ht);
                        fireCb(cb, finalBmp, null);
                    } else {
                        finalBmp.recycle(); // already done, discard
                    }

                } catch (Exception e) {
                    Log.e(TAG, "capture error", e);
                    if (done.compareAndSet(false, true)) {
                        doCleanup(vdRef, r, ht);
                        fireCb(cb, null, e.getMessage());
                    }
                } finally {
                    if (image != null) try { image.close(); } catch (Exception ignored) {}
                }
            }

            private void doCleanup(VirtualDisplay[] vd, ImageReader rd,
                                   android.os.HandlerThread thread) {
                try { rd.setOnImageAvailableListener(null, null); } catch (Exception ignored) {}
                try { if (vd[0] != null) { vd[0].release(); vd[0] = null; } } catch (Exception ignored) {}
                try { rd.close(); } catch (Exception ignored) {}
                try { thread.quitSafely(); } catch (Exception ignored) {}
            }

        }, bgHandler);
    }

    /**
     * Capture and return a scaled-down bitmap (for sending to AI).
     * maxPx = max dimension; keeps aspect ratio.
     */
    public void captureScaled(int maxPx, CaptureCallback cb) {
        capture(new CaptureCallback() {
            @Override public void onCaptured(Bitmap bitmap) {
                float scale = Math.min(
                        (float) maxPx / bitmap.getWidth(),
                        (float) maxPx / bitmap.getHeight());
                if (scale >= 1f) { cb.onCaptured(bitmap); return; }
                int w = Math.round(bitmap.getWidth()  * scale);
                int h = Math.round(bitmap.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, w, h, true);
                bitmap.recycle();
                cb.onCaptured(scaled);
            }
            @Override public void onError(String reason) { cb.onError(reason); }
        });
    }

    // ── Coordinate scaling ────────────────────────────────────────────────────

    /** Scale AI-returned coords (from a 720px-wide image) to real screen coords */
    public float[] scaleCoords(float aiX, float aiY, int aiImageWidth, int aiImageHeight) {
        float sx = (float) screenWidth  / aiImageWidth;
        float sy = (float) screenHeight / aiImageHeight;
        return new float[]{ aiX * sx, aiY * sy };
    }

    public int getScreenWidth()  { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    public void release() {
        if (projection != null) projection.stop();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void fireCb(CaptureCallback cb, Bitmap bmp, String err) {
        mainHandler.post(() -> {
            if (err != null) cb.onError(err);
            else             cb.onCaptured(bmp);
        });
    }
}