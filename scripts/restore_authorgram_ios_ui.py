#!/usr/bin/env python3
"""Compatibility entry point for the corrected AuthorGram iOS UI restoration."""

from restore_authorgram_ios_ui_v2 import main


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"AuthorGram iOS UI restoration failed:\n{exc}")
        raise SystemExit(1)
