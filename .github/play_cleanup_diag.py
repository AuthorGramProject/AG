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

result = subprocess.run(["python3", "scripts/strip_authorgram_play_runtime.py"], cwd=ROOT)

chat = (ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java").read_text(encoding="utf-8").splitlines()
for i, line in enumerate(chat):
    if "AuthorGramSpyPolicy.isSpyDisabled" in line:
        start = max(0, i - 12)
        end = min(len(chat), i + 55)
        print("\n===== ChatActivity TTL-save dev block =====")
        for j in range(start, end):
            print(f"{j + 1:06d}: {chat[j]}")
        break
else:
    print("AuthorGramSpyPolicy consumer not found")

raise SystemExit(result.returncode or 1)
