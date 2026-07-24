from pathlib import Path
import re

ROOT = Path.cwd()
LAUNCH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java"

text = LAUNCH.read_text("utf-8")
text = text.replace(
    'path.startsWith("agsettings/")',
    '(path.equals("agsettings") || path.startsWith("agsettings/"))'
)
text = text.replace("UnknownNekoSettingsOption", "UnknownAGSettingsOption")
LAUNCH.write_text(text, "utf-8")


def set_string(path: Path, key: str, value: str) -> None:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n', "utf-8")
    content = path.read_text("utf-8")
    escaped = (value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("'", "\\'"))
    pattern = re.compile(rf'(<string\s+name="{re.escape(key)}"[^>]*>)(.*?)(</string>)', re.S)
    if pattern.search(content):
        content = pattern.sub(lambda m: m.group(1) + escaped + m.group(3), content, count=1)
    else:
        content = content.replace(
            "</resources>",
            f'    <string name="{key}">{escaped}</string>\n</resources>',
            1,
        )
    path.write_text(content, "utf-8")


set_string(
    ROOT / "TMessagesProj/src/main/res/values/strings.xml",
    "UnknownAGSettingsOption",
    "Unknown AuthorGram settings option",
)
set_string(
    ROOT / "TMessagesProj/src/main/res/values-uk/strings.xml",
    "UnknownAGSettingsOption",
    "Невідомий пункт налаштувань AuthorGram",
)
set_string(
    ROOT / "TMessagesProj/src/main/res/values-de/strings.xml",
    "UnknownAGSettingsOption",
    "Unbekannte AuthorGram-Einstellungsoption",
)

final = LAUNCH.read_text("utf-8")
if 'path.equals("agsettings") || path.startsWith("agsettings/")' not in final:
    raise RuntimeError("Root AGSettings path is not handled")
if "UnknownNekoSettingsOption" in final:
    raise RuntimeError("Legacy settings error resource remains")
if "nasettings" in final or "tg:neko" in final:
    raise RuntimeError("Legacy settings URI remains")

print("AGSETTINGS ROOT DEEP LINK PATCH: PASS")
