#!/usr/bin/env python3
"""Final native-renderer and geometry repair for AuthorGram Main message menus.

This pass intentionally runs after patch_authorgram_main_stability.py. It owns the
final Main-only selected-message preview and deliberately reuses Telegram's live
ChatMessageCell model instead of synthesizing avatar/name/reply/media UI.

The critical geometry rule comes directly from Telegram PollItemMenu/TodoItemMenu:
a full-cell clone keeps the source cell width/height and copies visible/parameter/
spoiler state before rebinding the same MessageObject. The outer AuthorGram preview
therefore owns the full chat work area; the narrower action popup must never resize
or horizontally offset the native message cell.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
DIALOGS = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Adapters/DialogsAdapter.java"

NATIVE_CONTEXT_MARKER = "AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT"
VISIBLE_CONTEXT_MARKER = "AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT"
SOURCE_GEOMETRY_MARKER = "AUTHORGRAM_NATIVE_SOURCE_CELL_GEOMETRY"
WORKAREA_OWNER_MARKER = "AUTHORGRAM_IOS_PREVIEW_CHAT_WORKAREA_OWNER"
NO_POPUP_WIDTH_MARKER = "AUTHORGRAM_IOS_PREVIEW_NATIVE_SOURCE_GEOMETRY"
NATURAL_FOOTER_MARKER = "AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT"
ME_URL_DIFF_MARKER = "AUTHORGRAM_TELEGRAM_ME_URL_DIFF_FIX"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label} count is {count}, expected 1")
    return text.replace(old, new, 1)


FINAL_PREVIEW_SOURCE = r'''package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Main-only native selected-message preview for the iOS-style context menu.
 *
 * AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK
 * AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_FINAL_PREVIEW_COMPAT
 * AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY
 * AUTHORGRAM_NATIVE_ONLY_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_WEB_PREVIEW_SAFE_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW
 * AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY
 * AUTHORGRAM_NATIVE_CHAT_CELL_CONTEXT
 * AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT
 * AUTHORGRAM_NATIVE_SOURCE_CELL_GEOMETRY
 *
 * This is a real Telegram ChatMessageCell clone. The source cell owns sender,
 * avatar, reply/quote, media/file and bubble geometry; AuthorGram only places the
 * clone above the action card and bounds the vertical viewport for long messages.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";

    private final ChatMessageCell previewCell;
    private final ScrollView previewScroll;
    private final int maxPreviewHeight;

    public IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag(NATIVE_PREVIEW_TAG);
        setClipChildren(true);
        setClipToPadding(true);

        int viewportHeight = Math.max(AndroidUtilities.dp(360), AndroidUtilities.displaySize.y);
        maxPreviewHeight = Math.max(
                AndroidUtilities.dp(140),
                Math.min(AndroidUtilities.dp(420), Math.round(viewportHeight * 0.46f))
        );

        if (!AuthorGramPlayPolicy.canUseIosUi()
                || !NekoConfig.iOSMessageMenu.Bool()
                || messageObject == null) {
            setVisibility(GONE);
            previewCell = null;
            previewScroll = null;
            return;
        }

        final int sourceCellWidth = sourceCell != null && sourceCell.getWidth() > 0
                ? sourceCell.getWidth()
                : 0;
        final int sourceCellHeight = sourceCell != null && sourceCell.getHeight() > 0
                ? sourceCell.getHeight()
                : 0;

        previewScroll = new ScrollView(context);
        previewScroll.setFillViewport(false);
        previewScroll.setVerticalScrollBarEnabled(false);
        previewScroll.setHorizontalScrollBarEnabled(false);
        previewScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        previewScroll.setClipToPadding(false);
        previewScroll.setNestedScrollingEnabled(true);

        // AUTHORGRAM_NATIVE_SOURCE_CELL_GEOMETRY
        // Telegram PollItemMenu clones the complete live ChatMessageCell at the
        // exact source width/height. Preserve that geometry so the incoming
        // avatar lane and bubble/text coordinates cannot be cropped by the menu.
        previewCell = new ChatMessageCell(
                context,
                currentAccount,
                false,
                null,
                sourceCell != null ? sourceCell.getResourcesProvider() : resourcesProvider
        ) {
            @Override
            public void setPressed(boolean pressed) {
                // Preview is intentionally non-interactive.
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (sourceCellWidth > 0 && sourceCellHeight > 0) {
                    setMeasuredDimension(sourceCellWidth, sourceCellHeight);
                } else {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }
            }
        };
        previewCell.setTag(NATIVE_PREVIEW_TAG);
        previewCell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewCell.setClickable(false);
        previewCell.setLongClickable(false);
        previewCell.setFocusable(false);
        previewCell.setEnabled(false);
        previewCell.setFullyDraw(true);

        if (sourceCell != null) {
            // AUTHORGRAM_NATIVE_VISIBLE_PART_CONTEXT
            sourceCell.copyVisiblePartTo(previewCell);
            sourceCell.copyParamsTo(previewCell);
            previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);
        }

        previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            @Override
            public boolean canPerformActions() {
                return false;
            }
        });

        if (sourceCell != null) {
            previewCell.setMessageObject(
                    messageObject,
                    sourceCell.getCurrentMessagesGroup(),
                    sourceCell.pinnedBottom,
                    sourceCell.pinnedTop,
                    sourceCell.firstInChat
            );
        } else {
            previewCell.setMessageObject(messageObject, null, false, false, false);
        }

        int childWidth = sourceCellWidth > 0
                ? sourceCellWidth
                : ScrollView.LayoutParams.MATCH_PARENT;
        previewScroll.addView(previewCell, new ScrollView.LayoutParams(
                childWidth,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        addView(previewScroll, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentSize = MeasureSpec.getSize(heightMeasureSpec);
        int cap = parentMode == MeasureSpec.UNSPECIFIED || parentSize <= 0
                ? maxPreviewHeight
                : Math.min(parentSize, maxPreviewHeight);
        super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(Math.max(1, cap), MeasureSpec.AT_MOST)
        );
    }

    /** Compatibility API: selected-message content never joins action rows. */
    public boolean shouldScrollWithActions() {
        return false;
    }
}
'''


def patch_native_preview_context() -> None:
    # IOSMessageMenuPreview is an AuthorGram-owned Main-only component. Replacing
    # it deterministically is safer than layering another fragile anchor over a
    # generator that intentionally emits an intermediate compatibility shape.
    write(PREVIEW, FINAL_PREVIEW_SOURCE)


def patch_preview_container_geometry() -> None:
    text = read(SCRIM)
    if WORKAREA_OWNER_MARKER in text:
        return

    canonical = (
        "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
        "                    LayoutHelper.WRAP_CONTENT,\n"
        "                    LayoutHelper.WRAP_CONTENT\n"
        "            );\n"
        "            params.bottomMargin = AndroidUtilities.dp(4);\n"
    )
    canonical_reference = (
        "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
        "                    LayoutHelper.WRAP_CONTENT,\n"
        "                    LayoutHelper.WRAP_CONTENT\n"
        "            );\n"
        "            // AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY\n"
        "            params.topMargin = AndroidUtilities.dp(8);\n"
        "            params.bottomMargin = AndroidUtilities.dp(8);\n"
    )
    previous_aligned = (
        "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
        "                    LayoutHelper.WRAP_CONTENT,\n"
        "                    LayoutHelper.WRAP_CONTENT\n"
        "            );\n"
        "            // AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT\n"
        "            // Keep the selected native message anchored with the action card,\n"
        "            // but do not later force its width down to the action-card width.\n"
        "            if (popupWindowLayout != null\n"
        "                    && popupWindowLayout.getLayoutParams() instanceof LinearLayout.LayoutParams) {\n"
        "                LinearLayout.LayoutParams popupParams =\n"
        "                        (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();\n"
        "                params.leftMargin = popupParams.leftMargin;\n"
        "                params.rightMargin = popupParams.rightMargin;\n"
        "                params.setMarginStart(popupParams.getMarginStart());\n"
        "                params.setMarginEnd(popupParams.getMarginEnd());\n"
        "                params.gravity = popupParams.gravity;\n"
        "            }\n"
        "            // AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY\n"
        "            params.topMargin = AndroidUtilities.dp(8);\n"
        "            params.bottomMargin = AndroidUtilities.dp(8);\n"
    )
    previous_aligned_legacy_comment = previous_aligned.replace(
        "            // Keep the selected native message anchored with the action card,\n"
        "            // but do not later force its width down to the action-card width.\n",
        "            // The action popup gets asymmetric reaction-side margins in\n"
        "            // ChatActivity. Give the native selected-message cell the same\n"
        "            // horizontal footprint instead of laying it out from x=0.\n",
    )
    desired = (
        "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
        "                    LayoutHelper.MATCH_PARENT,\n"
        "                    LayoutHelper.WRAP_CONTENT\n"
        "            );\n"
        "            // AUTHORGRAM_IOS_PREVIEW_CHAT_WORKAREA_OWNER\n"
        "            // The selected Telegram cell owns chat-list geometry, including\n"
        "            // the incoming avatar lane. Keep the wrapper on the full work area;\n"
        "            // popup-card margins belong only to the action card below.\n"
        "            params.leftMargin = 0;\n"
        "            params.rightMargin = 0;\n"
        "            params.setMarginStart(0);\n"
        "            params.setMarginEnd(0);\n"
        "            // AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY\n"
        "            params.topMargin = AndroidUtilities.dp(8);\n"
        "            params.bottomMargin = AndroidUtilities.dp(8);\n"
    )

    for old in (previous_aligned, previous_aligned_legacy_comment, canonical_reference, canonical):
        if old in text:
            text = text.replace(old, desired, 1)
            write(SCRIM, text)
            return
    raise SystemExit("Unable to locate known fixed-preview container geometry")


def patch_preview_width_mutation() -> None:
    text = read(SCRIM)
    if NO_POPUP_WIDTH_MARKER in text:
        return

    previous_full_width = (
        "        if (fixedMessagePreview != null) {\n"
        "            LinearLayout.LayoutParams previewParams =\n"
        "                    (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();\n"
        "            // AUTHORGRAM_IOS_PREVIEW_FULL_WIDTH_MEASURE\n"
        "            // ChatMessageCell text/name/avatar geometry is calculated for the\n"
        "            // chat work area, not for the narrower action popup. Giving the\n"
        "            // preview only popupWindowLayout.getMeasuredWidth() clips native\n"
        "            // text and can suppress sender-side layout. Preserve placement\n"
        "            // margins, but measure against the real parent work area.\n"
        "            int parentWidthForPreview = MeasureSpec.getSize(adjustedWidthSpec);\n"
        "            int horizontalMargins = Math.max(0, previewParams.leftMargin)\n"
        "                    + Math.max(0, previewParams.rightMargin);\n"
        "            int previewWidth = Math.max(\n"
        "                    1,\n"
        "                    parentWidthForPreview\n"
        "                            - getPaddingLeft()\n"
        "                            - getPaddingRight()\n"
        "                            - horizontalMargins\n"
        "            );\n"
        "            if (parentWidthForPreview > 0 && previewParams.width != previewWidth) {\n"
        "                previewParams.width = previewWidth;\n"
        "                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
        "            }\n"
        "        }\n\n"
    )
    canonical_popup_width = (
        "        if (fixedMessagePreview != null) {\n"
        "            int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();\n"
        "            LinearLayout.LayoutParams previewParams =\n"
        "                    (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();\n"
        "            if (popupWidthForPreview > 0 && previewParams.width != popupWidthForPreview) {\n"
        "                previewParams.width = popupWidthForPreview;\n"
        "                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
        "            }\n"
        "        }\n\n"
    )
    desired = (
        "        // AUTHORGRAM_IOS_PREVIEW_NATIVE_SOURCE_GEOMETRY\n"
        "        // Do not mutate fixedMessagePreview width here. Its MATCH_PARENT\n"
        "        // wrapper is measured by this LinearLayout, while the child native\n"
        "        // ChatMessageCell preserves the exact source-cell width/height.\n\n"
    )
    for old in (previous_full_width, canonical_popup_width):
        if old in text:
            text = text.replace(old, desired, 1)
            write(SCRIM, text)
            return
    raise SystemExit("Unable to locate known fixed-preview width mutation block")


def patch_natural_footer_height() -> None:
    text = read(SCRIM)
    if NATURAL_FOOTER_MARKER in text:
        return

    old_marked = (
        "            // AUTHORGRAM_COMPACT_IOS_MENU_FOOTER\n"
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? Math.min(oldParams.height, AndroidUtilities.dp(44))\n"
        "                    : AndroidUtilities.dp(44);\n"
    )
    old_unmarked = (
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? Math.min(oldParams.height, AndroidUtilities.dp(44))\n"
        "                    : AndroidUtilities.dp(44);\n"
    )
    new = (
        "            // AUTHORGRAM_NATURAL_MENU_FOOTER_HEIGHT\n"
        "            // Preserve declared Telegram bottom-view height and otherwise\n"
        "            // measure naturally; arbitrary 44dp cropping is forbidden.\n"
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? oldParams.height\n"
        "                    : LayoutHelper.WRAP_CONTENT;\n"
    )
    if old_marked in text:
        text = text.replace(old_marked, new, 1)
    elif old_unmarked in text:
        text = text.replace(old_unmarked, new, 1)
    else:
        raise SystemExit("ChatScrim 44dp footer-cap anchor is missing")
    write(SCRIM, text)


def patch_recent_me_url_diff() -> None:
    text = read(DIALOGS)
    if ME_URL_DIFF_MARKER in text:
        return

    old = (
        "                return recentMeUrl != null && itemInternal.recentMeUrl != null "
        "&& recentMeUrl.url != null && recentMeUrl.url.equals(recentMeUrl.url);\n"
    )
    new = (
        "                // AUTHORGRAM_TELEGRAM_ME_URL_DIFF_FIX\n"
        "                // Compare old/new URL items; self-comparison breaks DiffUtil.\n"
        "                return recentMeUrl != null && itemInternal.recentMeUrl != null "
        "&& recentMeUrl.url != null && recentMeUrl.url.equals(itemInternal.recentMeUrl.url);\n"
    )
    if old in text:
        text = text.replace(old, new, 1)
        write(DIALOGS, text)
        return
    if "recentMeUrl.url.equals(itemInternal.recentMeUrl.url)" in text:
        return
    raise SystemExit("DialogsAdapter recent .me URL comparison anchor is missing")


def validate() -> None:
    preview = read(PREVIEW)
    scrim = read(SCRIM)
    dialogs = read(DIALOGS)

    required_preview = (
        NATIVE_CONTEXT_MARKER,
        VISIBLE_CONTEXT_MARKER,
        SOURCE_GEOMETRY_MARKER,
        "sourceCell.getWidth()",
        "sourceCell.getHeight()",
        "sourceCell.getResourcesProvider()",
        "setMeasuredDimension(sourceCellWidth, sourceCellHeight);",
        "sourceCell.copyVisiblePartTo(previewCell);",
        "sourceCell.copyParamsTo(previewCell);",
        "previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);",
        "previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()",
        "sourceCell.getCurrentMessagesGroup()",
        "sourceCell.pinnedBottom",
        "sourceCell.pinnedTop",
        "sourceCell.firstInChat",
        "Math.min(AndroidUtilities.dp(420), Math.round(viewportHeight * 0.46f))",
    )
    for token in required_preview:
        if token not in preview:
            raise SystemExit(f"native source-cell preview invariant missing: {token}")

    clone_positions = [
        preview.find("sourceCell.copyVisiblePartTo(previewCell);"),
        preview.find("sourceCell.copyParamsTo(previewCell);"),
        preview.find("previewCell.copySpoilerEffect2AttachIndexFrom(sourceCell);"),
        preview.find("previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()"),
        preview.find("sourceCell.getCurrentMessagesGroup()"),
    ]
    if any(position < 0 for position in clone_positions) or clone_positions != sorted(clone_positions):
        raise SystemExit("native ChatMessageCell clone order diverges from Telegram full-cell clone")

    for token in (
        "Bitmap.createBitmap",
        "BackupImageView avatarView",
        "TextView senderNameView",
        "previewCell.isChat = sourceCell != null && sourceCell.isChat;",
    ):
        if token in preview:
            raise SystemExit(f"synthetic/partial sender renderer survived: {token}")

    for token in (
        WORKAREA_OWNER_MARKER,
        "LayoutHelper.MATCH_PARENT,",
        "params.setMarginStart(0);",
        "params.setMarginEnd(0);",
        NO_POPUP_WIDTH_MARKER,
        NATURAL_FOOTER_MARKER,
        "? oldParams.height",
        ": LayoutHelper.WRAP_CONTENT;",
    ):
        if token not in scrim:
            raise SystemExit(f"message-menu source geometry invariant missing: {token}")

    forbidden_scrim = (
        "AUTHORGRAM_IOS_PREVIEW_CARD_ALIGNMENT",
        "AUTHORGRAM_IOS_PREVIEW_FULL_WIDTH_MEASURE",
        "params.setMarginStart(popupParams.getMarginStart());",
        "params.setMarginEnd(popupParams.getMarginEnd());",
        "params.gravity = popupParams.gravity;",
        "previewParams.width = popupWidthForPreview;",
        "previewParams.width = previewWidth;",
        "int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();",
        "int parentWidthForPreview = MeasureSpec.getSize(adjustedWidthSpec);",
        "Math.min(oldParams.height, AndroidUtilities.dp(44))",
    )
    for token in forbidden_scrim:
        if token in scrim:
            raise SystemExit(f"popup-owned clipping geometry survived: {token}")

    if "recentMeUrl.url.equals(recentMeUrl.url)" in dialogs:
        raise SystemExit("DialogsAdapter recent .me URL self-comparison survived")
    if "recentMeUrl.url.equals(itemInternal.recentMeUrl.url)" not in dialogs:
        raise SystemExit("DialogsAdapter recent .me URL DiffUtil fix missing")

    print("AuthorGram native source-cell avatar/name geometry + unclipped chat-workarea preview + footer/.me stability passed")


def apply() -> None:
    patch_native_preview_context()
    patch_preview_container_geometry()
    patch_preview_width_mutation()
    patch_natural_footer_height()
    patch_recent_me_url_diff()
    validate()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("apply", "validate"), default="apply")
    args = parser.parse_args()
    if args.mode == "apply":
        apply()
    else:
        validate()


if __name__ == "__main__":
    main()
