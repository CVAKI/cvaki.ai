plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.braingods.cva"
    compileSdk = 36          // compile against latest SDK so modern APIs are available

    defaultConfig {
        applicationId = "com.braingods.cva"
        minSdk        = 26   // Android 8.0  — lowest supported
        versionCode   = 1
        versionName   = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ── CRITICAL: targetSdk MUST stay at 28 ─────────────────────────────
        // This is the same reason real Termux uses targetSdk 28.
        //
        // Android SELinux domain rules:
        //   targetSdk ≤ 28  →  'untrusted_app'    domain  →  execve() on /data/data/ ALLOWED
        //   targetSdk ≥ 29  →  'untrusted_app_29' domain  →  execve() on /data/data/ BLOCKED
        //
        // The bootstrap bash lives at /data/data/com.braingods.cva/files/usr/bin/bash.
        // Raising targetSdk to 29+ makes the kernel return EACCES on every execve() of
        // that binary, regardless of file permissions.
        //
        // IMPORTANT: targetSdk=28 does NOT limit which Android version the app RUNS on.
        //   minSdk=26 → compileSdk=36 means the app installs and runs on Android 8 → 15.
        //   targetSdk only opts in/out of behavioral changes introduced at each API level.
        //   Android 13/14/15 devices run targetSdk=28 apps perfectly — Termux proves this.
        targetSdk = 28
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Must be true — .so files stored uncompressed so they can be
            // extracted to nativeLibraryDir at install time with correct permissions.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    // ── Lint: suppress warnings caused by targetSdk=28 on modern compileSdk ──
    // These are expected and intentional — not real bugs.
    lint {
        disable += setOf(
            "OldTargetApi",           // "targetSdkVersion 28 is too low" — intentional
            "ExpiredTargetSdkVersion" // Play Store warning — acceptable for sideload
        )
        abortOnError = false
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation("org.json:json:20231013")

    // AndroidX Core — NotificationCompat handles Android 13 POST_NOTIFICATIONS
    // gracefully (shows notification if permission granted, silent if not — no crash).
    implementation("androidx.core:core:1.13.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}