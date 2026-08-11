#!/usr/bin/env python3
"""Verify that canonical dev and Play differ only by controlled publication changes."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_EXACT = {
    "gradle.properties",
}
PLAY_SANITIZED_EXACT = {
    "TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java",
    "TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java",
    "TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/AyuGhostUtils.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuSavePreferences.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/messages/AyuMessagesController.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/utils/LastSeenHelper.java",
    "TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPremiumStatusHelper.kt",
    "TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPeerColorHelper.kt",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatState.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCrypto.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramChatCrypto.java",
    "TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java",
    "TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java",
}
ALLOWED_EXACT = REQUIRED_EXACT | PLAY_SANITIZED_EXACT
DYNAMIC_ARTIFACT_LINE = (
    "String gramName = APP_PACKAGE == 'toss.authorgram.apk' "
    "? 'AuthorGram-Play' : 'AuthorGram-Main'"
)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git command failed")
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--main-ref", default="origin/dev")
    parser.add_argument("--play-ref", default="origin/play-market")
    args = parser.parse_args()

    changed = {
        line.strip()
        for line in git("diff", "--name-only", args.main_ref, args.play_ref).splitlines()
        if line.strip()
    }
    application_changes = {path for path in changed if not path.startswith(".github/")}
    unexpected = sorted(application_changes - ALLOWED_EXACT)
    missing_required = sorted(REQUIRED_EXACT - application_changes)

    failures: list[str] = []
    if unexpected:
        failures.append(
            "Unexpected application-source differences: " + ", ".join(unexpected)
        )
    if missing_required:
        failures.append(
            "Expected controlled identity differences are missing: "
            + ", ".join(missing_required)
        )

    main_properties = git("show", f"{args.main_ref}:gradle.properties")
    play_properties = git("show", f"{args.play_ref}:gradle.properties")
    if "APP_PACKAGE=fork.risin42.nagramx" not in main_properties:
        failures.append("Canonical dev package must be fork.risin42.nagramx")
    if "APP_PACKAGE=toss.authorgram.apk" not in play_properties:
        failures.append("Play package must be toss.authorgram.apk")

    normalized_main = main_properties.replace(
        "APP_PACKAGE=fork.risin42.nagramx", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
    )
    normalized_play = play_properties.replace(
        "APP_PACKAGE=toss.authorgram.apk", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
    )
    if normalized_main != normalized_play:
        failures.append("gradle.properties differs by more than APP_PACKAGE")

    main_build = git("show", f"{args.main_ref}:TMessagesProj/build.gradle")
    play_build = git("show", f"{args.play_ref}:TMessagesProj/build.gradle")
    if main_build != play_build:
        failures.append("TMessagesProj/build.gradle must remain identical in dev and Play")
    if DYNAMIC_ARTIFACT_LINE not in main_build:
        failures.append(
            "Common Gradle source does not select Main/Play artifact names from APP_PACKAGE"
        )

    if failures:
        print("AuthorGram dev/Play parity failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    actual_sanitized = sorted(application_changes & PLAY_SANITIZED_EXACT)
    print(
        "AuthorGram dev/Play parity passed: only package and allowlisted "
        f"Play runtime stripping differ ({len(actual_sanitized)} sanitized source files)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
