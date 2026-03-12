package com.braingods.cva.permissions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PermissionRequest
 * ─────────────────────────────────────────────────────────────────────────────
 * Immutable value object describing a permission request.
 * Build via {@link #builder()}.
 *
 * USAGE:
 *   PermissionRequest req = PermissionRequest.builder()
 *       .add(Manifest.permission.CAMERA)
 *       .add(Manifest.permission.RECORD_AUDIO)
 *       .requestCode(MY_RC)
 *       .rationale("Camera is needed for screen capture")
 *       .callback(myCallback)
 *       .build();
 *   PermissionGateway.request(activity, req);
 */
public final class PermissionRequest {

    final String[]               permissions;
    final int                    requestCode;
    final String                 rationale;       // optional, shown before request
    final PermissionResult.Callback callback;     // optional result callback

    private PermissionRequest(Builder b) {
        this.permissions = b.permissions.toArray(new String[0]);
        this.requestCode = b.requestCode;
        this.rationale   = b.rationale;
        this.callback    = b.callback;
    }

    /** Start building a new PermissionRequest. */
    public static Builder builder() { return new Builder(); }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final List<String> permissions = new ArrayList<>();
        private int                requestCode  = PermissionGateway.RC_ALL;
        private String             rationale;
        private PermissionResult.Callback callback;

        /** Add a single Manifest.permission constant. */
        public Builder add(String permission) {
            if (permission != null && !permissions.contains(permission)) {
                permissions.add(permission);
            }
            return this;
        }

        /** Add multiple Manifest.permission constants. */
        public Builder addAll(String... perms) {
            for (String p : perms) add(p);
            return this;
        }

        /** The request code used with ActivityCompat.requestPermissions. */
        public Builder requestCode(int rc) {
            this.requestCode = rc;
            return this;
        }

        /**
         * Optional human-readable rationale shown to the user before the
         * system dialog appears.  Your Activity is responsible for showing it
         * (e.g. in a Snackbar or AlertDialog) when
         * {@link androidx.core.app.ActivityCompat#shouldShowRequestPermissionRationale}
         * returns true.
         */
        public Builder rationale(String text) {
            this.rationale = text;
            return this;
        }

        /** Callback invoked after the request completes. */
        public Builder callback(PermissionResult.Callback cb) {
            this.callback = cb;
            return this;
        }

        public PermissionRequest build() {
            if (permissions.isEmpty()) {
                throw new IllegalStateException("PermissionRequest: no permissions added");
            }
            return new PermissionRequest(this);
        }
    }
}