#!/usr/bin/env python3
"""Fail fast when AuthorGram branding, crypto, reply, or release invariants regress."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from finalize_authorgram_source import MAIN_PACKAGE, PLAY_PACKAGE, validate

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

    if args.expected_package not in {MAIN_PACKAGE, PLAY_PACKAGE}:
        failures.append(
            f"Unsupported package {args.expected_package!r}; expected {MAIN_PACKAGE!r} or {PLAY_PACKAGE!r}"
        )
    else:
        role = "play" if args.expected_package == PLAY_PACKAGE else "main"
        try:
            validate(role, args.expected_package)
        except RuntimeError as exc:
            failures.extend(str(exc).splitlines())

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
    require("quote_text = null" in interceptor, "Plaintext quote text is not removed", failures)
    require("quote_entities.clear()" in interceptor, "Plaintext quote entities are not removed", failures)
    require("quote_offset = 0" in interceptor, "Plaintext quote offset is not reset", failures)
    require("sanitizeLocalReplyHeader" in interceptor, "Local quoted preview is not sanitized", failures)

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

    manifest = read("TMessagesProj/src/main/AndroidManifest.xml")
    require('android:allowBackup="false"' in manifest, "Android backup must be disabled", failures)
    require(
        'android:allowAudioPlaybackCapture="false"' in manifest,
        "Audio playback capture must be disabled",
        failures,
    )

    workflow = read(".github/workflows/release.yml")
    release_script = read("scripts/final_release_12_9_1.sh")
    require(
        "scripts/final_release_12_9_1.sh" in workflow,
        "Release workflow must execute the auditable plain release script",
        failures,
    )
    require("assembleRelease" in release_script, "Release script does not build release APKs", failures)
    require(
        "bundleRelease" not in release_script,
        "APK-only release script must not build an Android App Bundle",
        failures,
    )
    require("assembleDebug" not in release_script, "Release script must never build a debug APK", failures)
    require("apksigner" in release_script, "Release script does not verify APK signatures", failures)
    require(
        "output-metadata.json" in release_script,
        "Release script does not resolve exact APK output metadata",
        failures,
    )
    require(
        "authorgram_guard.py" in release_script,
        "Release script does not execute the source guard before synchronization/build",
        failures,
    )
    require(
        "deleteWorkflowRun" in workflow
        and "'failure'" in workflow
        and "'cancelled'" in workflow,
        "Release workflow does not remove historical failed and cancelled runs",
        failures,
    )
    require(
        "Expected exactly two APKs" in workflow
        and "AAB files are forbidden" in workflow,
        "Release workflow does not enforce exactly two APK artifacts and zero AAB files",
        failures,
    )

    if failures:
        print("AuthorGram guard failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print(f"AuthorGram guard passed for {args.expected_package}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
