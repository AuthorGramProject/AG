#!/usr/bin/env python3
"""Separate the adaptive foreground bitmap from the launcher resource name."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "TMessagesProj/src/main/res"
SOURCE = RES / "mipmap-nodpi/ic_launcher_authorgram.png"
ARTWORK = RES / "mipmap-nodpi/authorgram_launcher_artwork.png"
FOREGROUND = RES / "drawable/ic_launcher_authorgram_foreground.xml"

if not SOURCE.is_file():
    raise SystemExit(f"Missing launcher bitmap: {SOURCE}")

ARTWORK.write_bytes(SOURCE.read_bytes())
FOREGROUND.write_text(
    """<?xml version="1.0" encoding="utf-8"?>
<inset xmlns:android="http://schemas.android.com/apk/res/android"
    android:drawable="@mipmap/authorgram_launcher_artwork"
    android:insetLeft="10dp"
    android:insetTop="10dp"
    android:insetRight="10dp"
    android:insetBottom="10dp" />
""",
    encoding="utf-8",
    newline="",
)

content = FOREGROUND.read_text(encoding="utf-8")
if "@mipmap/ic_launcher_authorgram" in content:
    raise SystemExit("Adaptive launcher foreground still references itself")
if "@mipmap/authorgram_launcher_artwork" not in content:
    raise SystemExit("Adaptive launcher artwork reference is missing")

print("AuthorGram adaptive launcher resource graph validated.")
