#!/usr/bin/env python3
"""Final Main-only iOS message-menu sender/header and reflow repair.

This pass intentionally runs LAST, after the legacy/native compatibility audit.
The previous exact-source-cell clone preserved chat-list coordinates and therefore
could still crop the right edge and lose the avatar lane inside a popup overlay.

Final ownership model:
- reactions stay Telegram-owned above this view;
- this view owns an explicit, always-visible sender header (avatar + name);
- the message body is still rendered by a real Telegram ChatMessageCell, but it is
  freshly measured inside the popup work area instead of inheriting source-cell
  width/height/params;
- long message bodies scroll in a bounded independent viewport;
- the action card remains a separate Telegram ScrollView below;
- if AuthorGram preview binding fails, only the preview degrades to raw text; the
  chat/context menu must not crash.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"

FINAL_MARKER = "AUTHORGRAM_FINAL_IOS_SENDER_HEADER_POSTPASS"
HEADER_MARKER = "AUTHORGRAM_IOS_EXPLICIT_SENDER_HEADER"
REFLOW_MARKER = "AUTHORGRAM_IOS_REFLOWED_NATIVE_MESSAGE"
FALLBACK_MARKER = "AUTHORGRAM_IOS_PREVIEW_GRACEFUL_FALLBACK"
NO_SOURCE_GEOMETRY_MARKER = "AUTHORGRAM_IOS_PREVIEW_NO_SOURCE_DIMENSIONS"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


FINAL_PREVIEW_SOURCE = r'''package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Final Main-only selected-message preview for the iOS-style context menu.
 *
 * AUTHORGRAM_FINAL_IOS_SENDER_HEADER_POSTPASS
 * AUTHORGRAM_IOS_EXPLICIT_SENDER_HEADER
 * AUTHORGRAM_IOS_REFLOWED_NATIVE_MESSAGE
 * AUTHORGRAM_IOS_PREVIEW_GRACEFUL_FALLBACK
 * AUTHORGRAM_IOS_PREVIEW_NO_SOURCE_DIMENSIONS
 *
 * The sender identity is an explicit fixed header. The message body is rendered
 * by Telegram's ChatMessageCell but is reflowed for this work area; source chat
 * cell dimensions/coordinates are deliberately not copied into the popup.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";
    public static final String SENDER_IDENTITY_TAG = "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY";

    private final ChatMessageCell previewCell;
    private final BoundedScrollView previewScroll;

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

        if (!AuthorGramPlayPolicy.canUseIosUi()
                || !NekoConfig.iOSMessageMenu.Bool()
                || messageObject == null) {
            setVisibility(GONE);
            previewCell = null;
            previewScroll = null;
            return;
        }

        final Theme.ResourcesProvider effectiveResourcesProvider =
                sourceCell != null && sourceCell.getResourcesProvider() != null
                        ? sourceCell.getResourcesProvider()
                        : resourcesProvider;
        final int viewportHeight = Math.max(AndroidUtilities.dp(360), AndroidUtilities.displaySize.y);
        final int maxMessageHeight = Math.max(
                AndroidUtilities.dp(120),
                Math.min(AndroidUtilities.dp(340), Math.round(viewportHeight * 0.38f))
        );

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(
                AndroidUtilities.dp(8),
                0,
                AndroidUtilities.dp(8),
                0
        );
        addView(content, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));

        // AUTHORGRAM_IOS_EXPLICIT_SENDER_HEADER
        // Sender identity is independent from ChatMessageCell's chat-list avatar
        // lane, so it cannot disappear when the selected message is reparented.
        LinearLayout senderHeader = new LinearLayout(context);
        senderHeader.setOrientation(LinearLayout.HORIZONTAL);
        senderHeader.setGravity(Gravity.CENTER_VERTICAL);
        senderHeader.setClipChildren(false);
        senderHeader.setClipToPadding(false);
        senderHeader.setPadding(
                AndroidUtilities.dp(8),
                AndroidUtilities.dp(3),
                AndroidUtilities.dp(8),
                AndroidUtilities.dp(3)
        );

        SenderIdentity identity = resolveSender(currentAccount, messageObject);
        AvatarDrawable senderAvatarDrawable = new AvatarDrawable();
        BackupImageView senderAvatarView = new BackupImageView(context);
        senderAvatarView.setTag(SENDER_IDENTITY_TAG);
        senderAvatarView.setRoundRadius(AndroidUtilities.dp(19));
        bindSenderAvatar(
                currentAccount,
                identity,
                senderAvatarView,
                senderAvatarDrawable
        );

        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(38),
                AndroidUtilities.dp(38)
        );
        avatarParams.setMarginEnd(AndroidUtilities.dp(9));
        senderHeader.addView(senderAvatarView, avatarParams);

        TextView senderNameView = new TextView(context);
        senderNameView.setTag(SENDER_IDENTITY_TAG);
        senderNameView.setText(identity.name);
        senderNameView.setTextSize(15);
        senderNameView.setTypeface(AndroidUtilities.bold());
        senderNameView.setSingleLine(true);
        senderNameView.setEllipsize(TextUtils.TruncateAt.END);
        senderNameView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        senderNameView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText,
                effectiveResourcesProvider
        ));
        senderHeader.addView(senderNameView, new LinearLayout.LayoutParams(
                0,
                AndroidUtilities.dp(44),
                1.0f
        ));
        content.addView(senderHeader, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                AndroidUtilities.dp(50)
        ));

        previewScroll = new BoundedScrollView(context, maxMessageHeight);
        previewScroll.setFillViewport(false);
        previewScroll.setVerticalScrollBarEnabled(false);
        previewScroll.setHorizontalScrollBarEnabled(false);
        previewScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        previewScroll.setClipChildren(false);
        previewScroll.setClipToPadding(false);
        previewScroll.setNestedScrollingEnabled(true);
        content.addView(previewScroll, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));

        ChatMessageCell cell = null;
        try {
            // AUTHORGRAM_IOS_REFLOWED_NATIVE_MESSAGE
            // AUTHORGRAM_IOS_PREVIEW_NO_SOURCE_DIMENSIONS
            // Re-bind the MessageObject into a fresh Telegram cell measured by this
            // popup work area. Never copy source width/height/params: those values
            // belong to RecyclerView/chat-list coordinates and caused clipping.
            cell = new ChatMessageCell(
                    context,
                    currentAccount,
                    false,
                    null,
                    effectiveResourcesProvider
            );
            cell.setTag(NATIVE_PREVIEW_TAG);
            cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.setClickable(false);
            cell.setLongClickable(false);
            cell.setFocusable(false);
            cell.setEnabled(false);
            cell.isChat = false;
            cell.setFullyDraw(true);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
                @Override
                public boolean canPerformActions() {
                    return false;
                }
            });
            cell.setMessageObject(messageObject, null, false, false, false);
            previewScroll.addView(cell, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
            ));
        } catch (Throwable error) {
            // AUTHORGRAM_IOS_PREVIEW_GRACEFUL_FALLBACK
            FileLog.e(error);
            TextView fallbackMessage = createFallbackMessageView(
                    context,
                    messageObject,
                    effectiveResourcesProvider
            );
            previewScroll.addView(fallbackMessage, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT,
                    ScrollView.LayoutParams.WRAP_CONTENT
            ));
        }
        previewCell = cell;
    }

    private static SenderIdentity resolveSender(int currentAccount, MessageObject messageObject) {
        try {
            long senderId = messageObject != null ? messageObject.getFromChatId() : 0L;
            if (senderId == 0L && messageObject != null && messageObject.isOutOwner()) {
                senderId = UserConfig.getInstance(currentAccount).getClientUserId();
            }
            if (senderId == 0L && messageObject != null) {
                senderId = messageObject.getDialogId();
            }

            TLRPC.User user = senderId > 0L
                    ? MessagesController.getInstance(currentAccount).getUser(senderId)
                    : null;
            TLRPC.Chat chat = senderId < 0L
                    ? MessagesController.getInstance(currentAccount).getChat(-senderId)
                    : null;

            if (user == null
                    && chat == null
                    && messageObject != null
                    && messageObject.isOutOwner()) {
                user = UserConfig.getInstance(currentAccount).getCurrentUser();
            }

            String name = null;
            if (user != null) {
                name = UserObject.getUserName(user);
            } else if (chat != null) {
                name = chat.title;
            } else if (messageObject != null && !TextUtils.isEmpty(messageObject.customName)) {
                name = messageObject.customName;
            }
            if (TextUtils.isEmpty(name)) {
                name = "Unknown";
            }
            return new SenderIdentity(user, chat, name);
        } catch (Throwable error) {
            FileLog.e(error);
            return new SenderIdentity(null, null, "Unknown");
        }
    }

    private static void bindSenderAvatar(
            int currentAccount,
            SenderIdentity identity,
            BackupImageView senderAvatarView,
            AvatarDrawable senderAvatarDrawable
    ) {
        try {
            if (identity.user != null) {
                senderAvatarDrawable.setInfo(currentAccount, identity.user);
                senderAvatarView.setForUserOrChat(identity.user, senderAvatarDrawable);
            } else if (identity.chat != null) {
                senderAvatarDrawable.setInfo(currentAccount, identity.chat);
                senderAvatarView.setForUserOrChat(identity.chat, senderAvatarDrawable);
            } else {
                senderAvatarDrawable.setInfo(0, identity.name, null);
                senderAvatarView.setImageDrawable(senderAvatarDrawable);
            }
        } catch (Throwable error) {
            FileLog.e(error);
            senderAvatarDrawable.setInfo(0, identity.name, null);
            senderAvatarView.setImageDrawable(senderAvatarDrawable);
        }
    }

    private static TextView createFallbackMessageView(
            Context context,
            MessageObject messageObject,
            Theme.ResourcesProvider resourcesProvider
    ) {
        TextView fallback = new TextView(context);
        CharSequence rawText = null;
        if (messageObject != null && !TextUtils.isEmpty(messageObject.messageText)) {
            rawText = messageObject.messageText;
        } else if (messageObject != null
                && messageObject.messageOwner != null
                && !TextUtils.isEmpty(messageObject.messageOwner.message)) {
            rawText = messageObject.messageOwner.message;
        }
        if (TextUtils.isEmpty(rawText)) {
            rawText = "Message";
        }
        fallback.setText(rawText);
        fallback.setTextSize(16);
        fallback.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText,
                resourcesProvider
        ));
        fallback.setPadding(
                AndroidUtilities.dp(12),
                AndroidUtilities.dp(10),
                AndroidUtilities.dp(12),
                AndroidUtilities.dp(10)
        );
        int bubbleColor = Theme.getColor(
                messageObject != null && messageObject.isOutOwner()
                        ? Theme.key_chat_outBubble
                        : Theme.key_chat_inBubble,
                resourcesProvider
        );
        fallback.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16),
                bubbleColor
        ));
        return fallback;
    }

    /** Compatibility API: the selected message never joins the action ScrollView. */
    public boolean shouldScrollWithActions() {
        return false;
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

    private static final class BoundedScrollView extends ScrollView {
        private final int maxHeight;

        BoundedScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int parentMode = MeasureSpec.getMode(heightMeasureSpec);
            int parentSize = MeasureSpec.getSize(heightMeasureSpec);
            int cap = parentMode == MeasureSpec.UNSPECIFIED || parentSize <= 0
                    ? maxHeight
                    : Math.min(parentSize, maxHeight);
            super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(Math.max(1, cap), MeasureSpec.AT_MOST)
            );
        }
    }
}
'''


def apply_preview() -> None:
    write(PREVIEW, FINAL_PREVIEW_SOURCE)


def validate() -> None:
    preview = read(PREVIEW)
    scrim = read(SCRIM)

    required = (
        FINAL_MARKER,
        HEADER_MARKER,
        REFLOW_MARKER,
        FALLBACK_MARKER,
        NO_SOURCE_GEOMETRY_MARKER,
        "BackupImageView senderAvatarView",
        "TextView senderNameView",
        "senderNameView.setSingleLine(true);",
        "senderNameView.setEllipsize(TextUtils.TruncateAt.END);",
        'name = "Unknown";',
        "new BoundedScrollView(context, maxMessageHeight)",
        "Math.min(AndroidUtilities.dp(340), Math.round(viewportHeight * 0.38f))",
        "cell.isChat = false;",
        "cell.setMessageObject(messageObject, null, false, false, false);",
        "catch (Throwable error)",
        "FileLog.e(error);",
        "createFallbackMessageView",
    )
    for token in required:
        if token not in preview:
            raise SystemExit(f"final iOS sender-header invariant missing: {token}")

    forbidden = (
        "sourceCellWidth",
        "sourceCellHeight",
        "setMeasuredDimension(sourceCellWidth, sourceCellHeight)",
        "sourceCell.copyVisiblePartTo",
        "sourceCell.copyParamsTo",
        "Bitmap.createBitmap",
        "sourceCell.draw(",
        "getPixels(",
        "NativeCellSnapshotView",
    )
    for token in forbidden:
        if token in preview:
            raise SystemExit(f"source-coordinate/snapshot clipping regression remains: {token}")

    # The already-audited parent must still own the whole work area and must not
    # force the preview down to the action-card width.
    for token in (
        "AUTHORGRAM_IOS_PREVIEW_CHAT_WORKAREA_OWNER",
        "AUTHORGRAM_IOS_PREVIEW_NATIVE_SOURCE_GEOMETRY",
        "params.setMarginStart(0);",
        "params.setMarginEnd(0);",
        "AUTHORGRAM_STRICT_IOS_MENU_VIEWPORT",
    ):
        if token not in scrim:
            raise SystemExit(f"final iOS work-area invariant missing: {token}")

    for token in (
        "previewParams.width = popupWidthForPreview;",
        "previewParams.width = previewWidth;",
        "int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();",
    ):
        if token in scrim:
            raise SystemExit(f"action-card width mutation returned: {token}")

    print("AuthorGram final explicit sender header + reflowed unclipped native message preview passed")


def apply() -> None:
    apply_preview()
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
