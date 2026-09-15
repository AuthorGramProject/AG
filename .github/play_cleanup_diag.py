#!/usr/bin/env python3
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

subprocess.run([
    "git", "fetch", "origin",
    "backup/play-market-before-clean-rollback-20260915:refs/remotes/origin/play-cleanup-backup",
], cwd=ROOT, check=True)
subprocess.run([
    "git", "checkout", "origin/play-cleanup-backup", "--",
    "scripts/play_stubs", "scripts/strip_authorgram_play_runtime.py",
], cwd=ROOT, check=True)

# The sanitizer deliberately fails on any still-live Play consumer. We want its
# source transformations in this disposable runner so we can inspect those consumers.
result = subprocess.run(["python3", "scripts/strip_authorgram_play_runtime.py"], cwd=ROOT)

checks = {
    "TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java": [
        "NekoConfig.unlimitedPinnedDialogs",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java": [
        "hideSponsoredMessage",
        "ignoreContentRestrictions",
        "NekoConfig.localPremium",
        "NekoConfig.unlimitedPinnedDialogs",
    ],
    "TMessagesProj/src/main/java/org/telegram/messenger/MediaDataController.java": [
        "NekoConfig.unlimitedFavedStickers",
    ],
}

for relative, needles in checks.items():
    path = ROOT / relative
    lines = path.read_text(encoding="utf-8").splitlines()
    print(f"\n===== {relative} =====")
    for needle in needles:
        found = False
        for i, line in enumerate(lines):
            if needle not in line:
                continue
            found = True
            start = max(0, i - 8)
            end = min(len(lines), i + 9)
            print(f"\n--- {needle} @ line {i + 1} ---")
            for j in range(start, end):
                print(f"{j + 1:06d}: {lines[j]}")
        if not found:
            print(f"\n--- {needle}: NOT FOUND ---")

raise SystemExit(result.returncode or 1)
