#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
path = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
lines = path.read_text(encoding="utf-8").splitlines()

tokens = (
    "AyuConstants.OPTION_HISTORY",
    "AyuConstants.OPTION_TTL_SAVE",
    "AyuConstants.OPTION_TTL",
    "AyuConstants.OPTION_READ_MESSAGE",
    "AuthorGramSpyPolicy",
    "GhostReadMessage",
)

found_any = False
for token in tokens:
    hits = [i for i, line in enumerate(lines) if token in line]
    print(f"\n===== {token}: {len(hits)} occurrence(s) =====")
    for i in hits:
        found_any = True
        start = max(0, i - 10)
        end = min(len(lines), i + 20)
        print(f"\n--- line {i + 1} ---")
        for j in range(start, end):
            print(f"{j + 1:06d}: {lines[j]}")

if not found_any:
    print("No dev-only option handlers found in ChatActivity")
raise SystemExit(1 if found_any else 0)
