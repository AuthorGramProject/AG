# NagramExtera — Replit Project Notes

## Project Overview

**NagramExtera** is a feature-rich, privacy-first fork of the official Telegram for Android client. It is a native Android application written in Java and Kotlin, with extensive C/C++ native code (JNI) for networking, encryption, and media processing.

## Tech Stack

- **Languages:** Java, Kotlin (JVM 21), C/C++ (NDK JNI)
- **Build System:** Gradle 9.4.0 (via Gradle Wrapper `./gradlew`)
- **Android Toolchain:** AGP 9.1, NDK 27.2.12479018, CMake 3.31+, Build Tools 36.0.0
- **Min SDK:** 27 (Android 8.1) | **Target SDK:** 36 (Android 15+)
- **Application ID:** `app.nagramextera`

## Project Structure

```
NagramExtera/
├── TMessagesProj/          # Main Android module (core app logic)
│   ├── src/main/java/      # Official Telegram + Nagram Java code
│   ├── src/main/kotlin/    # Nagram-specific Kotlin enhancements
│   ├── jni/                # C/C++ native code (networking, crypto, media)
│   └── build.gradle        # App module build config
├── buildSrc/               # Custom Gradle build logic (TL-RPC schema gen)
├── documentations/         # Developer docs (BUILDING.md, FEATURES.md, etc.)
├── Tools/                  # Internal build utilities
├── build.gradle            # Root build config
├── gradle.properties       # Project-wide Gradle settings
└── settings.gradle         # Project name and module registration
```

## Important Notes for Replit

This is a **native Android application** and **cannot run as a web server**. Building requires:

1. **Android SDK** (Platform android-36, Build Tools 36.0.0)
2. **Android NDK** (version 27.2.12479018 exactly)
3. **JDK 21** (Temurin recommended)
4. **CMake 3.31+**

These tools are **not available** in the standard Replit environment. The Gradle wrapper (`./gradlew`) is available and Gradle itself runs fine, but Android builds will fail without the Android SDK.

## Building (Locally or in CI)

### Prerequisites
1. Install JDK 21, Android SDK (platform-36, build-tools 36.0.0), NDK 27.2.12479018, CMake 3.31+
2. Obtain Telegram API credentials from https://my.telegram.org/auth
3. Create `local.properties` at project root:
   ```properties
   TELEGRAM_APP_ID=YOUR_APP_ID
   TELEGRAM_APP_HASH=YOUR_APP_HASH
   ```

### Build Commands
```bash
# Debug APK
./gradlew :TMessagesProj:assembleDebug

# Release APK (single ABI, as used in CI)
NATIVE_TARGET=arm64-v8a ./gradlew :TMessagesProj:assembleRelease

# Skip native build (faster iteration on Java/Kotlin)
NATIVE_TARGET=SKIP ./gradlew :TMessagesProj:assembleDebug
```

Output: `TMessagesProj/build/outputs/apk/`

## Workflow

The configured workflow runs `./gradlew tasks --group=build` to list available Gradle build tasks. This confirms Gradle is working even without the Android SDK installed in this environment.

## Documentation

Full documentation in the `documentations/` folder:
- `BUILDING.md` — Build prerequisites, signing, FCM, versioning
- `FEATURES.md` — Custom features vs stock Telegram
- `ARCHITECTURE.md` — Repository layout and native code
- `RELEASE.md` — CI/CD pipeline and secrets
- `CONTRIBUTING.md` — Code style and PR conventions
