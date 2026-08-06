package org.telegram.ui.Components;

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
