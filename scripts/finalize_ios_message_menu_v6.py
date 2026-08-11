#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
BASE_MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_V9_DEFERRED_DISMISS_CLEANUP"
MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_V11_PREDRAW_ATOMIC_LAYOUT"
BAD_V10_MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_V10_FIRST_FRAME_READY"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def validate(text: str) -> None:
    required = (
        MARKER,
        "import android.view.ViewTreeObserver;",
        "private static final int STACK_TOP_OFFSET_DP = 12;",
        "private static final int MAX_FIRST_FRAME_PREDRAW_ATTEMPTS = 4;",
        "private void installFirstFrameGate()",
        "private void preparePreviewForFirstFrame()",
        "private void removeFirstFrameGate()",
        "private void finishDeferredFirstFramePreparation()",
        "firstFrameObserver.addOnPreDrawListener(firstFramePreDrawListener);",
        "if (previewRevealed)",
        "return false;",
        "private void ensureStackTopSpacer()",
        "AUTHORGRAM_IOS_MESSAGE_STACK_TOP_SPACER",
        "scrimContainer.addView(spacer, 0, params);",
        "private static final int SCREEN_EDGE_DP = 12;",
        "private static final int PREVIEW_TOP_GAP_DP = 10;",
        "private static final int PREVIEW_MENU_GAP_DP = 12;",
        "private static final int PREVIEW_EXTRA_WIDTH_DP = 24;",
        "private static final int AVATAR_SIZE_DP = 36;",
        "private static final int AVATAR_GAP_DP = 8;",
        "private static final int MIN_PREVIEW_VIEWPORT_DP = 104;",
        "private static final int MAX_PREVIEW_VIEWPORT_DP = 260;",
        "private static final int MIN_MENU_VIEWPORT_DP = 150;",
        "private static final int MAX_MENU_VIEWPORT_DP = 220;",
        "private static final int MAX_LAYOUT_RETRY_COUNT = 4;",
        "scrimContainer.addView(previewHost, menuIndex, params);",
        "private void reflowPreviewAndMenu()",
        "private void constrainMenuScroll(ScrollView menuScroll, int menuViewportLimit)",
        "private void alignPreviewWithMenu()",
        "protected void onDetachedFromWindow()",
        "removeFirstFrameGate();",
        "rootView.postOnAnimation(releaseResourcesRunnable);",
        "rootView.postDelayed(recycleBitmapsRunnable, 64L);",
        "private void releaseResources()",
        "private void recycleBitmaps()",
        "scrimContainer.removeCallbacks(attachLayoutRetryRunnable);",
        "scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"iOS message menu V11 validation failed: missing {needle!r}")

    forbidden = (
        BAD_V10_MARKER,
        "scrimContainer.setAlpha(0.0f)",
        "scrimContainer.setAlpha(1.0f)",
        "scrimContainer.setTranslationY(",
        "firstFrameRevealFallbackRunnable",
        "scrimContainer.addOnAttachStateChangeListener",
        "protected void onWindowVisibilityChanged(int visibility)",
        "((ViewGroup) previewHost.getParent()).removeView(previewHost);",
        "scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);",
        "scrimContainer.post(this::reflowPreviewAndMenu);",
        "AUTHORGRAM_IOS_MESSAGE_MENU_SAFE_LIFECYCLE_V9",
    )
    for needle in forbidden:
        if needle in text:
            raise SystemExit(f"iOS message menu V11 validation failed: forbidden path {needle!r}")


def patch(text: str) -> str:
    if MARKER in text:
        validate(text)
        return text
    if BAD_V10_MARKER in text:
        raise SystemExit("Refusing unsafe V10 baseline; restore stable V9 first")
    if BASE_MARKER not in text:
        raise SystemExit("Unexpected iOS message menu baseline: expected stable V9 or V11")

    text = text.replace(BASE_MARKER, MARKER, 1)

    text = replace_once(
        text,
        "import android.view.ViewGroup;\nimport android.view.ViewParent;\n",
        "import android.view.ViewGroup;\nimport android.view.ViewParent;\nimport android.view.ViewTreeObserver;\n",
        "ViewTreeObserver import",
    )

    text = replace_once(
        text,
        """ * Visual/layout behavior is kept identical to V8. The only change is teardown:
 * Telegram's popup/scrim hierarchy is never synchronously mutated while it is
 * detaching. The root blur layer and bitmaps are released after the native popup
 * has yielded the UI thread, preventing the post-dismiss application freeze.
""",
        """ * V11 keeps the stable V9 deferred-dismiss invariant, but makes presentation
 * atomic for the human eye. The first popup draw is held by a bounded pre-draw
 * gate until the AuthorGram preview has joined the already-laid-out Telegram
 * hierarchy. Telegram's native scrim is never hidden, translated or detached.
 * A normal 12dp layout spacer lowers reactions, preview and menu as one stack.
""",
        "V11 class comment",
    )

    text = replace_once(
        text,
        "    private static final int PREVIEW_MENU_GAP_DP = 12;\n",
        "    private static final int PREVIEW_MENU_GAP_DP = 12;\n"
        "    private static final int STACK_TOP_OFFSET_DP = 12;\n",
        "stack top offset",
    )

    text = replace_once(
        text,
        "    private static final int MAX_LAYOUT_RETRY_COUNT = 4;\n",
        "    private static final int MAX_LAYOUT_RETRY_COUNT = 4;\n"
        "    private static final int MAX_FIRST_FRAME_PREDRAW_ATTEMPTS = 4;\n",
        "first-frame attempt bound",
    )

    text = replace_once(
        text,
        """    private boolean cleanedUp;
    private boolean resourcesReleased;
    private boolean bitmapsRecycled;

    // V8 visual layout retry guard retained unchanged.
""",
        """    private boolean cleanedUp;
    private boolean resourcesReleased;
    private boolean bitmapsRecycled;
    private int firstFramePreDrawAttempts;
    private ViewTreeObserver firstFrameObserver;
    private ViewTreeObserver.OnPreDrawListener firstFramePreDrawListener;
    private View stackTopSpacer;

    // V8 visual layout retry guard retained unchanged.
""",
        "first-frame state",
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
        installFirstFrameGate();
    }

    /**
     * Prevent Telegram's native popup-only frame from ever reaching the display.
     * We do not hide or translate Telegram views. Instead, the shared ViewTree
     * draw is held for at most four pre-draw passes while the already-created
     * popup hierarchy receives the AuthorGram preview and final geometry.
     */
    private void installFirstFrameGate() {
        if (!isUsable() || firstFramePreDrawListener != null) {
            return;
        }
        firstFramePreDrawAttempts = 0;
        firstFrameObserver = getViewTreeObserver();
        firstFramePreDrawListener = () -> {
            if (!isUsable() || cleanedUp) {
                removeFirstFrameGate();
                return true;
            }

            firstFramePreDrawAttempts++;
            preparePreviewForFirstFrame();
            if (previewRevealed) {
                removeFirstFrameGate();
                return true;
            }

            if (firstFramePreDrawAttempts >= MAX_FIRST_FRAME_PREDRAW_ATTEMPTS) {
                removeFirstFrameGate();
                post(this::finishDeferredFirstFramePreparation);
                return true;
            }
            return false;
        };

        if (firstFrameObserver != null && firstFrameObserver.isAlive()) {
            firstFrameObserver.addOnPreDrawListener(firstFramePreDrawListener);
        } else {
            firstFrameObserver = null;
            firstFramePreDrawListener = null;
            post(this::finishDeferredFirstFramePreparation);
        }
    }

    private void preparePreviewForFirstFrame() {
        if (!isUsable()) {
            return;
        }
        removeLegacyTopGap();
        attachPreviewBetweenReactionsAndMenu();
        if (scrimContainer != null
                && menuDirectChild != null
                && previewHost.getParent() == scrimContainer) {
            reflowPreviewAndMenu();
            if (previewHost.getWidth() > 0 && menuDirectChild.getWidth() > 0) {
                alignPreviewWithMenu();
            }
        }
    }

    private void removeFirstFrameGate() {
        if (firstFrameObserver != null
                && firstFramePreDrawListener != null
                && firstFrameObserver.isAlive()) {
            firstFrameObserver.removeOnPreDrawListener(firstFramePreDrawListener);
        }
        firstFrameObserver = null;
        firstFramePreDrawListener = null;
    }

    private void finishDeferredFirstFramePreparation() {
        if (!isUsable() || previewRevealed) {
            return;
        }
        preparePreviewForFirstFrame();
    }

""",
        "bounded pre-draw first-frame gate",
    )

    text = replace_once(
        text,
        """        scrimContainer = findScrimAncestor();
        if (scrimContainer == null) {
            return;
        }

        menuDirectChild = findDirectChildBelowScrim(scrimContainer);
""",
        """        scrimContainer = findScrimAncestor();
        if (scrimContainer == null) {
            return;
        }
        ensureStackTopSpacer();

        menuDirectChild = findDirectChildBelowScrim(scrimContainer);
""",
        "stack spacer insertion point",
    )

    text = replace_once(
        text,
        "    private View findDirectChildBelowScrim(ChatScrimPopupContainerLayout scrim) {\n",
        """    private void ensureStackTopSpacer() {
        if (scrimContainer == null || stackTopSpacer != null) {
            return;
        }
        for (int i = 0; i < scrimContainer.getChildCount(); i++) {
            View child = scrimContainer.getChildAt(i);
            if ("AUTHORGRAM_IOS_MESSAGE_STACK_TOP_SPACER".equals(child.getTag())) {
                stackTopSpacer = child;
                return;
            }
        }

        View spacer = new View(getContext());
        spacer.setTag("AUTHORGRAM_IOS_MESSAGE_STACK_TOP_SPACER");
        spacer.setClickable(false);
        spacer.setLongClickable(false);
        spacer.setFocusable(false);
        spacer.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(STACK_TOP_OFFSET_DP)
        );
        scrimContainer.addView(spacer, 0, params);
        stackTopSpacer = spacer;
    }

    private View findDirectChildBelowScrim(ChatScrimPopupContainerLayout scrim) {
""",
        "stack spacer helper",
    )

    text = replace_once(
        text,
        """        cleanedUp = true;

        // Stop every AuthorGram callback immediately, but do not mutate the
""",
        """        cleanedUp = true;
        removeFirstFrameGate();

        // Stop every AuthorGram callback immediately, but do not mutate the
""",
        "first-frame listener cleanup",
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
            raise SystemExit("iOS message menu V11 source patch is not applied")
        print("AuthorGram iOS message menu V11 atomic pre-draw layout validated")
        return 0

    if updated != original:
        TARGET.write_text(updated, encoding="utf-8")
        print("AuthorGram iOS message menu upgraded V9 -> V11 atomic first-frame layout")
    else:
        print("AuthorGram iOS message menu V11 already applied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
