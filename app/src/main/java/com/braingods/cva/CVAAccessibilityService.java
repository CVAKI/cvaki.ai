package com.braingods.cva;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;

/**
 * CVAAccessibilityService
 *
 * Provides:
 *   - performClick(x, y)         — tap any screen pixel
 *   - performLongClick(x, y)     — long-press any pixel
 *   - performSwipe(x1,y1,x2,y2) — drag / scroll
 *   - performType(text)          — inject text into focused field
 *   - teleportAndType(x,y,text)  — click a field then type into it
 *
 * Register in AndroidManifest.xml:
 *
 *   <service
 *       android:name=".CVAAccessibilityService"
 *       android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
 *       android:exported="true">
 *     <intent-filter>
 *       <action android:name="android.accessibilityservice.AccessibilityService"/>
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.accessibilityservice"
 *         android:resource="@xml/accessibility_service_config"/>
 *   </service>
 *
 * accessibility_service_config.xml (res/xml/):
 *
 *   <accessibility-service
 *       xmlns:android="http://schemas.android.com/apk/res/android"
 *       android:accessibilityEventTypes="typeAllMask"
 *       android:accessibilityFeedbackType="feedbackGeneric"
 *       android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"
 *       android:canPerformGestures="true"
 *       android:canRetrieveWindowContent="true"
 *       android:notificationTimeout="100"/>
 */
public class CVAAccessibilityService extends AccessibilityService {

    private static final String TAG = "CVAAccess";

    /** Singleton reference — set when service connects */
    public static CVAAccessibilityService instance;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onServiceConnected() {
        instance = this;
        Log.i(TAG, "CVA Accessibility Service connected ✓");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* not needed */ }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Tap a single pixel coordinate */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void performClick(float x, float y, Runnable onDone) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "dispatchGesture requires API 24+"); return;
        }
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();

        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Log.d(TAG, "Click done at (" + x + "," + y + ")");
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(GestureDescription g) {
                Log.w(TAG, "Click cancelled");
                if (onDone != null) onDone.run();
            }
        }, null);
    }

    /** Long-press a pixel coordinate (600 ms) */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void performLongClick(float x, float y, Runnable onDone) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 600))
                .build();

        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(GestureDescription g) {
                if (onDone != null) onDone.run();
            }
        }, null);
    }

    /** Swipe from (x1,y1) → (x2,y2) in durationMs milliseconds */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void performSwipe(float x1, float y1, float x2, float y2,
                             int durationMs, Runnable onDone) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();

        dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (onDone != null) onDone.run();
            }
            @Override public void onCancelled(GestureDescription g) {
                if (onDone != null) onDone.run();
            }
        }, null);
    }

    /**
     * Type text into the currently focused AccessibilityNodeInfo.
     * Works without clicking — just injects into whatever has focus.
     */
    public void performType(String text) {
        AccessibilityNodeInfo focus = findFocused();
        if (focus != null) {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            Log.d(TAG, "Typed text via SET_TEXT action");
        } else {
            Log.w(TAG, "No focused node found for typing");
        }
    }

    /**
     * Click a field (to focus it) then type text after a short delay.
     * Uses dispatchGesture for click so it works on any app.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void teleportAndType(float x, float y, String text, Runnable onDone) {
        // Step 1: click to focus
        performClick(x, y, () -> {
            // Step 2: small pause then type
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            // Try SET_TEXT on focused node first (most reliable)
            AccessibilityNodeInfo focus = findFocused();
            if (focus != null) {
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                boolean ok = focus.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (ok) {
                    Log.d(TAG, "teleportAndType: SET_TEXT success");
                    if (onDone != null) onDone.run();
                    return;
                }
            }

            // Fallback: paste via global action
            Log.d(TAG, "teleportAndType: fallback paste");
            android.content.ClipData clip = android.content.ClipData
                    .newPlainText("cva", text);
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(clip);
            // SELECT ALL then PASTE
            AccessibilityNodeInfo node = findFocused();
            if (node != null) {
                node.performAction(AccessibilityNodeInfo.ACTION_SELECT);
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            }
            if (onDone != null) onDone.run();
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Find the currently focused input field in any window */
    private AccessibilityNodeInfo findFocused() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Exception e) {
            Log.e(TAG, "findFocused error: " + e.getMessage());
            return null;
        }
    }

    /** Check if the service is alive and usable */
    public static boolean isAvailable() {
        return instance != null;
    }
}