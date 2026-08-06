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
 * Main-only native message preview used by the iOS-style long-press menu.
 *
 * The actual Telegram ChatMessageCell is snapshotted for message content while
 * the sender identity is rendered explicitly, so an avatar and a readable
 * sender/channel name are always present even when the source cell omits them.
 *
 * Full-screen blur is owned by ChatActivity. Do not add a local BluredView:
 * constraining blur to this preview was the reason the chat stayed sharp.
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

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(6),
                AndroidUtilities.dp(4)
        );
        addView(row, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));

        SenderIdentity identity = resolveSender(currentAccount, messageObject);

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        BackupImageView avatarView = new BackupImageView(context);
        avatarView.setTag(SENDER_IDENTITY_TAG);
        avatarView.setRoundRadius(AndroidUtilities.dp(21));

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
                AndroidUtilities.dp(42),
                AndroidUtilities.dp(42)
        );
        avatarParams.topMargin = AndroidUtilities.dp(2);
        avatarParams.rightMargin = AndroidUtilities.dp(8);
        row.addView(avatarView, avatarParams);

        LinearLayout messageColumn = new LinearLayout(context);
        messageColumn.setOrientation(LinearLayout.VERTICAL);
        messageColumn.setClipChildren(false);
        messageColumn.setClipToPadding(false);
        row.addView(messageColumn, new LinearLayout.LayoutParams(
                0,
                LayoutHelper.WRAP_CONTENT,
                1.0f
        ));

        TextView senderNameView = new TextView(context);
        senderNameView.setTag(SENDER_IDENTITY_TAG);
        senderNameView.setText(identity.name);
        senderNameView.setTextSize(15);
        senderNameView.setTextColor(Theme.getColor(
                Theme.key_windowBackgroundWhiteBlackText,
                resourcesProvider
        ));
        senderNameView.setTypeface(AndroidUtilities.bold());
        senderNameView.setSingleLine(true);
        senderNameView.setEllipsize(TextUtils.TruncateAt.END);
        senderNameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        senderNameView.setPadding(
                AndroidUtilities.dp(2),
                0,
                AndroidUtilities.dp(2),
                AndroidUtilities.dp(2)
        );
        messageColumn.addView(senderNameView, new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                AndroidUtilities.dp(24)
        ));

        snapshotView = new NativeCellSnapshotView(context, sourceCell);
        messageColumn.addView(snapshotView, new LinearLayout.LayoutParams(
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
                setMeasuredDimension(availableWidth, AndroidUtilities.dp(48));
                return;
            }

            int horizontalInset = AndroidUtilities.dp(2);
            int targetWidth = Math.max(
                    1,
                    Math.min(snapshot.getWidth(), availableWidth - horizontalInset * 2)
            );
            float scale = targetWidth / (float) snapshot.getWidth();
            int targetHeight = Math.max(1, Math.round(snapshot.getHeight() * scale));
            setMeasuredDimension(availableWidth, targetHeight + AndroidUtilities.dp(4));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (snapshot == null || snapshot.isRecycled()) {
                return;
            }

            int maxWidth = Math.max(1, getWidth() - AndroidUtilities.dp(4));
            int targetWidth = Math.min(snapshot.getWidth(), maxWidth);
            float scale = targetWidth / (float) snapshot.getWidth();
            int targetHeight = Math.max(1, Math.round(snapshot.getHeight() * scale));
            int left = 0;
            int top = Math.max(0, (getHeight() - targetHeight) / 2);
            destination.set(left, top, left + targetWidth, top + targetHeight);
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

            Rect opaqueBounds = findVisibleBounds(raw);
            if (opaqueBounds == null) {
                return raw;
            }

            int padding = AndroidUtilities.dp(3);
            opaqueBounds.left = Math.max(0, opaqueBounds.left - padding);
            opaqueBounds.top = Math.max(0, opaqueBounds.top - padding);
            opaqueBounds.right = Math.min(raw.getWidth(), opaqueBounds.right + padding);
            opaqueBounds.bottom = Math.min(raw.getHeight(), opaqueBounds.bottom + padding);

            if (opaqueBounds.left == 0
                    && opaqueBounds.top == 0
                    && opaqueBounds.right == raw.getWidth()
                    && opaqueBounds.bottom == raw.getHeight()) {
                return raw;
            }

            try {
                Bitmap cropped = Bitmap.createBitmap(
                        raw,
                        opaqueBounds.left,
                        opaqueBounds.top,
                        opaqueBounds.width(),
                        opaqueBounds.height()
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
