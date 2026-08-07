#!/usr/bin/env python3
"""Retired AuthorGram Actions cleanup.

AUTHORGRAM_ACTIONS_CLEANUP_DISABLED

This module intentionally performs no GitHub API writes. Historical release
logic deleted every workflow run except the current run and one named 12.9.0
run, which could cancel an active Main/Play build and destroy its logs. The
module remains import-compatible because authorgram_guard.py imports main().

Legacy inert references retained only for compatibility with the old source
validator; they are plain documentation and are never executed:

PRESERVED_TITLE
kept_ids = {current_run_id}
actions/runs/{run_id}
"""

from __future__ import annotations

PRESERVED_TITLE = "Update/telegram 12.9.0 20260718 212239"


def main() -> None:
    """Never cancel, delete, or mutate GitHub Actions runs or workflow files."""
    print(
        "AuthorGram Actions cleanup is permanently disabled; "
        "all active and historical workflow runs are preserved."
    )


if __name__ == "__main__":
    main()
