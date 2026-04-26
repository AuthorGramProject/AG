# Building Nagram Extera

This guide describes how to produce a working APK from a clean checkout of
this repository. The CI pipeline in [`.github/workflows/`](../.github/workflows)
performs the same steps in an automated fashion — see
[`RELEASE.md`](RELEASE.md).

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** (Temurin recommended) | Required by AGP 9.1 and the Kotlin toolchain. |
| Android SDK | Platform **android-36**, build-tools **36.0.0** | Install via Android Studio or `sdkmanager`. |
| Android NDK | **27.2.12479018** | Pinned in `build.gradle`; older NDKs will fail the native build. |
| CMake | **3.31.6+** | Comes bundled with recent Android Studio releases. |
| Git | Any recent version | Used by the build to derive `versionCode`/`versionName`. |
| `ccache` *(optional)* | Any recent version | Picked up automatically when on `PATH`; speeds up native builds. |

The project pins `compileSdk = 36`, `targetSdk = 36`, `minSdk = 27` (Android
8.1+).

## 2. Telegram API credentials

Telegram requires an **app id** and **app hash** for any third-party client.
Obtain them from <https://my.telegram.org/auth> and put them in
`local.properties` at the repository root:

```properties
TELEGRAM_APP_ID=123456
TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
```

The same values may be supplied through the `TELEGRAM_APP_ID` /
`TELEGRAM_APP_HASH` environment variables — useful in CI.

> **Note —** do not commit `local.properties`. It is already in `.gitignore`.

## 3. Signing (optional but recommended)

The repo ships a placeholder `TMessagesProj/release.keystore` with the
well-known `android` password so debug builds work out of the box. For real
releases, replace it with your own keystore and add the credentials to
`local.properties`:

```properties
KEYSTORE_PASS=...
ALIAS_NAME=...
ALIAS_PASS=...
```

CI uses a base64-encoded `LOCAL_PROPERTIES` secret instead — see
[`RELEASE.md`](RELEASE.md).

## 4. Firebase / FCM (optional)

Push notifications require a valid `TMessagesProj/google-services.json`. The
file in the repository is a placeholder; replace it with your own Firebase
project's configuration to enable FCM. Builds will succeed without a real
file, but push delivery will be disabled.

## 5. Building

Universal release APK (all ABIs):

```bash
./gradlew :TMessagesProj:assembleRelease
```

Single-architecture build (recommended for CI; matches the release pipeline):

```bash
NATIVE_TARGET=arm64-v8a ./gradlew :TMessagesProj:assembleRelease
```

Other useful tasks:

| Command | Purpose |
|---|---|
| `./gradlew :TMessagesProj:assembleDebug` | Debug build, debuggable, no minification. |
| `./gradlew :TMessagesProj:assembleStaging` | Minified pre-release flavour, mirrors release packaging. |
| `./gradlew :TMessagesProj:bundleRelease` | Build an Android App Bundle. |
| `NATIVE_TARGET=SKIP ./gradlew ...` | Skip native (CMake/JNI) tasks for faster iteration on Java/Kotlin code. |

Output APKs are renamed to:

```
NagramExtera-v<versionName>(<versionCode>)-<abi>.apk
```

…where `versionName` and `versionCode` are derived automatically — see the
next section.

## 6. Automatic versioning

**Nothing is bumped manually.** All version numbers are derived at build time
from git metadata. The single source of truth for the *base* version is
`APP_VERSION_NAME` in `gradle.properties`; everything else is automatic.

| Field | Source | Example |
|---|---|---|
| `versionCode` | `git rev-list --count HEAD` (strictly monotonic); falls back to `APP_VERSION_CODE` when git is absent | `42` |
| `versionName` (CI / clean) | `${APP_VERSION_NAME}-${shortSha}` | `1.0.0-a1b2c3d` |
| `versionName` (local dirty) | `${APP_VERSION_NAME}-dev+${shortSha}` | `1.0.0-dev+a1b2c3d` |
| `BUILD_TIMESTAMP` | `BUILD_TIMESTAMP` env var on CI; otherwise `System.currentTimeMillis()/1000` | `1714123456` |

### Nagram Extera vs. upstream Telegram versioning

`APP_VERSION_NAME` / `APP_VERSION_CODE` are **Nagram Extera** versions and
start at `1.0.0` / `1`. They are completely independent of the upstream
Telegram release cycle.

The upstream Telegram version is tracked separately in `TELEGRAM_VERSION_NAME`
/ `TELEGRAM_VERSION_CODE` for informational purposes only (shown in the
About screen as "Telegram version"). It **never** drives versionName or
versionCode.

### Bumping the base version

To tag a new named release (e.g. `1.1.0`), change only this line in
`gradle.properties`:

```properties
APP_VERSION_NAME=1.1.0
```

Commit the change. The next CI run will pick it up automatically. No other
file needs editing.

## 7. Common issues

| Symptom | Fix |
|---|---|
| `NDK at … did not have a source.properties file` | Install NDK `27.2.12479018` exactly. AGP 9.1 will refuse newer/older revisions. |
| `Could not find tools.jar` | Ensure `JAVA_HOME` points at JDK 21, not a JRE. |
| Native build hangs at link step | Lower `org.gradle.jvmargs` if you have <12 GB of RAM, or enable `ccache`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | You probably switched between debug/release signing keys. Uninstall the previous APK and try again. |
| `versionCode 1` after `git clone --depth=1` | Use a full clone; `versionCode` requires the entire history. CI does this automatically with `fetch-depth: 0`. |
