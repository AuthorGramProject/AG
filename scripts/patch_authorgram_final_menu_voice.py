#!/usr/bin/env python3
"""Apply the complete final AuthorGram chat-menu/composer repair chain.

The historical 12.9.2 patchers are intentionally kept as an intermediate
compatibility layer. This controller runs them first, then makes the canonical
final state authoritative:

* one scroll/measurement owner for selected-message preview, actions and footer;
* shared full-width quick-action footer with a 1dp themed divider;
* strict work-area viewport so the last action always remains reachable;
* Main-only iOS selected-message preview and full-surface blur policy preserved;
* paused voice-draft trash/cancel restores the iOS attachment button immediately.

This file is the only patch entry point the release workflow should call.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
ACTIONBAR = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java"
PLAY_POLICY = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java"


def run(relative: str) -> None:
    runpy.run_path(str(ROOT / relative), run_name="__main__")


def require(text: str, markers: tuple[str, ...], label: str) -> None:
    missing = [marker for marker in markers if marker not in text]
    if missing:
        raise SystemExit(f"{label} validation failed; missing: {missing}")


def validate_final_state() -> None:
    chat = CHAT.read_text(encoding="utf-8")
    scrim = SCRIM.read_text(encoding="utf-8")
    enter = ENTER.read_text(encoding="utf-8")
    preview = PREVIEW.read_text(encoding="utf-8")
    actionbar = ACTIONBAR.read_text(encoding="utf-8")
    play_policy = PLAY_POLICY.read_text(encoding="utf-8")

    require(
        actionbar,
        (
            "AUTHORGRAM_STANDARD_CHAT_HEADER",
            "parentFragment instanceof org.telegram.ui.ChatActivity",
            "return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();",
        ),
        "ordinary non-centered chat header",
    )

    require(
        chat,
        (
            "AUTHORGRAM_UNIFIED_MESSAGE_MENU_FLOW",
            "popupLayout.addView(iosPreview, iosPreviewParams);",
            "AUTHORGRAM_IOS_MESSAGE_PREVIEW_GAP",
            "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR",
            "dimBehindView(null, true, true);",
            "AuthorGramPlayPolicy.canUseIosUi()",
            "NekoConfig.iOSMessageMenu.Bool()",
        ),
        "unified selected-message/action flow",
    )

    require(
        scrim,
        (
            "AUTHORGRAM_UNIFIED_MENU_FOOTER",
            "AUTHORGRAM_MENU_FOOTER_SEPARATOR",
            "AUTHORGRAM_STRICT_MENU_VIEWPORT",
            "private void authorGramAttachPendingBottomViews()",
            "authorGramAttachPendingBottomViews();",
            "Theme.getColor(Theme.key_divider)",
            "AndroidUtilities.dp(1)",
            "popupWindowLayout.addView(bottomView, footerParams);",
            "LayoutHelper.MATCH_PARENT",
            "effectiveMaxHeight - occupiedHeight",
            "bottomView.setBackground(null);",
        ),
        "shared message-menu footer/viewport",
    )

    require(
        enter,
        (
            "AUTHORGRAM_MAIN_ONLY_IOS_INPUT",
            "AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT",
            "AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX",
            "public View getSendButtonInternal() {",
            "View sendButtonView = getSendButtonInternal();",
            "AUTHORGRAM_IOS_VOICE_DRAFT_ATTACH_RESTORE",
            "private void authorGramRestoreIosAttachAfterVoiceDraftDelete()",
            "hideRecordedAudioPanelInternal();\n                    authorGramRestoreIosAttachAfterVoiceDraftDelete(); // AUTHORGRAM_IOS_VOICE_DRAFT_ATTACH_RESTORE",
            "attachLayout.setVisibility(VISIBLE);",
            "attachButton.setVisibility(VISIBLE);",
            "attachButton.setTag(2);",
            "attachButton.setClickable(true);",
            "attachButton.setEnabled(true);",
        ),
        "iOS composer/send/paused-voice restore",
    )

    require(
        preview,
        (
            "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK",
            "BackupImageView avatarView",
            "TextView senderNameView",
            "sourceCell.draw(canvas);",
            "AuthorGramPlayPolicy.canUseIosUi()",
        ),
        "Main-only selected-message identity/native preview",
    )

    require(
        play_policy,
        (
            'values.put("iOSMessageInputField", false)',
            'values.put("iOSMessageMenu", false)',
        ),
        "Play iOS-UI policy",
    )

    forbidden_chat = (
        "scrimPopupContainerLayout.setFixedMessagePreview(",
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.shouldScrollWithActions()",
        "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
        "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER",
        "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
        "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
    )
    stale = [token for token in forbidden_chat if token in chat]
    if stale:
        raise SystemExit(f"split/overlay preview ownership survived finalization: {stale}")

    if "AndroidUtilities.dp(96),\n                effectiveMaxHeight - occupiedHeight" in scrim:
        raise SystemExit("artificial 96dp popup minimum survived finalization")

    if enter.count("public View getSendButtonInternal() {") != 1:
        raise SystemExit("native getSendButtonInternal() method count is not exactly one")
    if enter.count("private void authorGramRestoreIosAttachAfterVoiceDraftDelete()") != 1:
        raise SystemExit("paused-voice attachment restore helper count is not exactly one")
    if enter.count(
        "authorGramRestoreIosAttachAfterVoiceDraftDelete(); // AUTHORGRAM_IOS_VOICE_DRAFT_ATTACH_RESTORE"
    ) != 1:
        raise SystemExit("paused-voice attachment restore call count is not exactly one")

    for path in (CHAT, SCRIM, ENTER, PREVIEW):
        if "\r\n" in path.read_text(encoding="utf-8"):
            raise SystemExit(f"{path.name}: CRLF unexpectedly introduced")

    print("AuthorGram final unified menu + iOS voice-draft restore validation passed")


def main() -> None:
    # Compatibility/intermediate transformations. Their own validators must pass
    # before the canonical final state is applied.
    run("scripts/patch_authorgram_popup_bounds.py")

    # Final geometry owner: never let a legacy generator's split preview/footer
    # model become the source shipped in an APK.
    run("scripts/patch_authorgram_unified_message_menu.py")

    # Final composer invariant for the paused-recording trash/cancel transition.
    run("scripts/patch_authorgram_ios_voice_draft_restore.py")

    validate_final_state()


if __name__ == "__main__":
    main()
