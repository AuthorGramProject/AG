#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
MARKER_V6 = "AUTHORGRAM_IOS_MESSAGE_MENU_V6_FINAL"
MARKER_V7 = "AUTHORGRAM_IOS_MESSAGE_MENU_V7_TOUCH_CLEANUP"
MARKER_V8 = "AUTHORGRAM_IOS_MESSAGE_MENU_V8_BOUNDED_LAYOUT_RETRY"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def validate_v7(text: str) -> None:
    required = (
        MARKER_V7,
        "import android.view.MotionEvent;",
        "public boolean dispatchTouchEvent(MotionEvent event)",
        "public boolean onInterceptTouchEvent(MotionEvent event)",
        "blurOverlay.setClickable(false);",
        "blurOverlay.setLongClickable(false);",
        "blurOverlay.setFocusable(false);",
        "blurOverlay.setEnabled(false);",
        "scrimContainer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener()",
        "cleanup();",
        "protected void onWindowVisibilityChanged(int visibility)",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"V7 validation failed: missing {needle!r}")


def validate_v8(text: str) -> None:
    required = (
        MARKER_V8,
        MARKER_V7,
        "MAX_LAYOUT_RETRY_COUNT = 4",
        "attachLayoutRetryRunnable",
        "reflowLayoutRetryRunnable",
        "scheduleAttachLayoutRetry();",
        "scheduleReflowLayoutRetry();",
        "private boolean canRunLayoutRetry()",
        "scrimContainer.removeCallbacks(attachLayoutRetryRunnable);",
        "scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);",
        "if (!scrimLifecycleBound)",
        "scrimLifecycleBound = true;",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"V8 validation failed: missing {needle!r}")

    forbidden = (
        "scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);",
        "scrimContainer.post(this::reflowPreviewAndMenu);",
    )
    for needle in forbidden:
        if needle in text:
            raise SystemExit(f"V8 validation failed: unbounded retry still present: {needle!r}")

    # V8 must retain all V7 touch-transparent overlay protections.
    for needle in (
        "import android.view.MotionEvent;",
        "public boolean dispatchTouchEvent(MotionEvent event)",
        "public boolean onInterceptTouchEvent(MotionEvent event)",
        "blurOverlay.setClickable(false);",
        "blurOverlay.setLongClickable(false);",
        "blurOverlay.setFocusable(false);",
        "blurOverlay.setEnabled(false);",
        "protected void onWindowVisibilityChanged(int visibility)",
    ):
        if needle not in text:
            raise SystemExit(f"V8 regression: missing V7 protection {needle!r}")


def patch_v6_to_v7(text: str) -> str:
    if MARKER_V7 in text:
        validate_v7(text)
        return text
    if MARKER_V6 not in text:
        raise SystemExit("unexpected IOSMessageMenuPreview baseline: expected V6, V7 or V8")

    text = replace_once(
        text,
        "import android.view.View;\n",
        "import android.view.MotionEvent;\nimport android.view.View;\n",
        "MotionEvent import",
    )
    text = replace_once(text, MARKER_V6, MARKER_V7, "V7 marker")

    text = replace_once(
        text,
        """        blurOverlay = new FrameLayout(context);
        blurOverlay.setTag("AUTHORGRAM_IOS_MESSAGE_MENU_BLUR_OVERLAY");
        blurOverlay.setClickable(false);
        blurOverlay.setFocusable(false);
        blurOverlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
""",
        """        // This overlay is visual only. It must never become a touch target,
        // even for a single frame after the popup starts its dismiss animation.
        blurOverlay = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                return false;
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                return false;
            }
        };
        blurOverlay.setTag("AUTHORGRAM_IOS_MESSAGE_MENU_BLUR_OVERLAY");
        blurOverlay.setClickable(false);
        blurOverlay.setLongClickable(false);
        blurOverlay.setFocusable(false);
        blurOverlay.setEnabled(false);
        blurOverlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
""",
        "touch-transparent blur overlay",
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

        // The visible preview was moved out of the popup's inner LinearLayout,
        // so do not rely solely on this hidden controller's detach callback.
        // Bind cleanup to the actual popup root as well.
        scrimContainer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                cleanup();
            }
        });

        menuDirectChild = findDirectChildBelowScrim(scrimContainer);
""",
        "popup-root lifecycle cleanup",
    )

    text = replace_once(
        text,
        """    @Override
    protected void onDetachedFromWindow() {
        cleanup();
        super.onDetachedFromWindow();
    }

    private void cleanup() {
""",
        """    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && previewRevealed) {
            cleanup();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cleanup();
        super.onDetachedFromWindow();
    }

    private void cleanup() {
""",
        "window visibility cleanup",
    )

    validate_v7(text)
    return text


def patch_v7_to_v8(text: str) -> str:
    if MARKER_V8 in text:
        validate_v8(text)
        return text

    validate_v7(text)

    text = replace_once(
        text,
        """    private boolean previewRevealed;
    private boolean cleanedUp;
""",
        """    private boolean previewRevealed;
    private boolean cleanedUp;

    // AUTHORGRAM_IOS_MESSAGE_MENU_V8_BOUNDED_LAYOUT_RETRY
    // Layout retries are allowed only for a few display frames while the popup
    // is actually attached. An unbounded View.post(self) loop can otherwise
    // survive popup dismissal with a zero-width menu and starve Android's main
    // thread, blocking taps, Back and Activity lifecycle callbacks.
    private static final int MAX_LAYOUT_RETRY_COUNT = 4;
    private int attachLayoutRetryCount;
    private int reflowLayoutRetryCount;
    private boolean attachLayoutRetryPosted;
    private boolean reflowLayoutRetryPosted;
    private boolean scrimLifecycleBound;

    private final Runnable attachLayoutRetryRunnable = () -> {
        attachLayoutRetryPosted = false;
        if (!canRunLayoutRetry()) {
            return;
        }
        attachPreviewBetweenReactionsAndMenu();
    };

    private final Runnable reflowLayoutRetryRunnable = () -> {
        reflowLayoutRetryPosted = false;
        if (!canRunLayoutRetry()) {
            return;
        }
        reflowPreviewAndMenu();
    };
""",
        "V8 bounded retry state",
    )

    text = replace_once(
        text,
        """        // The visible preview was moved out of the popup's inner LinearLayout,
        // so do not rely solely on this hidden controller's detach callback.
        // Bind cleanup to the actual popup root as well.
        scrimContainer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeOnAttachStateChangeListener(this);
                cleanup();
            }
        });
""",
        """        // The visible preview was moved out of the popup's inner LinearLayout,
        // so do not rely solely on this hidden controller's detach callback.
        // Register the popup-root lifecycle listener exactly once: retries must
        // never accumulate additional listeners while the menu is measuring.
        if (!scrimLifecycleBound) {
            scrimLifecycleBound = true;
            scrimContainer.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    cleanup();
                }
            });
        }
""",
        "single popup-root lifecycle listener",
    )

    text = replace_once(
        text,
        "scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);",
        "scheduleAttachLayoutRetry();",
        "bounded attach retry",
    )
    text = replace_once(
        text,
        "scrimContainer.post(this::reflowPreviewAndMenu);",
        "scheduleReflowLayoutRetry();",
        "bounded reflow retry",
    )

    text = replace_once(
        text,
        """    private ChatScrimPopupContainerLayout findScrimAncestor() {
""",
        """    private boolean canRunLayoutRetry() {
        return !cleanedUp
                && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE
                && scrimContainer != null
                && scrimContainer.isAttachedToWindow()
                && scrimContainer.getWindowVisibility() == VISIBLE;
    }

    private void scheduleAttachLayoutRetry() {
        if (!canRunLayoutRetry()
                || attachLayoutRetryPosted
                || attachLayoutRetryCount >= MAX_LAYOUT_RETRY_COUNT) {
            return;
        }
        attachLayoutRetryCount++;
        attachLayoutRetryPosted = true;
        scrimContainer.postOnAnimation(attachLayoutRetryRunnable);
    }

    private void scheduleReflowLayoutRetry() {
        if (!canRunLayoutRetry()
                || reflowLayoutRetryPosted
                || reflowLayoutRetryCount >= MAX_LAYOUT_RETRY_COUNT) {
            return;
        }
        reflowLayoutRetryCount++;
        reflowLayoutRetryPosted = true;
        scrimContainer.postOnAnimation(reflowLayoutRetryRunnable);
    }

    private ChatScrimPopupContainerLayout findScrimAncestor() {
""",
        "V8 retry helpers",
    )

    text = replace_once(
        text,
        """        cleanedUp = true;

        if (messagePreviewView != null) {
""",
        """        cleanedUp = true;

        // Cancel every layout retry before detaching visual children. This is
        // the critical dismissal invariant: no preview callback may keep the
        // UI queue alive after the popup has started closing.
        if (scrimContainer != null) {
            scrimContainer.removeCallbacks(attachLayoutRetryRunnable);
            scrimContainer.removeCallbacks(reflowLayoutRetryRunnable);
        }
        removeCallbacks(attachLayoutRetryRunnable);
        removeCallbacks(reflowLayoutRetryRunnable);
        attachLayoutRetryPosted = false;
        reflowLayoutRetryPosted = false;

        if (messagePreviewView != null) {
""",
        "cancel V8 retry callbacks during cleanup",
    )

    validate_v8(text)
    return text


def patch(text: str) -> str:
    if MARKER_V8 in text:
        validate_v8(text)
        return text
    text = patch_v6_to_v7(text)
    return patch_v7_to_v8(text)


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
            raise SystemExit("iOS message menu V8 dismiss-freeze fix is not applied")
        print("AuthorGram iOS message menu V8 bounded layout retry validated")
        return 0

    if updated != original:
        TARGET.write_text(updated, encoding="utf-8")
        print("AuthorGram iOS message menu upgraded to V8 bounded layout retry")
    else:
        print("AuthorGram iOS message menu V8 already applied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
