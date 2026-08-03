#!/usr/bin/env python3
"""Validate the complete AuthorGram adaptive and legacy launcher resource graph."""

from pathlib import Path
import struct
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "TMessagesProj/src/main/res"
FOREGROUND = RES / "drawable/ic_launcher_authorgram_foreground.xml"
MONOCHROME = RES / "drawable/ic_launcher_authorgram_monochrome.xml"
ADAPTIVE = RES / "mipmap-anydpi-v26/ic_launcher_authorgram.xml"
PLAY_STORE = ROOT / "TMessagesProj/src/main/ic_launcher_authorgram-playstore.png"

subprocess.run(
    [sys.executable, str(ROOT / "scripts/fix_authorgram_spy_compile.py")],
    cwd=ROOT,
    check=True,
)


def png_size(path: Path) -> tuple[int, int]:
    header = path.read_bytes()[:24]
    if header[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"Invalid PNG: {path}")
    return struct.unpack(">II", header[16:24])


required_sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
for density, expected in required_sizes.items():
    for suffix in ("", "_round"):
        path = RES / f"mipmap-{density}/ic_launcher_authorgram{suffix}.png"
        if not path.is_file():
            raise SystemExit(f"Missing launcher variant: {path}")
        if png_size(path) != (expected, expected):
            raise SystemExit(
                f"Wrong launcher dimensions for {path}: {png_size(path)}"
            )

if not PLAY_STORE.is_file() or png_size(PLAY_STORE) != (512, 512):
    raise SystemExit("The 512x512 AuthorGram store artwork is missing")

foreground = FOREGROUND.read_text(encoding="utf-8")
if "@drawable/authorgram_launcher_foreground" not in foreground:
    raise SystemExit("Adaptive launcher foreground bitmap is not wired correctly")

adaptive = ADAPTIVE.read_text(encoding="utf-8")
if "<monochrome " not in adaptive or not MONOCHROME.is_file():
    raise SystemExit("Android 13 monochrome AuthorGram icon is missing")

print("AuthorGram adaptive, monochrome, round and density launcher resources validated.")
