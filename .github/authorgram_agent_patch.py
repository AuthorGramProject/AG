from pathlib import Path
import re

ROOT = Path.cwd()
RES = ROOT / "TMessagesProj/src/main/res"
SETTINGS = ROOT / "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java"

visible_patterns = [
    (re.compile(r"Nagram\s*XF", re.I), "TOSS"),
    (re.compile(r"Nagram\s*X", re.I), "TOSS"),
    (re.compile(r"Naigram", re.I), "TOSS"),
    (re.compile(r"Nagram", re.I), "TOSS"),
    (re.compile(r"Nekogram\s*X", re.I), "TOSS"),
    (re.compile(r"Nekogram", re.I), "TOSS"),
    (re.compile(r"NekoX", re.I), "TOSS"),
    (re.compile(r"\bNeko\b", re.I), "TOSS"),
    (re.compile(r"\bNaX\b", re.I), "TOSS"),
    (re.compile(r"Ayu\s*Moments", re.I), "Privacy tools"),
    (re.compile(r"AyuGram", re.I), "AuthorGram"),
]


def clean(value: str) -> str:
    result = value
    for pattern, replacement in visible_patterns:
        result = pattern.sub(replacement, result)
    return result


for path in RES.glob("values*/strings*.xml"):
    text = path.read_text("utf-8", errors="replace")
    string_pattern = re.compile(r"(<string\b[^>]*>)(.*?)(</string>)", re.S)
    item_pattern = re.compile(r"(<item\b[^>]*>)(.*?)(</item>)", re.S)

    def replace_string(match):
        opening, value, closing = match.groups()
        # Upstream acknowledgements are intentionally preserved only here.
        if 'name="AGCreditsText"' in opening:
            return match.group(0)
        return opening + clean(value) + closing

    text = string_pattern.sub(replace_string, text)
    text = item_pattern.sub(lambda m: m.group(1) + clean(m.group(2)) + m.group(3), text)
    path.write_text(text, "utf-8")


def set_string(path: Path, key: str, value: str) -> None:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n', "utf-8")
    text = path.read_text("utf-8")
    escaped = (value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("'", "\\'"))
    pattern = re.compile(rf'(<string\s+name="{re.escape(key)}"[^>]*>)(.*?)(</string>)', re.S)
    if pattern.search(text):
        text = pattern.sub(lambda m: m.group(1) + escaped + m.group(3), text, count=1)
    else:
        text = text.replace(
            "</resources>",
            f'    <string name="{key}">{escaped}</string>\n</resources>',
            1,
        )
    path.write_text(text, "utf-8")


set_string(RES / "values/strings.xml", "AGPasscode", "Passcode")
set_string(RES / "values-uk/strings.xml", "AGPasscode", "Код-пароль")
set_string(RES / "values-de/strings.xml", "AGPasscode", "App-Sperrcode")

text = SETTINGS.read_text("utf-8")
text = text.replace("R.string.PasscodeNeko", "R.string.AGPasscode")
SETTINGS.write_text(text, "utf-8")

for path in RES.glob("values*/strings*.xml"):
    text = path.read_text("utf-8", errors="replace")
    for match in re.finditer(r"<string\b([^>]*)>(.*?)</string>", text, re.S):
        attributes, value = match.groups()
        if 'name="AGCreditsText"' in attributes:
            continue
        for forbidden in ("Nagram", "Naigram", "Nekogram", "NekoX", "AyuMoments", "AyuGram"):
            if re.search(re.escape(forbidden), value, re.I):
                raise RuntimeError(f"Visible legacy brand {forbidden} remains in {path}")
        if re.search(r"\bNeko\b", value, re.I):
            raise RuntimeError(f"Visible standalone Neko remains in {path}")

if "R.string.PasscodeNeko" in SETTINGS.read_text("utf-8"):
    raise RuntimeError("Legacy passcode resource remains in AGSettings")

print("VISIBLE BRAND STRING CLEANUP: PASS")
