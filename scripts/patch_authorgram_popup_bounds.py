#!/usr/bin/env python3
"""Constrain chat context menus and repair the Main-only iOS menu/input UI."""

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
CHAT_PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER_PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
MARKER = "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS"
NATIVE_MENU_MARKER = "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS"
NATIVE_SCRIM_MARKER = "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_SCRIM"
PREVIEW_MARKER = "AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW"
INPUT_RESTORE_MARKER = "AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE"


def patch_native_ios_message_menu() -> None:
    """Keep the selected-message preview between reactions and menu actions."""
    text = CHAT_PATH.read_text(encoding="utf-8")

    # patch_authorgram_ui_12_9_2.py inserts the bounded preview immediately
    # before scrimPopupWindowItems, which is exactly between the reaction row
    # and the action list. It must remain there; the original chat cell must not
    # be elevated at its old on-screen position.
    required_preview = (
        PREVIEW_MARKER,
        "new org.telegram.ui.Components.IOSMessageMenuPreview(",
        "popupLayout.addView(iosPreview, iosPreviewParams);",
        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",
    )
    missing_preview = [item for item in required_preview if item not in text]
    if missing_preview:
        raise SystemExit(f"iOS message preview insertion is missing: {missing_preview}")

    # Reuse Nagram's existing GroupedIconsView for the compact bottom actions.
    grouped_old = (
        "                final boolean hasGroupedIcons = GroupedIconsView.useGroupedIcons();\n"
    )
    grouped_new = (
        "                // AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS\n"
        "                final boolean hasGroupedIcons = GroupedIconsView.useGroupedIcons()\n"
        "                        || (org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                                && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool());\n"
    )
    if NATIVE_MENU_MARKER not in text:
        count = text.count(grouped_old)
        if count != 1:
            raise SystemExit(f"grouped message-menu anchor count is {count}, expected 1")
        text = text.replace(grouped_old, grouped_new, 1)

    # A previous implementation used the three-argument overload to keep the
    # real ChatMessageCell above the scrim. That leaves the message pinned at
    # its old chat position. Restore Telegram's normal dimming here; the copied
    # preview inside popupLayout is now the only selected message shown.
    elevated_scrim = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            // AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_SCRIM\n"
        "            dimBehindView(\n"
        "                    v,\n"
        "                    org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                            && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool(),\n"
        "                    true\n"
        "            );\n"
        "            hideHints(false);\n"
    )
    normal_scrim = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            dimBehindView(v, true);\n"
        "            hideHints(false);\n"
    )
    if elevated_scrim in text:
        text = text.replace(elevated_scrim, normal_scrim, 1)
    elif normal_scrim not in text:
        raise SystemExit("normal chat scrim anchor is missing")

    CHAT_PATH.write_text(text, encoding="utf-8", newline="")

    check = CHAT_PATH.read_text(encoding="utf-8")
    required = (
        NATIVE_MENU_MARKER,
        PREVIEW_MARKER,
        "GroupedIconsView.useGroupedIcons()",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "NekoConfig.iOSMessageMenu.Bool()",
        "popupLayout.addView(iosPreview, iosPreviewParams);",
        "dimBehindView(v, true);",
    )
    missing = [item for item in required if item not in check]
    if missing:
        raise SystemExit(f"native iOS message menu validation failed: {missing}")
    forbidden = (
        NATIVE_SCRIM_MARKER,
        "dimBehindView(\n                    v,",
    )
    remaining = [item for item in forbidden if item in check]
    if remaining:
        raise SystemExit(f"old-position selected-message elevation remains: {remaining}")
    print("Main-only iOS message preview placement passed")


def patch_ios_input_media_restore() -> None:
    """Restore the mic/video-round button after the iOS input becomes empty."""
    text = ENTER_PATH.read_text(encoding="utf-8")

    broken_guard = (
        "        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        "        // A delayed MENU-state animation could leave the media container translated\n"
        "        // over the chat avatar. Remove rendering and touch interception whenever\n"
        "        // the send button owns this slot.\n"
        "        if (isIOSInputStyle() && shownSendButton && audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.animate().cancel();\n"
        "            audioVideoButtonContainer.setVisibility(GONE);\n"
        "            audioVideoButtonContainer.setAlpha(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(false);\n"
        "            audioVideoButtonContainer.setEnabled(false);\n"
        "            audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "            audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "        } else if (audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        }\n"
    )
    fixed_guard = (
        "        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        "        // A delayed MENU-state animation could leave the media container translated\n"
        "        // over the chat avatar. Remove rendering and touch interception whenever\n"
        "        // the send button owns this slot.\n"
        "        if (isIOSInputStyle() && shownSendButton && audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.animate().cancel();\n"
        "            audioVideoButtonContainer.setVisibility(GONE);\n"
        "            audioVideoButtonContainer.setAlpha(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(false);\n"
        "            audioVideoButtonContainer.setEnabled(false);\n"
        "            audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "            audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "        } else if (isIOSInputStyle() && audioVideoButtonContainer != null) {\n"
        "            // AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE\n"
        "            // Clearing the editor must reverse every property changed above.\n"
        "            audioVideoButtonContainer.animate().cancel();\n"
        "            audioVideoButtonContainer.setVisibility(VISIBLE);\n"
        "            audioVideoButtonContainer.setAlpha(1.0f);\n"
        "            audioVideoButtonContainer.setScaleX(1.0f);\n"
        "            audioVideoButtonContainer.setScaleY(1.0f);\n"
        "            audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "            audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        } else if (audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        }\n"
    )

    if INPUT_RESTORE_MARKER not in text:
        count = text.count(broken_guard)
        if count != 1:
            raise SystemExit(f"iOS input media restore anchor count is {count}, expected 1")
        text = text.replace(broken_guard, fixed_guard, 1)
        ENTER_PATH.write_text(text, encoding="utf-8", newline="")

    check = ENTER_PATH.read_text(encoding="utf-8")
    required = (
        INPUT_RESTORE_MARKER,
        "audioVideoButtonContainer.setVisibility(VISIBLE);",
        "audioVideoButtonContainer.setAlpha(1.0f);",
        "audioVideoButtonContainer.setScaleX(1.0f);",
        "audioVideoButtonContainer.setScaleY(1.0f);",
    )
    missing = [item for item in required if item not in check]
    if missing:
        raise SystemExit(f"iOS input media restore validation failed: {missing}")
    print("Main-only iOS input mic/video restore passed")


patch_native_ios_message_menu()
patch_ios_input_media_restore()

text = PATH.read_text(encoding="utf-8")
if MARKER not in text:
    old = """        int constrainedHeightSpec = maxHeight != 0 ? MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) : heightMeasureSpec;
        int adjustedWidthSpec = widthMeasureSpec;
        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
"""
    new = """        // AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS
        // Some OEM/window combinations pass an effectively unbounded measure spec.
        // Always cap the menu to the real display/work-area height so the internal
        // ScrollView scrolls instead of the popup escaping below the screen.
        int parentMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        int displayHeight = Math.max(AndroidUtilities.dp(240), AndroidUtilities.displaySize.y);
        int availableHeight = parentMode == MeasureSpec.UNSPECIFIED || parentHeight <= 0
                ? displayHeight
                : Math.min(parentHeight, displayHeight);
        availableHeight = Math.max(AndroidUtilities.dp(160), availableHeight - AndroidUtilities.dp(16));
        int effectiveMaxHeight = maxHeight > 0
                ? Math.min(maxHeight, availableHeight)
                : availableHeight;
        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);
        int adjustedWidthSpec = widthMeasureSpec;
        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
"""
    if text.count(old) != 1:
        raise SystemExit(f"popup measure anchor count is {text.count(old)}, expected 1")
    text = text.replace(old, new, 1)

    old_setter = """    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }
"""
    new_setter = """    public void setMaxHeight(int maxHeight) {
        int safeDisplayHeight = Math.max(AndroidUtilities.dp(160), AndroidUtilities.displaySize.y - AndroidUtilities.dp(16));
        this.maxHeight = maxHeight > 0 ? Math.min(maxHeight, safeDisplayHeight) : safeDisplayHeight;
        requestLayout();
    }
"""
    if text.count(old_setter) != 1:
        raise SystemExit(f"popup max-height setter anchor count is {text.count(old_setter)}, expected 1")
    text = text.replace(old_setter, new_setter, 1)
    PATH.write_text(text, encoding="utf-8", newline="")

check = PATH.read_text(encoding="utf-8")
required = (
    MARKER,
    "effectiveMaxHeight",
    "requestLayout();",
)
missing = [item for item in required if item not in check]
if missing:
    raise SystemExit(f"adaptive popup bounds validation failed: {missing}")
print("Adaptive chat popup bounds patch passed")

# release.yml executes this lightweight script before Java/Gradle/Android SDK.
# These scripts therefore validate all profile/header surfaces and every HMAC
# token during the early, inexpensive preflight stage.
for relative in (
    "scripts/patch_authorgram_badge_surfaces.py",
    "scripts/verify_authorgram_badge_tokens.py",
):
    runpy.run_path(str(ROOT / relative), run_name="__main__")
