#!/usr/bin/env python3
"""Stabilize AuthorGram Main-only iOS composer geometry across empty/text states."""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"

MARKER = "AUTHORGRAM_IOS_INPUT_GEOMETRY_INVARIANT"

HELPER_ANCHOR = """    int botCommandLastPosition = -1;
    int botCommandLastTop;

"""
HELPER = """    int botCommandLastPosition = -1;
    int botCommandLastTop;

    // AUTHORGRAM_IOS_INPUT_GEOMETRY_INVARIANT
    // Empty and non-empty iOS composer states must share the same baseline.
    // Telegram/Nagram can briefly retain vertical compensation from a previous
    // measurement; normalize only the Main-only iOS composer after layout.
    private final Runnable authorGramInputGeometryRunnable =
            this::authorGramStabilizeIOSInputGeometry;

    private void authorGramStabilizeIOSInputGeometry() {
        if (!isIOSInputStyle()
                || recordingAudioVideo
                || (recordedAudioPanel != null && recordedAudioPanel.getVisibility() == VISIBLE)) {
            return;
        }

        if (textFieldContainer != null) {
            textFieldContainer.setTranslationY(0.0f);
        }
        if (messageEditTextContainer != null) {
            messageEditTextContainer.setTranslationY(0.0f);
        }
        if (attachBubble != null) {
            attachBubble.setTranslationY(0.0f);
        }
        if (sendButtonContainer != null) {
            sendButtonContainer.setTranslationY(0.0f);
        }
        if (audioVideoButtonContainer != null) {
            audioVideoButtonContainer.setTranslationY(0.0f);
        }
        if (aiButton != null) {
            aiButton.animate().cancel();
            aiButton.setTranslationY(0.0f);
        }
        if (richButton != null) {
            richButton.animate().cancel();
            richButton.setTranslationY(0.0f);
        }
        if (aiHint != null) {
            aiHint.animate().cancel();
            aiHint.setTranslationY(0.0f);
        }

        updateSideBubbles();
    }

    private void authorGramScheduleInputGeometryInvariant() {
        if (!isIOSInputStyle()) {
            return;
        }
        removeCallbacks(authorGramInputGeometryRunnable);
        authorGramStabilizeIOSInputGeometry();
        post(authorGramInputGeometryRunnable);
        postDelayed(authorGramInputGeometryRunnable, 320L);
    }

"""

OLD_HEIGHT_BLOCK = """        if (wasHeight > 0 && textFieldContainer.getMeasuredHeight() != wasHeight) {
            for (int i = 0; i < 2; ++i) {
                final View view = i == 0 ? aiButton : richButton;
                view.setTranslationY(view.getTranslationY() + textFieldContainer.getMeasuredHeight() - wasHeight);
                view.animate()
                    .translationY(0)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(420)
                    .start();
            }
            if (aiHint != null) {
                aiHint.setTranslationY(aiHint.getTranslationY() + textFieldContainer.getMeasuredHeight() - wasHeight);
                aiHint.animate()
                    .translationY(0)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(420)
                    .start();
            }
        }
"""

NEW_HEIGHT_BLOCK = """        if (wasHeight > 0 && textFieldContainer.getMeasuredHeight() != wasHeight) {
            if (isIOSInputStyle()) {
                // The iOS composer uses bottom-gravity side bubbles. Do not replay
                // Telegram's vertical compensation on those controls: it can leave
                // the empty composer visually displaced until the first text change.
                authorGramScheduleInputGeometryInvariant();
            } else {
                for (int i = 0; i < 2; ++i) {
                    final View view = i == 0 ? aiButton : richButton;
                    view.setTranslationY(view.getTranslationY() + textFieldContainer.getMeasuredHeight() - wasHeight);
                    view.animate()
                        .translationY(0)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(420)
                        .start();
                }
                if (aiHint != null) {
                    aiHint.setTranslationY(aiHint.getTranslationY() + textFieldContainer.getMeasuredHeight() - wasHeight);
                    aiHint.animate()
                        .translationY(0)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(420)
                        .start();
                }
            }
        } else if (isIOSInputStyle()) {
            // Also normalize restored/initial empty states where measured height is
            // unchanged but stale child translation survived a delayed animation.
            authorGramScheduleInputGeometryInvariant();
        }
"""

LAYOUT_OLD = """    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (botCommandLastPosition != -1 && botCommandsMenuContainer != null) {
"""

LAYOUT_NEW = """    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (isIOSInputStyle()) {
            authorGramScheduleInputGeometryInvariant();
        }
        if (botCommandLastPosition != -1 && botCommandsMenuContainer != null) {
"""


def read() -> str:
    if not ENTER.is_file():
        raise SystemExit(f"Missing ChatActivityEnterView.java: {ENTER}")
    return ENTER.read_text(encoding="utf-8")


def write(text: str) -> None:
    ENTER.write_text(text, encoding="utf-8", newline="")


def pre_apply() -> None:
    text = read()
    if MARKER in text:
        validate()
        print("AuthorGram iOS input geometry pre-apply passed: canonical invariant already present")
        return
    for label, anchor in (
        ("helper anchor", HELPER_ANCHOR),
        ("legacy height compensation", OLD_HEIGHT_BLOCK),
        ("layout anchor", LAYOUT_OLD),
    ):
        count = text.count(anchor)
        if count != 1:
            raise SystemExit(f"pre-apply failed: {label} count={count}, expected 1")
    print("AuthorGram iOS input geometry pre-apply passed: known legacy geometry is patchable")


def apply() -> None:
    pre_apply()
    text = read()
    if MARKER in text:
        print("AuthorGram iOS input geometry repair already applied")
        return

    text = text.replace(HELPER_ANCHOR, HELPER, 1)
    text = text.replace(OLD_HEIGHT_BLOCK, NEW_HEIGHT_BLOCK, 1)
    text = text.replace(LAYOUT_OLD, LAYOUT_NEW, 1)
    write(text)
    validate()
    print("AuthorGram iOS input geometry repair applied")


def validate() -> None:
    text = read()
    failures: list[str] = []
    for required in (
        MARKER,
        "private void authorGramStabilizeIOSInputGeometry()",
        "private void authorGramScheduleInputGeometryInvariant()",
        "textFieldContainer.setTranslationY(0.0f);",
        "messageEditTextContainer.setTranslationY(0.0f);",
        "attachBubble.setTranslationY(0.0f);",
        "sendButtonContainer.setTranslationY(0.0f);",
        "audioVideoButtonContainer.setTranslationY(0.0f);",
        "postDelayed(authorGramInputGeometryRunnable, 320L);",
        "if (isIOSInputStyle()) {\n            authorGramScheduleInputGeometryInvariant();\n        }\n        if (botCommandLastPosition",
        "} else if (isIOSInputStyle()) {\n            // Also normalize restored/initial empty states",
    ):
        if required not in text:
            failures.append(f"missing invariant: {required}")
    if OLD_HEIGHT_BLOCK in text:
        failures.append("legacy unconditional vertical-compensation block remains")
    if "AUTHORGRAM_MAIN_ONLY_IOS_INPUT" not in text:
        failures.append("Main-only iOS policy gate is missing")
    if failures:
        raise SystemExit("iOS input geometry validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram iOS input geometry validation passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("pre-apply", "apply", "validate"), default="apply")
    args = parser.parse_args()
    if args.mode == "pre-apply":
        pre_apply()
    elif args.mode == "validate":
        validate()
    else:
        apply()


if __name__ == "__main__":
    main()
