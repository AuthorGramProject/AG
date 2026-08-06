#!/usr/bin/env python3
"""Apply AuthorGram's adaptive iOS selected-message preview behavior.

Short selected messages stay fixed above the action card. Long selected messages
are inserted into the action ScrollView so the preview and every menu item scroll
as one continuous surface. The popup viewport is always capped to the available
work area, preventing the last action from being clipped.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"
RELEASE = ROOT / "scripts/final_main_release_12_9_2.sh"

ADAPTIVE_OWNER = "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER"
ADAPTIVE_SCROLL = "AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_SCROLL"
LONG_GAP = "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP"
COMPOSER_FIX = "AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


PREVIEW_SOURCE = r"""package org.telegram.ui.Components;

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
 * Main-only adaptive selected-message preview for the iOS-style context menu.
 *
 * AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK: avatar, sender and the native Telegram
 * message rendering are one coherent message item.
 * AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW: a short preview stays fixed above
 * the actions; a tall preview joins the action ScrollView so all content can
 * be reached by one continuous scroll without clipping the final menu item.
 * AUTHORGRAM_FINAL_PREVIEW_COMPAT: no preview-local BluredView is used; blur
 * remains owned by ChatActivity across the complete chat surface.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";
    public static final String SENDER_IDENTITY_TAG = "AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY";

    private final NativeCellSnapshotView snapshotView;
    private final boolean scrollWithActions;

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
            scrollWithActions = false;
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
        scrollWithActions = snapshotView.shouldScrollWithActions();
        unifiedMessage.addView(snapshotView, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));
    }

    public boolean shouldScrollWithActions() {
        return scrollWithActions;
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

        boolean shouldScrollWithActions() {
            if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) {
                return false;
            }
            int viewportHeight = Math.max(
                    AndroidUtilities.dp(320),
                    AndroidUtilities.displaySize.y
            );
            int threshold = Math.max(
                    AndroidUtilities.dp(156),
                    Math.min(AndroidUtilities.dp(232), Math.round(viewportHeight * 0.28f))
            );
            int previewWidth = Math.max(
                    AndroidUtilities.dp(160),
                    AndroidUtilities.displaySize.x - AndroidUtilities.dp(104)
            );
            int targetWidth = Math.max(1, Math.min(snapshot.getWidth(), previewWidth));
            int targetHeight = Math.max(
                    1,
                    Math.round(snapshot.getHeight() * (targetWidth / (float) snapshot.getWidth()))
            );
            return targetHeight + AndroidUtilities.dp(31) > threshold;
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
"""


def patch_preview_source() -> None:
    write(PREVIEW, PREVIEW_SOURCE)
    text = read(PREVIEW)
    for required in (
        "AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW",
        "public boolean shouldScrollWithActions()",
        "scrollWithActions = snapshotView.shouldScrollWithActions();",
        "AUTHORGRAM_FINAL_PREVIEW_COMPAT",
        "sourceCell.draw(canvas);",
    ):
        if required not in text:
            raise SystemExit(f"adaptive preview source validation failed: {required}")
    print("Adaptive selected-message preview source passed")


def patch_chat_activity_owner() -> None:
    text = read(CHAT)
    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER\n"
        "                // Short messages stay fixed above the action viewport. Tall\n"
        "                // messages join the ScrollView so preview and actions scroll together.\n"
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
        "                    if (iosPreview.shouldScrollWithActions()) {\n"
        "                        LinearLayout.LayoutParams iosPreviewParams = LayoutHelper.createLinear(\n"
        "                                LayoutHelper.MATCH_PARENT,\n"
        "                                LayoutHelper.WRAP_CONTENT\n"
        "                        );\n"
        "                        iosPreviewParams.topMargin = AndroidUtilities.dp(2);\n"
        "                        popupLayout.addView(iosPreview, iosPreviewParams);\n"
        "\n"
        "                        org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView longPreviewGap =\n"
        "                                new org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView(\n"
        "                                        getParentActivity(),\n"
        "                                        android.graphics.Color.TRANSPARENT,\n"
        "                                        android.graphics.Color.TRANSPARENT\n"
        "                                );\n"
        "                        longPreviewGap.setTag(\"AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP\");\n"
        "                        popupLayout.addView(longPreviewGap, LayoutHelper.createLinear(\n"
        "                                LayoutHelper.MATCH_PARENT,\n"
        "                                8\n"
        "                        ));\n"
        "                    } else {\n"
        "                        scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);\n"
        "                    }\n"
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
        raise SystemExit(f"adaptive ChatActivity preview block count is {count}, expected 1")
    write(CHAT, text)

    check = read(CHAT)
    for required in (
        ADAPTIVE_OWNER,
        "iosPreview.shouldScrollWithActions()",
        "popupLayout.addView(iosPreview, iosPreviewParams);",
        LONG_GAP,
        "scrimPopupContainerLayout.setFixedMessagePreview(iosPreview);",
    ):
        if required not in check:
            raise SystemExit(f"adaptive ChatActivity validation failed: {required}")
    if "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP" in check:
        raise SystemExit("obsolete unconditional iOS preview gap remains")
    print("Adaptive fixed/scrolling ChatActivity ownership passed")


def patch_scrim_viewport() -> None:
    text = read(SCRIM)
    replacement = (
        "        // Reset a previous viewport cap before measuring natural popup content.\n"
        "        if (popupWindowLayout != null) {\n"
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
        "        // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_SCROLL\n"
        "        // Top-level children (reactions and, for short messages, the preview)\n"
        "        // remain fixed. The popup receives exactly the remaining viewport.\n"
        "        // When a long preview is inside popupLayout, it scrolls with all actions.\n"
        "        int occupiedHeight = getPaddingTop() + getPaddingBottom();\n"
        "        for (int i = 0; i < getChildCount(); i++) {\n"
        "            View child = getChildAt(i);\n"
        "            if (child == popupWindowLayout || child.getVisibility() == GONE) {\n"
        "                continue;\n"
        "            }\n"
        "            LinearLayout.LayoutParams childParams =\n"
        "                    (LinearLayout.LayoutParams) child.getLayoutParams();\n"
        "            occupiedHeight += child.getMeasuredHeight()\n"
        "                    + childParams.topMargin\n"
        "                    + childParams.bottomMargin;\n"
        "        }\n"
        "        int availableForActions = Math.max(\n"
        "                AndroidUtilities.dp(96),\n"
        "                effectiveMaxHeight - occupiedHeight\n"
        "        );\n"
        "        LinearLayout.LayoutParams popupParams =\n"
        "                (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();\n"
        "        int desiredPopupHeight = popupWindowLayout.getMeasuredHeight();\n"
        "        if (desiredPopupHeight > availableForActions) {\n"
        "            popupParams.height = availableForActions;\n"
        "            super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);\n"
        "        }\n"
        "\n"
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
    pattern = re.compile(
        r"        // Reset a prior temporary height cap before measuring current content\.\n"
        r".*?"
        r"(?=        if \(reactionsLayout != null\) \{)",
        re.DOTALL,
    )
    if not pattern.search(text):
        pattern = re.compile(
            r"        // Reset a previous viewport cap before measuring natural popup content\.\n"
            r".*?"
            r"(?=        if \(reactionsLayout != null\) \{)",
            re.DOTALL,
        )
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"adaptive scrim viewport block count is {count}, expected 1")
    write(SCRIM, text)

    check = read(SCRIM)
    for required in (
        ADAPTIVE_SCROLL,
        "availableForActions",
        "effectiveMaxHeight - occupiedHeight",
        "popupParams.height = availableForActions;",
        "if (fixedMessagePreview != null)",
    ):
        if required not in check:
            raise SystemExit(f"adaptive scrim validation failed: {required}")
    print("Adaptive popup viewport and unclipped final action passed")


def patch_composer_compile_and_null_safety() -> None:
    text = read(ENTER)
    helper = (
        "    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER\n"
        "    // AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT\n"
        "    // AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX\n"
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
        "        View sendButtonView = sendButton;\n"
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
        "        if (audioVideoButtonContainer == null) {\n"
        "            return;\n"
        "        }\n"
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
        raise SystemExit(f"composer invariant helper count is {count}, expected 1")
    write(ENTER, text)

    check = read(ENTER)
    for required in (
        COMPOSER_FIX,
        "View sendButtonView = sendButton;",
        "if (audioVideoButtonContainer == null)",
        "sendButtonView.setVisibility(VISIBLE);",
    ):
        if required not in check:
            raise SystemExit(f"composer compile/null-safety validation failed: {required}")
    if "getSendButtonInternal()" in check:
        raise SystemExit("undefined getSendButtonInternal() call remains")
    print("iOS composer send-button compile and null safety passed")


def patch_release_summary() -> None:
    if not RELEASE.exists():
        return
    text = read(RELEASE)
    text = text.replace(
        "- The Main-only iOS selected-message preview is fixed outside the actions ScrollView.\n"
        "- Only the action menu scrolls; normal and iOS message menus can reach the final item.\n",
        "- Short Main-only iOS selected-message previews stay fixed above the action menu.\n"
        "- Long selected-message previews scroll together with every action; the final item remains reachable.\n",
    )
    write(RELEASE, text)


def main() -> None:
    patch_preview_source()
    patch_chat_activity_owner()
    patch_scrim_viewport()
    patch_composer_compile_and_null_safety()
    patch_release_summary()

    for path in (CHAT, SCRIM, ENTER, PREVIEW):
        text = read(path)
        if "\r\n" in text:
            raise SystemExit(f"{path.name}: CRLF unexpectedly introduced")
    print("Adaptive iOS message-menu repair completed")


if __name__ == "__main__":
    main()
