#!/usr/bin/env python3
"""Allow only the package identifier and Play signing-key exclusion to differ."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_DIFFERENCES = {"gradle.properties", "TMessagesProj/release.keystore"}


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "git command failed")
    return result.stdout


def git_object_exists(ref: str, path: str) -> bool:
    return subprocess.run(
        ["git", "cat-file", "-e", f"{ref}:{path}"],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    ).returncode == 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--main-ref", default="origin/main")
    parser.add_argument("--play-ref", default="origin/play-market")
    args = parser.parse_args()

    changed = {
        line.strip()
        for line in git("diff", "--name-only", args.main_ref, args.play_ref).splitlines()
        if line.strip()
    }
    unexpected = sorted(changed - ALLOWED_DIFFERENCES)
    missing = sorted(ALLOWED_DIFFERENCES - changed)

    failures: list[str] = []
    if unexpected:
        failures.append("Unexpected branch differences: " + ", ".join(unexpected))
    if missing:
        failures.append("Required Main/Play differences are missing: " + ", ".join(missing))

    main_properties = git("show", f"{args.main_ref}:gradle.properties")
    play_properties = git("show", f"{args.play_ref}:gradle.properties")
    if "APP_PACKAGE=top.authorche.authorgram" not in main_properties:
        failures.append("Main package must be top.authorche.authorgram")
    if "APP_PACKAGE=toss.authorgram.apk" not in play_properties:
        failures.append("Play package must be toss.authorgram.apk")

    normalized_main_properties = main_properties.replace(
        "APP_PACKAGE=top.authorche.authorgram", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
    )
    normalized_play_properties = play_properties.replace(
        "APP_PACKAGE=toss.authorgram.apk", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
    )
    if normalized_main_properties != normalized_play_properties:
        failures.append("gradle.properties differs by more than APP_PACKAGE")

    keystore_path = "TMessagesProj/release.keystore"
    if not git_object_exists(args.main_ref, keystore_path):
        failures.append("Main release keystore is missing")
    if git_object_exists(args.play_ref, keystore_path):
        failures.append("Play release keystore must not be tracked")

    if failures:
        print("AuthorGram branch parity failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print("AuthorGram Main/Play parity passed: APP_PACKAGE differs and Play excludes the tracked keystore")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
