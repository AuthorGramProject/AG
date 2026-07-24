from pathlib import Path
import re

ROOT = Path.cwd()
JAVA_ROOT = ROOT / "TMessagesProj/src/main/java"
SETTINGS = JAVA_ROOT / "toss/authorgram/settings"
FILTERS = JAVA_ROOT / "toss/authorgram/filters"


def patch(path: Path, replacements: list[tuple[str, str]]) -> None:
    text = path.read_text("utf-8")
    for old, new in replacements:
        text = text.replace(old, new)
    path.write_text(text, "utf-8")


settings_activity = SETTINGS / "AGSettingsActivity.java"
patch(settings_activity, [
    ("import tw.nekomimi.nekogram.helpers.AGSettingsRouter;\n", ""),
    ("import tw.nekomimi.nekogram.helpers.AGSettingsSearchResult;\n", ""),
    ("nSettingsEndRow", "agSettingsEndRow"),
])

router = SETTINGS / "AGSettingsRouter.java"
text = router.read_text("utf-8")
if "import tw.nekomimi.nekogram.helpers.PasscodeHelper;" not in text:
    marker = "import java.util.Map;\n"
    if marker not in text:
        raise RuntimeError("AGSettingsRouter import anchor not found")
    text = text.replace(
        marker,
        marker + "\nimport tw.nekomimi.nekogram.helpers.PasscodeHelper;\n",
        1,
    )
text = text.replace("neko_fragment", "agFragment")
text = text.replace("nekox_fragment", "agxFragment")
text = text.replace("finalNeko_fragment", "finalAGFragment")
text = text.replace("finalNekoX_fragment", "finalAGXFragment")
router.write_text(text, "utf-8")

# New source files were untracked at the moment of the first generated scan.
# Scan the physical tree now and remove every stale settings/filter import.
for path in list(SETTINGS.rglob("*.java")) + list(FILTERS.rglob("*.java")):
    text = path.read_text("utf-8")
    text = text.replace(
        "tw.nekomimi.nekogram.helpers.AGSettingsRouter",
        "toss.authorgram.settings.AGSettingsRouter",
    )
    text = text.replace(
        "tw.nekomimi.nekogram.helpers.AGSettingsSearchResult",
        "toss.authorgram.settings.AGSettingsSearchResult",
    )
    text = text.replace(
        "tw.nekomimi.nekogram.settings.",
        "toss.authorgram.settings.",
    )
    text = text.replace(
        "tw.nekomimi.nekogram.filters.",
        "toss.authorgram.filters.",
    )
    path.write_text(text, "utf-8")

# Cross-module users outside the moved source tree must also point to TOSS.
for path in JAVA_ROOT.rglob("*.java"):
    text = path.read_text("utf-8", errors="replace")
    updated = text.replace(
        "tw.nekomimi.nekogram.helpers.AGSettingsRouter",
        "toss.authorgram.settings.AGSettingsRouter",
    ).replace(
        "tw.nekomimi.nekogram.helpers.AGSettingsSearchResult",
        "toss.authorgram.settings.AGSettingsSearchResult",
    ).replace(
        "tw.nekomimi.nekogram.settings.",
        "toss.authorgram.settings.",
    ).replace(
        "tw.nekomimi.nekogram.filters.",
        "toss.authorgram.filters.",
    )
    if updated != text:
        path.write_text(updated, "utf-8")

# Compile-oriented source assertions.
for path in JAVA_ROOT.rglob("*.java"):
    text = path.read_text("utf-8", errors="replace")
    relative = path.relative_to(ROOT)
    for forbidden in (
        "tw.nekomimi.nekogram.settings.",
        "tw.nekomimi.nekogram.filters.",
        "tw.nekomimi.nekogram.helpers.AGSettingsRouter",
        "tw.nekomimi.nekogram.helpers.AGSettingsSearchResult",
        "NekoAyuMomentsSettingsActivity",
    ):
        if forbidden in text:
            raise RuntimeError(f"Stale reference {forbidden} in {relative}")

router_text = router.read_text("utf-8")
if "PasscodeHelper.getSettingsKey()" in router_text and "import tw.nekomimi.nekogram.helpers.PasscodeHelper;" not in router_text:
    raise RuntimeError("PasscodeHelper import is missing")

settings_text = settings_activity.read_text("utf-8")
if "AGSettingsRouter.onCreateSearchArray" not in settings_text:
    raise RuntimeError("AGSettings search router is disconnected")
if "import tw.nekomimi.nekogram.helpers.AGSettings" in settings_text:
    raise RuntimeError("Stale AGSettings helper import remains")

print("TOSS PACKAGE IMPORT FIX: PASS")
