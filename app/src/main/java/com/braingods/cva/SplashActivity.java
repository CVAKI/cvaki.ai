package com.braingods.cva;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * SplashActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * Branded splash screen.
 *
 * Animation sequence:
 *   200 ms  — Fox logo bounces in from scale 0.5 with overshoot
 *   600 ms  — Logo starts a slow breathing pulse
 *   900 ms  — "CVAKI AI" slides up
 *  1300 ms  — "Cognitive Virtual Agent" fades in
 *  1700 ms  — "powered by BrainGods" fades in
 *  2800 ms  — Full screen fades out → MainActivity
 */
public class SplashActivity extends AppCompatActivity {

    private static final int DELAY_LOGO    = 200;
    private static final int DELAY_PULSE   = 600;
    private static final int DELAY_CVAKI   = 900;
    private static final int DELAY_TAGLINE = 1300;
    private static final int DELAY_POWERED = 1700;
    private static final int DELAY_LAUNCH  = 2800;

    private final Handler   handler   = new Handler(Looper.getMainLooper());
    private ValueAnimator   pulseAnim = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen immersive
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_splash);

        // Views — splash_logo_ring is now the ImageView holding cvaki.png
        View     logo      = findViewById(R.id.splash_logo_ring);
        TextView tvCvaki   = findViewById(R.id.splash_tv_cvaki);
        TextView tvTagline = findViewById(R.id.splash_tv_tagline);
        TextView tvPowered = findViewById(R.id.splash_tv_powered);
        View     scanlines = findViewById(R.id.splash_scanlines);

        // Hide everything at start
        logo     .setAlpha(0f);
        tvCvaki  .setAlpha(0f);
        tvTagline.setAlpha(0f);
        tvPowered.setAlpha(0f);
        scanlines.setAlpha(0f);

        // ── Scanlines slow drift ──────────────────────────────────────────────
        handler.postDelayed(() -> {
            scanlines.animate().alpha(0.07f).setDuration(800).start();
            ObjectAnimator drift = ObjectAnimator.ofFloat(scanlines, "translationY", 0f, 140f);
            drift.setDuration(3200);
            drift.setRepeatCount(ValueAnimator.INFINITE);
            drift.setRepeatMode(ValueAnimator.RESTART);
            drift.setInterpolator(new AccelerateDecelerateInterpolator());
            drift.start();
        }, 100);

        // ── 1. Fox logo bounces in ────────────────────────────────────────────
        handler.postDelayed(() -> {
            logo.setScaleX(0.5f);
            logo.setScaleY(0.5f);
            logo.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(550)
                    .setInterpolator(new OvershootInterpolator(1.8f))
                    .start();
        }, DELAY_LOGO);

        // ── 2. Gentle breathing pulse on the logo ─────────────────────────────
        handler.postDelayed(() -> {
            pulseAnim = ValueAnimator.ofFloat(0.96f, 1.04f);
            pulseAnim.setDuration(1400);
            pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnim.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
            pulseAnim.addUpdateListener(a -> {
                float v = (float) a.getAnimatedValue();
                logo.setScaleX(v);
                logo.setScaleY(v);
            });
            pulseAnim.start();
        }, DELAY_PULSE);

        // ── 3. "CVAKI AI" slides up ────────────────────────────────────────────
        handler.postDelayed(() -> {
            tvCvaki.setTranslationY(18f);
            tvCvaki.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(460)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .start();
        }, DELAY_CVAKI);

        // ── 4. Tagline fades in ────────────────────────────────────────────────
        handler.postDelayed(() ->
                        tvTagline.animate().alpha(0.72f).setDuration(500).start(),
                DELAY_TAGLINE);

        // ── 5. "powered by BrainGods" fades in ────────────────────────────────
        handler.postDelayed(() ->
                        tvPowered.animate().alpha(0.85f).setDuration(600).start(),
                DELAY_POWERED);

        // ── 6. Fade out → launch MainActivity ─────────────────────────────────
        handler.postDelayed(() ->
                        getWindow().getDecorView()
                                .animate()
                                .alpha(0f)
                                .setDuration(380)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() -> {
                                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                                    overridePendingTransition(
                                            android.R.anim.fade_in,
                                            android.R.anim.fade_out);
                                    finish();
                                })
                                .start(),
                DELAY_LAUNCH);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (pulseAnim != null) { pulseAnim.cancel(); pulseAnim = null; }
    }
}