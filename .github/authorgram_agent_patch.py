from pathlib import Path

replacements = {
    Path("TMessagesProj/src/main/java/org/telegram/messenger/NotificationsController.java"): {
        "R.drawable.nagramx_notification": "R.drawable.toss_notification",
    },
    Path("TMessagesProj/src/main/java/org/telegram/ui/Cells/SessionCell.java"): {
        "R.drawable.nagramx_notification": "R.drawable.toss_notification",
    },
    Path("TMessagesProj/src/main/java/toss/authorgram/settings/AGAppearanceSettingsActivity.java"): {
        "getString(R.string.NekoSettings), R.drawable.nagramx_outline":
            "getString(R.string.AGSettings), R.drawable.ag_settings",
    },
}

for path, mapping in replacements.items():
    text = path.read_text(encoding="utf-8")
    for old, new in mapping.items():
        count = text.count(old)
        if count != 1:
            raise RuntimeError(f"Expected exactly one {old!r} in {path}, found {count}")
        text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")

for path in Path("TMessagesProj/src/main/java").rglob("*.java"):
    text = path.read_text(encoding="utf-8", errors="ignore")
    if "R.drawable.nagramx_notification" in text or "R.drawable.nagramx_outline" in text:
        raise RuntimeError(f"Legacy TOSS drawable reference remains in {path}")

print("TOSS resource references fixed")
