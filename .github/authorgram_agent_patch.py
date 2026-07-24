from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path.cwd()

TEXT_SUFFIXES = {
    ".java", ".kt", ".kts", ".xml", ".gradle", ".properties",
    ".yml", ".yaml", ".json", ".md", ".txt", ".sh", ".pro"
}


def tracked_paths():
    raw = subprocess.check_output(["git", "ls-files", "-z"])
    for item in raw.split(b"\0"):
        if not item:
            continue
        path = ROOT / item.decode("utf-8", "replace")
        if path.is_file():
            yield path


def text_paths():
    for path in tracked_paths():
        if path.suffix.lower() in TEXT_SUFFIXES or path.name in {
            "gradle.properties", "AndroidManifest.xml"
        }:
            yield path


def read(path: Path) -> str:
    return path.read_text("utf-8", errors="replace")


def write(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, "utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    result, count = re.subn(pattern, replacement, text, count=1, flags=re.S | re.M)
    if count != 1:
        raise RuntimeError(f"{label}: expected one regex match, found {count}")
    return result


def add_strings(path: Path, values: dict[str, str]) -> None:
    if not path.exists():
        write(path, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n')
    text = read(path)
    additions = []
    for key, value in values.items():
        escaped = (value.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("'", "\\'"))
        pattern = re.compile(rf'(<string\s+name="{re.escape(key)}"[^>]*>)(.*?)(</string>)', re.S)
        if pattern.search(text):
            text = pattern.sub(lambda m: m.group(1) + escaped + m.group(3), text, count=1)
        else:
            additions.append(f'    <string name="{key}">{escaped}</string>')
    if additions:
        text = replace_once(
            text,
            "</resources>",
            "\n" + "\n".join(additions) + "\n</resources>",
            f"append strings to {path}",
        )
    write(path, text)


def remove_file(path: Path) -> None:
    if path.exists():
        path.unlink()


# ---------------------------------------------------------------------------
# Play Market application identity
# ---------------------------------------------------------------------------

gradle_properties = ROOT / "gradle.properties"
text = read(gradle_properties)
text = re.sub(r"(?m)^APP_PACKAGE=.*$", "APP_PACKAGE=toss.authorgram.apk", text)
text = re.sub(r"(?m)^APP_VERSION_NAME=([^\n]+)$", lambda m: (
    "APP_VERSION_NAME=" + (m.group(1) if m.group(1).endswith("-TOSS") else m.group(1) + "-TOSS")
), text)
write(gradle_properties, text)

for path in list(text_paths()):
    if path == ROOT / ".github/authorgram_agent_patch.py":
        continue
    text = read(path)
    updated = (text
        .replace("fork.risin42.nagramx", "toss.authorgram.apk")
        .replace("tg.authorche.top", "toss.authorgram.apk"))
    if updated != text:
        write(path, updated)

build_gradle = ROOT / "TMessagesProj/build.gradle"
text = read(build_gradle)
text = text.replace("gramName = 'NagramXF'", "gramName = 'TOSS'")
text = text.replace('gramName = "NagramXF"', 'gramName = "TOSS"')
write(build_gradle, text)

manifest = ROOT / "TMessagesProj/src/main/AndroidManifest.xml"
text = read(manifest)
text = text.replace("@mipmap/ic_launcher_nagram_dark_blue_round", "@mipmap/ic_launcher_toss_round")
text = text.replace("@mipmap/ic_launcher_nagram_dark_blue", "@mipmap/ic_launcher_toss")
text = re.sub(
    r'\s*<uses-permission\s+android:name="android\.permission\.REQUEST_INSTALL_PACKAGES"\s*/>',
    "",
    text,
)
write(manifest, text)

# ---------------------------------------------------------------------------
# Public startup: remove the private access/allow-list gate
# ---------------------------------------------------------------------------

launch = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"
text = read(launch)
text = re.sub(r"(?m)^import org\.telegram\.messenger\.AuthorgramAccessChecker;\n", "", text)
text = re.sub(
    r"(?m)^\s*//\s*Authorgram:.*\n\s*AuthorgramAccessChecker\.checkAndEnforceAccess\(this\);\n",
    "",
    text,
)
write(launch, text)
remove_file(ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/AuthorgramAccessChecker.java")
for candidate in list(tracked_paths()):
    if candidate.name.lower() in {"allow.txt", "authorgram_allow.txt"}:
        remove_file(candidate)

# ---------------------------------------------------------------------------
# Rename primary settings and filters source modules
# ---------------------------------------------------------------------------

class_map = {
    "BaseNekoXSettingsActivity": "BaseAGXSettingsActivity",
    "BaseNekoSettingsActivity": "BaseAGSettingsActivity",
    "NekoSettingsActivity": "AGSettingsActivity",
    "NekoAboutActivity": "AGAboutActivity",
    "NekoAppearanceSettingsActivity": "AGAppearanceSettingsActivity",
    "NekoAyuSpySettingsActivity": "AGPrivacySettingsActivity",
    "NekoChatSettingsActivity": "AGChatSettingsActivity",
    "NekoEmojiSettingsActivity": "AGEmojiSettingsActivity",
    "NekoExperimentalSettingsActivity": "AGExperimentalSettingsActivity",
    "NekoGeneralSettingsActivity": "AGGeneralSettingsActivity",
    "NekoPasscodeSettingsActivity": "AGPasscodeSettingsActivity",
    "NekoTranslatorSettingsActivity": "AGTranslatorSettingsActivity",
    "SettingsSearchResult": "AGSettingsSearchResult",
    "SettingsHelper": "AGSettingsRouter",
    "RegexFiltersSettingActivity": "AGFiltersSettingsActivity",
    "AyuFilterCache": "AGFilterCache",
    "AyuFilter": "AGFilter",
}

old_settings_dir = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram/settings"
old_filters_dir = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram/filters"
new_settings_dir = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings"
new_filters_dir = ROOT / "TMessagesProj/src/main/java/toss/authorgram/filters"
new_settings_dir.mkdir(parents=True, exist_ok=True)
new_filters_dir.mkdir(parents=True, exist_ok=True)

remove_file(old_settings_dir / "NekoAyuMomentsSettingsActivity.java")

for path in list(text_paths()):
    if path == ROOT / ".github/authorgram_agent_patch.py":
        continue
    text = read(path)
    updated = text.replace("tw.nekomimi.nekogram.settings", "toss.authorgram.settings")
    updated = updated.replace("tw.nekomimi.nekogram.filters", "toss.authorgram.filters")
    for old, new in class_map.items():
        updated = re.sub(rf"\b{re.escape(old)}\b", new, updated)
    if updated != text:
        write(path, updated)

# Move all settings source files into the AuthorGram namespace.
for source in sorted(old_settings_dir.glob("*.java")):
    target_name = source.name
    for old, new in class_map.items():
        if target_name == old + ".java":
            target_name = new + ".java"
            break
    target = new_settings_dir / target_name
    if target.exists():
        raise RuntimeError(f"Settings target already exists: {target}")
    shutil.move(str(source), str(target))

# Move Settings router/search model out of the legacy helper namespace.
helper_dir = ROOT / "TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers"
router_source = helper_dir / "SettingsHelper.java"
search_source = helper_dir / "SettingsSearchResult.java"
if router_source.exists():
    router_text = read(router_source)
    router_text = router_text.replace(
        "package tw.nekomimi.nekogram.helpers;",
        "package toss.authorgram.settings;",
    )
    router_text = router_text.replace(
        "import tw.nekomimi.nekogram.helpers.AGSettingsSearchResult;\n",
        "",
    )
    write(new_settings_dir / "AGSettingsRouter.java", router_text)
    router_source.unlink()
if search_source.exists():
    search_text = read(search_source).replace(
        "package tw.nekomimi.nekogram.helpers;",
        "package toss.authorgram.settings;",
    )
    write(new_settings_dir / "AGSettingsSearchResult.java", search_text)
    search_source.unlink()

# Correct imports produced by the global class-token rename.
for path in list(text_paths()):
    if path == ROOT / ".github/authorgram_agent_patch.py":
        continue
    text = read(path)
    updated = (text
        .replace("tw.nekomimi.nekogram.helpers.AGSettingsRouter", "toss.authorgram.settings.AGSettingsRouter")
        .replace("tw.nekomimi.nekogram.helpers.AGSettingsSearchResult", "toss.authorgram.settings.AGSettingsSearchResult"))
    if updated != text:
        write(path, updated)

# Move the complete filters module, preserving its subpackages.
for source in sorted(old_filters_dir.rglob("*.java")):
    relative = source.relative_to(old_filters_dir)
    target_name = relative.name
    if target_name == "AyuFilter.java":
        target_name = "AGFilter.java"
    elif target_name == "AyuFilterCache.java":
        target_name = "AGFilterCache.java"
    elif target_name == "RegexFiltersSettingActivity.java":
        target_name = "AGFiltersSettingsActivity.java"
    target = new_filters_dir / relative.parent / target_name
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise RuntimeError(f"Filters target already exists: {target}")
    shutil.move(str(source), str(target))

# ---------------------------------------------------------------------------
# AGSettings navigation, AyuMoments removal, filters under Chats
# ---------------------------------------------------------------------------

settings = new_settings_dir / "AGSettingsActivity.java"
text = read(settings)
text = re.sub(r"(?m)^\s*private int ayuMomentsRow;\n", "", text)
text = re.sub(r"(?m)^\s*ayuMomentsRow = addRow\(\);\n", "", text)
text = re.sub(
    r"\s*else if \(position == ayuMomentsRow\) \{\s*presentFragment\(new [A-Za-z0-9_]+\(\)\);\s*\}",
    "",
    text,
)
text = re.sub(
    r"\s*else if \(position == ayuMomentsRow\) \{\s*textCell\.setTextAndIcon\([^;]+;\s*\}",
    "",
    text,
)
text = text.replace(" || position == ayuMomentsRow", "")
text = text.replace("position == ayuMomentsRow || ", "")
text = text.replace("return getString(R.string.NekoSettings);", "return getString(R.string.AGSettings);")
text = text.replace(".nekox-settings.json", ".authorgram-settings.json")
write(settings, text)

router = new_settings_dir / "AGSettingsRouter.java"
text = read(router)
text = text.replace('"nasettings"', '"agsettings"')
text = re.sub(r"(?m)^import .*NekoAyuMomentsSettingsActivity;\n", "", text)
text = re.sub(
    r"\s*case \"ayumoments\":\s*case \"ayugrammoment\":\s*case \"m\":\s*fragment = nekox_fragment = new [A-Za-z0-9_]+\(\);\s*break;",
    "",
    text,
)
text = re.sub(r"(?m)^\s*fragments\.add\(new [A-Za-z0-9_]*AyuMoments[A-Za-z0-9_]*\(\)\);\n", "", text)
text = text.replace("R.string.NekoSettings", "R.string.AGSettings")
write(router, text)

chat_settings = new_settings_dir / "AGChatSettingsActivity.java"
text = read(chat_settings)
if "import toss.authorgram.filters.AGFiltersSettingsActivity;" not in text:
    marker = "import tw.nekomimi.nekogram.NekoConfig;\n"
    text = replace_once(
        text,
        marker,
        marker + "import toss.authorgram.filters.AGFiltersSettingsActivity;\n",
        "AG filters import",
    )
filters_row = """    private final AbstractConfigCell agFiltersRow = cellGroup.appendCell(new ConfigCellTextDetailIcon(
            "AGRegexFilters",
            getString(R.string.AGRegexFilters),
            getString(R.string.AGRegexFiltersInfo),
            R.drawable.ag_filter,
            true,
            () -> presentFragment(new AGFiltersSettingsActivity())
    ));
"""
text = replace_once(
    text,
    "    private final AbstractConfigCell dividerChats = cellGroup.appendCell(new ConfigCellDivider());\n",
    filters_row + "    private final AbstractConfigCell dividerChats = cellGroup.appendCell(new ConfigCellDivider());\n",
    "AG filters row in Chats",
)
write(chat_settings, text)

filter_settings = new_filters_dir / "AGFiltersSettingsActivity.java"
text = read(filter_settings).replace('"NagramXF Filters"', '"TOSS Filters"')
write(filter_settings, text)

# Drawer identity.
drawer = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Adapters/DrawerLayoutAdapter.java"
text = read(drawer)
text = re.sub(r"\bshowNSettings\b", "showAGSettings", text)
text = text.replace("R.string.NekoSettings", "R.string.AGSettings")
text = text.replace("R.drawable.nagramx_outline", "R.drawable.ag_settings")
write(drawer, text)

# ---------------------------------------------------------------------------
# About screen: TOSS description and upstream credits
# ---------------------------------------------------------------------------

about = new_settings_dir / "AGAboutActivity.java"
text = read(about)
text = text.replace('return "AuthorGram v" + versionName;', 'return "TOSS v" + versionName;')
text = text.replace("R.string.NaxAboutInfo", "R.string.AGAboutInfo")
text = text.replace("R.string.NaxLinks", "R.string.AGLinks")
text = text.replace("R.string.NaxAboutDesc", "R.string.TOSSAboutDesc")
text = replace_once(
    text,
    "    private int versionRow;\n",
    "    private int versionRow;\n    private int creditsRow;\n",
    "About credits field",
)
text = replace_once(
    text,
    "        versionRow = addRow();\n        toggleLogsRow = addRow();\n",
    "        versionRow = addRow();\n        creditsRow = addRow();\n        toggleLogsRow = addRow();\n",
    "About credits row",
)
text = replace_once(
    text,
    """                    if (position == versionRow) {
                        detailCell.setMultilineDetail(true);
                        detailCell.setTextAndValue(getSimpleVersion(), getString(R.string.TOSSAboutDesc), false);
                    }
""",
    """                    if (position == versionRow) {
                        detailCell.setMultilineDetail(true);
                        detailCell.setTextAndValue(getSimpleVersion(), getString(R.string.TOSSAboutDesc), true);
                    } else if (position == creditsRow) {
                        detailCell.setMultilineDetail(true);
                        detailCell.setTextAndValue(
                                getString(R.string.AGCredits),
                                getString(R.string.AGCreditsText),
                                false
                        );
                    }
""",
    "About credits binding",
)
text = text.replace(
    "} else if (position == versionRow) {",
    "} else if (position == versionRow || position == creditsRow) {",
)
write(about, text)

# ---------------------------------------------------------------------------
# TOSS strings and visible-brand cleanup
# ---------------------------------------------------------------------------

brand_replacements = [
    (re.compile(r"Nagram\s*XF", re.I), "TOSS"),
    (re.compile(r"Nagram\s*X", re.I), "TOSS"),
    (re.compile(r"Naigram", re.I), "TOSS"),
    (re.compile(r"Nagram", re.I), "TOSS"),
    (re.compile(r"Nekogram\s*X", re.I), "TOSS"),
    (re.compile(r"Nekogram", re.I), "TOSS"),
    (re.compile(r"NekoX", re.I), "TOSS"),
    (re.compile(r"Ayu\s*Moments", re.I), "Privacy tools"),
    (re.compile(r"AyuGram", re.I), "AuthorGram"),
]

def clean_visible_value(value: str) -> str:
    result = value
    for pattern, replacement in brand_replacements:
        result = pattern.sub(replacement, result)
    return result

for strings in (ROOT / "TMessagesProj/src/main/res").glob("values*/strings*.xml"):
    text = read(strings)
    string_pattern = re.compile(r"(<string\b[^>]*>)(.*?)(</string>)", re.S)
    item_pattern = re.compile(r"(<item\b[^>]*>)(.*?)(</item>)", re.S)
    text = string_pattern.sub(lambda m: m.group(1) + clean_visible_value(m.group(2)) + m.group(3), text)
    text = item_pattern.sub(lambda m: m.group(1) + clean_visible_value(m.group(2)) + m.group(3), text)
    write(strings, text)

DEFAULT_STRINGS = {
    "AppName": "TOSS",
    "AppNameBeta": "TOSS Beta",
    "TOSS": "TOSS",
    "AGSettings": "AuthorGram Settings",
    "AGRegexFilters": "Message filters",
    "AGRegexFiltersInfo": "Filter global or per-chat messages with regular expressions.",
    "AGAboutInfo": "TOSS",
    "AGLinks": "AuthorChe links",
    "TOSSAboutDesc": "TOSS is a privacy-focused Telegram multitool designed for secure communication, flexible controls and convenient everyday use.",
    "AGCredits": "Open-source credits",
    "AGCreditsText": "Thanks to Cherrygram, Nagram and Nekogram and their contributors. Parts of TOSS are based on or adapted from their open-source work. Telegram for Android remains the upstream foundation.",
}
UK_STRINGS = {
    "AppName": "TOSS",
    "AppNameBeta": "TOSS Beta",
    "TOSS": "TOSS",
    "AGSettings": "Налаштування AuthorGram",
    "AGRegexFilters": "Фільтри повідомлень",
    "AGRegexFiltersInfo": "Фільтруйте повідомлення глобально або для окремих чатів за допомогою регулярних виразів.",
    "AGAboutInfo": "TOSS",
    "AGLinks": "Посилання AuthorChe",
    "TOSSAboutDesc": "TOSS — орієнтований на приватність багатофункціональний Telegram-клієнт для захищеного спілкування, гнучких налаштувань і зручного щоденного користування.",
    "AGCredits": "Подяки open-source проєктам",
    "AGCreditsText": "Дяка Cherrygram, Nagram, Nekogram та їхнім учасникам. Частина можливостей TOSS створена на основі або з адаптацією їхнього відкритого коду. Базовою платформою залишається Telegram for Android.",
}
DE_STRINGS = {
    "AppName": "TOSS",
    "AppNameBeta": "TOSS Beta",
    "TOSS": "TOSS",
    "AGSettings": "AuthorGram-Einstellungen",
    "AGRegexFilters": "Nachrichtenfilter",
    "AGRegexFiltersInfo": "Filtere Nachrichten global oder pro Chat mit regulären Ausdrücken.",
    "AGAboutInfo": "TOSS",
    "AGLinks": "AuthorChe-Links",
    "TOSSAboutDesc": "TOSS ist ein datenschutzorientiertes Telegram-Multitool für sichere Kommunikation, flexible Steuerung und komfortable tägliche Nutzung.",
    "AGCredits": "Open-Source-Danksagung",
    "AGCreditsText": "Danke an Cherrygram, Nagram und Nekogram sowie ihre Mitwirkenden. Teile von TOSS basieren auf deren Open-Source-Arbeit oder wurden daraus angepasst. Telegram for Android bleibt die technische Grundlage.",
}
add_strings(ROOT / "TMessagesProj/src/main/res/values/strings.xml", DEFAULT_STRINGS)
add_strings(ROOT / "TMessagesProj/src/main/res/values-uk/strings.xml", UK_STRINGS)
add_strings(ROOT / "TMessagesProj/src/main/res/values-de/strings.xml", DE_STRINGS)

# Default title for clean installs.
defaults = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java"
if defaults.exists():
    text = read(defaults)
    text = re.sub(
        r'("CustomTitle"\s*,\s*)"[^"]*"',
        r'\1"TOSS"',
        text,
    )
    write(defaults, text)

# ---------------------------------------------------------------------------
# Rename legacy branded launcher/resource filenames still used by icon packs.
# ---------------------------------------------------------------------------

res_root = ROOT / "TMessagesProj/src/main/res"
for path in sorted([p for p in res_root.rglob("*") if p.is_file()], key=lambda p: len(p.parts), reverse=True):
    name = path.name
    if "nagramx" not in name.lower() and "nagram" not in name.lower():
        continue
    # The custom foreground intentionally replaces the old default foreground.
    if name == "ic_launcher_nagram_foreground.xml":
        path.unlink()
        continue
    new_name = re.sub("nagramx", "toss", name, flags=re.I)
    new_name = re.sub("nagram", "toss", new_name, flags=re.I)
    target = path.with_name(new_name)
    if target.exists():
        path.unlink()
    else:
        path.rename(target)

for path in list(text_paths()):
    if path == ROOT / ".github/authorgram_agent_patch.py":
        continue
    text = read(path)
    updated = (text
        .replace("nagramx_outline2", "toss_outline2")
        .replace("nagramx_outline", "toss_outline")
        .replace("nagram_notification", "toss_notification")
        .replace("ic_launcher_nagram", "ic_launcher_toss"))
    if updated != text:
        write(path, updated)

# ---------------------------------------------------------------------------
# Remove empty legacy directories and validate the Play Market identity
# ---------------------------------------------------------------------------

for directory in sorted(
        [old_settings_dir, old_filters_dir] + list(old_filters_dir.glob("**/*")),
        key=lambda p: len(p.parts),
        reverse=True,
):
    if directory.is_dir():
        try:
            directory.rmdir()
        except OSError:
            pass

checks = {
    "package id": "APP_PACKAGE=toss.authorgram.apk" in read(gradle_properties),
    "TOSS version": "-TOSS" in read(gradle_properties),
    "AGSettings class": (new_settings_dir / "AGSettingsActivity.java").exists(),
    "AG filters class": (new_filters_dir / "AGFiltersSettingsActivity.java").exists(),
    "AyuMoments page removed": not (old_settings_dir / "NekoAyuMomentsSettingsActivity.java").exists(),
    "access checker removed": not (ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/AuthorgramAccessChecker.java").exists(),
    "new drawer icon": "R.drawable.ag_settings" in read(drawer),
    "new settings title": "R.string.AGSettings" in read(drawer),
    "new deep link": '"agsettings"' in read(router),
    "old deep link removed": '"nasettings"' not in read(router),
    "filters under chats": "new AGFiltersSettingsActivity()" in read(chat_settings),
    "TOSS launcher": "@mipmap/ic_launcher_toss" in read(manifest),
    "Play install permission removed": "REQUEST_INSTALL_PACKAGES" not in read(manifest),
    "credits preserved": "Cherrygram, Nagram and Nekogram" in read(ROOT / "TMessagesProj/src/main/res/values/strings.xml"),
}
failed = [name for name, ok in checks.items() if not ok]
if failed:
    raise RuntimeError("TOSS validation failed: " + ", ".join(failed))

for path in text_paths():
    if path == ROOT / ".github/authorgram_agent_patch.py":
        continue
    text = read(path)
    if "fork.risin42.nagramx" in text or "tg.authorche.top" in text:
        raise RuntimeError(f"Old package remains in {path.relative_to(ROOT)}")
    if "AuthorgramAccessChecker" in text:
        raise RuntimeError(f"Access checker reference remains in {path.relative_to(ROOT)}")

print("TOSS PLAY MARKET REBRAND PATCH: PASS")
