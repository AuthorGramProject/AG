#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def run(*args: str) -> None:
    subprocess.run(args, cwd=ROOT, check=True)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one old marker, found {count}")
    return text.replace(old, new, 1)


props = (ROOT / "gradle.properties").read_text(encoding="utf-8")
if "APP_PACKAGE=toss.authorgram.apk" not in props:
    raise RuntimeError("Refusing to modify non-Play source")

# Bring in the already-audited sanitization templates only temporarily.
run(
    "git",
    "fetch",
    "origin",
    "backup/play-market-before-clean-rollback-20260915:refs/remotes/origin/play-cleanup-backup",
)
run(
    "git",
    "checkout",
    "origin/play-cleanup-backup",
    "--",
    "scripts/play_stubs",
    "scripts/strip_authorgram_play_runtime.py",
)
run("python3", "scripts/strip_authorgram_play_runtime.py")

shutil.rmtree(ROOT / "scripts/play_stubs", ignore_errors=True)
(ROOT / "scripts/strip_authorgram_play_runtime.py").unlink(missing_ok=True)

# Preserve only the current badge-vs-mute positioning correction from dev.
dialog_path = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"
text = dialog_path.read_text(encoding="utf-8")

text = replace_once(
    text,
    """        } else if (reserveMuteSlot) {
            int w = dp(6) + Theme.dialogs_muteDrawable.getIntrinsicWidth();
            if (drawPremium) {
                w += dp(6 + 24 + 6);
            }
            nameWidth -= w;
            nameAdditionalsForChannelSubscriber += w;
            if (LocaleController.isRTL) {
                nameLeft += w;
            }
        } else if (drawAuthorBadge) {
            int w = dp(6) + dp(18);
        } else if (drawVerified) {""",
    """        } else if (reserveMuteSlot) {
            int w = dp(6) + Theme.dialogs_muteDrawable.getIntrinsicWidth();
            if (drawAuthorBadge) {
                w += dp(6 + 18);
            } else if (drawPremium) {
                w += dp(6 + 24 + 6);
            }
            nameWidth -= w;
            nameAdditionalsForChannelSubscriber += w;
            if (LocaleController.isRTL) {
                nameLeft += w;
            }
        } else if (drawAuthorBadge) {
            int w = dp(6) + dp(18);
            nameWidth -= w;
            nameAdditionalsForChannelSubscriber += w;
            if (LocaleController.isRTL) {
                nameLeft += w;
            }
        } else if (drawVerified) {""",
    "badge width reservation",
)

text = replace_once(
    text,
    """                if ((dialogMuted || drawUnmute || dialogMutedProgress > 0) && !drawVerified && drawScam == 0) {
                    if (drawPremium) {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(24));
                        nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                    } else {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx) - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth());
                    }
                } else if (drawVerified) {""",
    """                if ((dialogMuted || drawUnmute || dialogMutedProgress > 0) && !drawVerified && drawScam == 0) {
                    if (drawAuthorBadge) {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(18));
                        nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                    } else if (drawPremium) {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(24));
                        nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                    } else {
                        nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx) - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth());
                    }
                } else if (drawAuthorBadge) {
                    nameMuteLeft = (int) (nameLeft + (nameWidth - widthpx - left) - dp(18));
                    nameMutedIconLeft = nameMuteLeft - dp(6) - Theme.dialogs_muteDrawable.getIntrinsicWidth();
                } else if (drawVerified) {""",
    "RTL badge/mute positioning",
)

text = replace_once(
    text,
    """                if ((dialogMuted || true) || drawUnmute || drawVerified || drawPremium || drawScam != 0) {
                    nameMuteLeft = (int) (nameLeft + left + dp(6));
                    if (drawPremium) {
                        nameMutedIconLeft = nameMuteLeft + dp(24 + 6);
                    }
                }""",
    """                if ((dialogMuted || true) || drawUnmute || drawVerified || drawPremium || drawAuthorBadge || drawScam != 0) {
                    nameMuteLeft = (int) (nameLeft + left + dp(6));
                    if (drawAuthorBadge) {
                        nameMutedIconLeft = nameMuteLeft + dp(18 + 6);
                    } else if (drawPremium) {
                        nameMutedIconLeft = nameMuteLeft + dp(24 + 6);
                    }
                }""",
    "LTR badge/mute positioning",
)

text = replace_once(
    text,
    "                int muteAnchor = drawPremium ? nameMutedIconLeft : nameMuteLeft;",
    "                int muteAnchor = (drawPremium || drawAuthorBadge) ? nameMutedIconLeft : nameMuteLeft;",
    "mute draw anchor",
)

dialog_path.write_text(text, encoding="utf-8", newline="")

# Final source-level assertions. These prove implementations are absent/inert,
# rather than merely hidden by a settings toggle.
def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")

spy = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java")
privacy = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java")
ghost = read("TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java")
user_config = read("TMessagesProj/src/main/java/org/telegram/messenger/UserConfig.java")
dialog = dialog_path.read_text(encoding="utf-8")

for label, source in (("Spy", spy), ("Privacy", privacy), ("Ghost", ghost)):
    if "Play-Market compatibility stub" not in source:
        raise RuntimeError(f"{label} is not a Play stub")

if "NekoConfig.localPremium" in spy or "new GhostModeActivity" in spy:
    raise RuntimeError("Dev-only Spy/Local Premium UI remains")
if "NekoConfig.localPremium.Bool()" in user_config:
    raise RuntimeError("Local Premium runtime bypass remains")
if "return user != null && user.premium;" not in user_config:
    raise RuntimeError("Premium status is not server-authoritative")

for relative in (
    "TMessagesProj/src/main/java/com/radolyn/ayugram/ui/AyuViewDeleted.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/ui/AyuMessageHistory.java",
    "TMessagesProj/src/main/java/com/radolyn/ayugram/proprietary/AyuHistoryHook.java",
):
    if (ROOT / relative).exists():
        raise RuntimeError(f"Forbidden Play source remains: {relative}")

if "drawPremium || drawAuthorBadge" not in dialog or "w += dp(6 + 18);" not in dialog:
    raise RuntimeError("Badge spacing fix is missing")

for strings_file in (ROOT / "TMessagesProj/src/main/res").rglob("strings*.xml"):
    content = strings_file.read_text(encoding="utf-8", errors="ignore")
    if '<string name="AppName">AuthorGram+</string>' in content:
        raise RuntimeError(f"AuthorGram+ AppName remains in {strings_file}")
    if '<string name="AppNameBeta">AuthorGram+</string>' in content:
        raise RuntimeError(f"AuthorGram+ AppNameBeta remains in {strings_file}")

# Remove one-shot repair machinery from the final source tree.
(ROOT / ".github/workflows/play-cleanup-once.yml").unlink(missing_ok=True)
Path(__file__).unlink(missing_ok=True)

run("git", "diff", "--check")
print("Play source cleanup and badge spacing patch validated successfully")
