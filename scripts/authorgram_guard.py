#!/usr/bin/env python3
"""Fail fast when AuthorGram branding, crypto, Play policy, or release invariants regress."""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import cleanup_authorgram_actions
import fix_authorgram_spy_compile
import patch_authorgram_build_key
import patch_authorgram_play_policy
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
    cleanup_authorgram_actions.main()
    fix_authorgram_spy_compile.main()
    # patch_authorgram_build_key applies its idempotent patch during import.
    patch_authorgram_play_policy.main()

    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-package", required=True)
    args = parser.parse_args()
    failures: list[str] = []

    if args.expected_package not in {MAIN_PACKAGE, PLAY_PACKAGE}:
        failures.append(
            f"Unsupported package {args.expected_package!r}; expected "
            f"{MAIN_PACKAGE!r} or {PLAY_PACKAGE!r}"
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
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramChatKeyStore.java"
    )
    key_protector = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramKeyProtector.java"
    )
    kdf = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramPassphraseKdf.java"
    )
    key_dialog = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramKeyDialog.java"
    )
    chat_crypto = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramChatCrypto.java"
    )
    system_crypto = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramCrypto.java"
    )
    play_policy = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramPlayPolicy.java"
    )
    interceptor = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
        "AuthorGramCryptoInterceptor.java"
    )
    build_gradle = read("TMessagesProj/build.gradle")
    config_item = read(
        "TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java"
    )
    settings_router = read(
        "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java"
    )
    messages_controller = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
    )

    for obsolete in (
        "generateAndStore",
        "importAndStore",
        "exportCurrentKey",
        "decodeHex(",
        "encodeHex(",
    ):
        require(
            obsolete not in key_store,
            f"Obsolete raw-key API remains: {obsolete}",
            failures,
        )
    for obsolete in (
        "ClipboardManager",
        "ClipData",
        "AuthorGramGenerateKey",
        "AuthorGramExportKey",
    ):
        require(
            obsolete not in key_dialog,
            f"Obsolete key UI remains: {obsolete}",
            failures,
        )

    require('DOMAIN = "AuthorGram-Chat-KDF-v1"' in kdf, "KDF domain changed", failures)
    require("ITERATIONS = 600_000" in kdf, "KDF iteration count changed", failures)
    require("KEY_BYTES = 32" in kdf, "KDF output is not 256 bits", failures)
    require("Normalizer.Form.NFKC" in kdf, "NFKC normalization missing", failures)
    require("HmacSHA256" in kdf, "PBKDF2 HMAC-SHA256 missing", failures)
    require("deriveAndStore" in key_store, "Passphrase storage entry point missing", failures)
    require("stableKdfScope" in key_store, "Per-chat KDF scope missing", failures)
    require("currentName(account, dialogId)" in key_store, "Per-dialog key slot missing", failures)
    require("putAtHistoryFront" in key_store, "Historical key retention missing", failures)
    require('ALIAS_V2 = "authorgram.chat.keys.master.v2"' in key_protector,
            "Repaired Android Keystore alias missing", failures)
    require('PREFIX_V2 = "v2:"' in key_protector,
            "Versioned key wrapper missing", failures)
    require("getOrCreateHealthyMasterKey" in key_protector,
            "Broken Android Keystore recovery missing", failures)
    require("AuthorGramChatKeyStore.hasCustomKey(account, dialogId)" in key_dialog,
            "Word-key UI does not verify persistence", failures)
    require("AuthorGramChatState.setEnabled(account, dialogId, true)" in key_dialog,
            "Word-key UI does not enable encryption atomically", failures)
    require('PREFIX_SOFTWARE_V3 = "v3s:"' in key_protector,
            "Broken-keystore device-vault fallback missing", failures)
    require("getNoBackupFilesDir()" in key_protector,
            "Device-vault fallback is not stored outside backup", failures)

    require("BuildConfig.AUTHORGRAM_SYSTEM_KEY_HEX" in system_crypto,
            "System key is not package BuildConfig-driven", failures)
    require("private static final String KEY_HEX" not in system_crypto,
            "Hardcoded system key remains in Java source", failures)
    require(
        "def authorGramSystemKeyHex = APP_PACKAGE == 'toss.authorgram.apk' ? ''" in build_gradle,
        "Play package is not compiled with an empty system key",
        failures,
    )
    require("AUTHORGRAM_SYSTEM_KEY_HEX" in build_gradle,
            "Package-specific system-key BuildConfig field missing", failures)
    require("AuthorGramPlayPolicy.isPlayBuild()" in chat_crypto,
            "Play custom-key-only crypto branch missing", failures)
    require("AuthorGramCrypto.encryptText(plaintext)" in chat_crypto,
            "Main system-key compatibility fallback missing", failures)

    required_play_policy = (
        'values.put("hideSponsoredMessage", false)',
        'values.put("HideProxySponsorChannel", false)',
        'values.put("localPremium", false)',
        'values.put("EnableSaveDeletedMessages", false)',
        'values.put("EnableSaveEditsHistory", false)',
        'values.put("SaveLocalLastSeen", false)',
        'values.put("sendReadMessagePackets", true)',
        'values.put("sendReadStoriesPackets", true)',
        'values.put("sendOnlinePackets", true)',
        'values.put("ignoreContentRestrictions", false)',
        "OWNER_DIALOG_ID = 6316376597L",
        "canEnableEncryption",
        "canDelete",
        "applyStartupPolicy",
    )
    for item in required_play_policy:
        require(item in play_policy, f"Play policy invariant missing: {item}", failures)

    require(config_item.count("AuthorGramPlayPolicy.sanitizeConfigValue") >= 8,
            "Play settings are not centrally write-protected", failures)
    require("AuthorGramPlayPolicy.isPlayBuild()" in settings_router,
            "Play settings router restrictions missing", failures)
    require("blocked message deletion in the author dialog" in messages_controller,
            "Author-dialog message deletion guard missing", failures)
    require("blocked chat/history deletion in the author dialog" in messages_controller,
            "Author-dialog chat deletion guard missing", failures)

    sanitize_index = interceptor.find(
        "sanitizeReplyToEncryptedSource(account, request, messageObject)"
    )
    toggle_index = interceptor.find(
        "if (!AuthorGramChatState.isEnabled(account, dialogId))"
    )
    require(sanitize_index >= 0, "Encrypted-source reply sanitizer missing", failures)
    require(toggle_index >= 0, "Outgoing encryption toggle check missing", failures)
    require(0 <= sanitize_index < toggle_index,
            "Reply quotes must be sanitized before the encryption toggle", failures)
    require("reply_to_msg_id" in interceptor,
            "Normal reply relationship is not documented/preserved", failures)
    require("quote_text = null" in interceptor,
            "Plaintext quote text is not removed", failures)
    require("quote_entities.clear()" in interceptor,
            "Plaintext quote entities are not removed", failures)
    require("quote_offset = 0" in interceptor,
            "Plaintext quote offset is not reset", failures)
    require("sanitizeLocalReplyHeader" in interceptor,
            "Local quoted preview is not sanitized", failures)

    def icon_paths(path: str) -> list[str]:
        root = ET.fromstring(read(path))
        android = "{http://schemas.android.com/apk/res/android}"
        return [item.attrib.get(android + "pathData", "") for item in root.findall("path")]

    require(
        icon_paths("TMessagesProj/src/main/res/drawable/ag_settings.xml")
        == icon_paths("TMessagesProj/src/main/res/drawable/authorgram_settings_a.xml"),
        "Drawer and main settings do not use identical AuthorGram artwork",
        failures,
    )

    manifest = read("TMessagesProj/src/main/AndroidManifest.xml")
    require('android:allowBackup="false"' in manifest,
            "Android backup must be disabled", failures)
    require('android:allowAudioPlaybackCapture="false"' in manifest,
            "Audio playback capture must be disabled", failures)

    workflow = read(".github/workflows/release.yml")
    cleanup_script = read("scripts/cleanup_authorgram_actions.py")
    release_script = read("scripts/final_release_12_9_1.sh")
    require("scripts/final_release_12_9_1.sh" in workflow,
            "Release workflow must execute the plain release script", failures)
    require("patch_authorgram_build_key.py" in workflow,
            "Workflow does not explicitly apply package-specific keys", failures)
    require("patch_authorgram_play_policy.py" in workflow,
            "Workflow does not explicitly apply Play policy", failures)
    require("assembleRelease" in release_script,
            "Release script does not build release APKs", failures)
    require("bundleRelease" not in release_script,
            "APK-only release script must not build an AAB", failures)
    require("assembleDebug" not in release_script,
            "Release script must never build a debug APK", failures)
    require("apksigner" in release_script,
            "Release script does not verify APK signatures", failures)
    require("output-metadata.json" in release_script,
            "Release script does not resolve exact APK output metadata", failures)
    require("authorgram_guard.py" in release_script,
            "Release script does not execute the source guard", failures)
    require(
        "cleanup_authorgram_actions.py" in workflow
        and "PRESERVED_TITLE" in cleanup_script
        and "kept_ids = {current_run_id}" in cleanup_script
        and "actions/runs/{run_id}" in cleanup_script,
        "Workflow does not preserve the requested run and purge all other runs",
        failures,
    )
    require(
        "Expected exactly two APKs" in workflow
        and "AAB files are forbidden" in workflow,
        "Workflow does not enforce two APKs and zero AABs",
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
