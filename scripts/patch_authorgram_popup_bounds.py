#!/usr/bin/env python3
"""Constrain chat context menus and apply the native Main-only iOS menu."""

import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
CHAT_PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
MARKER = "AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS"
NATIVE_MENU_MARKER = "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_ACTIONS"
NATIVE_SCRIM_MARKER = "AUTHORGRAM_NATIVE_IOS_MESSAGE_MENU_SCRIM"


def patch_native_ios_message_menu() -> None:
    """Use Telegram's real selected cell instead of a duplicated preview card."""
    text = CHAT_PATH.read_text(encoding="utf-8")

    # The previous repair inserted a synthetic sender/message card into the old
    # popup. Remove that whole block. Telegram already keeps the selected
    # ChatMessageCell above its scrim, preserving the actual bubble, quote,
    # avatar, timestamp, reactions and theme-specific rendering.
    preview_start = "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
    items_anchor = "                scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];\n"
    if preview_start in text:
        start = text.index(preview_start)
        end = text.find(items_anchor, start)
        if end < 0:
            raise SystemExit("synthetic iOS preview end anchor is missing")
        text = text[:start] + text[end:]

    # Reuse Nagram's existing GroupedIconsView. It moves the canonical quick
    # actions into a compact horizontal bottom bar without deleting any other
    # menu action. The ordinary user preference still works; iOS mode simply
    # guarantees the bar in trusted Main builds.
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

    # The two-argument overload only dims the chat. The three-argument overload
    # enables Telegram's native blur while retaining the real selected message
    # cell above the scrim. Play always evaluates canUseIosUi() to false.
    scrim_old = (
        "            chatLayoutManager.setCanScrollVertically(false);\n"
        "            dimBehindView(v, true);\n"
        "            hideHints(false);\n"
    )
    scrim_new = (
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
    if NATIVE_SCRIM_MARKER not in text:
        count = text.count(scrim_old)
        if count != 1:
            raise SystemExit(f"native chat scrim anchor count is {count}, expected 1")
        text = text.replace(scrim_old, scrim_new, 1)

    CHAT_PATH.write_text(text, encoding="utf-8", newline="")

    check = CHAT_PATH.read_text(encoding="utf-8")
    required = (
        NATIVE_MENU_MARKER,
        NATIVE_SCRIM_MARKER,
        "GroupedIconsView.useGroupedIcons()",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "NekoConfig.iOSMessageMenu.Bool()",
        "dimBehindView(\n                    v,",
    )
    missing = [item for item in required if item not in check]
    if missing:
        raise SystemExit(f"native iOS message menu validation failed: {missing}")
    forbidden = (
        "AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW",
        "new org.telegram.ui.Components.IOSMessageMenuPreview",
        "popupLayout.addView(iosPreview",
    )
    remaining = [item for item in forbidden if item in check]
    if remaining:
        raise SystemExit(f"synthetic iOS message preview remains in ChatActivity: {remaining}")
    print("Native Main-only iOS message menu patch passed")


patch_native_ios_message_menu()

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
