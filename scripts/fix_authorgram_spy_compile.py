#!/usr/bin/env python3
"""Repair and validate the private Main-only Spy settings page before release."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPY = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java"
SETTINGS = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java"
ROUTER = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java"


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise RuntimeError(f"Missing {label}: {needle}")


def main() -> None:
    spy = SPY.read_text(encoding="utf-8")

    # This Solar-only identifier is not present in Telegram 12.9.0 resources and
    # caused compileReleaseJavaWithJavac to fail. msg_folders is an existing,
    # theme-compatible Telegram drawable and accurately represents filters.
    spy = spy.replace(
        "R.drawable.menu_tag_filter_solar",
        "R.drawable.msg_folders",
    )
    SPY.write_text(spy, encoding="utf-8", newline="")

    spy = SPY.read_text(encoding="utf-8")
    for needle, label in (
        ("NekoConfig.localPremium", "Local Premium toggle"),
        ("NekoConfig.hideSponsoredMessage", "sponsored-message toggle"),
        ("NekoConfig.hideProxySponsorChannel", "proxy sponsor toggle"),
        ("NaConfig.INSTANCE.getTranslucentDeletedMessages()", "deleted-message translucency toggle"),
        ("NaConfig.INSTANCE.getDeletedIconStyle()", "deleted-message marker selector"),
        ("new GhostModeActivity()", "Ghost Mode entry"),
        ("new AGFiltersSettingsActivity()", "Regex Filters entry"),
        ("new AGPrivacySettingsActivity()", "privacy/spy entry"),
    ):
        require(spy, needle, label)

    if "menu_tag_filter_solar" in spy:
        raise RuntimeError("Invalid Telegram 12.9.0 drawable remains in Spy settings")

    settings = SETTINGS.read_text(encoding="utf-8")
    for needle, label in (
        ("private static final String PLAY_PACKAGE = \"toss.authorgram.apk\";", "Play package guard"),
        ("if (isPrivateMainBuild())", "Main-only Spy row guard"),
        ("presentFragment(new AGSpySettingsActivity());", "complete Spy navigation"),
        ("getString(R.string.AuthorGramSpy)", "Spy label"),
    ):
        require(settings, needle, label)

    router = ROUTER.read_text(encoding="utf-8")
    require(router, "new AGSpySettingsActivity()", "Spy deep-link/search routing")
    require(router, "if (!isPrivateMainBuild())", "Play exclusion in settings router")

    print("Private Main-only Spy page compile repair and content validation passed.")


if __name__ == "__main__":
    main()
