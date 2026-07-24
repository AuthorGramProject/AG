from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# Gradle: isolate debug builds, remove release signing from debug, validate release
# credentials, derive/pin the APK signing certificate, and use the real version code.
path = "TMessagesProj/build.gradle"
text = read(path)
text = replace_once(
    text,
    "import java.nio.file.Paths\nimport org.gradle.internal.os.OperatingSystem\n",
    "import java.nio.file.Paths\n"
    "import java.security.KeyStore\n"
    "import java.security.MessageDigest\n"
    "import java.util.Arrays\n"
    "import java.util.Locale\n"
    "import org.gradle.internal.os.OperatingSystem\n",
    "build.gradle imports",
)
text = replace_once(
    text,
    "def verCode = 1246\n",
    "def verCode = Integer.parseInt(APP_VERSION_CODE.toString())\n",
    "version code",
)
anchor = """keystorePwd = keystorePwd ?: System.getenv('KEYSTORE_PASS')
alias = alias ?: System.getenv('ALIAS_NAME')
pwd = pwd ?: System.getenv('ALIAS_PASS')

def nativeTarget = System.getenv('NATIVE_TARGET') ?: ''
"""
insert = """keystorePwd = keystorePwd ?: System.getenv('KEYSTORE_PASS')
alias = alias ?: System.getenv('ALIAS_NAME')
pwd = pwd ?: System.getenv('ALIAS_PASS')

def releaseStoreFile = project.file('release.keystore')
def requestedTasks = gradle.startParameter.taskNames.collect { it.toLowerCase(Locale.ROOT) }
def releaseBuildRequested = requestedTasks.any { it.contains('release') || it.contains('staging') }
def normalizeCertificateDigest = { String value ->
    value == null ? '' : value.replaceAll('[^0-9A-Fa-f]', '').toLowerCase(Locale.ROOT)
}
def configuredSigningCertificates = (
        properties?.getProperty('AUTHORGRAM_SIGNING_CERT_SHA256')
                ?: System.getenv('AUTHORGRAM_SIGNING_CERT_SHA256')
                ?: ''
).split(',').collect { normalizeCertificateDigest(it) }.findAll { it.length() == 64 }

def deriveSigningCertificate = {
    if (!releaseStoreFile.isFile() || !keystorePwd || !alias) {
        return ''
    }
    char[] password = keystorePwd.toCharArray()
    try {
        for (String type : ['PKCS12', 'JKS']) {
            try {
                KeyStore keyStore = KeyStore.getInstance(type)
                releaseStoreFile.withInputStream { stream -> keyStore.load(stream, password) }
                def certificate = keyStore.getCertificate(alias)
                if (certificate != null) {
                    byte[] digest = MessageDigest.getInstance('SHA-256').digest(certificate.encoded)
                    return digest.collect { String.format('%02x', it & 0xff) }.join('')
                }
            } catch (Exception ignored) {
                // Try the other common Android keystore container format.
            }
        }
        return ''
    } finally {
        Arrays.fill(password, (char) 0)
    }
}

def derivedSigningCertificate = deriveSigningCertificate()
if (!configuredSigningCertificates.isEmpty()
        && derivedSigningCertificate
        && !configuredSigningCertificates.contains(derivedSigningCertificate)) {
    throw new GradleException('Configured AuthorGram signing certificate does not match release.keystore')
}
def trustedSigningCertificates = configuredSigningCertificates.isEmpty()
        ? derivedSigningCertificate
        : configuredSigningCertificates.join(',')

if (releaseBuildRequested) {
    if (!releaseStoreFile.isFile()) {
        throw new GradleException('TMessagesProj/release.keystore is required for release builds')
    }
    if (!keystorePwd || !alias || !pwd) {
        throw new GradleException('Release signing credentials are missing from LOCAL_PROPERTIES or environment')
    }
    if (!trustedSigningCertificates) {
        throw new GradleException('Unable to derive or configure the trusted signing certificate SHA-256')
    }
}

def nativeTarget = System.getenv('NATIVE_TARGET') ?: ''
"""
text = replace_once(text, anchor, insert, "release security configuration")
text = replace_once(
    text,
    """        buildConfigField 'boolean', 'DEBUG_VERSION', 'false'
        buildConfigField 'boolean', 'DEBUG_PRIVATE_VERSION', 'false'
""",
    """        buildConfigField 'boolean', 'DEBUG_VERSION', 'false'
        buildConfigField 'boolean', 'DEBUG_PRIVATE_VERSION', 'false'
        buildConfigField 'boolean', 'OFFICIAL_BUILD', 'false'
        buildConfigField 'String', 'TRUSTED_SIGNING_CERT_SHA256', '\"\"'
""",
    "default BuildConfig fields",
)
text = replace_once(
    text,
    """        release {
            storeFile project.file('release.keystore')
            storePassword keystorePwd
            keyAlias alias
            keyPassword pwd
        }
""",
    """        release {
            storeFile releaseStoreFile
            if (keystorePwd) {
                storePassword keystorePwd
            }
            if (alias) {
                keyAlias alias
            }
            if (pwd) {
                keyPassword pwd
            }
        }
""",
    "release signing config",
)
text = replace_once(
    text,
    """        debug {
            isDefault = true
            debuggable = true
            jniDebuggable = false
            multiDexEnabled = true
            signingConfig = signingConfigs.release
        }
""",
    """        debug {
            isDefault = true
            debuggable = true
            jniDebuggable = false
            multiDexEnabled = true
            applicationIdSuffix '.debug'
            versionNameSuffix '-debug'
            buildConfigField 'boolean', 'DEBUG_VERSION', 'true'
            buildConfigField 'boolean', 'OFFICIAL_BUILD', 'false'
            buildConfigField 'String', 'TRUSTED_SIGNING_CERT_SHA256', '\"\"'
        }
""",
    "debug build type",
)
text = replace_once(
    text,
    """            matchingFallbacks = ['release', 'staging', 'debug']
            signingConfig = signingConfigs.release
        }

        release {
""",
    """            matchingFallbacks = ['release', 'staging', 'debug']
            signingConfig = signingConfigs.release
            buildConfigField 'boolean', 'OFFICIAL_BUILD', 'true'
            buildConfigField 'String', 'TRUSTED_SIGNING_CERT_SHA256', '\"' + trustedSigningCertificates + '\"'
        }

        release {
""",
    "staging integrity fields",
)
text = replace_once(
    text,
    """            matchingFallbacks = ['release', 'staging', 'debug']
            signingConfig = signingConfigs.release
        }
    }
""",
    """            matchingFallbacks = ['release', 'staging', 'debug']
            signingConfig = signingConfigs.release
            buildConfigField 'boolean', 'OFFICIAL_BUILD', 'true'
            buildConfigField 'String', 'TRUSTED_SIGNING_CERT_SHA256', '\"' + trustedSigningCertificates + '\"'
        }
    }
""",
    "release integrity fields",
)
text = replace_once(text, "String gramName = 'NagramXF'", "String gramName = 'AuthorGram'", "APK name")
write(path, text)

# Remove insecure public fallback signing credentials.
path = "gradle.properties"
text = read(path)
for line in (
    "RELEASE_KEY_PASSWORD=android\n",
    "RELEASE_KEY_ALIAS=androidkey\n",
    "RELEASE_STORE_PASSWORD=android\n",
):
    if line not in text:
        raise RuntimeError(f"Missing expected insecure property: {line.strip()}")
    text = text.replace(line, "", 1)
write(path, text)

# Prevent Android backup/restore from creating undecryptable key state, and do not
# allow other apps to capture AuthorGram audio playback.
path = "TMessagesProj/src/main/AndroidManifest.xml"
text = read(path)
text = replace_once(
    text,
    """        android:allowBackup="true"
        android:restoreAnyVersion="true"
        android:backupAgent=".BackupAgent"
""",
    """        android:allowBackup="false"
""",
    "manifest backup policy",
)
text = replace_once(text, 'android:hasFragileUserData="true"', 'android:hasFragileUserData="false"', "fragile data")
text = replace_once(text, 'android:allowAudioPlaybackCapture="true"', 'android:allowAudioPlaybackCapture="false"', "audio capture")
write(path, text)

# Atomic, fail-closed custom-key persistence.
path = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatKeyStore.java"
text = read(path)
text = replace_once(
    text,
    "import org.telegram.messenger.ApplicationLoader;\n",
    "import org.telegram.messenger.ApplicationLoader;\nimport org.telegram.messenger.FileLog;\n",
    "key store imports",
)
text = replace_once(
    text,
    """        SharedPreferences.Editor editor = preferences().edit()
                .remove(currentName(account, dialogId));
        for (int index = 0; index < HISTORY_LIMIT; index++) {
            editor.remove(historyName(account, dialogId, index));
        }
        editor.apply();
""",
    """        SharedPreferences.Editor editor = preferences().edit()
                .remove(currentName(account, dialogId));
        for (int index = 0; index < HISTORY_LIMIT; index++) {
            editor.remove(historyName(account, dialogId, index));
        }
        if (!editor.commit()) {
            FileLog.e("AuthorGram: unable to remove custom chat keys");
        }
""",
    "atomic key removal",
)
text = replace_once(
    text,
    """        } catch (GeneralSecurityException exception) {
            return null;
        }
""",
    """        } catch (GeneralSecurityException exception) {
            FileLog.e("AuthorGram: unable to unwrap the current custom chat key", exception);
            return null;
        }
""",
    "key unwrap logging",
)
text = replace_once(
    text,
    """        editor.putString(currentName, wrapped).apply();
""",
    """        editor.putString(currentName, wrapped);
        if (!editor.commit()) {
            throw new GeneralSecurityException("Unable to persist AuthorGram chat key");
        }
""",
    "atomic key write",
)
write(path, text)

# If a custom key is configured but Android Keystore cannot unwrap it, never
# silently downgrade outgoing encryption to the shared system key.
path = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatCrypto.java"
text = read(path)
text = replace_once(
    text,
    """    private static final int TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
""",
    """    private static final int TAG_BYTES = 16;
    private static final int MAX_ENCODED_PAYLOAD_CHARS = 65_536;
    private static final SecureRandom RANDOM = new SecureRandom();
""",
    "custom payload limit",
)
text = replace_once(
    text,
    """        byte[] customKey = AuthorGramChatKeyStore.getCurrentKey(account, dialogId);
        if (customKey == null) {
            return AuthorGramCrypto.encryptText(plaintext);
        }
""",
    """        boolean customKeyConfigured = AuthorGramChatKeyStore.hasCustomKey(account, dialogId);
        byte[] customKey = AuthorGramChatKeyStore.getCurrentKey(account, dialogId);
        if (customKey == null) {
            return customKeyConfigured ? null : AuthorGramCrypto.encryptText(plaintext);
        }
""",
    "fail-closed custom key",
)
text = replace_once(
    text,
    """            String encoded = payload.substring(AuthorGramCrypto.MARKER.length());
            if (encoded.isEmpty()) {
                return null;
            }
""",
    """            String encoded = payload.substring(AuthorGramCrypto.MARKER.length());
            if (encoded.isEmpty() || encoded.length() > MAX_ENCODED_PAYLOAD_CHARS) {
                return null;
            }
""",
    "custom payload bounds",
)
write(path, text)

# Protect the shared system key against simple repackaging/resigning and reject
# oversized malicious payloads before Base64 allocation.
path = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCrypto.java"
text = read(path)
text = replace_once(
    text,
    """    private static final int GCM_TAG_LENGTH_BYTES = 16;
""",
    """    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int MAX_ENCODED_PAYLOAD_CHARS = 65_536;
""",
    "system payload limit",
)
text = replace_once(
    text,
    """        if (isAuthorGramPayload(plaintext)) {
            return plaintext;
        }

        try {
""",
    """        if (isAuthorGramPayload(plaintext)) {
            return plaintext;
        }
        if (!AuthorGramBuildIntegrity.canUseSystemKey()) {
            return null;
        }

        try {
""",
    "system encryption integrity",
)
text = replace_once(
    text,
    """        if (!isAuthorGramPayload(payload)) {
            return null;
        }

        try {
""",
    """        if (!isAuthorGramPayload(payload)
                || !AuthorGramBuildIntegrity.canUseSystemKey()) {
            return null;
        }

        try {
""",
    "system decryption integrity",
)
text = replace_once(
    text,
    """            if (encoded.isEmpty()) {
                return null;
            }
""",
    """            if (encoded.isEmpty()
                    || encoded.length() > MAX_ENCODED_PAYLOAD_CHARS) {
                return null;
            }
""",
    "system payload bounds",
)
write(path, text)

# Mark copied keys as sensitive and reject obscured/tapjacked import touches.
path = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramKeyDialog.java"
text = read(path)
text = replace_once(
    text,
    """import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
""",
    """import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.PersistableBundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
""",
    "key dialog imports",
)
text = replace_once(
    text,
    """        input.setHint(LocaleController.getString(R.string.AuthorGramKeyInputHint));
""",
    """        input.setHint(LocaleController.getString(R.string.AuthorGramKeyInputHint));
        input.setFilterTouchesWhenObscured(true);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
""",
    "secure key input",
)
text = replace_once(
    text,
    """                        clipboard.setPrimaryClip(ClipData.newPlainText("AuthorGram key", key));
                        toast(activity, R.string.TextCopied);
""",
    """                        ClipData clip = ClipData.newPlainText("AuthorGram key", key);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PersistableBundle extras = new PersistableBundle();
                            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                            clip.getDescription().setExtras(extras);
                        }
                        clipboard.setPrimaryClip(clip);
                        toast(activity, R.string.TextCopied);
""",
    "sensitive clipboard",
)
write(path, text)

# Add runtime signing-certificate verification. Debug builds remain unrestricted;
# release builds use a certificate digest derived from the release keystore or a
# comma-separated AUTHORGRAM_SIGNING_CERT_SHA256 override for Play App Signing.
integrity_path = Path("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramBuildIntegrity.java")
if integrity_path.exists():
    raise RuntimeError("AuthorGramBuildIntegrity.java already exists")
integrity_path.write_text("""package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Detects APK resigning before the shared AuthorGram system key is used. */
public final class AuthorGramBuildIntegrity {
    private static volatile Boolean trusted;

    private AuthorGramBuildIntegrity() {
    }

    public static boolean canUseSystemKey() {
        if (!BuildConfig.OFFICIAL_BUILD) {
            return true;
        }
        Boolean cached = trusted;
        if (cached != null) {
            return cached;
        }
        synchronized (AuthorGramBuildIntegrity.class) {
            if (trusted == null) {
                trusted = verifyInstalledSignature();
                if (!trusted) {
                    FileLog.e("AuthorGram: APK signature verification failed; system-key crypto disabled");
                }
            }
            return trusted;
        }
    }

    private static boolean verifyInstalledSignature() {
        String configured = BuildConfig.TRUSTED_SIGNING_CERT_SHA256;
        if (configured == null || configured.trim().isEmpty()) {
            return false;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return false;
        }
        List<byte[]> expected = new ArrayList<>();
        for (String item : configured.split(",")) {
            byte[] digest = decodeDigest(item);
            if (digest != null) {
                expected.add(digest);
            }
        }
        if (expected.isEmpty()) {
            return false;
        }
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = manager.getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES
                );
                SigningInfo signingInfo = info.signingInfo;
                if (signingInfo == null) {
                    return false;
                }
                signatures = signingInfo.hasMultipleSigners()
                        ? signingInfo.getApkContentsSigners()
                        : signingInfo.getSigningCertificateHistory();
            } else {
                //noinspection deprecation
                info = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
                //noinspection deprecation
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) {
                return false;
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                byte[] actual = sha256.digest(signature.toByteArray());
                for (byte[] allowed : expected) {
                    if (MessageDigest.isEqual(actual, allowed)) {
                        return true;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException exception) {
            FileLog.e("AuthorGram: unable to verify APK signature", exception);
        }
        return false;
    }

    private static byte[] decodeDigest(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.ROOT);
        if (normalized.length() != 64) {
            return null;
        }
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(normalized.charAt(index * 2), 16);
            int low = Character.digit(normalized.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }
}
""", encoding="utf-8")

# CI: main produces isolated debug builds; dev/play-market produce signed releases.
write(".github/workflows/pr.yml", """name: Debug validation

on:
  push:
    branches: [main]
    paths-ignore: ['**.md']
  pull_request:
    branches: [main, dev, play-market]
    types: [opened, reopened, synchronize]
    paths-ignore: ['**.md']

permissions:
  contents: read

concurrency:
  group: debug-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Debug Build
    runs-on: ubuntu-latest
    env:
      NATIVE_TARGET: arm64-v8a
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - uses: android-actions/setup-android@v3
        with:
          packages: 'build-tools;36.0.0 ndk;27.2.12479018 platforms;android-36'
      - name: Configure SDK
        run: echo "sdk.dir=${ANDROID_HOME}" > local.properties
      - name: Security invariants
        run: |
          set -euo pipefail
          ! grep -qE '^RELEASE_(KEY|STORE)_PASSWORD=' gradle.properties
          ! grep -A8 'debug {' TMessagesProj/build.gradle | grep -q 'signingConfig = signingConfigs.release'
          grep -q 'applicationIdSuffix' TMessagesProj/build.gradle
          grep -q 'android:allowBackup="false"' TMessagesProj/src/main/AndroidManifest.xml
      - name: Build debug APK
        id: build
        continue-on-error: true
        env:
          COMMIT_ID: ${{ github.sha }}
          BUILD_TIMESTAMP: ${{ github.run_id }}
        run: |
          set -o pipefail
          ./gradlew --no-daemon TMessagesProj:assembleDebug 2>&1 | tee gradle-debug.log
      - name: Collect debug artifact
        if: steps.build.outcome == 'success'
        run: |
          set -euo pipefail
          mkdir -p artifacts
          APK=$(find TMessagesProj/build/outputs/apk -type f -name '*arm64-v8a*.apk' | head -n 1)
          test -n "$APK" -a -f "$APK"
          cp "$APK" artifacts/
          (cd artifacts && sha256sum *.apk > SHA256SUMS)
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: AuthorGram-${{ github.ref_name }}-Debug
          path: |
            artifacts/
            gradle-debug.log
          retention-days: 7
      - name: Fail failed build
        if: steps.build.outcome != 'success'
        run: exit 1
""")

write(".github/workflows/release.yml", """name: AuthorGram Release

on:
  push:
    branches: [dev, play-market]
    paths-ignore: ['**.md']
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

jobs:
  build:
    name: Release Build
    if: github.ref_name == 'dev' || github.ref_name == 'play-market'
    runs-on: ubuntu-latest
    env:
      NATIVE_TARGET: arm64-v8a
      LOCAL_PROPERTIES: ${{ secrets.LOCAL_PROPERTIES }}
      AUTHORGRAM_SIGNING_CERT_SHA256: ${{ secrets.AUTHORGRAM_SIGNING_CERT_SHA256 }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
        with:
          cache-disabled: true
      - uses: android-actions/setup-android@v3
        with:
          packages: 'build-tools;36.0.0 ndk;27.2.12479018 platforms;android-36'
      - name: Configure SDK and validate signing input
        run: |
          set -euo pipefail
          echo "sdk.dir=${ANDROID_HOME}" > local.properties
          test -n "$LOCAL_PROPERTIES"
          python3 - <<'PY'
          import base64, os
          raw = os.environ['LOCAL_PROPERTIES']
          data = base64.b64decode(raw, validate=True).decode('utf-8')
          props = {}
          for line in data.splitlines():
              line = line.strip()
              if line and not line.startswith('#') and '=' in line:
                  key, value = line.split('=', 1)
                  props[key.strip()] = value.strip()
          missing = [key for key in ('KEYSTORE_PASS', 'ALIAS_NAME', 'ALIAS_PASS') if not props.get(key)]
          if missing:
              raise SystemExit('Missing release signing properties: ' + ', '.join(missing))
          PY
      - name: Security invariants
        run: |
          set -euo pipefail
          ! grep -qE '^RELEASE_(KEY|STORE)_PASSWORD=' gradle.properties
          ! grep -A8 'debug {' TMessagesProj/build.gradle | grep -q 'signingConfig = signingConfigs.release'
          grep -q 'android:allowBackup="false"' TMessagesProj/src/main/AndroidManifest.xml
          test -f TMessagesProj/release.keystore
      - name: Build signed release APK
        id: build
        continue-on-error: true
        env:
          COMMIT_ID: ${{ github.sha }}
          BUILD_TIMESTAMP: ${{ github.run_id }}
        run: |
          set -o pipefail
          ./gradlew --no-daemon TMessagesProj:assembleRelease 2>&1 | tee gradle-release.log
      - name: Collect release artifact
        if: steps.build.outcome == 'success'
        run: |
          set -euo pipefail
          mkdir -p artifacts
          APK=$(find TMessagesProj/build/outputs/apk -type f -name '*arm64-v8a*.apk' | head -n 1)
          test -n "$APK" -a -f "$APK"
          cp "$APK" artifacts/
          (cd artifacts && sha256sum *.apk > SHA256SUMS)
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: ${{ github.ref_name }}-Release
          path: |
            artifacts/
            gradle-release.log
          retention-days: 30
      - name: Fail failed build
        if: steps.build.outcome != 'success'
        run: exit 1
""")

staging = Path(".github/workflows/staging.yml")
if not staging.exists():
    raise RuntimeError("staging.yml not found")
staging.unlink()

# Final static assertions.
assert "signingConfig = signingConfigs.release\n        }\n\n        staging" not in read("TMessagesProj/build.gradle")
assert "RELEASE_KEY_PASSWORD" not in read("gradle.properties")
assert 'android:allowBackup="false"' in read("TMessagesProj/src/main/AndroidManifest.xml")
assert Path("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramBuildIntegrity.java").exists()
print("AuthorGram security hardening applied")
