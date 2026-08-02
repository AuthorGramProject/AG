#!/usr/bin/env python3
"""Apply the AuthorGram visual cleanup, launcher branding and private Spy menu."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAY_PACKAGE = "toss.authorgram.apk"
APP_SOURCE = ROOT / "TMessagesProj/src/main"
AG_SETTINGS = APP_SOURCE / "java/toss/authorgram/settings/AGSettingsActivity.java"
AG_ROUTER = APP_SOURCE / "java/toss/authorgram/settings/AGSettingsRouter.java"

TEXT_SUFFIXES = {
    ".xml", ".java", ".kt", ".kts", ".gradle", ".properties", ".py", ".sh", ".md", ".txt"
}
LAUNCHER_REPLACEMENTS = (
    ("ic_launcher_toss_dark_blue_round", "ic_launcher_authorgram_round"),
    ("ic_launcher_toss_dark_blue", "ic_launcher_authorgram"),
    ("ic_launcher_toss_round", "ic_launcher_authorgram_round"),
    ("ic_launcher_toss", "ic_launcher_authorgram"),
    ("nagram_dark_blue_background", "authorgram_launcher_background"),
    ("ic_launcher_nagram_background", "authorgram_launcher_background"),
)
REQUIRED_ICON_FILES = (
    "TMessagesProj/src/main/res/drawable/ic_launcher_authorgram_background.xml",
    "TMessagesProj/src/main/res/drawable/ic_launcher_authorgram_foreground.xml",
    "TMessagesProj/src/main/res/mipmap-anydpi/ic_launcher_authorgram.xml",
    "TMessagesProj/src/main/res/mipmap-anydpi/ic_launcher_authorgram_round.xml",
    "TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher_authorgram.xml",
    "TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher_authorgram_round.xml",
)


def read(path: Path) -> str:
    if not path.is_file():
        raise RuntimeError(f"Missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8-sig")


def write(path: Path, content: str) -> bool:
    previous = path.read_text(encoding="utf-8-sig") if path.exists() else None
    if previous == content:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="")
    return True


def replace_required(content: str, old: str, new: str, label: str) -> str:
    if new in content:
        return content
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one patch target, found {count}")
    return content.replace(old, new, 1)


def patch_launcher_references() -> int:
    changed = 0
    roots = (
        ROOT / "TMessagesProj/src/main",
        ROOT / "TMessagesProj/src/release",
        ROOT / "TMessagesProj/src/staging",
        ROOT / "TMessagesProj/src/debug",
    )
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
                continue
            try:
                content = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                continue
            updated = content
            for old, new in LAUNCHER_REPLACEMENTS:
                updated = updated.replace(old, new)
            if updated != content:
                path.write_text(updated, encoding="utf-8", newline="")
                changed += 1
    return changed


def patch_spy_menu() -> int:
    changed = 0
    content = read(AG_SETTINGS)

    content = replace_required(
        content,
        "    private static final int MENU_SEARCH = 1;\n",
        "    private static final int MENU_SEARCH = 1;\n"
        f"    private static final String PLAY_PACKAGE = \"{PLAY_PACKAGE}\";\n",
        "AGSettingsActivity PLAY_PACKAGE",
    )
    content = replace_required(
        content,
        "    private int appearanceRow;\n    private int translatorRow;\n",
        "    private int appearanceRow;\n    private int spyRow;\n    private int translatorRow;\n",
        "AGSettingsActivity spy field",
    )
    content = replace_required(
        content,
        "        appearanceRow = addRow();\n        translatorRow = addRow();\n",
        "        appearanceRow = addRow();\n"
        "        if (isPrivateMainBuild()) {\n"
        "            spyRow = addRow();\n"
        "        } else {\n"
        "            spyRow = -1;\n"
        "        }\n"
        "        translatorRow = addRow();\n",
        "AGSettingsActivity spy row",
    )
    content = replace_required(
        content,
        "    @Override\n    protected String getActionBarTitle() {\n",
        "    private boolean isPrivateMainBuild() {\n"
        "        return ApplicationLoader.applicationContext == null\n"
        "                || !PLAY_PACKAGE.equals(ApplicationLoader.applicationContext.getPackageName());\n"
        "    }\n\n"
        "    @Override\n    protected String getActionBarTitle() {\n",
        "AGSettingsActivity private-build helper",
    )
    content = replace_required(
        content,
        "        } else if (position == appearanceRow) {\n"
        "            presentFragment(new AGAppearanceSettingsActivity());\n"
        "        } else if (position == passcodeRow) {\n",
        "        } else if (position == appearanceRow) {\n"
        "            presentFragment(new AGAppearanceSettingsActivity());\n"
        "        } else if (position == spyRow) {\n"
        "            presentFragment(new AGPrivacySettingsActivity());\n"
        "        } else if (position == passcodeRow) {\n",
        "AGSettingsActivity spy click",
    )
    content = replace_required(
        content,
        "                    } else if (position == appearanceRow) {\n"
        "                        textCell.setTextAndIcon(getString(R.string.Appearance), R.drawable.msg_theme, true);\n"
        "                    } else if (position == translatorRow) {\n",
        "                    } else if (position == appearanceRow) {\n"
        "                        textCell.setTextAndIcon(getString(R.string.Appearance), R.drawable.msg_theme, true);\n"
        "                    } else if (position == spyRow) {\n"
        "                        textCell.setTextAndIcon(getString(R.string.AuthorGramSpy), R.drawable.msg_secret, true);\n"
        "                    } else if (position == translatorRow) {\n",
        "AGSettingsActivity spy label",
    )
    content = replace_required(
        content,
        "position == chatRow || position == generalRow || position == appearanceRow || position == passcodeRow",
        "position == chatRow || position == generalRow || position == appearanceRow || position == spyRow || position == passcodeRow",
        "AGSettingsActivity spy view type",
    )
    changed += int(write(AG_SETTINGS, content))

    router = read(AG_ROUTER)
    router = replace_required(
        router,
        "import org.telegram.messenger.AndroidUtilities;\nimport org.telegram.messenger.R;\n",
        "import org.telegram.messenger.AndroidUtilities;\n"
        "import org.telegram.messenger.ApplicationLoader;\n"
        "import org.telegram.messenger.R;\n",
        "AGSettingsRouter application import",
    )
    router = replace_required(
        router,
        "public class AGSettingsRouter {\n",
        "public class AGSettingsRouter {\n\n"
        f"    private static final String PLAY_PACKAGE = \"{PLAY_PACKAGE}\";\n\n"
        "    private static boolean isPrivateMainBuild() {\n"
        "        return ApplicationLoader.applicationContext == null\n"
        "                || !PLAY_PACKAGE.equals(ApplicationLoader.applicationContext.getPackageName());\n"
        "    }\n",
        "AGSettingsRouter private-build helper",
    )
    router = replace_required(
        router,
        "                case \"privacy\":\n"
        "                case \"security\":\n"
        "                case \"p\":\n"
        "                    fragment = agxFragment = new AGPrivacySettingsActivity();\n"
        "                    break;\n",
        "                case \"privacy\":\n"
        "                case \"security\":\n"
        "                case \"spy\":\n"
        "                case \"p\":\n"
        "                    if (!isPrivateMainBuild()) {\n"
        "                        unknown.run();\n"
        "                        return;\n"
        "                    }\n"
        "                    fragment = agxFragment = new AGPrivacySettingsActivity();\n"
        "                    break;\n",
        "AGSettingsRouter private Spy deep link",
    )
    router = replace_required(
        router,
        "        fragments.add(new AGAppearanceSettingsActivity());\n"
        "        fragments.add(new AGPrivacySettingsActivity());\n"
        "        fragments.add(new AGChatSettingsActivity());\n",
        "        fragments.add(new AGAppearanceSettingsActivity());\n"
        "        if (isPrivateMainBuild()) {\n"
        "            fragments.add(new AGPrivacySettingsActivity());\n"
        "        }\n"
        "        fragments.add(new AGChatSettingsActivity());\n",
        "AGSettingsRouter private Spy search",
    )
    changed += int(write(AG_ROUTER, router))
    return changed


def remove_legacy_launcher_files() -> list[str]:
    removed: list[str] = []
    for path in list(ROOT.rglob("*")):
        if not path.is_file():
            continue
        name = path.name.lower()
        relative = path.relative_to(ROOT).as_posix()
        should_remove = (
            name.startswith("ic_launcher_nagram")
            or name.startswith("ic_launcher_toss")
            or (
                name == "ic_launcher_foreground.png"
                and "/res/mipmap-" in f"/{relative}"
            )
        )
        if should_remove:
            path.unlink()
            removed.append(relative)
    obsolete_script = ROOT / "b.sh"
    if obsolete_script.is_file():
        obsolete_script.unlink()
        removed.append("b.sh")
    return sorted(removed)


def validate() -> None:
    failures: list[str] = []
    for relative in REQUIRED_ICON_FILES:
        if not (ROOT / relative).is_file():
            failures.append(f"Missing AuthorGram launcher asset: {relative}")

    release_manifest = read(ROOT / "TMessagesProj/src/release/AndroidManifest.xml")
    for required in (
        'android:icon="@mipmap/ic_launcher_authorgram"',
        'android:roundIcon="@mipmap/ic_launcher_authorgram_round"',
    ):
        if required not in release_manifest:
            failures.append(f"Release manifest does not use {required}")

    settings = read(AG_SETTINGS)
    for required in (
        "private int spyRow;",
        "R.string.AuthorGramSpy",
        "new AGPrivacySettingsActivity()",
        "!PLAY_PACKAGE.equals(ApplicationLoader.applicationContext.getPackageName())",
    ):
        if required not in settings:
            failures.append(f"Private Spy menu invariant missing: {required}")

    router = read(AG_ROUTER)
    if 'case "spy":' not in router or "if (isPrivateMainBuild())" not in router:
        failures.append("Private Spy router/search gating is missing")

    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        name = path.name.lower()
        if name.startswith("ic_launcher_nagram") or name.startswith("ic_launcher_toss"):
            failures.append(f"Legacy launcher file remains: {path.relative_to(ROOT)}")

    launcher_reference_hits: list[str] = []
    for root in (
        ROOT / "TMessagesProj/src/main",
        ROOT / "TMessagesProj/src/release",
        ROOT / "TMessagesProj/src/staging",
        ROOT / "TMessagesProj/src/debug",
    ):
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
                continue
            try:
                content = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                continue
            if re.search(r"ic_launcher_(?:nagram|toss)|nagram_dark_blue_background", content, re.I):
                launcher_reference_hits.append(str(path.relative_to(ROOT)))
    if launcher_reference_hits:
        failures.append("Legacy launcher references remain: " + ", ".join(sorted(launcher_reference_hits)[:20]))

    if failures:
        raise RuntimeError("\n".join(failures))


def main() -> int:
    changed = patch_launcher_references()
    changed += patch_spy_menu()
    removed = remove_legacy_launcher_files()
    validate()
    print(
        f"AuthorGram visual revision applied: {changed} text file(s) changed, "
        f"{len(removed)} obsolete file(s) removed"
    )
    for relative in removed:
        print(f"removed: {relative}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"AuthorGram visual revision failed:\n{exc}")
        raise SystemExit(1)
