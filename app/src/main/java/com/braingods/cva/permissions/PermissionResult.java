package com.braingods.cva.permissions;

import java.util.Collections;
import java.util.List;

/**
 * PermissionResult
 * ─────────────────────────────────────────────────────────────────────────────
 * Immutable result returned after a permission request completes.
 * Passed to your {@link Callback} by {@link PermissionGateway#onRequestPermissionsResult}.
 */
public final class PermissionResult {

    /** The request code that triggered this result. */
    public final int          requestCode;

    /** Permissions that were GRANTED. */
    public final List<String> granted;

    /** Permissions that were DENIED (includes "never ask again" denials). */
    public final List<String> denied;

    PermissionResult(int rc, List<String> granted, List<String> denied) {
        this.requestCode = rc;
        this.granted     = Collections.unmodifiableList(granted);
        this.denied      = Collections.unmodifiableList(denied);
    }

    /** True if every requested permission was granted. */
    public boolean isAllGranted() {
        return denied.isEmpty();
    }

    /** True if every requested permission was denied. */
    public boolean isAllDenied() {
        return granted.isEmpty();
    }

    /** True if at least one permission was granted. */
    public boolean isAnyGranted() {
        return !granted.isEmpty();
    }

    /** True if the given permission was granted in this result. */
    public boolean wasGranted(String permission) {
        return granted.contains(permission);
    }

    /** True if the given permission was denied in this result. */
    public boolean wasDenied(String permission) {
        return denied.contains(permission);
    }

    @Override
    public String toString() {
        return "PermissionResult{rc=" + requestCode +
                ", granted=" + granted +
                ", denied="  + denied + "}";
    }

    // ── Callback interface ────────────────────────────────────────────────────

    /**
     * Implement this to receive permission results from {@link PermissionGateway}.
     *
     * Minimum implementation: override {@link #onAllGranted()} and
     * {@link #onAllDenied(PermissionResult)}.
     */
    public interface Callback {

        /** Called when EVERY requested permission was granted. */
        void onAllGranted();

        /** Called when EVERY requested permission was denied. */
        void onAllDenied(PermissionResult result);

        /**
         * Called when SOME (but not all) permissions were granted.
         * Default implementation routes to {@link #onAllDenied} for the denied ones;
         * override if you need fine-grained handling.
         */
        default void onPartiallyGranted(PermissionResult result) {
            // Default: treat partial grant as partial failure — log and continue
            onAllDenied(result);
        }
    }
}