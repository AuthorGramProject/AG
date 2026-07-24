from pathlib import Path
import re

ROOT = Path.cwd()
JAVA_ROOT = ROOT / "TMessagesProj/src/main/java"
LAUNCH = JAVA_ROOT / "org/telegram/ui/LaunchActivity.java"
ROUTER = JAVA_ROOT / "toss/authorgram/settings/AGSettingsRouter.java"

# Rename all side-menu identifiers, not only the visible label.
for path in JAVA_ROOT.rglob("*.java"):
    text = path.read_text("utf-8", errors="replace")
    updated = re.sub(r"\bnkbtn", "agbtn", text)
    if updated != text:
        path.write_text(updated, "utf-8")

# Replace the old settings URI path and branded legacy URI alias.
text = LAUNCH.read_text("utf-8")
text = text.replace('path.startsWith("nasettings/")', 'path.startsWith("agsettings/")')
text = text.replace('url.startsWith("tg:neko") || url.startsWith("tg://neko")',
                    'url.startsWith("tg:agsettings") || url.startsWith("tg://agsettings")')
text = text.replace(
    'url.replace("tg:neko", "tg://t.me/nasettings").replace("tg://neko", "tg://t.me/nasettings")',
    'url.replace("tg:agsettings", "tg://t.me/agsettings").replace("tg://agsettings", "tg://t.me/agsettings")'
)
LAUNCH.write_text(text, "utf-8")

# Remove the last Ayu-specific deep-link name from the public router.
text = ROUTER.read_text("utf-8")
text = text.replace(
    '''                case "ayuspy":
                case "spy":
                    fragment = agxFragment = new AGPrivacySettingsActivity();
                    break;
''',
    '''                case "privacy":
                case "security":
                case "p":
                    fragment = agxFragment = new AGPrivacySettingsActivity();
                    break;
'''
)
text = re.sub(r"(?m)^import toss\.authorgram\.settings\.[A-Za-z0-9_]+;\n", "", text)
text = text.replace("String n_title", "String agTitle")
text = text.replace("n_title, f_title", "agTitle, f_title")
ROUTER.write_text(text, "utf-8")

# Assertions cover the public navigation surface.
launch_text = LAUNCH.read_text("utf-8")
router_text = ROUTER.read_text("utf-8")
if "nasettings" in launch_text or "tg:neko" in launch_text:
    raise RuntimeError("Legacy settings deep link remains in LaunchActivity")
if 'path.startsWith("agsettings/")' not in launch_text:
    raise RuntimeError("AGSettings path routing is missing")
if "ayuspy" in router_text:
    raise RuntimeError("Ayu-specific settings route remains")

for path in JAVA_ROOT.rglob("*.java"):
    text = path.read_text("utf-8", errors="replace")
    if "nkbtn" in text:
        raise RuntimeError(f"Legacy drawer identifier remains in {path.relative_to(ROOT)}")

print("AGSETTINGS ROUTE AND DRAWER PATCH: PASS")
