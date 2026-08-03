#!/usr/bin/env python3
"""Apply the provided AuthorGram launcher artwork and restore the complete private Spy page."""

from __future__ import annotations

import base64
import binascii
import re
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "TMessagesProj/src/main/res"
SIZE = 512
PAYLOAD_PARTS = tuple(
    ROOT / f"scripts/branding/icon_payload_{index:02d}.txt"
    for index in range(4)
)

ADAPTIVE_ICON = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/authorgram_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_authorgram_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_authorgram_monochrome" />
</adaptive-icon>
"""

FOREGROUND_LAYER = """<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <bitmap
            android:gravity="fill"
            android:src="@drawable/authorgram_launcher_foreground" />
    </item>
</layer-list>
"""

MONOCHROME_ICON = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M16,52 L47,37 L39,68 L33,58 L16,52 M33,58 L47,37 L27,54 Z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="M53,78 L69,29 L85,78 L76,78 L72.5,66 L64.5,66 L61,78 Z M67,58 L70,58 L68.5,51 Z" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M86,37 C92,29 102,35 98,44 C95,50 89,54 86,57 C83,54 77,50 74,44 C70,35 80,29 86,37 Z" />
</vector>
"""

LAUNCHER_COLORS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="authorgram_launcher_background">#101C83</color>
</resources>
"""

REPLACEMENTS = (
    ("ic_launcher_toss_dark_blue_round", "ic_launcher_authorgram_round"),
    ("ic_launcher_toss_round", "ic_launcher_authorgram_round"),
    ("ic_launcher_toss_dark_blue_foreground", "ic_launcher_authorgram_foreground"),
    ("ic_launcher_toss_foreground", "ic_launcher_authorgram_foreground"),
    ("ic_launcher_toss_dark_blue", "ic_launcher_authorgram"),
    ("ic_launcher_toss", "ic_launcher_authorgram"),
    ("ic_launcher_nagram_block_round", "ic_launcher_authorgram_round"),
    ("ic_launcher_nagram", "ic_launcher_authorgram"),
)

OBSOLETE_FILES = (
    "b.sh",
    "TMessagesProj/src/main/ic_launcher_nagram-playstore.png",
    "TMessagesProj/src/main/ic_launcher_nagram_block_round-playstore.png",
    "TMessagesProj/src/main/res/values/ic_launcher_toss_background.xml",
    "TMessagesProj/src/main/res/drawable/ic_launcher_toss_background.xml",
    "TMessagesProj/src/main/res/drawable/ic_launcher_toss_foreground.xml",
    "TMessagesProj/src/main/res/drawable/ic_launcher_toss_dark_blue_foreground.xml",
    "TMessagesProj/src/main/res/mipmap/ic_launcher_authorgram.xml",
    "TMessagesProj/src/main/res/mipmap/ic_launcher_authorgram_round.xml",
)


def read_text(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write_text(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="")


def png_chunk(kind: bytes, data: bytes) -> bytes:
    crc = binascii.crc32(kind)
    crc = binascii.crc32(data, crc) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", crc)


def reconstruct_icon_rgba() -> bytes:
    encoded = "".join(path.read_text(encoding="ascii").strip() for path in PAYLOAD_PARTS)
    packed = zlib.decompress(base64.b64decode(encoded))
    expected = 768 + SIZE * SIZE * 2
    if len(packed) != expected:
        raise RuntimeError(f"Unexpected launcher payload size: {len(packed)} != {expected}")

    palette = packed[:768]
    indices = packed[768 : 768 + SIZE * SIZE]
    alpha = packed[768 + SIZE * SIZE :]
    rgba = bytearray(SIZE * SIZE * 4)
    for pixel, palette_index in enumerate(indices):
        palette_offset = palette_index * 3
        rgba_offset = pixel * 4
        rgba[rgba_offset] = palette[palette_offset]
        rgba[rgba_offset + 1] = palette[palette_offset + 1]
        rgba[rgba_offset + 2] = palette[palette_offset + 2]
        rgba[rgba_offset + 3] = alpha[pixel]
    return bytes(rgba)


def encode_png_rgba(size: int, rgba: bytes) -> bytes:
    stride = size * 4
    scanlines = b"".join(
        b"\x00" + rgba[row * stride : (row + 1) * stride]
        for row in range(size)
    )
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", header)
        + png_chunk(b"IDAT", zlib.compress(scanlines, 9))
        + png_chunk(b"IEND", b"")
    )


def axis_weights(source_size: int, target_size: int) -> list[tuple[tuple[int, float], ...]]:
    scale = source_size / target_size
    result: list[tuple[tuple[int, float], ...]] = []
    for target in range(target_size):
        start = target * scale
        end = (target + 1) * scale
        first = int(start)
        last = min(source_size, int(end) + 1)
        values: list[tuple[int, float]] = []
        for source in range(first, last):
            overlap = max(0.0, min(end, source + 1.0) - max(start, float(source)))
            if overlap > 0:
                values.append((source, overlap / scale))
        result.append(tuple(values))
    return result


def resize_rgba_area(source: bytes, source_size: int, target_size: int) -> bytes:
    if target_size == source_size:
        return source
    x_weights = axis_weights(source_size, target_size)
    y_weights = axis_weights(source_size, target_size)
    output = bytearray(target_size * target_size * 4)
    for target_y, y_values in enumerate(y_weights):
        for target_x, x_values in enumerate(x_weights):
            sums = [0.0, 0.0, 0.0, 0.0]
            for source_y, y_weight in y_values:
                row = source_y * source_size
                for source_x, x_weight in x_values:
                    weight = x_weight * y_weight
                    offset = (row + source_x) * 4
                    sums[0] += source[offset] * weight
                    sums[1] += source[offset + 1] * weight
                    sums[2] += source[offset + 2] * weight
                    sums[3] += source[offset + 3] * weight
            destination = (target_y * target_size + target_x) * 4
            for channel in range(4):
                output[destination + channel] = max(
                    0,
                    min(255, int(sums[channel] + 0.5)),
                )
    return bytes(output)


def circularize_rgba(source: bytes, size: int) -> bytes:
    output = bytearray(source)
    center = (size - 1) / 2.0
    radius = size / 2.0 - 1.0
    for y in range(size):
        for x in range(size):
            distance = ((x - center) ** 2 + (y - center) ** 2) ** 0.5
            coverage = max(0.0, min(1.0, radius + 0.75 - distance))
            alpha_offset = (y * size + x) * 4 + 3
            output[alpha_offset] = int(output[alpha_offset] * coverage + 0.5)
    return bytes(output)

def patch_launcher_references() -> None:
    roots = (
        ROOT / "TMessagesProj/src/main/java",
        ROOT / "TMessagesProj/src/main/kotlin",
        ROOT / "TMessagesProj/src/main/res",
        ROOT / "TMessagesProj/src/release",
    )
    for root in roots:
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".kt", ".xml"}:
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            updated = content
            for old, new in REPLACEMENTS:
                updated = updated.replace(old, new)
            if updated != content:
                path.write_text(updated, encoding="utf-8", newline="")


def install_launcher_icon() -> None:
    patch_launcher_references()

    for relative in OBSOLETE_FILES:
        path = ROOT / relative
        if path.exists():
            path.unlink()

    for directory in RES.glob("mipmap-*"):
        if directory.name == "mipmap-nodpi":
            continue
        for path in directory.glob("ic_launcher_authorgram*.png"):
            path.unlink()

    source_rgba = reconstruct_icon_rgba()
    source_png = encode_png_rgba(SIZE, source_rgba)
    round_png = encode_png_rgba(SIZE, circularize_rgba(source_rgba, SIZE))

    exact_outputs = {
        "TMessagesProj/src/main/ic_launcher_authorgram-playstore.png": source_png,
        "TMessagesProj/src/main/res/mipmap-nodpi/ic_launcher_authorgram.png": source_png,
        "TMessagesProj/src/main/res/mipmap-nodpi/ic_launcher_authorgram_round.png": round_png,
        "TMessagesProj/src/main/res/mipmap-nodpi/authorgram_launcher_artwork.png": source_png,
        "TMessagesProj/src/main/res/drawable-nodpi/authorgram_launcher_foreground.png": source_png,
    }
    for relative, payload in exact_outputs.items():
        path = ROOT / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)

    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in densities.items():
        rgba = resize_rgba_area(source_rgba, SIZE, size)
        square = ROOT / f"TMessagesProj/src/main/res/mipmap-{density}/ic_launcher_authorgram.png"
        rounded = ROOT / f"TMessagesProj/src/main/res/mipmap-{density}/ic_launcher_authorgram_round.png"
        square.parent.mkdir(parents=True, exist_ok=True)
        square.write_bytes(encode_png_rgba(size, rgba))
        rounded.write_bytes(encode_png_rgba(size, circularize_rgba(rgba, size)))

    write_text(
        "TMessagesProj/src/main/res/drawable/ic_launcher_authorgram_foreground.xml",
        FOREGROUND_LAYER,
    )
    write_text(
        "TMessagesProj/src/main/res/drawable/ic_launcher_authorgram_monochrome.xml",
        MONOCHROME_ICON,
    )
    write_text(
        "TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher_authorgram.xml",
        ADAPTIVE_ICON,
    )
    write_text(
        "TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher_authorgram_round.xml",
        ADAPTIVE_ICON,
    )
    write_text(
        "TMessagesProj/src/main/res/values/authorgram_launcher_colors.xml",
        LAUNCHER_COLORS,
    )

def patch_spy_navigation() -> None:
    settings_path = "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java"
    settings = read_text(settings_path)
    old = """        } else if (position == spyRow) {
            presentFragment(new AGPrivacySettingsActivity());
"""
    new = """        } else if (position == spyRow) {
            presentFragment(new AGSpySettingsActivity());
"""
    if old in settings:
        settings = settings.replace(old, new, 1)
    elif "presentFragment(new AGSpySettingsActivity());" not in settings:
        raise RuntimeError("Unable to route the main menu Spy row")
    write_text(settings_path, settings)

    router_path = "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java"
    router = read_text(router_path)
    old_block = """                case "privacy":
                case "security":
                case "spy":
                case "p":
                    if (!isPrivateMainBuild()) {
                        unknown.run();
                        return;
                    }
                    fragment = agxFragment = new AGPrivacySettingsActivity();
                    break;
"""
    new_block = """                case "spy":
                    if (!isPrivateMainBuild()) {
                        unknown.run();
                        return;
                    }
                    fragment = agxFragment = new AGSpySettingsActivity();
                    break;
                case "privacy":
                case "security":
                case "p":
                    if (!isPrivateMainBuild()) {
                        unknown.run();
                        return;
                    }
                    fragment = agxFragment = new AGPrivacySettingsActivity();
                    break;
"""
    if old_block in router:
        router = router.replace(old_block, new_block, 1)
    elif "fragment = agxFragment = new AGSpySettingsActivity();" not in router:
        raise RuntimeError("Unable to add the Spy deep link")

    old_search = """        if (isPrivateMainBuild()) {
            fragments.add(new AGPrivacySettingsActivity());
        }
"""
    new_search = """        if (isPrivateMainBuild()) {
            fragments.add(new AGSpySettingsActivity());
            fragments.add(new AGPrivacySettingsActivity());
        }
"""
    if old_search in router:
        router = router.replace(old_search, new_search, 1)
    elif "fragments.add(new AGSpySettingsActivity());" not in router:
        raise RuntimeError("Unable to add complete Spy settings to search")
    write_text(router_path, router)


def validate() -> None:
    required_files = (
        "TMessagesProj/src/main/res/mipmap-nodpi/ic_launcher_authorgram.png",
        "TMessagesProj/src/main/res/mipmap-nodpi/ic_launcher_authorgram_round.png",
        "TMessagesProj/src/main/res/drawable/ic_launcher_authorgram_foreground.xml",
        "TMessagesProj/src/main/res/drawable/ic_launcher_authorgram_monochrome.xml",
        "TMessagesProj/src/main/res/drawable-nodpi/authorgram_launcher_foreground.png",
        "TMessagesProj/src/main/ic_launcher_authorgram-playstore.png",
        "TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java",
    )
    for relative in required_files:
        if not (ROOT / relative).is_file():
            raise RuntimeError(f"Missing required file: {relative}")

    expected_sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, expected_size in expected_sizes.items():
        for suffix in ("", "_round"):
            path = RES / f"mipmap-{density}/ic_launcher_authorgram{suffix}.png"
            if not path.is_file():
                raise RuntimeError(f"Missing density launcher icon: {path}")
            header = path.read_bytes()[:24]
            if header[:8] != b"\x89PNG\r\n\x1a\n":
                raise RuntimeError(f"Invalid PNG launcher icon: {path}")
            width, height = struct.unpack(">II", header[16:24])
            if (width, height) != (expected_size, expected_size):
                raise RuntimeError(
                    f"Wrong launcher size for {path}: {width}x{height}"
                )

    adaptive = read_text(
        "TMessagesProj/src/main/res/mipmap-anydpi-v26/ic_launcher_authorgram.xml"
    )
    if "<monochrome " not in adaptive:
        raise RuntimeError("Android 13 monochrome launcher resource is missing")

    spy = read_text(
        "TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java"
    )
    for required in (
        "NekoConfig.localPremium",
        "NekoConfig.hideSponsoredMessage",
        "NekoConfig.hideProxySponsorChannel",
        "new GhostModeActivity()",
        "new AGFiltersSettingsActivity()",
        "new AGPrivacySettingsActivity()",
        "getTranslucentDeletedMessages",
        "getDeletedIconStyle",
    ):
        if required not in spy:
            raise RuntimeError(f"Complete Spy content is missing: {required}")

    settings = read_text(
        "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java"
    )
    if "presentFragment(new AGSpySettingsActivity());" not in settings:
        raise RuntimeError("Main settings does not open the complete Spy page")

    router = read_text(
        "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java"
    )
    if "fragment = agxFragment = new AGSpySettingsActivity();" not in router:
        raise RuntimeError("Spy deep link does not open the complete Spy page")

    for relative in (
        "b.sh",
        "TMessagesProj/src/main/ic_launcher_nagram-playstore.png",
        "TMessagesProj/src/main/ic_launcher_nagram_block_round-playstore.png",
    ):
        if (ROOT / relative).exists():
            raise RuntimeError(f"Obsolete prior-app artifact remains: {relative}")

    forbidden = re.compile(r"ic_launcher_(?:toss|nagram)", re.IGNORECASE)
    hits: list[str] = []
    for root in (
        ROOT / "TMessagesProj/src/main/java",
        ROOT / "TMessagesProj/src/main/kotlin",
        ROOT / "TMessagesProj/src/main/res",
        ROOT / "TMessagesProj/src/release",
    ):
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".kt", ".xml"}:
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            if forbidden.search(content):
                hits.append(str(path.relative_to(ROOT)))
    if hits:
        raise RuntimeError("Legacy launcher references remain: " + ", ".join(hits[:20]))


def main() -> None:
    patch_spy_navigation()
    install_launcher_icon()
    validate()
    print("Exact AuthorGram icon and complete private Spy page applied.")


if __name__ == "__main__":
    main()
