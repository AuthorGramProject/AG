#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
BASE_MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_V9_DEFERRED_DISMISS_CLEANUP"
MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_V10_FIRST_FRAME_READY"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def validate(text: str) -> None:
    required = (
        MARKER,
        "private static final int SCREEN_EDGE_DP = 12;",
        "private static final int PREVIEW_TOP_GAP_DP = 10;",
        "private static final int PREVIEW_MENU_GAP_DP = 12;",
        "private static final int STACK_VERTICAL_OFFSET_DP = 10;",
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
        # Current accepted visual composition.
        "scrimContainer.addView(previewHost, menuIndex, params);",
        "private void reflowPreviewAndMenu()",
        "private void constrainMenuScroll(ScrollView menuScroll, int menuViewportLimit)",
        "private void alignPreviewWithMenu()",
        "private static final int MAX_LAYOUT_RETRY_COUNT = 4;",
        # First-frame gate: never expose the oversized intermediate popup.
        "scrimContainer.setAlpha(0.0f);",
        "scrimContainer.setTranslationY(AndroidUtilities.dp(STACK_VERTICAL_OFFSET_DP));",
        "scrimContainer.postDelayed(firstFrameRevealFallbackRunnable, 120L);",
        "private void revealPreparedPopup()",
        "scrimContainer.setAlpha(1.0f);",
        # Stable dismiss lifecycle from V9 must remain intact.
        "protected void onDetachedFromWindow()",
        "rootView.postOnAnimation(releaseResourcesRunnable);",
        "rootView.postDelayed(recycleBitmapsRunnable, 64L);",
        "private void releaseResources()",
        "private void recycleBitmaps()",
        "scrimContainer.removeCallbacks(attachLayoutRetryRunnable);",
        "scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);",
        "scrimContainer.removeCallbacks(firstFrameRevealFallbackRunnable);",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"iOS message menu validation failed: missing {needle!r}")

    forbidden = (
        "scrimContainer.addOnAttachStateChangeListener",
        "protected void onWindowVisibilityChanged(int visibility)",
        "((ViewGroup) previewHost.getParent()).removeView(previewHost);",
        "scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);",
        "scrimContainer.post(this::reflowPreviewAndMenu);",
        "AUTHORGRAM_IOS_MESSAGE_MENU_SAFE_LIFECYCLE_V9",
        "post(() -> {\n            if (!isUsable())",
    )
    for needle in forbidden:
        if needle in text:
            raise SystemExit(f"iOS message menu validation failed: forbidden legacy path {needle!r}")


def patch(text: str) -> str:
    if MARKER in text:
        validate(text)
        return text
    if BASE_MARKER not in text:
        raise SystemExit("unexpected iOS message menu baseline: expected V9 or V10")

    # Upgrade both the source marker comment and View tag.
    text = text.replace(BASE_MARKER, MARKER)

    text = replace_once(
        text,
        "    private static final int PREVIEW_MENU_GAP_DP = 12;\n",
        "    private static final int PREVIEW_MENU_GAP_DP = 12;\n"
        "    private static final int STACK_VERTICAL_OFFSET_DP = 10;\n",
        "vertical stack offset",
    )

    text = replace_once(
        text,
        """    private boolean cleanedUp;
    private boolean resourcesReleased;
    private boolean bitmapsRecycled;
""",
        """    private boolean cleanedUp;
    private boolean resourcesReleased;
    private boolean bitmapsRecycled;
    private boolean firstFrameGateActive;
""",
        "first-frame state",
    )

    text = replace_once(
        text,
        """    private final Runnable releaseResourcesRunnable = this::releaseResources;
    private final Runnable recycleBitmapsRunnable = this::recycleBitmaps;
""",
        """    private final Runnable releaseResourcesRunnable = this::releaseResources;
    private final Runnable recycleBitmapsRunnable = this::recycleBitmaps;
    private final Runnable firstFrameRevealFallbackRunnable = this::revealPreparedPopup;
""",
        "first-frame fallback runnable",
    )

    text = replace_once(
        text,
        """    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(() -> {
            if (!isUsable()) {
                return;
            }

            removeLegacyTopGap();
            attachPreviewBetweenReactionsAndMenu();
        });
    }
""",
        """    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!isUsable()) {
            return;
        }

        // Do not expose Telegram's first oversized/unfinished layout pass.
        // The same V8/V9 geometry is prepared while the popup stack is fully
        // transparent, then revealed as soon as the preview is aligned.
        scrimContainer = findScrimAncestor();
        if (scrimContainer != null) {
            scrimContainer.setAlpha(0.0f);
            scrimContainer.setTranslationY(AndroidUtilities.dp(STACK_VERTICAL_OFFSET_DP));
            firstFrameGateActive = true;
            scrimContainer.removeCallbacks(firstFrameRevealFallbackRunnable);
            scrimContainer.postDelayed(firstFrameRevealFallbackRunnable, 120L);
        }

        removeLegacyTopGap();
        attachPreviewBetweenReactionsAndMenu();
    }
""",
        "synchronous first-frame preparation",
    )

    text = replace_once(
        text,
        """        if (!previewRevealed) {
            previewScrollView.scrollTo(0, 0);
            previewHost.setVisibility(VISIBLE);
            previewRevealed = true;
        }
    }

    private static String resolveSenderName(TLObject senderPeer) {
""",
        """        if (!previewRevealed) {
            previewScrollView.scrollTo(0, 0);
            previewHost.setVisibility(VISIBLE);
            previewRevealed = true;
        }
        revealPreparedPopup();
    }

    private void revealPreparedPopup() {
        if (!firstFrameGateActive) {
            return;
        }
        firstFrameGateActive = false;
        if (scrimContainer != null) {
            scrimContainer.removeCallbacks(firstFrameRevealFallbackRunnable);
            if (!cleanedUp && scrimContainer.isAttachedToWindow()) {
                scrimContainer.setAlpha(1.0f);
            }
        }
    }

    private static String resolveSenderName(TLObject senderPeer) {
""",
        "first-frame reveal",
    )

    text = replace_once(
        text,
        """        if (scrimContainer != null) {
            scrimContainer.removeCallbacks(attachLayoutRetryRunnable);
            scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);
        }
        removeCallbacks(attachLayoutRetryRunnable);
""",
        """        if (scrimContainer != null) {
            scrimContainer.removeCallbacks(attachLayoutRetryRunnable);
            scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);
            scrimContainer.removeCallbacks(firstFrameRevealFallbackRunnable);
        }
        firstFrameGateActive = false;
        removeCallbacks(attachLayoutRetryRunnable);
""",
        "first-frame cleanup",
    )

    validate(text)
    return text


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    if not TARGET.is_file():
        raise SystemExit(f"missing target: {TARGET}")

    original = TARGET.read_text(encoding="utf-8")
    updated = patch(original)

    if args.check:
        if updated != original:
            raise SystemExit("iOS message menu V10 first-frame fix is not applied")
        print("AuthorGram iOS message menu V10 first-frame + dismiss lifecycle validated")
        return 0

    if updated != original:
        TARGET.write_text(updated, encoding="utf-8")
        print("AuthorGram iOS message menu upgraded to V10 first-frame-ready layout")
    else:
        print("AuthorGram iOS message menu V10 already applied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
