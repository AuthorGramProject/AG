#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
POPUP = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"

STABLE_OWNER = "AUTHORGRAM_STABLE_FIXED_IOS_PREVIEW"
STABLE_INPUT = "AUTHORGRAM_STABLE_IOS_INPUT_LIFECYCLE"
STABLE_FOOTER = "AUTHORGRAM_STABLE_IOS_MENU_FOOTER"
STABLE_PREVIEW = "AUTHORGRAM_NATIVE_ONLY_IOS_MESSAGE_PREVIEW"
WEB_PREVIEW_SAFE = "AUTHORGRAM_WEB_PREVIEW_SAFE_IOS_MESSAGE_PREVIEW"
BOUNDED_PREVIEW = "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW"
SEPARATE_PREVIEW = "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW"
LIVE_STYLE = "AUTHORGRAM_LIVE_IOS_INPUT_STYLE_GATE"
COMPACT_FOOTER = "AUTHORGRAM_COMPACT_IOS_MENU_FOOTER"
CLASSIC_POPUP = "AUTHORGRAM_CLASSIC_POPUP_ZERO_EXTRA_PADDING"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


PREVIEW_SOURCE = r'''package org.telegram.ui.Components;

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
 *
 * A fresh Telegram ChatMessageCell renders avatar, sender, reply/quote, media
 * and message text. Tall content is bounded and scrolls inside this independent
 * preview; it is never inserted into the action-card ScrollView.
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

        int viewportHeight = Math.max(AndroidUtilities.dp(320), AndroidUtilities.displaySize.y);
        maxPreviewHeight = Math.max(
                AndroidUtilities.dp(120),
                Math.min(AndroidUtilities.dp(300), Math.round(viewportHeight * 0.34f))
        );

        if (!AuthorGramPlayPolicy.canUseIosUi()
                || !NekoConfig.iOSMessageMenu.Bool()
                || messageObject == null) {
            setVisibility(GONE);
            previewCell = null;
            previewScroll = null;
            return;
        }

        previewScroll = new ScrollView(context);
        previewScroll.setFillViewport(false);
        previewScroll.setVerticalScrollBarEnabled(false);
        previewScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        previewScroll.setClipToPadding(false);

        previewCell = new ChatMessageCell(context, currentAccount);
        previewCell.setTag(NATIVE_PREVIEW_TAG);
        previewCell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewCell.setClickable(false);
        previewCell.setLongClickable(false);
        previewCell.setFocusable(false);
        previewCell.setEnabled(false);
        previewCell.isChat = sourceCell != null && sourceCell.isChat;
        previewCell.setFullyDraw(true);
        previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            @Override
            public boolean canPerformActions() {
                return false;
            }
        });
        previewCell.setMessageObject(messageObject, null, false, false, false);

        previewScroll.addView(previewCell, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
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

    public boolean shouldScrollWithActions() {
        return false;
    }
}
'''


def patch_preview() -> None:
    write(PREVIEW, PREVIEW_SOURCE)


def patch_chat_owner() -> None:
    text = read(CHAT)
    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_STABLE_FIXED_IOS_PREVIEW\n"
        "                // AUTHORGRAM_WEB_PREVIEW_SAFE_IOS_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW\n"
        "                // Selected message is always separate from the action card.\n"
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
        "                    // AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT\n"
        "                    android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();\n"
        "                    while (authorgramIosPreviewParent != null\n"
        "                            && !(authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout)) {\n"
        "                        if (authorgramIosPreviewParent instanceof android.view.View) {\n"
        "                            authorgramIosPreviewParent =\n"
        "                                    ((android.view.View) authorgramIosPreviewParent).getParent();\n"
        "                        } else {\n"
        "                            authorgramIosPreviewParent = null;\n"
        "                        }\n"
        "                    }\n"
        "                    if (authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout) {\n"
        "                        ((org.telegram.ui.Components.ChatScrimPopupContainerLayout) authorgramIosPreviewParent)\n"
        "                                .setFixedMessagePreview(iosPreview);\n"
        "                    } else {\n"
        "                        iosPreview.setVisibility(android.view.View.GONE);\n"
        "                        org.telegram.messenger.FileLog.e(\"AuthorGram: iOS preview owner not found\");\n"
        "                    }\n"
        "                }\n\n"
    )
    pattern = re.compile(
        r"                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        r".*?"
        r"(?=                scrimPopupWindowItems = new ActionBarMenuSubItem\[items\.size\(\)\];)",
        re.DOTALL,
    )
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"stable ChatActivity preview block count is {count}, expected 1")
    write(CHAT, text)


def patch_input_lifecycle() -> None:
    text = read(ENTER)

    old_style = (
        "    public boolean isIOSInputStyle() {\n"
        "        return iosLayoutMode != null ? iosLayoutMode : computeIOSInputStyle();\n"
        "    }\n"
    )
    new_style = (
        "    // AUTHORGRAM_LIVE_IOS_INPUT_STYLE_GATE\n"
        "    public boolean isIOSInputStyle() {\n"
        "        return computeIOSInputStyle();\n"
        "    }\n"
    )
    if old_style in text:
        text = text.replace(old_style, new_style, 1)
    elif LIVE_STYLE not in text:
        raise SystemExit("Unable to locate isIOSInputStyle cache gate")

    pattern = re.compile(
        r"    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER\n"
        r".*?"
        r"(?=    public void checkSendButton\(boolean animated\) \{)",
        re.DOTALL,
    )
    helper = r'''    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER
    // AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT
    // AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX
    // AUTHORGRAM_STABLE_IOS_INPUT_LIFECYCLE
    private final Runnable authorGramInputMenuInvariantRunnable =
            this::authorGramEnforceInputMenuInvariant;

    private void authorGramEnforceInputMenuInvariant() {
        if (!isIOSInputStyle()
                || !isAttachedToWindow()
                || audioVideoButtonContainer == null
                || recordingAudioVideo
                || editingMessageObject != null) {
            return;
        }

        CharSequence composerText = messageEditText == null
                ? ""
                : AndroidUtilities.getTrimmedString(messageEditText.getTextToUse());
        final boolean hasComposerText = !TextUtils.isEmpty(composerText);
        final boolean finiteSlowModeOwnsSlot = slowModeTimer > 0
                && slowModeTimer != Integer.MAX_VALUE
                && !isSlowModeIgnored();

        audioVideoButtonContainer.animate().cancel();
        audioVideoButtonContainer.clearAnimation();
        audioVideoButtonContainer.setTranslationX(0.0f);
        audioVideoButtonContainer.setTranslationY(0.0f);
        audioVideoButtonContainer.setScaleX(1.0f);
        audioVideoButtonContainer.setScaleY(1.0f);

        View sendButtonView = getSendButtonInternal();
        if (hasComposerText && !finiteSlowModeOwnsSlot) {
            audioVideoButtonContainer.setVisibility(GONE);
            audioVideoButtonContainer.setAlpha(0.0f);
            audioVideoButtonContainer.setClickable(false);
            audioVideoButtonContainer.setEnabled(false);

            if (sendButtonView != null) {
                sendButtonView.animate().cancel();
                sendButtonView.clearAnimation();
                sendButtonView.setVisibility(VISIBLE);
                sendButtonView.setAlpha(1.0f);
                sendButtonView.setScaleX(1.0f);
                sendButtonView.setScaleY(1.0f);
                sendButtonView.setTranslationX(0.0f);
                sendButtonView.setTranslationY(0.0f);
                sendButtonView.setClickable(true);
                sendButtonView.setEnabled(true);
                sendButtonView.bringToFront();
            }
        } else if (!hasComposerText && !finiteSlowModeOwnsSlot) {
            // AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE
            audioVideoButtonContainer.setVisibility(VISIBLE);
            audioVideoButtonContainer.setAlpha(1.0f);
            audioVideoButtonContainer.setClickable(true);
            audioVideoButtonContainer.setEnabled(true);
        }
    }

    private void authorGramScheduleInputMenuInvariant() {
        if (audioVideoButtonContainer == null) {
            return;
        }

        audioVideoButtonContainer.removeCallbacks(authorGramInputMenuInvariantRunnable);
        if (!isIOSInputStyle() || !isAttachedToWindow()) {
            return;
        }

        authorGramEnforceInputMenuInvariant();
        audioVideoButtonContainer.post(authorGramInputMenuInvariantRunnable);
        audioVideoButtonContainer.postDelayed(authorGramInputMenuInvariantRunnable, 160L);
    }

'''
    text, count = pattern.subn(helper, text, count=1)
    if count != 1:
        raise SystemExit(f"stable input invariant helper count is {count}, expected 1")
    write(ENTER, text)


def patch_scrim_footer() -> None:
    text = read(SCRIM)

    if "private boolean authorGramIosMessageMenuActive()" not in text:
        anchor = "    // AUTHORGRAM_UNIFIED_MENU_FOOTER\n"
        helper = (
            "    // AUTHORGRAM_STABLE_IOS_MENU_FOOTER\n"
            "    private boolean authorGramIosMessageMenuActive() {\n"
            "        return AuthorGramPlayPolicy.canUseIosUi() && NekoConfig.iOSMessageMenu.Bool();\n"
            "    }\n\n"
        )
        if anchor not in text:
            raise SystemExit("ChatScrim unified footer helper anchor missing")
        text = text.replace(anchor, helper + anchor, 1)

    if STABLE_FOOTER not in text:
        text = text.replace(
            "    private boolean authorGramIosMessageMenuActive() {\n",
            "    // AUTHORGRAM_STABLE_IOS_MENU_FOOTER\n"
            "    private boolean authorGramIosMessageMenuActive() {\n",
            1,
        )

    text = text.replace(
        "Math.min(oldParams.height, AndroidUtilities.dp(44))",
        "Math.min(oldParams.height, AndroidUtilities.dp(40))",
    )
    text = text.replace(
        ": AndroidUtilities.dp(44);",
        ": AndroidUtilities.dp(40);",
    )
    if COMPACT_FOOTER not in text:
        marker_anchor = "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        if marker_anchor not in text:
            raise SystemExit("ChatScrim compact footer anchor missing")
        text = text.replace(
            marker_anchor,
            "            // AUTHORGRAM_COMPACT_IOS_MENU_FOOTER\n" + marker_anchor,
            1,
        )

    text = text.replace(
        "            params.bottomMargin = AndroidUtilities.dp(4);\n",
        "            params.bottomMargin = 0;\n",
        1,
    )
    write(SCRIM, text)


def patch_popup_padding() -> None:
    text = read(POPUP)
    old = (
        "                    scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);\n"
        "                    scrollView.setPadding(0, 0, 0, dp(8));\n"
    )
    new = (
        "                    scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);\n"
        "                    // AUTHORGRAM_CLASSIC_POPUP_ZERO_EXTRA_PADDING\n"
        "                    // Do not inflate every classic Telegram popup by 8dp.\n"
        "                    // The iOS message menu owns its own explicit separators.\n"
        "                    scrollView.setPadding(0, 0, 0, 0);\n"
    )
    if old in text:
        text = text.replace(old, new, 1)
    elif CLASSIC_POPUP not in text:
        raise SystemExit("ActionBarPopupWindow padding anchor missing")
    write(POPUP, text)


def validate() -> None:
    preview = read(PREVIEW)
    chat = read(CHAT)
    enter = read(ENTER)
    scrim = read(SCRIM)
    popup = read(POPUP)

    failures: list[str] = []

    for required in (
        STABLE_PREVIEW,
        WEB_PREVIEW_SAFE,
        BOUNDED_PREVIEW,
        "new ChatMessageCell(context, currentAccount)",
        "new ScrollView(context)",
        "maxPreviewHeight",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
        "previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate()",
        "NekoConfig.iOSMessageMenu.Bool()",
        "return false;",
    ):
        if required not in preview:
            failures.append(f"preview missing {required}")

    for forbidden in (
        "Bitmap.createBitmap",
        "getPixels(",
        "sourceCell.draw(",
        "BackupImageView avatarView",
        "TextView senderNameView",
        "Theme.createRoundRectDrawable",
        "NativeCellSnapshotView",
    ):
        if forbidden in preview:
            failures.append(f"unsafe/synthetic preview code remains: {forbidden}")

    for required in (
        STABLE_OWNER,
        WEB_PREVIEW_SAFE,
        SEPARATE_PREVIEW,
        "NekoConfig.iOSMessageMenu.Bool()",
        "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
        ".setFixedMessagePreview(iosPreview);",
        "iosPreview.setVisibility(android.view.View.GONE);",
    ):
        if required not in chat:
            failures.append(f"ChatActivity missing {required}")

    for forbidden in (
        "popupLayout.addView(iosPreview",
        "iosPreview.shouldScrollWithActions()",
        "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
    ):
        if forbidden in chat:
            failures.append(f"ChatActivity preview/action regression remains: {forbidden}")

    for required in (
        STABLE_INPUT,
        LIVE_STYLE,
        "return computeIOSInputStyle();",
        "if (!isIOSInputStyle() || !isAttachedToWindow())",
        "removeCallbacks(authorGramInputMenuInvariantRunnable);",
        "postDelayed(authorGramInputMenuInvariantRunnable, 160L);",
    ):
        if required not in enter:
            failures.append(f"composer missing {required}")
    if "return iosLayoutMode != null ? iosLayoutMode : computeIOSInputStyle();" in enter:
        failures.append("stale cached iOS input mode remains")

    for required in (
        STABLE_FOOTER,
        COMPACT_FOOTER,
        "Math.min(oldParams.height, AndroidUtilities.dp(40))",
        "|| !authorGramIosMessageMenuActive())",
        "params.bottomMargin = 0;",
    ):
        if required not in scrim:
            failures.append(f"scrim/footer missing {required}")

    for required in (
        CLASSIC_POPUP,
        "scrollView.setPadding(0, 0, 0, 0);",
    ):
        if required not in popup:
            failures.append(f"popup missing {required}")
    if "scrollView.setPadding(0, 0, 0, dp(8));" in popup:
        failures.append("global 8dp popup bottom padding remains")

    if failures:
        raise SystemExit("AuthorGram Main stability validation failed:\n - " + "\n - ".join(failures))
    print("AuthorGram Main stability validation passed")


def main() -> None:
    patch_preview()
    patch_chat_owner()
    patch_input_lifecycle()
    patch_scrim_footer()
    patch_popup_padding()
    validate()


if __name__ == "__main__":
    main()
