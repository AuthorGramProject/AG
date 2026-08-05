#!/usr/bin/env python3
from pathlib import Path
import runpy

root = Path(__file__).resolve().parents[1]

# Compile every chained source patch and verifier before executing the first one.
# This keeps a syntax regression from partially mutating the release checkout.
validation_chain = (
    "scripts/patch_authorgram_ui_12_9_2.py",
    "scripts/patch_authorgram_popup_bounds.py",
    "scripts/patch_authorgram_badge_surfaces.py",
    "scripts/verify_authorgram_badge_tokens.py",
)
for relative in validation_chain:
    source = (root / relative).read_text(encoding="utf-8")
    compile(source, relative, "exec")
    print(f"Python syntax passed: {relative}")

# The popup-bounds patch chains badge-surface repair and deterministic token
# verification. Running these two entry points therefore applies the complete
# idempotent UI repair set exactly once.
for relative in (
    "scripts/patch_authorgram_ui_12_9_2.py",
    "scripts/patch_authorgram_popup_bounds.py",
):
    runpy.run_path(str(root / relative), run_name="__main__")

# Use the existing Nagram string that explicitly names the feature rather than
# the generic Telegram "Folders" label, so the new local folders are discoverable.
settings_path = root / "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java"
settings_text = settings_path.read_text(encoding="utf-8")
generic_local_folders_row = (
    "textCell.setTextAndIcon(getString(R.string.Filters), R.drawable.msg_folders, true);"
)
explicit_local_folders_row = (
    "textCell.setTextAndIcon(getString(R.string.BuiltInFolders), R.drawable.msg_folders, true);"
)
if generic_local_folders_row in settings_text:
    settings_text = settings_text.replace(
        generic_local_folders_row,
        explicit_local_folders_row,
        1,
    )
if explicit_local_folders_row not in settings_text:
    raise SystemExit("explicit Built-in Folders settings row is missing")
settings_path.write_text(settings_text, encoding="utf-8", newline="")

path = root / "TMessagesProj/build.gradle"
text = path.read_text(encoding="utf-8")
key = "6b8ce70d889daed80852c204106d51bf" + "91f114ad32936b6b17068e7b399ef3fa"

if "def authorGramSystemKeyHex =" not in text:
    marker = "def telegramAdBlockingEnabled = APP_PACKAGE != 'toss.authorgram.apk'\n"
    if text.count(marker) != 1:
        raise SystemExit("build key marker missing")
    text = text.replace(
        marker,
        marker + "def authorGramSystemKeyHex = APP_PACKAGE == 'toss.authorgram.apk' ? '' : '" + key + "'\n",
        1,
    )

if "'AUTHORGRAM_SYSTEM_KEY_HEX'" not in text:
    marker = "        buildConfigField 'boolean', 'TELEGRAM_AD_BLOCKING_ENABLED', telegramAdBlockingEnabled.toString()\n"
    if text.count(marker) != 1:
        raise SystemExit("BuildConfig marker missing")
    text = text.replace(
        marker,
        marker + "        buildConfigField 'String', 'AUTHORGRAM_SYSTEM_KEY_HEX', '\"' + authorGramSystemKeyHex + '\"'\n",
        1,
    )

path.write_text(text, encoding="utf-8", newline="")
print("AuthorGram package-specific system key BuildConfig passed")
