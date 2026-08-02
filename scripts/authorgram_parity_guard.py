#!/usr/bin/env python3
"""Verify that Main and Play differ only in package, artifact label, and tracked keystore."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED_DIFFERENCES = {
    "gradle.properties",
    "TMessagesProj/build.gradle",
    "TMessagesProj/release.keystore",
}


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
        failures.append("Expected controlled branch differences are missing: " + ", ".join(missing))

    main_properties = git("show", f"{args.main_ref}:gradle.properties")
    play_properties = git("show", f"{args.play_ref}:gradle.properties")
    if "APP_PACKAGE=fork.risin42.nagramx" not in main_properties:
        failures.append("Main package must be fork.risin42.nagramx")
    if "APP_PACKAGE=toss.authorgram.apk" not in play_properties:
        failures.append("Play package must be toss.authorgram.apk")

    normalized_main_properties = main_properties.replace(
        "APP_PACKAGE=fork.risin42.nagramx", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
    )
    normalized_play_properties = play_properties.replace(
        "APP_PACKAGE=toss.authorgram.apk", "APP_PACKAGE=AUTHORGRAM_PACKAGE"
    )
    if normalized_main_properties != normalized_play_properties:
        failures.append("gradle.properties differs by more than APP_PACKAGE")

    main_build = git("show", f"{args.main_ref}:TMessagesProj/build.gradle")
    play_build = git("show", f"{args.play_ref}:TMessagesProj/build.gradle")
    if "String gramName = 'AuthorGram-Main'" not in main_build:
        failures.append("Main artifact label must be AuthorGram-Main")
    if "String gramName = 'AuthorGram-Play'" not in play_build:
        failures.append("Play artifact label must be AuthorGram-Play")

    normalized_main_build = main_build.replace(
        "String gramName = 'AuthorGram-Main'", "String gramName = 'AuthorGram-ROLE'"
    )
    normalized_play_build = play_build.replace(
        "String gramName = 'AuthorGram-Play'", "String gramName = 'AuthorGram-ROLE'"
    )
    if normalized_main_build != normalized_play_build:
        failures.append("TMessagesProj/build.gradle differs by more than the artifact label")

    if failures:
        print("AuthorGram branch parity failed:", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    print(
        "AuthorGram Main/Play parity passed: only package, artifact label, and keystore differ"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
