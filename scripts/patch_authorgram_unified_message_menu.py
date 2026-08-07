#!/usr/bin/env python3
"""Canonicalize AuthorGram message-menu flow after the legacy UI patch chain.

The 12.9.2 intermediate generators intentionally preserve compatibility with
older AuthorGram/Nagram layouts. Their final intermediate shape still has two
geometry owners that can fight each other:

* short Main-only iOS message previews are attached above popupWindowLayout,
  while long previews are inserted into popupWindowLayout's ScrollView;
* the bottom quick-action row is a top-level ChatScrimPopupContainerLayout child
  and is translated independently from the action ScrollView.

That split ownership causes short previews to overlap action rows and can leave
the bottom quick-action row below the usable viewport. This final, idempotent
post-patch makes one scroll/measurement owner authoritative:

* every iOS selected-message preview is inserted before the action rows in
  popupLayout, preserving the already-correct long-message presentation;
* bottom quick actions are moved into popupWindowLayout's internal LinearLayout
  at measure time, after all normal actions, so they scroll as the same card;
* a one-dp Theme.key_divider separator is inserted between normal actions and
  the quick-action footer;
* the popup viewport uses the actual remaining work-area height, without an
  artificial minimum that could force content below the screen.

The footer change is shared message-menu behavior and is not gated by iOS UI.
The iOS selected-message preview remains Main-only through AuthorGramPlayPolicy.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"

FLOW_MARKER = "AUTHORGRAM_UNIFIED_MESSAGE_MENU_FLOW"
FOOTER_MARKER = "AUTHORGRAM_UNIFIED_MENU_FOOTER"
SEPARATOR_MARKER = "AUTHORGRAM_MENU_FOOTER_SEPARATOR"
VIEWPORT_MARKER = "AUTHORGRAM_STRICT_MENU_VIEWPORT"
PREVIEW_GAP_TAG = "AUTHORGRAM_IOS_MESSAGE_PREVIEW_GAP"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def patch_chat_activity() -> None:
    text = read(CHAT)

    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_UNIFIED_MESSAGE_MENU_FLOW\n"
        "                // Short and long selected-message previews share exactly one\n"
        "                // layout owner. popupLayout is the action ScrollView content,\n"
        "                // so the preview can never overlay the first action and the full\n"
        "                // preview/actions/footer surface remains reachable by scrolling.\n"
        "                if (selectedObject != null\n"
        "                        && v instanceof org.telegram.ui.Cells.ChatMessageCell\n"
        "                        && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                        && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
        "                    org.telegram.ui.Cells.ChatMessageCell selectedMessageCell =\n"
        "                            (org.telegram.ui.Cells.ChatMessageCell) v;\n"
        "                    org.telegram.ui.Components.IOSMessageMenuPreview iosPreview =\n"
        "                            new org.telegram.ui.Components.IOSMessageMenuPreview(\n"
        "                                    getParentActivity(),\n"
        "                                    currentAccount,\n"
        "                                    selectedObject,\n"
        "                                    selectedMessageCell,\n"
        "                                    themeDelegate\n"
        "                            );\n"
        "                    LinearLayout.LayoutParams iosPreviewParams = LayoutHelper.createLinear(\n"
        "                            LayoutHelper.MATCH_PARENT,\n"
        "                            LayoutHelper.WRAP_CONTENT\n"
        "                    );\n"
        "                    iosPreviewParams.topMargin = AndroidUtilities.dp(2);\n"
        "                    popupLayout.addView(iosPreview, iosPreviewParams);\n"
        "\n"
        "                    org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView previewGap =\n"
        "                            new org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView(\n"
        "                                    getParentActivity(),\n"
        "                                    android.graphics.Color.TRANSPARENT,\n"
        "                                    android.graphics.Color.TRANSPARENT\n"
        "                            );\n"
        "                    previewGap.setTag(\"AUTHORGRAM_IOS_MESSAGE_PREVIEW_GAP\");\n"
        "                    popupLayout.addView(previewGap, LayoutHelper.createLinear(\n"
        "                            LayoutHelper.MATCH_PARENT,\n"
        "                            8\n"
        "                    ));\n"
        "                }\n\n"
    )

    pattern = re.compile(
        r"                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        r".*?"
        r"                \}\n\n"
        r"(?=                scrimPopupWindowItems = new ActionBarMenuSubItem\[items\.size\(\)\];)",
        re.DOTALL,
    )
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"unified ChatActivity preview block count is {count}, expected 1")
    write(CHAT, text)

    check = read(CHAT)
    for required in (
        FLOW_MARKER,
        "popupLayout.addView(iosPreview, iosPreviewParams);",
        PREVIEW_GAP_TAG,
        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "NekoConfig.iOSMessageMenu.Bool()",
    ):
        if required not in check:
            raise SystemExit(f"unified ChatActivity validation missing: {required}")

    for forbidden in (
        "scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);",
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.shouldScrollWithActions()",
        "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
        "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER",
        "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
        "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
    ):
        if forbidden in check:
            raise SystemExit(f"stale split preview ownership remains: {forbidden}")

    print("Unified short/long iOS selected-message preview flow passed")


def patch_scrim() -> None:
    text = read(SCRIM)

    if "import org.telegram.ui.ActionBar.Theme;\n" not in text:
        anchor = "import org.telegram.ui.ActionBar.ActionBarPopupWindow;\n"
        if anchor not in text:
            raise SystemExit("ChatScrim Theme import anchor is missing")
        text = text.replace(
            anchor,
            anchor + "import org.telegram.ui.ActionBar.Theme;\n",
            1,
        )

    field = (
        "    private final List<FrameLayout> bottomViews = new ArrayList<>();\n"
        "    private boolean authorGramUnifiedFooterSeparatorAdded; // AUTHORGRAM_UNIFIED_MENU_FOOTER\n"
    )
    if FOOTER_MARKER not in text:
        old_field = "    private final List<FrameLayout> bottomViews = new ArrayList<>();\n"
        if old_field not in text:
            raise SystemExit("bottomViews field anchor is missing")
        text = text.replace(old_field, field, 1)

    if "authorGramAttachPendingBottomViews(); // AUTHORGRAM_UNIFIED_MENU_FOOTER" not in text:
        anchor = (
            "        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);\n"
            "        int adjustedWidthSpec = widthMeasureSpec;\n\n"
        )
        replacement = (
            "        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);\n"
            "        int adjustedWidthSpec = widthMeasureSpec;\n\n"
            "        authorGramAttachPendingBottomViews(); // AUTHORGRAM_UNIFIED_MENU_FOOTER\n\n"
        )
        if anchor not in text:
            raise SystemExit("onMeasure footer-attach anchor is missing")
        text = text.replace(anchor, replacement, 1)

    old_viewport = (
        "        int availableForActions = Math.max(\n"
        "                AndroidUtilities.dp(96),\n"
        "                effectiveMaxHeight - occupiedHeight\n"
        "        );\n"
    )
    new_viewport = (
        "        // AUTHORGRAM_STRICT_MENU_VIEWPORT\n"
        "        // Never force the popup beyond the real work area. Content that does\n"
        "        // not fit belongs to ActionBarPopupWindowLayout's internal ScrollView.\n"
        "        int availableForActions = Math.max(\n"
        "                1,\n"
        "                effectiveMaxHeight - occupiedHeight\n"
        "        );\n"
    )
    if VIEWPORT_MARKER not in text:
        if old_viewport not in text:
            raise SystemExit("adaptive viewport anchor is missing")
        text = text.replace(old_viewport, new_viewport, 1)

    helper = r'''    // AUTHORGRAM_UNIFIED_MENU_FOOTER
    // Move quick actions into the same ActionBarPopupWindowLayout content as the
    // normal action rows. ActionBarPopupWindowLayout.addView() routes these views
    // into its internal LinearLayout/ScrollView, making the entire card reachable.
    private void authorGramAttachPendingBottomViews() {
        if (popupWindowLayout == null || bottomViews.isEmpty()) {
            return;
        }

        ArrayList<FrameLayout> pendingBottomViews = new ArrayList<>(bottomViews);
        bottomViews.clear();

        if (!authorGramUnifiedFooterSeparatorAdded) {
            View authorGramFooterSeparator = new View(getContext());
            // AUTHORGRAM_MENU_FOOTER_SEPARATOR
            authorGramFooterSeparator.setBackgroundColor(Theme.getColor(Theme.key_divider));
            popupWindowLayout.addView(
                    authorGramFooterSeparator,
                    new LinearLayout.LayoutParams(
                            LayoutHelper.MATCH_PARENT,
                            AndroidUtilities.dp(1)
                    )
            );
            authorGramUnifiedFooterSeparatorAdded = true;
        }

        for (FrameLayout bottomView : pendingBottomViews) {
            if (bottomView == null) {
                continue;
            }
            ViewGroup.LayoutParams oldParams = bottomView.getLayoutParams();
            int footerHeight = oldParams != null && oldParams.height != 0
                    ? oldParams.height
                    : LayoutHelper.WRAP_CONTENT;

            if (bottomView.getParent() instanceof ViewGroup) {
                ((ViewGroup) bottomView.getParent()).removeView(bottomView);
            }

            // The popup owns the single rounded card background. Keeping a second
            // footer background here would recreate the visually detached block.
            bottomView.setBackground(null);
            bottomView.setAlpha(1.0f);
            bottomView.setTranslationX(0.0f);
            bottomView.setTranslationY(0.0f);
            bottomView.setScaleX(1.0f);
            bottomView.setScaleY(1.0f);

            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    LayoutHelper.MATCH_PARENT,
                    footerHeight
            );
            footerParams.leftMargin = 0;
            footerParams.rightMargin = 0;
            footerParams.topMargin = 0;
            footerParams.bottomMargin = 0;
            popupWindowLayout.addView(bottomView, footerParams);
        }
    }

'''
    if "private void authorGramAttachPendingBottomViews()" not in text:
        anchor = "    public void applyViewBottom(FrameLayout bottomView) {\n"
        if anchor not in text:
            raise SystemExit("applyViewBottom helper anchor is missing")
        text = text.replace(anchor, helper + anchor, 1)

    old_apply = (
        "    public void applyViewBottom(FrameLayout bottomView) {\n"
        "        if (bottomView != null) {\n"
        "            bottomViews.add(bottomView);\n"
        "            if (popupWindowLayout != null) {\n"
        "                updateBottomOffset();\n"
        "            }\n"
        "        }\n"
        "    }\n"
    )
    new_apply = (
        "    public void applyViewBottom(FrameLayout bottomView) {\n"
        "        if (bottomView != null && !bottomViews.contains(bottomView)) {\n"
        "            // AUTHORGRAM_UNIFIED_MENU_FOOTER\n"
        "            // Queue until measure: by then all normal menu rows are present,\n"
        "            // so the footer is appended last inside the popup ScrollView.\n"
        "            bottomViews.add(bottomView);\n"
        "            requestLayout();\n"
        "        }\n"
        "    }\n"
    )
    if new_apply not in text:
        if old_apply not in text:
            raise SystemExit("applyViewBottom legacy block is missing")
        text = text.replace(old_apply, new_apply, 1)

    write(SCRIM, text)

    check = read(SCRIM)
    for required in (
        FOOTER_MARKER,
        SEPARATOR_MARKER,
        VIEWPORT_MARKER,
        "authorGramAttachPendingBottomViews();",
        "private void authorGramAttachPendingBottomViews()",
        "Theme.getColor(Theme.key_divider)",
        "AndroidUtilities.dp(1)",
        "popupWindowLayout.addView(bottomView, footerParams);",
        "LayoutHelper.MATCH_PARENT",
        "effectiveMaxHeight - occupiedHeight",
        "bottomView.setBackground(null);",
    ):
        if required not in check:
            raise SystemExit(f"unified ChatScrim validation missing: {required}")

    if "AndroidUtilities.dp(96),\n                effectiveMaxHeight - occupiedHeight" in check:
        raise SystemExit("artificial 96dp popup minimum remains")

    print("Unified message-menu footer/card width, separator and strict viewport passed")


def validate() -> None:
    chat = read(CHAT)
    scrim = read(SCRIM)

    if chat.count(FLOW_MARKER) != 1:
        raise SystemExit(
            f"unified preview marker count is {chat.count(FLOW_MARKER)}, expected 1"
        )
    if scrim.count("private void authorGramAttachPendingBottomViews()") != 1:
        raise SystemExit("unified footer helper count is not exactly one")
    if scrim.count(SEPARATOR_MARKER) != 1:
        raise SystemExit("unified footer separator marker count is not exactly one")
    if scrim.count(VIEWPORT_MARKER) != 1:
        raise SystemExit("strict viewport marker count is not exactly one")

    if "AUTHORGRAM_STANDARD_CHAT_HEADER" not in (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java"
    ).read_text(encoding="utf-8"):
        raise SystemExit("standard non-centered chat header invariant was lost")

    enter = (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
    ).read_text(encoding="utf-8")
    for required in (
        "AUTHORGRAM_MAIN_ONLY_IOS_INPUT",
        "AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT",
        "public View getSendButtonInternal() {",
    ):
        if required not in enter:
            raise SystemExit(f"composer invariant was lost: {required}")
    if enter.count("public View getSendButtonInternal() {") != 1:
        raise SystemExit("native getSendButtonInternal() method count is not exactly one")

    preview = (
        ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
    ).read_text(encoding="utf-8")
    for required in (
        "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK",
        "BackupImageView avatarView",
        "TextView senderNameView",
        "sourceCell.draw(canvas);",
        "AuthorGramPlayPolicy.canUseIosUi()",
    ):
        if required not in preview:
            raise SystemExit(f"selected-message preview invariant was lost: {required}")

    for forbidden in (
        "scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);",
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.shouldScrollWithActions()",
        "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
        "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER",
    ):
        if forbidden in chat:
            raise SystemExit(f"split preview ownership survived final canonicalization: {forbidden}")

    print("AuthorGram unified message-menu canonical validation passed")


def main() -> None:
    patch_chat_activity()
    patch_scrim()
    validate()


if __name__ == "__main__":
    main()
