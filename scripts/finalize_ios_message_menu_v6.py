#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
MARKER_V6 = "AUTHORGRAM_IOS_MESSAGE_MENU_V6_FINAL"
MARKER_V7 = "AUTHORGRAM_IOS_MESSAGE_MENU_V7_TOUCH_CLEANUP"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def validate(text: str) -> None:
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
            raise SystemExit(f"validation failed: missing {needle!r}")


def patch(text: str) -> str:
    if MARKER_V7 in text:
        validate(text)
        return text
    if MARKER_V6 not in text:
        raise SystemExit("unexpected IOSMessageMenuPreview baseline: expected V6 or V7")

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
            raise SystemExit("iOS message menu touch-cleanup patch is not applied")
        print("AuthorGram iOS message menu V7 touch cleanup validated")
        return 0

    if updated != original:
        TARGET.write_text(updated, encoding="utf-8")
        print("AuthorGram iOS message menu upgraded V6 -> V7 touch cleanup")
    else:
        print("AuthorGram iOS message menu V7 already applied")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
