#!/usr/bin/env python3
"""Apply and validate source-level Play publication sanitization."""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAY_PACKAGE = "toss.authorgram.apk"
TEMPLATE_ROOT = ROOT / "scripts/play_stubs"

TARGETS = {
    "AGSpySettingsActivity.java": "TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java",
    "AGPrivacySettingsActivity.java": "TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java",
    "GhostModeActivity.java": "TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java",
    "AyuGhostUtils.java": "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuGhostUtils.java",
    "AyuSavePreferences.java": "TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuSavePreferences.java",
    "AyuMessagesController.java": "TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuMessagesController.java",
    "AyuData.java": "TMessagesProj/src/main/java/com/radolyn/ayugram/database/AyuData.java",
    "LastSeenHelper.java": "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/LastSeenHelper.java",
    "LocalPremiumStatusHelper.kt": "TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPremiumStatusHelper.kt",
    "LocalPeerColorHelper.kt": "TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPeerColorHelper.kt",
    "AuthorGramCryptoInterceptor.java": "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java",
    "AuthorGramChatState.java": "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatState.java",
    "AuthorGramCrypto.java": "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCrypto.java",
    "AuthorGramChatCrypto.java": "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatCrypto.java",
}


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"Missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def write(relative: str, content: str) -> bool:
    path = ROOT / relative
    previous = path.read_text(encoding="utf-8") if path.exists() else None
    if previous == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="")
    return True


def template(name: str) -> str:
    path = TEMPLATE_ROOT / name
    if not path.is_file():
        raise RuntimeError(f"Missing Play stub template: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def replace_once(relative: str, old: str, new: str) -> bool:
    content = read(relative)
    if new in content:
        return False
    count = content.count(old)
    if count != 1:
        raise RuntimeError(
            f"Play sanitizer marker changed in {relative}: expected 1 occurrence, got {count}"
        )
    return write(relative, content.replace(old, new, 1))


def apply_templates() -> int:
    changed = 0
    for name, relative in TARGETS.items():
        changed += int(write(relative, template(name)))
    return changed


def patch_user_config() -> bool:
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java"
    old = """    public boolean isPremiumOrLocal() {
        TLRPC.User user = currentUser;
        if (user == null) {
            return false;
        }
        return user.premium || NekoConfig.localPremium.Bool();
    }
"""
    new = """    public boolean isPremiumOrLocal() {
        TLRPC.User user = currentUser;
        return user != null && user.premium;
    }
"""
    changed = replace_once(relative, old, new)
    content = read(relative)
    if "NekoConfig." not in content and "import tw.nekomimi.nekogram.NekoConfig;\n" in content:
        changed = write(
            relative,
            content.replace("import tw.nekomimi.nekogram.NekoConfig;\n", "", 1),
        ) or changed
    return changed


def patch_config_read_lock() -> bool:
    relative = "TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java"
    old = """    public boolean Bool() {
        return (boolean) value;
    }
"""
    new = """    public boolean Bool() {
        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, value);
        return (boolean) value;
    }
"""
    return replace_once(relative, old, new)


def patch_experimental_premium_rows() -> bool:
    relative = "TMessagesProj/src/main/java/toss/authorgram/settings/AGExperimentalSettingsActivity.java"
    content = read(relative)
    changed = False
    for line in (
        "    private final AbstractConfigCell unlimitedPinnedDialogsRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.unlimitedPinnedDialogs, getString(R.string.UnlimitedPinnedDialogsAbout)));\n",
        "    private final AbstractConfigCell unlimitedFavedStickersRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.unlimitedFavedStickers, getString(R.string.UnlimitedFavoredStickersAbout)));\n",
    ):
        if line in content:
            content = content.replace(line, "", 1)
            changed = True
    if "NekoConfig.unlimitedPinnedDialogs" in content or "NekoConfig.unlimitedFavedStickers" in content:
        raise RuntimeError("Play Experimental settings still exposes unlimited Premium controls")
    return write(relative, content) if changed else False


def validate_templates() -> None:
    for name, relative in TARGETS.items():
        if read(relative) != template(name):
            raise RuntimeError(f"Play sanitized source drifted: {relative}")


def validate_direct_gates() -> None:
    user_config = read("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
    if "NekoConfig.localPremium.Bool()" in user_config:
        raise RuntimeError("Direct localPremium bypass remains in UserConfig")
    if "return user != null && user.premium;" not in user_config:
        raise RuntimeError("Play Premium gate is not server-authoritative")

    config_item = read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java")
    read_guard = "value = AuthorGramPlayPolicy.sanitizeConfigValue(key, value);"
    if read_guard not in config_item:
        raise RuntimeError("Play ConfigItem boolean reads are not policy-sanitized")

    experimental = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGExperimentalSettingsActivity.java")
    if "NekoConfig.unlimitedPinnedDialogs" in experimental or "NekoConfig.unlimitedFavedStickers" in experimental:
        raise RuntimeError("Unlimited Premium controls remain in Play Experimental settings")


def validate_runtime_absence() -> None:
    ghost = read(TARGETS["AyuGhostUtils.java"])
    for forbidden in (
        "sendFakeReadResponse",
        "Blocking read",
        "Blocking story",
        "Forcing offline",
        "updateStatus.offline = true",
    ):
        if forbidden in ghost:
            raise RuntimeError(f"Ghost runtime marker remains: {forbidden}")

    retention = read(TARGETS["AyuMessagesController.java"])
    for forbidden in ("AyuData", "DeletedMessageDao", "EditedMessageDao", ".insert(", "clearMediaPath"):
        if forbidden in retention:
            raise RuntimeError(f"Retention runtime marker remains: {forbidden}")

    ayu_data = read(TARGETS["AyuData.java"])
    for forbidden in ("androidx.room", "Room.databaseBuilder", "Migration", "ZipInputStream", "getWritableDatabase"):
        if forbidden in ayu_data:
            raise RuntimeError(f"Spy database runtime remains in Play: {forbidden}")

    save_prefs = read(TARGETS["AyuSavePreferences.java"])
    if "return false;" not in save_prefs or "NaConfig" in save_prefs:
        raise RuntimeError("Deleted-message policy stub is not inert")

    local_status = read(TARGETS["LocalPremiumStatusHelper.kt"])
    local_colors = read(TARGETS["LocalPeerColorHelper.kt"])
    if "NekoConfig" in local_status or "NekoConfig" in local_colors:
        raise RuntimeError("Local Premium implementation remains in Play helpers")

    chat_state = read(TARGETS["AuthorGramChatState.java"])
    if "SharedPreferences" in chat_state or "return false;" not in chat_state:
        raise RuntimeError("Outgoing AuthorGram encryption state remains in Play")

    interceptor = read(TARGETS["AuthorGramCryptoInterceptor.java"])
    if "encryptOutgoingText" in interceptor or "AuthorGramChatCrypto.encryptText" in interceptor:
        raise RuntimeError("Outgoing AuthorGram crypto hook remains in Play")
    if "AuthorGramChatCrypto.decryptTextOrNull" not in interceptor:
        raise RuntimeError("Incoming AuthorGram compatibility was accidentally removed")

    system_crypto = read(TARGETS["AuthorGramCrypto.java"])
    for forbidden in ("BuildConfig", "Cipher", "SecretKeySpec", "SecureRandom"):
        if forbidden in system_crypto:
            raise RuntimeError(f"System-key crypto implementation remains in Play: {forbidden}")

    chat_crypto = read(TARGETS["AuthorGramChatCrypto.java"])
    for forbidden in ("Cipher.ENCRYPT_MODE", "SecureRandom", "AuthorGramCrypto.encryptText"):
        if forbidden in chat_crypto:
            raise RuntimeError(f"Outgoing chat crypto implementation remains in Play: {forbidden}")
    if "Cipher.DECRYPT_MODE" not in chat_crypto:
        raise RuntimeError("Receive-only AuthorGram decryption compatibility is missing")


def validate_policy_consumers() -> None:
    source_roots = [ROOT / "TMessagesProj/src/main/java", ROOT / "TMessagesProj/src/main/kotlin"]
    allowed = {
        "hideSponsoredMessage": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java",
        },
        "HideProxySponsorChannel": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java",
        },
        "ignoreContentRestrictions": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java",
            "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java",
        },
        "NekoConfig.localPremium": {
            "TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/SettingsBackupHelper.java",
        },
        "NekoConfig.unlimitedPinnedDialogs": set(),
        "NekoConfig.unlimitedFavedStickers": set(),
    }

    failures: list[str] = []
    for root in source_roots:
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix not in {".java", ".kt"}:
                continue
            relative = path.relative_to(ROOT).as_posix()
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for token, allowed_paths in allowed.items():
                if token in content and relative not in allowed_paths:
                    failures.append(f"{token} has a Play runtime consumer in {relative}")
    if failures:
        raise RuntimeError("\n".join(failures))


def validate_stubs() -> None:
    validate_templates()
    validate_direct_gates()
    validate_runtime_absence()
    validate_policy_consumers()


def main() -> int:
    if f"APP_PACKAGE={PLAY_PACKAGE}" not in read("gradle.properties"):
        raise RuntimeError("Refusing to sanitize Main/dev: APP_PACKAGE is not the Play package")

    changed = apply_templates()
    changed += int(patch_user_config())
    changed += int(patch_config_read_lock())
    changed += int(patch_experimental_premium_rows())
    validate_stubs()
    print(f"AuthorGram Play source sanitizer passed; changed files: {changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
