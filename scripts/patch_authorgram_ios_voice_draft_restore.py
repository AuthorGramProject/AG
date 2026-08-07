#!/usr/bin/env python3
"""Restore the iOS attachment button after deleting a paused voice draft.

Telegram's recorded-audio exit animation restores the normal composer through
several asynchronous state changes. In the Main-only iOS input layout,
attachLayout can remain hidden after the trash action even though the recording
draft has already been removed. The symptom is a missing paperclip until the
chat is recreated.

This patch adds one idempotent post-delete invariant. It runs only for the iOS
input style and only on the non-send recorded-panel exit path, after the native
recorded state has been cleared.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"

MARKER = "AUTHORGRAM_IOS_VOICE_DRAFT_ATTACH_RESTORE"
HELPER_NAME = "authorGramRestoreIosAttachAfterVoiceDraftDelete"


def read() -> str:
    if not ENTER.is_file():
        raise SystemExit(f"Missing ChatActivityEnterView.java: {ENTER}")
    return ENTER.read_text(encoding="utf-8")


def write(text: str) -> None:
    ENTER.write_text(text, encoding="utf-8", newline="")


def patch() -> None:
    text = read()

    helper = """    // AUTHORGRAM_IOS_VOICE_DRAFT_ATTACH_RESTORE
    // The recorded-audio panel can hide attachLayout while the draft owns the
    // composer. After trash/cancel, restore the complete iOS attachment state
    // only after Telegram has cleared the recorded draft.
    private void authorGramRestoreIosAttachAfterVoiceDraftDelete() {
        if (!isIOSInputStyle() || attachLayout == null || attachButton == null) {
            return;
        }

        if (attachButtonAnimator != null) {
            attachButtonAnimator.cancel();
            attachButtonAnimator = null;
        }
        attachLayout.animate().cancel();
        attachButton.animate().cancel();

        attachLayout.setVisibility(VISIBLE);
        attachLayoutAlpha = 1.0f;
        updateAttachLayoutParams();
        attachLayout.setScaleX(1.0f);

        attachButton.setVisibility(VISIBLE);
        attachButton.setTag(2);
        attachButton.setAlpha(attachButtonAlpha = 1.0f);
        attachButton.setScaleX(1.0f);
        attachButton.setScaleY(1.0f);
        attachButton.setTranslationX(0.0f);
        attachButton.setTranslationY(0.0f);
        attachButton.setClickable(true);
        attachButton.setEnabled(true);

        updateFieldRight(1);
        if (delegate != null && getVisibility() == VISIBLE) {
            delegate.onAttachButtonShow();
        }
    }

"""

    helper_anchor = "    private void hideRecordedAudioPanelInternal() {\n"
    if MARKER not in text:
        if helper_anchor not in text:
            raise SystemExit("voice-draft helper anchor is missing")
        text = text.replace(helper_anchor, helper + helper_anchor, 1)

    call = f"                    {HELPER_NAME}(); // {MARKER}\n"
    call_anchor = (
        "                    hideRecordedAudioPanelInternal();\n"
        "                    if (recordCircle != null) {\n"
    )
    if call not in text:
        if call_anchor not in text:
            raise SystemExit("voice-draft restore call anchor is missing")
        text = text.replace(
            call_anchor,
            "                    hideRecordedAudioPanelInternal();\n"
            + call
            + "                    if (recordCircle != null) {\n",
            1,
        )

    write(text)
    validate()


def validate() -> None:
    text = read()
    failures: list[str] = []

    if text.count(f"private void {HELPER_NAME}()") != 1:
        failures.append("voice-draft restore helper count is not exactly one")
    if text.count(f"{HELPER_NAME}(); // {MARKER}") != 1:
        failures.append("voice-draft restore call count is not exactly one")

    for required in (
        "if (!isIOSInputStyle() || attachLayout == null || attachButton == null)",
        "attachLayout.setVisibility(VISIBLE);",
        "attachLayoutAlpha = 1.0f;",
        "attachButton.setVisibility(VISIBLE);",
        "attachButton.setTag(2);",
        "attachButton.setAlpha(attachButtonAlpha = 1.0f);",
        "attachButton.setClickable(true);",
        "attachButton.setEnabled(true);",
        "updateFieldRight(1);",
        "delegate.onAttachButtonShow();",
        "hideRecordedAudioPanelInternal();\n"
        f"                    {HELPER_NAME}(); // {MARKER}",
    ):
        if required not in text:
            failures.append(f"voice-draft restore invariant missing: {required}")

    if failures:
        raise SystemExit(
            "iOS paused-voice attachment restore validation failed:\n - "
            + "\n - ".join(failures)
        )

    print("iOS paused-voice delete/cancel attachment restore passed")


if __name__ == "__main__":
    patch()
