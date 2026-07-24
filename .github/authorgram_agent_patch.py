from pathlib import Path

path = Path("TMessagesProj/build.gradle")
text = path.read_text(encoding="utf-8")
old = "            applicationIdSuffix '.debug'\n"
if text.count(old) != 1:
    raise RuntimeError(f"Expected one debug applicationIdSuffix, found {text.count(old)}")
path.write_text(text.replace(old, "", 1), encoding="utf-8")
print("Removed debug applicationIdSuffix to preserve Firebase package compatibility")
