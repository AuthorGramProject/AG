#!/usr/bin/env python3
"""Apply and validate AuthorGram's final popup, input and chat-header repairs.

The canonical final patch restores the shared baseline. The adaptive functions
then keep short Main-only iOS message previews fixed, move long previews into
the action ScrollView, cap the popup viewport, and preserve the native composer
send-button API.

A read-only legacy-call inventory now runs before any generator touches
ChatActivity. The scope-safety pass then repairs only known legacy variants and
validation refuses every stale back-call before Gradle can start.
"""

from __future__ import annotations

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
FINAL_MARKER = "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK"

# PRE-APPLY GUARANTEE: inspect the committed ChatActivity before any UI generator
# is allowed to mutate it. Unknown legacy back-calls are a hard failure.
scope_safety = runpy.run_path(
    str(ROOT / "scripts/patch_authorgram_chat_scope_safety.py"),
    run_name="authorgram_chat_scope_safety_pre_apply",
)
scope_safety["pre_apply_check"]()

# The legacy repair validates legacy preview wording. Run it only while upgrading
# old source; committed final source is handled by the canonical final patches.
if FINAL_MARKER not in PREVIEW.read_text(encoding="utf-8"):
    runpy.run_path(
        str(ROOT / "scripts/patch_authorgram_ios_menu_v2.py"),
        run_name="__main__",
    )

for relative in (
    "scripts/patch_authorgram_final_chat_ui.py",
    "scripts/patch_authorgram_final_ui_compat.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")

# Load the adaptive patch as a module so its individual transformations can be
# validated explicitly. The adaptive script's old global check incorrectly
# treated Telegram's real public getSendButtonInternal() method as undefined.
adaptive = runpy.run_path(
    str(ROOT / "scripts/patch_authorgram_adaptive_ios_preview.py"),
    run_name="authorgram_adaptive_ios_preview",
)
adaptive["patch_preview_source"]()
adaptive["patch_chat_activity_owner"]()
adaptive["patch_scrim_viewport"]()
try:
    adaptive["patch_composer_compile_and_null_safety"]()
except SystemExit as exc:
    if str(exc) != "undefined getSendButtonInternal() call remains":
        raise
    print("Preserved Telegram's valid getSendButtonInternal() API")

# Keep the helper block's null-safety changes while restoring the native method
# abstraction instead of binding the invariant directly to the backing field.
enter_text = ENTER.read_text(encoding="utf-8")
direct_helper = "View sendButtonView = sendButton;"
helper_count = enter_text.count(direct_helper)
if helper_count != 1:
    raise SystemExit(
        f"composer helper direct-field count is {helper_count}, expected 1"
    )
enter_text = enter_text.replace(
    direct_helper,
    "View sendButtonView = getSendButtonInternal();",
    1,
)
ENTER.write_text(enter_text, encoding="utf-8", newline="")
adaptive["patch_release_summary"]()

# Generators above may materialize the exact historical back-call. Repair only
# that inventoried variant immediately, then require a completely clean result.
scope_safety["apply"]()
scope_safety["validate"]()

checks = {
    "standard chat header with global centering preserved": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java",
        (
            "AUTHORGRAM_STANDARD_CHAT_HEADER",
            "parentFragment instanceof org.telegram.ui.ChatActivity",
            "return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();",
        ),
    ),
    "adaptive popup bounds and message ownership": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java",
        (
            "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS",
            "AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_SCROLL",
            "AUTHORGRAM_MENU_FOOTER_WIDTH_PARITY",
            "setFixedMessagePreview(View preview)",
            "availableForActions",
            "popupParams.height = availableForActions;",
            "int newWidth = popupWidth > 0 ? popupWidth : foregroundWidth;",
        ),
    ),
    "native grouped actions, adaptive preview and full blur": (
        CHAT,
        (
            "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS",
            "GroupedIconsView.useGroupedIcons()",
            "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW",
            "AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER",
            "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER",
            "iosPreview.shouldScrollWithActions()",
            "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
            "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
            "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
            "while (authorgramIosPreviewParent != null",
            "((android.view.View) authorgramIosPreviewParent).getParent();",
            ".setFixedMessagePreview(iosPreview);",
            "AUTHORGRAM_FULL_SCREEN_IOS_MENU_BLUR",
            "dimBehindView(null, true, true);",
        ),
    ),
    "reliable normal and iOS popup scrolling": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java",
        (
            "AUTHORGRAM_RELIABLE_POPUP_SCROLL",
            "scrollView.setFillViewport(false);",
            "scrollView.setScrollContainer(true);",
            "scrollView.setNestedScrollingEnabled(true);",
            "scrollView.setPadding(0, 0, 0, dp(8));",
        ),
    ),
    "iOS send icon and native media-slot ownership": (
        ENTER,
        (
            "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE",
            "AUTHORGRAM_TYPING_OVERLAY_GUARD_V3",
            "AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER",
            "AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT",
            "AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX",
            "View sendButtonView = getSendButtonInternal();",
            "public View getSendButtonInternal() {",
            "sendButtonView.setVisibility(VISIBLE);",
            "sendButtonView.setAlpha(1.0f);",
            "audioVideoButtonContainer.setVisibility(VISIBLE);",
            "audioVideoButtonContainer.setVisibility(GONE);",
        ),
    ),
    "adaptive unified sender/message preview": (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java",
        (
            "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK",
            "AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW",
            "AUTHORGRAM_FINAL_PREVIEW_COMPAT",
            "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY",
            "BackupImageView avatarView",
            "TextView senderNameView",
            "public boolean shouldScrollWithActions()",
            "Theme.key_chat_outBubble",
            "Theme.key_chat_inBubble",
            "sourceCell.draw(canvas);",
        ),
    ),
}

for label, (path, required) in checks.items():
    text = path.read_text(encoding="utf-8")
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"{label} validation failed: {missing}")
    print(f"{label} validation passed")

chat_activity = CHAT.read_text(encoding="utf-8")
if "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP" in chat_activity:
    raise SystemExit("obsolete unconditional iOS preview gap remains")
for forbidden in (
    "scrimPopupContainerLayout.setFixedMessagePreview(",
    "scrimPopupContainerLayout.getBottomOffset()",
    "chatActivityEnterView.setFixedMessagePreview(",
):
    if forbidden in chat_activity:
        raise SystemExit(f"scope-invalid ChatActivity call remains: {forbidden}")

enter_text = ENTER.read_text(encoding="utf-8")
if "public View sendButton {" in enter_text:
    raise SystemExit("native getSendButtonInternal() method was corrupted")
if enter_text.count("public View getSendButtonInternal() {") != 1:
    raise SystemExit("native getSendButtonInternal() method count is not exactly one")

for path in (
    CHAT,
    ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java",
    ENTER,
    PREVIEW,
):
    if "\r\n" in path.read_text(encoding="utf-8"):
        raise SystemExit(f"{path.name}: CRLF unexpectedly introduced")

for relative in (
    "scripts/patch_authorgram_badge_surfaces.py",
    "scripts/verify_authorgram_badge_tokens.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")
