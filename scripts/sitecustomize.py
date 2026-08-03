"""One-time AuthorGram release bootstrap for GitHub Actions Python processes."""

from __future__ import annotations

import os
from pathlib import Path


def _bootstrap_release() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return
    run_id = os.environ.get("GITHUB_RUN_ID", "unknown")
    runner_temp = Path(os.environ.get("RUNNER_TEMP", "/tmp"))
    marker = runner_temp / f"authorgram-release-bootstrap-{run_id}.done"
    if marker.exists():
        return

    # Mark before executing to prevent recursive startup if a helper launches a
    # child Python process. A failing helper removes the marker for a clean retry.
    marker.write_text("running\n", encoding="utf-8")
    try:
        import cleanup_authorgram_actions
        import fix_authorgram_spy_compile

        cleanup_authorgram_actions.main()
        fix_authorgram_spy_compile.main()
        marker.write_text("complete\n", encoding="utf-8")
    except BaseException:
        marker.unlink(missing_ok=True)
        raise


_bootstrap_release()
