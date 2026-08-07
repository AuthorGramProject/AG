#!/usr/bin/env python3
"""Finalize AuthorGram chat header, iOS composer and message-menu behavior.

This patch is intentionally idempotent. It keeps title centering everywhere
except ChatActivity, fixes ScrollView ownership for all chat context menus, and
moves the Main-only iOS selected-message preview outside the scrolling actions.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ACTION_BAR = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java"
POPUP = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"

HEADER_MARKER = "AUTHORGRAM_STANDARD_CHAT_HEADER"
SCROLL_MARKER = "AUTHORGRAM_RELIABLE_POPUP_SCROLL"
FIXED_MARKER = "AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW"
WIDTH_MARKER = "AUTHORGRAM_MENU_FOOTER_WIDTH_PARITY"
INPUT_MARKER = "AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT"
UNIFIED_MARKER = "AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_standard_chat_header() -> None:
    text = read(ACTION_BAR)
    if HEADER_MARKER not in text:
        old = (
            "    private boolean isCentered() {\n"
            "        return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();\n"
            "    }\n"
        )
        new = (
            "    private boolean isCentered() {\n"
            "        // AUTHORGRAM_STANDARD_CHAT_HEADER\n"
            "        // ChatActivity must always use Telegram's ordinary header.\n"
            "        // CenterActionBarTitle continues to apply to every other screen.\n"
            "        if (parentFragment instanceof org.telegram.ui.ChatActivity) {\n"
            "            return false;\n"
            "        }\n"
            "        return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();\n"
            "    }\n"
        )
        text = replace_once(text, old, new, "standard chat header")
        write(ACTION_BAR, text)

    text = read(ACTION_BAR)
    for required in (
        HEADER_MARKER,
        "parentFragment instanceof org.telegram.ui.ChatActivity",
        "return NaConfig.INSTANCE.getCenterActionBarTitle().Bool();",
    ):
        if required not in text:
            raise SystemExit(f"chat header validation failed: {required}")
    print("Standard non-centered chat header passed for Main and Play")


def patch_reliable_popup_scroll() -> None:
    text = read(POPUP)
    if SCROLL_MARKER not in text:
        old = "                    scrollView.setVerticalScrollBarEnabled(false);\n"
        new = (
            "                    scrollView.setVerticalScrollBarEnabled(false);\n"
            "                    // AUTHORGRAM_RELIABLE_POPUP_SCROLL\n"
            "                    // Keep the content naturally tall and constrain only the viewport.\n"
            "                    // This prevents the final action from being measured out or clipped.\n"
            "                    scrollView.setFillViewport(false);\n"
            "                    scrollView.setScrollContainer(true);\n"
            "                    scrollView.setNestedScrollingEnabled(true);\n"
            "                    scrollView.setClipToPadding(false);\n"
            "                    scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);\n"
            "                    scrollView.setPadding(0, 0, 0, dp(8));\n"
        )
        text = replace_once(text, old, new, "popup ScrollView configuration")
        write(POPUP, text)

    text = read(POPUP)
    for required in (
        SCROLL_MARKER,
        "scrollView.setScrollContainer(true);",
        "scrollView.setFillViewport(false);",
        "scrollView.setPadding(0, 0, 0, dp(8));",
    ):
        if required not in text:
            raise SystemExit(f"popup scrolling validation failed: {required}")
    print("Reliable scrolling passed for normal and iOS message menus")


def patch_scrim_fixed_preview_and_width() -> None:
    text = read(SCRIM)

    if "import android.view.ViewGroup;\n" not in text:
        text = replace_once(
            text,
            "import android.view.View;\n",
            "import android.view.View;\nimport android.view.ViewGroup;\n",
            "fixed preview ViewGroup import",
        )

    if FIXED_MARKER not in text:
        text = replace_once(
            text,
            "    private int maxHeight;\n",
            "    private int maxHeight;\n"
            "    private View fixedMessagePreview; // AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW\n",
            "fixed preview field",
        )

        method_anchor = "    public void setReactionsLayout(ReactionsContainerLayout reactionsLayout) {\n"
        method = (
            "    public void setFixedMessagePreview(View preview) {\n"
            "        if (fixedMessagePreview == preview) {\n"
            "            return;\n"
            "        }\n"
            "        if (fixedMessagePreview != null && fixedMessagePreview.getParent() == this) {\n"
            "            removeView(fixedMessagePreview);\n"
            "        }\n"
            "        fixedMessagePreview = preview;\n"
            "        if (preview != null) {\n"
            "            if (preview.getParent() instanceof ViewGroup) {\n"
            "                ((ViewGroup) preview.getParent()).removeView(preview);\n"
            "            }\n"
            "            int popupIndex = popupWindowLayout == null\n"
            "                    ? getChildCount()\n"
            "                    : indexOfChild(popupWindowLayout);\n"
            "            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n"
            "                    LayoutHelper.WRAP_CONTENT,\n"
            "                    LayoutHelper.WRAP_CONTENT\n"
            "            );\n"
            "            params.bottomMargin = AndroidUtilities.dp(8);\n"
            "            addView(preview, Math.max(0, popupIndex), params);\n"
            "        }\n"
            "        requestLayout();\n"
            "    }\n\n"
        )
        text = replace_once(text, method_anchor, method + method_anchor, "fixed preview API")

        popup_setter_old = (
            "    public void setPopupWindowLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout) {\n"
            "        this.popupWindowLayout = popupWindowLayout;\n"
            "        popupWindowLayout.setOnSizeChangedListener(this::updateBottomOffset);\n"
        )
        popup_setter_new = (
            "    public void setPopupWindowLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout) {\n"
            "        this.popupWindowLayout = popupWindowLayout;\n"
            "        if (fixedMessagePreview != null) {\n"
            "            int popupIndex = indexOfChild(popupWindowLayout);\n"
            "            int previewIndex = indexOfChild(fixedMessagePreview);\n"
            "            if (popupIndex >= 0 && previewIndex > popupIndex) {\n"
            "                LinearLayout.LayoutParams previewParams =\n"
            "                        (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();\n"
            "                removeView(fixedMessagePreview);\n"
            "                addView(fixedMessagePreview, popupIndex, previewParams);\n"
            "            }\n"
            "        }\n"
            "        popupWindowLayout.setOnSizeChangedListener(this::updateBottomOffset);\n"
        )
        text = replace_once(text, popup_setter_old, popup_setter_new, "fixed preview ordering")

        first_measure = (
            "        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);\n"
            "        int adjustedWidthSpec = widthMeasureSpec;\n"
            "        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
            "        if (popupWindowLayout == null) {\n"
            "            return;\n"
            "        }\n"
        )
        fixed_measure = (
            "        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);\n"
            "        int adjustedWidthSpec = widthMeasureSpec;\n"
            "\n"
            "        // Reset a prior temporary height cap before measuring current content.\n"
            "        if (fixedMessagePreview != null && popupWindowLayout != null) {\n"
            "            LinearLayout.LayoutParams popupParams =\n"
            "                    (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();\n"
            "            if (popupParams.height != LayoutHelper.WRAP_CONTENT) {\n"
            "                popupParams.height = LayoutHelper.WRAP_CONTENT;\n"
            "            }\n"
            "        }\n"
            "        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
            "        if (popupWindowLayout == null) {\n"
            "            return;\n"
            "        }\n"
            "\n"
            "        // AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW\n"
            "        // Reactions and the selected-message preview remain fixed. Only the\n"
            "        // popup action viewport receives the remaining height and scrolls.\n"
            "        if (fixedMessagePreview != null) {\n"
            "            int occupiedHeight = getPaddingTop() + getPaddingBottom();\n"
            "            for (int i = 0; i < getChildCount(); i++) {\n"
            "                View child = getChildAt(i);\n"
            "                if (child == popupWindowLayout || child.getVisibility() == GONE) {\n"
            "                    continue;\n"
            "                }\n"
            "                LinearLayout.LayoutParams childParams =\n"
            "                        (LinearLayout.LayoutParams) child.getLayoutParams();\n"
            "                occupiedHeight += child.getMeasuredHeight()\n"
            "                        + childParams.topMargin\n"
            "                        + childParams.bottomMargin;\n"
            "            }\n"
            "            int availableForActions = Math.max(\n"
            "                    AndroidUtilities.dp(72),\n"
            "                    effectiveMaxHeight - occupiedHeight\n"
            "            );\n"
            "            LinearLayout.LayoutParams popupParams =\n"
            "                    (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();\n"
            "            int desiredPopupHeight = popupWindowLayout.getMeasuredHeight();\n"
            "            if (desiredPopupHeight > availableForActions) {\n"
            "                popupParams.height = availableForActions;\n"
            "                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
            "            }\n"
            "\n"
            "            int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();\n"
            "            LinearLayout.LayoutParams previewParams =\n"
            "                    (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();\n"
            "            if (popupWidthForPreview > 0 && previewParams.width != popupWidthForPreview) {\n"
            "                previewParams.width = popupWidthForPreview;\n"
            "                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
            "            }\n"
            "        }\n"
        )
        text = replace_once(text, first_measure, fixed_measure, "fixed preview height allocation")

    if WIDTH_MARKER not in text:
        width_pattern = re.compile(
            r"            int newWidth;\n"
            r"            if \(\(reactionsLayout == null \|\| !reactionsLayout\.showCustomEmojiReaction\(\)\) && view\.getTag\(R\.id\.fit_width_tag\) == null\) \{\n"
            r"                newWidth = LayoutHelper\.MATCH_PARENT;\n"
            r"            \} else \{\n"
            r"                newWidth = foregroundWidth \+ AndroidUtilities\.dp\(16\);\n"
            r"                if \(popupWidth > 0 && newWidth > popupWidth\) \{\n"
            r"                    newWidth = popupWidth;\n"
            r"                \}\n"
            r"            \}\n"
        )
        replacement = (
            "            // AUTHORGRAM_MENU_FOOTER_WIDTH_PARITY\n"
            "            // Bottom quick-action blocks must exactly match the menu card width.\n"
            "            int newWidth = popupWidth > 0 ? popupWidth : foregroundWidth;\n"
        )
        text, count = width_pattern.subn(replacement, text, count=1)
        if count != 1:
            raise SystemExit(f"menu footer width parity: expected one block, found {count}")

    write(SCRIM, text)

    text = read(SCRIM)
    for required in (
        FIXED_MARKER,
        "public void setFixedMessagePreview(View preview)",
        "availableForActions",
        WIDTH_MARKER,
        "int newWidth = popupWidth > 0 ? popupWidth : foregroundWidth;",
    ):
        if required not in text:
            raise SystemExit(f"fixed preview/container validation failed: {required}")
    print("Fixed quote/reactions and menu-width parity passed")


def patch_chat_activity_preview_ownership() -> None:
    text = read(CHAT)
    marker = "AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER"
    if marker not in text:
        replacement = (
            "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
            "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
            "                // AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER\n"
            "                // The preview belongs to ChatScrimPopupContainerLayout, not to\n"
            "                // ActionBarPopupWindow's ScrollView. Only actions can scroll.\n"
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
            "                    scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);\n"
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
            raise SystemExit(f"fixed iOS preview ownership: expected one block, found {count}")
        write(CHAT, text)

    text = read(CHAT)
    for required in (
        marker,
        ".setFixedMessagePreview(iosPreview);",
        "scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];",
    ):
        if required not in text:
            raise SystemExit(f"ChatActivity fixed preview validation failed: {required}")
    if "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP" in text:
        raise SystemExit("obsolete scroll-owned iOS preview gap remains")
    print("ChatActivity fixed preview ownership passed")


def patch_ios_send_button_invariant() -> None:
    text = read(ENTER)
    helper = (
        "    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER\n"
        "    // AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT\n"
        "    private final Runnable authorGramInputMenuInvariantRunnable =\n"
        "            this::authorGramEnforceInputMenuInvariant;\n"
        "\n"
        "    private void authorGramEnforceInputMenuInvariant() {\n"
        "        if (!isIOSInputStyle()\n"
        "                || audioVideoButtonContainer == null\n"
        "                || recordingAudioVideo\n"
        "                || editingMessageObject != null) {\n"
        "            return;\n"
        "        }\n"
        "\n"
        "        CharSequence composerText = messageEditText == null\n"
        "                ? \"\"\n"
        "                : AndroidUtilities.getTrimmedString(messageEditText.getTextToUse());\n"
        "        final boolean hasComposerText = !TextUtils.isEmpty(composerText);\n"
        "        final boolean finiteSlowModeOwnsSlot = slowModeTimer > 0\n"
        "                && slowModeTimer != Integer.MAX_VALUE\n"
        "                && !isSlowModeIgnored();\n"
        "\n"
        "        audioVideoButtonContainer.animate().cancel();\n"
        "        audioVideoButtonContainer.clearAnimation();\n"
        "        audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "        audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "        audioVideoButtonContainer.setScaleX(1.0f);\n"
        "        audioVideoButtonContainer.setScaleY(1.0f);\n"
        "\n"
        "        View sendButtonView = getSendButtonInternal();\n"
        "        if (hasComposerText && !finiteSlowModeOwnsSlot) {\n"
        "            audioVideoButtonContainer.setVisibility(GONE);\n"
        "            audioVideoButtonContainer.setAlpha(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(false);\n"
        "            audioVideoButtonContainer.setEnabled(false);\n"
        "\n"
        "            if (sendButtonView != null) {\n"
        "                sendButtonView.animate().cancel();\n"
        "                sendButtonView.setVisibility(VISIBLE);\n"
        "                sendButtonView.setAlpha(1.0f);\n"
        "                sendButtonView.setScaleX(1.0f);\n"
        "                sendButtonView.setScaleY(1.0f);\n"
        "                sendButtonView.setClickable(true);\n"
        "                sendButtonView.setEnabled(true);\n"
        "                sendButtonView.bringToFront();\n"
        "            }\n"
        "        } else if (!hasComposerText && !finiteSlowModeOwnsSlot) {\n"
        "            // AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE\n"
        "            audioVideoButtonContainer.setVisibility(VISIBLE);\n"
        "            audioVideoButtonContainer.setAlpha(1.0f);\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private void authorGramScheduleInputMenuInvariant() {\n"
        "        authorGramEnforceInputMenuInvariant();\n"
        "        audioVideoButtonContainer.removeCallbacks(authorGramInputMenuInvariantRunnable);\n"
        "        audioVideoButtonContainer.post(authorGramInputMenuInvariantRunnable);\n"
        "        audioVideoButtonContainer.postDelayed(authorGramInputMenuInvariantRunnable, 260L);\n"
        "    }\n"
        "\n"
    )

    pattern = re.compile(
        r"    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER\n"
        r".*?"
        r"(?=    public void checkSendButton\(boolean animated\) \{)",
        re.DOTALL,
    )
    text, count = pattern.subn(helper, text, count=1)
    if count != 1:
        raise SystemExit(f"iOS send-button invariant helper: expected one block, found {count}")
    write(ENTER, text)

    text = read(ENTER)
    for required in (
        INPUT_MARKER,
        "AndroidUtilities.getTrimmedString(messageEditText.getTextToUse())",
        "sendButtonView.setVisibility(VISIBLE);",
        "sendButtonView.setAlpha(1.0f);",
        "audioVideoButtonContainer.setVisibility(GONE);",
        "audioVideoButtonContainer.setVisibility(VISIBLE);",
    ):
        if required not in text:
            raise SystemExit(f"iOS input validation failed: {required}")
    print("iOS input send/media ownership invariant passed")


PREVIEW_SOURCE = r'''package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Main-only fixed selected-message preview for the iOS-style context menu.
 *
 * AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK: avatar, sender and the native Telegram
 * message rendering are one coherent message item. The parent scrim owns this
 * view outside the actions ScrollView, so the quote never moves while actions
 * are scrolled.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";
    public static final String SENDER_IDENTITY_TAG = "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY";

    private final NativeCellSnapshotView snapshotView;

    public IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag(NATIVE_PREVIEW_TAG);
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);

        if (!AuthorGramPlayPolicy.canUseIosUi()) {
            setVisibility(GONE);
            snapshotView = null;
            return;
        }

        LinearLayout messageItem = new LinearLayout(context);
        messageItem.setOrientation(LinearLayout.HORIZONTAL);
        messageItem.setGravity(Gravity.BOTTOM);
        messageItem.setClipChildren(false);
        messageItem.setClipToPadding(false);
        messageItem.setPadding(
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(4)
        );
        addView(messageItem, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));

        SenderIdentity identity = resolveSender(currentAccount, messageObject);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        BackupImageView avatarView = new BackupImageView(context);
        avatarView.setTag(SENDER_IDENTITY_TAG);
        avatarView.setRoundRadius(AndroidUtilities.dp(20));
        if (identity.user != null) {
            avatarDrawable.setInfo(currentAccount, identity.user);
            avatarView.setForUserOrChat(identity.user, avatarDrawable);
        } else if (identity.chat != null) {
            avatarDrawable.setInfo(currentAccount, identity.chat);
            avatarView.setForUserOrChat(identity.chat, avatarDrawable);
        } else {
            avatarDrawable.setInfo(0, identity.name, null);
            avatarView.setImageDrawable(avatarDrawable);
        }

        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(40),
                AndroidUtilities.dp(40)
        );
        avatarParams.rightMargin = AndroidUtilities.dp(7);
        avatarParams.bottomMargin = AndroidUtilities.dp(3);
        messageItem.addView(avatarView, avatarParams);

        LinearLayout unifiedMessage = new LinearLayout(context);
        unifiedMessage.setOrientation(LinearLayout.VERTICAL);
        unifiedMessage.setClipChildren(false);
        unifiedMessage.setClipToPadding(false);
        unifiedMessage.setPadding(
                AndroidUtilities.dp(8),
                AndroidUtilities.dp(5),
                AndroidUtilities.dp(8),
                AndroidUtilities.dp(5)
        );
        int bubbleColor = Theme.getColor(
                messageObject != null && messageObject.isOutOwner()
                        ? Theme.key_chat_outBubble
                        : Theme.key_chat_inBubble,
                resourcesProvider
        );
        unifiedMessage.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(17),
                bubbleColor
        ));
        messageItem.addView(unifiedMessage, new LinearLayout.LayoutParams(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f
        ));

        TextView senderNameView = new TextView(context);
        senderNameView.setTag(SENDER_IDENTITY_TAG);
        senderNameView.setText(identity.name);
        senderNameView.setTextSize(14);
        senderNameView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText,
                resourcesProvider
        ));
        senderNameView.setTypeface(AndroidUtilities.bold());
        senderNameView.setSingleLine(true);
        senderNameView.setEllipsize(TextUtils.TruncateAt.END);
        senderNameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        unifiedMessage.addView(senderNameView, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                AndroidUtilities.dp(21)
        ));

        snapshotView = new NativeCellSnapshotView(context, sourceCell);
        unifiedMessage.addView(snapshotView, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));
    }

    private static SenderIdentity resolveSender(int currentAccount, MessageObject messageObject) {
        long senderId = messageObject == null ? 0 : messageObject.getFromChatId();
        if (senderId == 0 && messageObject != null && messageObject.isOutOwner()) {
            senderId = UserConfig.getInstance(currentAccount).getClientUserId();
        }
        if (senderId == 0 && messageObject != null) {
            senderId = messageObject.getDialogId();
        }

        TLRPC.User user = senderId > 0
                ? MessagesController.getInstance(currentAccount).getUser(senderId)
                : null;
        TLRPC.Chat chat = senderId < 0
                ? MessagesController.getInstance(currentAccount).getChat(-senderId)
                : null;
        if (user == null && chat == null && messageObject != null && messageObject.isOutOwner()) {
            user = UserConfig.getInstance(currentAccount).getCurrentUser();
        }

        String name = null;
        if (user != null) {
            name = UserObject.getUserName(user);
        } else if (chat != null) {
            name = chat.title;
        }
        if (TextUtils.isEmpty(name)) {
            name = "Telegram";
        }
        return new SenderIdentity(user, chat, name);
    }

    private static final class SenderIdentity {
        final TLRPC.User user;
        final TLRPC.Chat chat;
        final String name;

        SenderIdentity(TLRPC.User user, TLRPC.Chat chat, String name) {
            this.user = user;
            this.chat = chat;
            this.name = name;
        }
    }

    private static final class NativeCellSnapshotView extends View {
        private static final int ALPHA_THRESHOLD = 8;
        private final Paint bitmapPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
        );
        private Bitmap snapshot;
        private final Rect destination = new Rect();

        NativeCellSnapshotView(Context context, ChatMessageCell sourceCell) {
            super(context);
            setWillNotDraw(false);
            snapshot = captureNativeCell(sourceCell);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int availableWidth = Math.max(
                    AndroidUtilities.dp(120),
                    MeasureSpec.getSize(widthMeasureSpec)
            );
            if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) {
                setMeasuredDimension(availableWidth, AndroidUtilities.dp(44));
                return;
            }
            int targetWidth = Math.max(1, Math.min(snapshot.getWidth(), availableWidth));
            float scale = targetWidth / (float) snapshot.getWidth();
            int targetHeight = Math.max(1, Math.round(snapshot.getHeight() * scale));
            setMeasuredDimension(availableWidth, targetHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (snapshot == null || snapshot.isRecycled()) {
                return;
            }
            int targetWidth = Math.min(snapshot.getWidth(), Math.max(1, getWidth()));
            float scale = targetWidth / (float) snapshot.getWidth();
            int targetHeight = Math.max(1, Math.round(snapshot.getHeight() * scale));
            int top = Math.max(0, (getHeight() - targetHeight) / 2);
            destination.set(0, top, targetWidth, top + targetHeight);
            canvas.drawBitmap(snapshot, null, destination, bitmapPaint);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (snapshot != null && !snapshot.isRecycled()) {
                snapshot.recycle();
            }
            snapshot = null;
        }

        private static Bitmap captureNativeCell(ChatMessageCell sourceCell) {
            if (sourceCell == null) {
                return null;
            }
            int width = sourceCell.getWidth();
            int height = sourceCell.getHeight();
            if (width <= 0 || height <= 0) {
                width = sourceCell.getMeasuredWidth();
                height = sourceCell.getMeasuredHeight();
            }
            if (width <= 0 || height <= 0) {
                return null;
            }

            Bitmap raw;
            try {
                raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(raw);
                sourceCell.draw(canvas);
            } catch (Throwable ignored) {
                return null;
            }

            Rect visibleBounds = findVisibleBounds(raw);
            if (visibleBounds == null) {
                return raw;
            }
            int padding = AndroidUtilities.dp(2);
            visibleBounds.left = Math.max(0, visibleBounds.left - padding);
            visibleBounds.top = Math.max(0, visibleBounds.top - padding);
            visibleBounds.right = Math.min(raw.getWidth(), visibleBounds.right + padding);
            visibleBounds.bottom = Math.min(raw.getHeight(), visibleBounds.bottom + padding);
            if (visibleBounds.left == 0
                    && visibleBounds.top == 0
                    && visibleBounds.right == raw.getWidth()
                    && visibleBounds.bottom == raw.getHeight()) {
                return raw;
            }
            try {
                Bitmap cropped = Bitmap.createBitmap(
                        raw,
                        visibleBounds.left,
                        visibleBounds.top,
                        visibleBounds.width(),
                        visibleBounds.height()
                );
                raw.recycle();
                return cropped;
            } catch (Throwable ignored) {
                return raw;
            }
        }

        private static Rect findVisibleBounds(Bitmap bitmap) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] pixels;
            try {
                pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            } catch (Throwable ignored) {
                return null;
            }

            int left = width;
            int top = height;
            int right = -1;
            int bottom = -1;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    if ((pixels[row + x] >>> 24) > ALPHA_THRESHOLD) {
                        if (x < left) left = x;
                        if (x > right) right = x;
                        if (y < top) top = y;
                        if (y > bottom) bottom = y;
                    }
                }
            }
            return right < left || bottom < top
                    ? null
                    : new Rect(left, top, right + 1, bottom + 1);
        }
    }
}
'''


def patch_unified_preview_component() -> None:
    current = read(PREVIEW)
    if UNIFIED_MARKER not in current or current != PREVIEW_SOURCE:
        write(PREVIEW, PREVIEW_SOURCE)
    text = read(PREVIEW)
    for required in (
        UNIFIED_MARKER,
        "Theme.key_chat_outBubble",
        "Theme.key_chat_inBubble",
        "BackupImageView avatarView",
        "TextView senderNameView",
        "sourceCell.draw(canvas);",
    ):
        if required not in text:
            raise SystemExit(f"unified preview validation failed: {required}")
    print("Unified avatar/name/message preview block passed")


def validate_no_play_ios_regression() -> None:
    chat = read(CHAT)
    preview = read(PREVIEW)
    enter = read(ENTER)
    if "AuthorGramPlayPolicy.canUseIosUi()" not in chat:
        raise SystemExit("ChatActivity lost Main-only iOS menu policy")
    if "AuthorGramPlayPolicy.canUseIosUi()" not in preview:
        raise SystemExit("Preview lost Main-only policy")
    if "AUTHORGRAM_MAIN_ONLY_IOS_INPUT" not in enter:
        raise SystemExit("iOS input lost Main-only policy")
    print("Play remains free of Main-only iOS input/message UI")


def main() -> None:
    patch_standard_chat_header()
    patch_reliable_popup_scroll()
    patch_scrim_fixed_preview_and_width()
    patch_chat_activity_preview_ownership()
    patch_ios_send_button_invariant()
    patch_unified_preview_component()
    validate_no_play_ios_regression()


if __name__ == "__main__":
    main()
