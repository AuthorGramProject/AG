#!/usr/bin/env python3
"""Fail fast when AuthorGram branding, crypto, reply, or release invariants regress."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    file_path = ROOT / path
    if not file_path.is_file():
        raise AssertionError(f"Missing required file: {path}")
    return file_path.read_text(encoding="utf-8")


def resources(path: str) -> dict[str, str]:
    root = ET.fromstring(read(path))
    return {
        item.attrib["name"]: "".join(item.itertext()).strip()
        for item in root.findall("string")
        if "name" in item.attrib
    }


def require(condition: bool, message: str, failures: list[str]) -> None:
    if not condition:
        failures.append(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-package", required=True)
    args = parser.parse_args()
    failures: list[str] = []

    overlays = [
        "TMessagesProj/src/debug/res/values/authorgram_brand.xml",
        "TMessagesProj/src/staging/res/values/authorgram_brand.xml",
        "TMessagesProj/src/release/res/values/authorgram_brand.xml",
    ]
    required_brand_values = {
        "AppName": "AuthorGram",
        "AppNameBeta": "AuthorGram",
        "TOSS": "AuthorGram",
        "AGAboutInfo": "AuthorGram",
        "NekoSettings": "AuthorGram Settings",
    }
    for overlay in overlays:
        values = resources(overlay)
        for name, expected in required_brand_values.items():
            require(
                values.get(name) == expected,
                f"{overlay}: {name} must equal {expected!r}",
                failures,
            )

    for locale_file in (
        "TMessagesProj/src/main/res/values-uk/strings.xml",
        "TMessagesProj/src/main/res/values-de/strings.xml",
    ):
        values = resources(locale_file)
        require(values.get("AppName") == "AuthorGram", f"{locale_file}: wrong AppName", failures)
        require(values.get("AppNameBeta") == "AuthorGram", f"{locale_file}: wrong AppNameBeta", failures)
        for name, value in values.items():
            if name == "AGCreditsText":
                continue
            require(
                not re.search(r"(?i)\b(?:TOSS|Nagram\s*X|Ngram\s*X|Ngram|NASAtings)\b", value),
                f"{locale_file}: legacy visible brand in {name}: {value!r}",
                failures,
            )

    key_store = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatKeyStore.java"
    )
    kdf = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPassphraseKdf.java"
    )
    key_dialog = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramKeyDialog.java"
    )
    interceptor = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"
    )

    for obsolete in ("generateAndStore", "importAndStore", "exportCurrentKey", "decodeHex(", "encodeHex("):
        require(obsolete not in key_store, f"Obsolete raw-key API remains: {obsolete}", failures)
    for obsolete in ("ClipboardManager", "ClipData", "AuthorGramGenerateKey", "AuthorGramExportKey"):
        require(obsolete not in key_dialog, f"Obsolete key UI remains: {obsolete}", failures)

    require('DOMAIN = "AuthorGram-Chat-KDF-v1"' in kdf, "KDF domain changed", failures)
    require("ITERATIONS = 600_000" in kdf, "KDF iteration count changed", failures)
    require("KEY_BYTES = 32" in kdf, "KDF output is not 256 bits", failures)
    require("Normalizer.Form.NFKC" in kdf, "NFKC normalization missing", failures)
    require("HmacSHA256" in kdf, "PBKDF2 HMAC-SHA256 missing", failures)
    require("deriveAndStore" in key_store, "Passphrase storage entry point missing", failures)
    require("useSystemKey" in key_store, "System-key fallback missing", failures)
    require("putAtHistoryFront" in key_store, "Historical decryption-key retention missing", failures)

    sanitize_index = interceptor.find("sanitizeReplyToEncryptedSource(account, request, messageObject)")
    toggle_index = interceptor.find("if (!AuthorGramChatState.isEnabled(account, dialogId))")
    require(sanitize_index >= 0, "Encrypted-source reply sanitizer is missing", failures)
    require(toggle_index >= 0, "Outgoing encryption toggle check is missing", failures)
    require(
        0 <= sanitize_index < toggle_index,
        "Reply quotes must be sanitized before checking the outgoing encryption toggle",
        failures,
    )
    require("reply_to_msg_id" in interceptor, "Normal reply relationship is not documented/preserved", failures)
    require("quote_text = null" in interceptor, "Plaintext quote is not removed", failures)

    def icon_paths(path: str) -> list[str]:
        root = ET.fromstring(read(path))
        android = "{http://schemas.android.com/apk/res/android}"
        return [item.attrib.get(android + "pathData", "") for item in root.findall("path")]

    require(
        icon_paths("TMessagesProj/src/main/res/drawable/ag_settings.xml")
        == icon_paths("TMessagesProj/src/main/res/drawable/authorgram_settings_a.xml"),
        "Legacy drawer and main settings do not use identical AuthorGram artwork",
        failures,
    )

    gradle_properties = read("gradle.properties")
    package_line = f"APP_PACKAGE={args.expected_package}"
    require(package_line in gradle_properties, f"Expected package missing: {package_line}", failures)

    build_gradle = read("TMessagesProj/build.gradle")
    defaults = read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java")
    config_item = read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java")
    is_play = args.expected_package == "toss.authorgram.apk"

    require("String gramName = 'AuthorGram" in build_gradle, "Artifact name is not AuthorGram", failures)
    require(
        "def telegramAdBlockingEnabled = APP_PACKAGE != 'toss.authorgram.apk'" in build_gradle,
        "Package-based Telegram ad-blocking build flag is missing",
        failures,
    )
    require(
        "'TELEGRAM_AD_BLOCKING_ENABLED', telegramAdBlockingEnabled.toString()" in build_gradle,
        "Telegram ad-blocking BuildConfig flag is missing",
        failures,
    )
    for key in ("hideSponsoredMessage", "HideProxySponsorChannel"):
        require(key in defaults, f"Missing controlled Telegram ad preference: {key}", failures)
        require(key in config_item, f"ConfigItem does not guard Telegram ad preference: {key}", failures)
    require(
        "!isTelegramAdBlockingUnavailable() && (boolean) value" in config_item,
        "Play build can still read Telegram ad blocking as enabled",
        failures,
    )
    require(
        "value = isTelegramAdBlockingUnavailable() ? false : v" in config_item,
        "Play build can still enable Telegram ad blocking",
        failures,
    )
    release_keystore = ROOT / "TMessagesProj/release.keystore"
    if is_play:
        require(not release_keystore.exists(), "Play release keystore must not be tracked", failures)
    else:
        require(release_keystore.is_file(), "Main release keystore is missing", failures)
    release_match = re.search(r"\n\s*release\s*\{(?P<body>.*?)\n\s*\}\n", build_gradle, re.S)
    require(release_match is not None, "Release build type missing", failures)
    if release_match:
        body = release_match.group("body")
        require("debuggable = false" in body, "Release build is debuggable", failures)
        require("signingConfig = signingConfigs.release" in body, "Release signing config missing", failures)
        require("minifyEnabled = true" in body, "Release minification disabled", failures)

    manifest = read("TMessagesProj/src/main/AndroidManifest.xml")
    require('android:allowBackup="false"' in manifest, "Android backup must be disabled", failures)
    require(
        'android:allowAudioPlaybackCapture="false"' in manifest,
        "Audio playback capture must be disabled",
        failures,
    )

    workflow = read(".github/workflows/release.yml")
    if "maintenance lock" not in workflow.lower():
        require("assembleRelease" in workflow, "Release workflow does not build release APK", failures)
        require("assembleDebug" not in workflow, "Release workflow must never publish a debug APK", failures)
        require("apksigner" in workflow, "Release workflow does not verify APK signature", failures)
        require("output-metadata.json" in workflow, "Release workflow does not resolve exact output metadata", failures)
        require("Generate fresh Play upload key" in workflow, "Play signing key generation is missing", failures)
        require("ARTIFACT_DIR: ${{ runner.temp }}" not in workflow, "runner.temp is invalid in job-level env", failures)

    if failures:
        print("AuthorGram guard failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print(f"AuthorGram guard passed for {args.expected_package}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
