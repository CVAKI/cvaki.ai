<div align="center">

<!-- LOGO -->
<img src="app/src/main/res/drawable/cvaki.png" alt="Cvaki Logo" width="140" height="140" style="border-radius:28px;"/>

<br/>

# ◈ CVAKI · AI

### *Cognitive Virtual Agent — Android Intelligence Layer*

<br/>

[![Build](https://img.shields.io/badge/build-passing-brightgreen?style=for-the-badge&logo=android&logoColor=white)](.)
[![SDK](https://img.shields.io/badge/minSDK-26-orange?style=for-the-badge&logo=android)](.)
[![Target](https://img.shields.io/badge/targetSDK-36-FF6A1A?style=for-the-badge&logo=android)](.)
[![Java](https://img.shields.io/badge/Java-17-blue?style=for-the-badge&logo=openjdk)](.)
[![License](https://img.shields.io/badge/license-Proprietary-red?style=for-the-badge)](.)

[![Anthropic](https://img.shields.io/badge/Claude-Haiku%204.5-6B46C1?style=for-the-badge&logo=anthropic&logoColor=white)](https://anthropic.com)
[![Gemini](https://img.shields.io/badge/Google-Gemini-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://ai.google.dev)
[![OpenRouter](https://img.shields.io/badge/OpenRouter-Multi--Model-00C2A0?style=for-the-badge)](https://openrouter.ai)
[![Groq](https://img.shields.io/badge/Groq-LPU%20Inference-F55036?style=for-the-badge)](https://groq.com)

<br/>

> **CVA** is an Android-native AI agent that lives inside your keyboard, floats over every app as a smart bubble, and can see your screen — think, act, and automate — all powered by frontier LLMs.

<br/>

---

</div>

## 📋 Table of Contents

- [✨ Features](#-features)
- [🏗 Architecture](#-architecture)
- [📁 Project Structure](#-project-structure)
- [🧩 Core Components](#-core-components)
- [🔐 Encryption Engine](#-encryption-engine)
- [🤖 AI Vision Engine](#-ai-vision-engine)
- [🧠 Brain Agent](#-brain-agent)
- [⌨️ Keyboard Service](#️-keyboard-service)
- [🫧 Overlay System](#-overlay-system)
- [♿ Accessibility Service](#-accessibility-service)
- [📱 Permissions](#-permissions)
- [🎨 Animations & UI](#-animations--ui)
- [🔧 Build & Setup](#-build--setup)
- [📦 Dependencies](#-dependencies)
- [⚙️ Configuration](#️-configuration)

---

## ✨ Features

<div align="center">

| Feature | Description |
|---|---|
| 🧠 **Multi-LLM Brain** | Anthropic Claude, Google Gemini, OpenRouter, Groq — hot-swap in Settings |
| 👁️ **AI Vision** | Screenshot → Claude/Gemini vision → structured tap/type/scroll actions |
| ⌨️ **Smart Keyboard** | Custom IME with Normal, Brain, Hash, and Terminal modes |
| 🫧 **Float Bubble** | Always-on system overlay with single-tap CVA access |
| 🔐 **5-Layer Encryption** | Base64 → XOR → Base64 → MD5 scramble → Unicode glyph substitution |
| 📡 **Live Terminal** | Real-time shell command typing with character-by-character animation |
| ♿ **Gesture Injection** | Tap / swipe / type via Android Accessibility Service API |
| 📸 **Screen Capture** | MediaProjection pipeline (Android 14+ compliant foreground service) |
| 💾 **Persistent Storage** | Encrypted local storage via `CvakiStorage` |
| 🌙 **Dark Mode** | Full Day/Night theme support |

</div>

---

## 🏗 Architecture

```
┌──────────────────────────────────────────────────────────┐
│                       CVA Android App                     │
│                                                          │
│  ┌─────────────┐   ┌──────────────┐   ┌───────────────┐ │
│  │ SplashActivity│  │ MainActivity  │   │ScreenCapture  │ │
│  │  (Launcher)  │  │  (Settings)  │   │PermActivity   │ │
│  └──────┬──────┘   └──────┬───────┘   └───────┬───────┘ │
│         │                 │                    │         │
│  ┌──────▼──────────────────▼────────────────────▼──────┐ │
│  │                    Services Layer                    │ │
│  │                                                      │ │
│  │  ┌─────────────┐  ┌───────────────┐  ┌───────────┐  │ │
│  │  │KeyboardService│ │SmartOverlayService│ │OverlaySvc │  │ │
│  │  │   (IME)     │  │(MediaProjection)│  │  (Bubble) │  │ │
│  │  └──────┬──────┘  └───────┬───────┘  └─────┬─────┘  │ │
│  │         │                 │                 │        │ │
│  │  ┌──────▼─────────────────▼─────────────────▼──────┐ │ │
│  │  │                  Core Engine                     │ │ │
│  │  │  BrainAgent  ←→  AIVisionEngine                  │ │ │
│  │  │  TerminalManager  ←→  CVAAccessibilityService    │ │ │
│  │  │  AdvancedEncryption  ←→  CvakiStorage            │ │ │
│  │  └──────────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌──────────────────────────────────────────────────────┐ │
│  │                  AI Provider Layer                    │ │
│  │  Anthropic API  │  Gemini API  │  OpenRouter  │  Groq │ │
│  └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
cvaki-cvaki.ai/
├── 📄 build.gradle.kts                   # Root build — plugin declarations
├── 📄 settings.gradle.kts                # Project: "Cvaki", includes :app
├── 📄 gradle.properties                  # JVM args, AndroidX flags
│
└── app/
    ├── 📄 build.gradle.kts               # App config (namespace, SDK, deps)
    ├── 📄 proguard-rules.pro             # Release obfuscation rules
    │
    └── src/main/
        ├── 📄 AndroidManifest.xml        # Permissions, activities, services
        │
        ├── java/com/braingods/cva/
        │   ├── 🔐 AdvancedEncryption.java   # 5-layer encryption engine
        │   ├── 💬 AgentChatView.java        # Custom chat UI widget
        │   ├── 👁️  AIVisionEngine.java       # Screenshot → AI → actions
        │   ├── 🧠 BrainAgent.java           # Multi-LLM agentic loop
        │   ├── ♿ CVAAccessibilityService.java # Gesture injection
        │   ├── 💾 CvakiStorage.java          # Encrypted local storage
        │   ├── ⌨️  KeyboardService.java       # IME service (4 keyboard modes)
        │   ├── 🏠 MainActivity.java          # Settings & configuration
        │   ├── 🫧 OverlayService.java        # Basic float bubble service
        │   ├── 📸 ScreenCaptureManager.java  # MediaProjection manager
        │   ├── 🪟 ScreenCapturePermissionActivity.java  # Transparent trampoline
        │   ├── 🌊 SmartOverlayService.java   # Smart AI overlay + capture
        │   ├── 💥 SplashActivity.java        # Animated launcher
        │   ├── 💻 TerminalBootstrap.java     # Shell environment setup
        │   └── 💻 TerminalManager.java       # Shell I/O management
        │
        └── res/
            ├── anim/
            │   ├── fade_slide_up.xml         # Entry animation
            │   ├── pulse_orange.xml          # CVA brand pulse
            │   └── slide_in_left.xml         # Keyboard slide-in
            ├── drawable/                     # Backgrounds, icons, shapes
            ├── layout/                       # XML layouts (10 files)
            ├── values/                       # Colors, strings, themes
            └── xml/                          # Service configs
```

---

## 🧩 Core Components

### 💥 SplashActivity
The app entry point. Renders the animated CVA splash screen before transitioning to `MainActivity`. Uses the `fade_slide_up` and `pulse_orange` animations to deliver a high-impact brand moment on launch.

### 🏠 MainActivity
The settings and configuration hub. Allows the user to:
- Choose and save AI provider (Anthropic / Gemini / OpenRouter / Groq)
- Enter and persist API keys
- Toggle overlay, accessibility, and keyboard services
- View memory/context status

### 🪟 ScreenCapturePermissionActivity
A fully transparent trampoline activity that handles the `MediaProjection` permission dialog. On Android 14+, the raw projection token is passed to `SmartOverlayService` rather than being used directly in the activity — satisfying the strict foreground service requirements.

---

## 🔐 Encryption Engine

`AdvancedEncryption.java` implements a **5-layer encryption pipeline**:

```
Plaintext
    │
    ▼  Layer 1: UTF-8 bytes → Base64
    │
    ▼  Layer 2: XOR cipher with password key
    │
    ▼  Layer 3: ISO-8859-1 bytes → Base64
    │
    ▼  Layer 4: Positional scramble (MD5-seeded permutation)
    │
    ▼  Layer 5: Character substitution → obscure Unicode glyphs
    │
Ciphertext (Unicode glyph string)
```

**Substitution sets** use three glyph tables (`S1`, `S2`, `S3`) covering Tibetan, Arabic presentation forms, archaic scripts, and combining diacritics — making the output visually unrecognisable as encoded text.

The **Hash Keyboard** mode uses this engine to render every keystroke as its corresponding glyph in real time, providing visual privacy for sensitive input.

---

## 🤖 AI Vision Engine

`AIVisionEngine.java` powers the screen-understanding pipeline:

```
Screenshot (Bitmap)
        │
        ▼  Scale to 768px wide (token budget control)
        │
        ▼  JPEG → Base64 encode
        │
        ▼  Send to Vision API
        │    ├─ Anthropic  →  claude-haiku-4-5-20251001
        │    ├─ Gemini     →  gemini-1.5-flash
        │    └─ OpenRouter →  gemini-2.0-flash-exp:free
        │                     llama-3.2-11b-vision-instruct:free
        │
        ▼  Parse JSON action array
        │
        ▼  Scale coordinates (AI image space → screen pixels)
        │
        └─▶  List<ScreenAction>
```

### Action Types

| Type | Description |
|---|---|
| `CLICK` | Tap at `(x, y)` |
| `TYPE` | Inject text at current focus |
| `TELEPORT_TYPE` | Click `(x, y)` then type text |
| `SCROLL` | Swipe from `(x1,y1)` to `(x2,y2)` |
| `WAIT` | Pause for `delayMs` milliseconds |
| `DONE` | Task complete — speak summary message |
| `ERROR` | Propagate parse or execution error |

> **Note:** Groq is not supported in Vision mode (Groq has no vision API). Attempting to use Groq for vision will return a clear, actionable error message directing the user to switch providers.

---

## 🧠 Brain Agent

`BrainAgent.java` is the core agentic loop — it handles multi-turn LLM conversations with tool-use via shell command execution:

### Agentic Loop

```
User message
      │
      ▼  callAI(message)    ← Anthropic / Gemini / OpenRouter / Groq
      │
      ▼  extractCommand(reply)   ← looks for {"cmd":"..."}
      │
   ┌──┴──── cmd found? ────────────────────────────────────────┐
   │  YES                                                       │ NO
   │                                                            ▼
   ▼  [LIVE MODE if enabled]                           Final text reply
   │   1. Open terminal panel
   │   2. Type command char-by-char (18ms/char)
   │   3. Press Enter (submitLiveInput)
   │   4. Capture output (sentinel pattern, 15s timeout)
   │   5. Show coloured result (green=ok, red=error)
   │   6. Close terminal panel
   │
   ▼  callAI(output feedback)
   │
   └──────────────── loop (max 6 iterations) ─────────────────►
```

### Supported Providers & Models

| Provider | Models Used |
|---|---|
| **Anthropic** | `claude-haiku-4-5-20251001` |
| **Gemini** | `gemini-2.0-flash-lite`, `gemini-1.5-flash-8b`, `gemini-1.5-flash`, `gemini-2.0-flash` |
| **Groq** | `llama-3.3-70b-versatile`, `llama3-70b-8192`, `llama3-8b-8192`, `gemma2-9b-it`, `llama-3.1-8b-instant` |
| **OpenRouter** | `openrouter/auto`, `gemini-2.0-flash-exp:free`, `llama-3.3-70b-instruct:free`, `mistral-7b-instruct:free`, `gemma-3-12b-it:free`, `deepseek-r1:free` |

### System Prompt Commands (Examples)

```bash
# Take a photo
am start -a android.media.action.IMAGE_CAPTURE

# Web search
am start -a android.intent.action.VIEW -d "https://www.google.com/search?q=YOUR+QUERY"

# Open settings
am start -a android.settings.SETTINGS

# List files
ls /sdcard/

# Network info
ip addr show
dumpsys wifi | grep mWifiInfo

# Battery status
dumpsys battery
```

---

## ⌨️ Keyboard Service

`KeyboardService.java` implements a full **Android IME** with four distinct keyboard modes:

| Mode | Layout File | Description |
|---|---|---|
| **Normal** | `keyboard_normal.xml` | Standard QWERTY keyboard |
| **Brain** | `keyboard_brain.xml` | AI chat panel embedded below keys |
| **Hash** | `keyboard_hash.xml` | Every key renders as an encrypted glyph |
| **Terminal** | `keyboard_terminal.xml` | Shell terminal with live-mode toggle |

The terminal mode includes the `switch_live_import` toggle that enables/disables the live typing animation panel in `AgentChatView`.

---

## 🫧 Overlay System

Two overlay services provide the floating bubble:

### OverlayService (Basic)
The original, lightweight float bubble — uses `SYSTEM_ALERT_WINDOW` permission with `foregroundServiceType="specialUse"`. Kept for backward compatibility.

### SmartOverlayService (AI-Powered)
The full-featured overlay that combines:
- MediaProjection screen capture
- BrainAgent integration
- AIVisionEngine analysis
- Gesture injection via AccessibilityService

Uses `foregroundServiceType="mediaProjection"` as required by Android 14+.

### Overlay Layouts

| Layout | Purpose |
|---|---|
| `overlay_bubble.xml` | Standard CVA float bubble |
| `overlay_smart_bubble.xml` | Smart overlay with vision controls |
| `tv_overlay_status.xml` | Status chip for overlay notifications |

---

## ♿ Accessibility Service

`CVAAccessibilityService.java` provides **programmatic gesture injection** — enabling the AI to:

- **Tap** any pixel on screen
- **Swipe** in any direction
- **Type** text into any focused field
- **Observe** UI hierarchy changes

Configuration lives in `res/xml/accessibility_service_config.xml`. The service binds with `android.permission.BIND_ACCESSIBILITY_SERVICE`.

---

## 📱 Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | AI API calls (Anthropic, Gemini, Groq, OpenRouter) |
| `SYSTEM_ALERT_WINDOW` | Floating overlay bubble |
| `FOREGROUND_SERVICE` | Persistent services |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Screen capture (Android 14+) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Basic overlay service |
| `BIND_ACCESSIBILITY_SERVICE` | Gesture / tap injection |
| `CAMERA` | Photo capture via intent |
| `RECORD_AUDIO` | Voice input support |
| `READ/WRITE_EXTERNAL_STORAGE` | File access |
| `MANAGE_EXTERNAL_STORAGE` | Full storage management |
| `ACCESS_FINE_LOCATION` | Location-based AI queries |
| `READ_CONTACTS` | Contact-aware AI responses |
| `READ_SMS / RECEIVE_SMS` | SMS-aware agent context |
| `READ_CALL_LOG` | Call history for AI context |
| `VIBRATE` | Haptic feedback |

---

## 🎨 Animations & UI

### Animation Resources (`res/anim/`)

| File | Effect | Used In |
|---|---|---|
| `fade_slide_up.xml` | Fade + slide from bottom | Splash, bubble entry |
| `pulse_orange.xml` | Repeating alpha pulse (orange) | CVA logo badge, status indicators |
| `slide_in_left.xml` | Slide from left edge | Keyboard mode transitions |

### AgentChatView Animations (Programmatic)

The `AgentChatView` custom widget includes several animations created entirely in code (no XML needed):

- **●CVA● blink** — `ValueAnimator` cycling alpha `1.0 → 0.1 → 1.0` at 850ms, runs while the agent is processing
- **⚙ gear rotation** — `ObjectAnimator` 0°→360° at 900ms with `LinearInterpolator`, signals active execution  
- **Bubble fade-in** — `AlphaAnimation` (0→1, 200ms) on each new chat bubble
- **Live panel slide** — `translationY(-200 → 0)` with `AccelerateDecelerateInterpolator` (220ms open, 200ms close)
- **Live panel pulse** — `AlphaAnimation` (0.25→1.0, 500ms, `INFINITE REVERSE`) on the `▶ LIVE TERMINAL` label

### Color Palette

| Token | Hex | Usage |
|---|---|---|
| `CLR_ORANGE` | `#FF6A1A` | Brand accent, agent bubbles, CVA label |
| `CLR_CYAN` | `#00FFFF` | User bubbles, input text |
| `CLR_BG` | `#0D0D0D` | Chat background |
| `CLR_AGENT_BG` | `#111111` | Agent bubble fill |
| `CLR_USER_BG` | `#1A1A2E` | User bubble fill |
| `CLR_LIVE_BG` | `#001A00` | Terminal panel background |
| `CLR_LIVE_TEXT` | `#00FF41` | Matrix green — terminal command text |
| `CLR_RED` | `#FF3333` | Error states |

### Drawable Resources

| File | Description |
|---|---|
| `bg_btn_primary.xml` | Orange primary button background |
| `bg_btn_dark.xml` | Dark button background |
| `bg_btn_ghost.xml` | Transparent outlined button |
| `bg_card_elevated.xml` | Elevated card surface |
| `bg_hero.xml` | Hero/splash section background |
| `bg_logo_ring.xml` | Circular ring around logo |
| `splash_logo_ring.xml` | Animated splash logo ring |
| `splash_scanlines.xml` | CRT scanline overlay for splash |
| `divider_orange.xml` | Orange horizontal divider |
| `ic_dot_orange.xml` | Small orange dot indicator |
| `bg_status_chip.xml` | Pill-shaped status chip |
| `bg_provider_selected.xml` | Selected AI provider card |
| `bg_provider_normal.xml` | Normal AI provider card |
| `bg_edittext.xml` | API key input field |
| `bg_danger_btn.xml` | Red destructive action button |

---

## 🔧 Build & Setup

### Prerequisites

- Android Studio **Hedgehog** or newer
- JDK 17+
- Android device / emulator with API 26+

### 1. Clone

```bash
git clone https://github.com/cvaki/cvaki.ai.git
cd cvaki.ai
```

### 2. Add your logo

Place your `cvaki.png` into:
```
app/src/main/res/drawable/cvaki.png
```

### 3. Build

```bash
./gradlew assembleDebug
```

Or for release:
```bash
./gradlew assembleRelease
```

### 4. Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5. First Launch Setup

1. Open **CVAKI** from launcher
2. Navigate to **Settings**
3. Select your AI provider
4. Enter your API key and tap **SAVE**
5. Enable **Accessibility Service** (required for gesture injection)
6. Enable **CVA** as your default keyboard (optional)
7. Tap the float bubble to begin

---

## 📦 Dependencies

```toml
# gradle/libs.versions.toml

[libraries]
appcompat        = "androidx.appcompat:appcompat"
material         = "com.google.android.material:material"
activity         = "androidx.activity:activity"
constraintlayout = "androidx.constraintlayout:constraintlayout"

# JSON parsing for AI API responses
json             = "org.json:json:20231013"

# Testing
junit            = "junit:junit"
ext-junit        = "androidx.test.ext:junit"
espresso-core    = "androidx.test.espresso:espresso-core"
```

**JNI Libraries** are bundled for three ABIs:
- `arm64-v8a` — modern 64-bit ARM (Snapdragon, Dimensity)
- `armeabi-v7a` — legacy 32-bit ARM
- `x86_64` — emulator / Intel Chromebook

---

## ⚙️ Configuration

### `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

### `app/build.gradle.kts`

```kotlin
android {
    namespace       = "com.braingods.cva"
    compileSdk      = 36

    defaultConfig {
        applicationId = "com.braingods.cva"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

### SharedPreferences Keys

The app uses `cva_prefs` as the preference file. Keys:

| Key | Type | Description |
|---|---|---|
| `provider` | String | Active AI provider (`anthropic`/`gemini`/`openrouter`/`groq`) |
| `key_anthropic` | String | Anthropic API key |
| `key_gemini` | String | Google Gemini API key |
| `key_openrouter` | String | OpenRouter API key |
| `key_groq` | String | Groq API key |

---

<div align="center">

---

<img src="app/src/main/res/drawable/cvaki.png" alt="Cvaki" width="52" height="52" style="border-radius:12px; opacity:0.85;"/>

<br/>

**CVAKI · AI** &nbsp;·&nbsp; Built by [BrainGods](https://braingods.com) &nbsp;·&nbsp; `com.braingods.cva`

<br/>

*"The keyboard that thinks."*

<br/>

![Wave](https://capsule-render.vercel.app/api?type=waving&color=FF6A1A&height=80&section=footer&fontSize=0)

</div>