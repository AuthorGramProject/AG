#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_V9_DEFERRED_DISMISS_CLEANUP"


def validate(text: str) -> None:
    # Visual geometry must stay exactly on the currently accepted V8 layout.
    required = (
        MARKER,
        "private static final int SCREEN_EDGE_DP = 12;",
        "private static final int PREVIEW_TOP_GAP_DP = 10;",
        "private static final int PREVIEW_MENU_GAP_DP = 12;",
        "private static final int PREVIEW_EXTRA_WIDTH_DP = 24;",
        "private static final int AVATAR_SIZE_DP = 36;",
        "private static final int AVATAR_GAP_DP = 8;",
        "private static final int SENDER_NAME_HEIGHT_DP = 20;",
        "private static final int SENDER_NAME_BOTTOM_GAP_DP = 4;",
        "private static final int MIN_PREVIEW_VIEWPORT_DP = 104;",
        "private static final int MAX_PREVIEW_VIEWPORT_DP = 260;",
        "private static final int MIN_MENU_VIEWPORT_DP = 150;",
        "private static final int MAX_MENU_VIEWPORT_DP = 220;",
        "private static final int BACKGROUND_DOWNSCALE = 12;",
        "private static final int BACKGROUND_BLUR_RADIUS = 15;",
        "Color.argb(52, 0, 0, 0)",
        "scrimContainer.addView(previewHost, menuIndex, params);",
        "private void reflowPreviewAndMenu()",
        "private void constrainMenuScroll(ScrollView menuScroll, int menuViewportLimit)",
        "private void alignPreviewWithMenu()",
        "private static final int MAX_LAYOUT_RETRY_COUNT = 4;",
        # Dismiss lifecycle invariants.
        "protected void onDetachedFromWindow()",
        "rootView.postOnAnimation(releaseResourcesRunnable);",
        "rootView.postDelayed(recycleBitmapsRunnable, 64L);",
        "private void releaseResources()",
        "private void recycleBitmaps()",
        "scrimContainer.removeCallbacks(attachLayoutRetryRunnable);",
        "scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"iOS message menu validation failed: missing {needle!r}")

    # These were the dangerous teardown paths that could mutate/recycle native
    # popup resources while Telegram was in its own dismiss traversal.
    forbidden = (
        "scrimContainer.addOnAttachStateChangeListener",
        "protected void onWindowVisibilityChanged(int visibility)",
        "((ViewGroup) previewHost.getParent()).removeView(previewHost);",
        "scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);",
        "scrimContainer.post(this::reflowPreviewAndMenu);",
        "AUTHORGRAM_IOS_MESSAGE_MENU_SAFE_LIFECYCLE_V9",
    )
    for needle in forbidden:
        if needle in text:
            raise SystemExit(f"iOS message menu validation failed: forbidden legacy path {needle!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.parse_args()

    if not TARGET.is_file():
        raise SystemExit(f"missing target: {TARGET}")

    validate(TARGET.read_text(encoding="utf-8"))
    print("AuthorGram iOS message menu visuals + deferred dismiss cleanup validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
