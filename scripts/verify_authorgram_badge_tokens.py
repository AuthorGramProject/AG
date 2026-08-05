#!/usr/bin/env python3
"""Verify every protected AuthorGram badge token without exposing IDs in APK code."""

from __future__ import annotations

import hashlib
import hmac
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = ROOT / (
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/"
    "AuthorGramAuthorBadge.java"
)

# Build-time only: scripts are not packaged into either APK.
EXPECTED_IDS = (
    6316376597,
    2021861896,
    2815463434,
    6802848305,
    6822670748,
    8470484374,
    8154455619,
    7913929703,
    8856346711,
    8357439344,
    8548193112,
    8395237407,
    8925149503,
    3781500049,
    4297907963,
)


def extract_block(source: str, name: str) -> str:
    match = re.search(
        rf"private static final (?:byte|long)\[\] {re.escape(name)} = \{{(.*?)\n    \}};",
        source,
        re.DOTALL,
    )
    if match is None:
        raise SystemExit(f"Unable to locate {name} in AuthorGramAuthorBadge.java")
    return match.group(1)


def parse_key_part(source: str, name: str) -> bytes:
    block = extract_block(source, name)
    values = [int(item, 16) for item in re.findall(r"0x([0-9a-fA-F]{2})", block)]
    if len(values) != 32:
        raise SystemExit(f"{name} must contain exactly 32 bytes, found {len(values)}")
    return bytes(values)


def parse_tokens(source: str) -> tuple[tuple[int, int], ...]:
    block = extract_block(source, "ALLOWED_TOKENS")
    values = [int(item, 16) for item in re.findall(r"0x([0-9a-fA-F]{16})L", block)]
    if len(values) % 2 != 0:
        raise SystemExit("ALLOWED_TOKENS must contain high/low pairs")
    return tuple(zip(values[0::2], values[1::2]))


def token_for(key: bytes, peer_id: int) -> tuple[int, int]:
    digest = hmac.new(key, struct.pack(">Q", peer_id), hashlib.sha256).digest()
    return struct.unpack(">QQ", digest[:16])


def main() -> None:
    source = SOURCE_PATH.read_text(encoding="utf-8")
    if "Math.abs(objectId)" not in source or "objectId == Long.MIN_VALUE" not in source:
        raise SystemExit("Telegram negative dialog IDs are not normalized safely")

    leaked = [str(peer_id) for peer_id in EXPECTED_IDS if str(peer_id) in source]
    if leaked:
        raise SystemExit(f"Raw protected IDs leaked into badge application source: {leaked}")

    part_a = parse_key_part(source, "KEY_PART_A")
    part_b = parse_key_part(source, "KEY_PART_B")
    key = bytes(a ^ b for a, b in zip(part_a, part_b))
    actual_tokens = parse_tokens(source)

    if len(actual_tokens) != len(EXPECTED_IDS):
        raise SystemExit(
            f"Expected {len(EXPECTED_IDS)} protected token pairs, found {len(actual_tokens)}"
        )

    expected_tokens = tuple(token_for(key, peer_id) for peer_id in EXPECTED_IDS)
    missing = [
        peer_id
        for peer_id, token in zip(EXPECTED_IDS, expected_tokens)
        if token not in actual_tokens
    ]
    unexpected = [token for token in actual_tokens if token not in expected_tokens]
    if missing or unexpected:
        raise SystemExit(
            "Protected author_badge token mismatch: "
            f"missing IDs={missing}, unexpected token pairs={len(unexpected)}"
        )

    if len(set(actual_tokens)) != len(actual_tokens):
        raise SystemExit("Duplicate protected author_badge token pair detected")

    print(
        "Protected author_badge token self-test passed: "
        f"{len(EXPECTED_IDS)} peer IDs, positive and negative Telegram dialog forms"
    )


if __name__ == "__main__":
    main()
