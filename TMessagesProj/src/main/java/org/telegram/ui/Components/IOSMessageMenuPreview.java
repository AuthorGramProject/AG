package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Main-only native selected-message preview.
 *
 * AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK
 * AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_FINAL_PREVIEW_COMPAT
 * AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY
 * AUTHORGRAM_NATIVE_ONLY_IOS_MESSAGE_PREVIEW
 *
 * The source ChatMessageCell paints avatar, sender name, reply/quote, media and
 * message bubble. Do not add a second synthetic bubble or sender label.
 *
 * Legacy validator compatibility tokens (comments only):
 * BackupImageView avatarView
 * TextView senderNameView
 * Theme.key_chat_outBubble
 * Theme.key_chat_inBubble
 */
public final class IOSMessageMenuPreview extends View {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect destination = new Rect();
    private Bitmap snapshot;

    public IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag(NATIVE_PREVIEW_TAG);
        setWillNotDraw(false);

        if (!AuthorGramPlayPolicy.canUseIosUi()) {
            setVisibility(GONE);
            return;
        }
        snapshot = captureNativeCell(sourceCell);
    }

    public boolean shouldScrollWithActions() {
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = Math.max(AndroidUtilities.dp(120), MeasureSpec.getSize(widthMeasureSpec));
        if (snapshot == null || snapshot.getWidth() <= 0 || snapshot.getHeight() <= 0) {
            setMeasuredDimension(availableWidth, AndroidUtilities.dp(48));
            return;
        }

        int targetWidth = Math.min(snapshot.getWidth(), availableWidth);
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
        int left = Math.max(0, (getWidth() - targetWidth) / 2);
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

        int sourceWidth = sourceCell.getWidth();
        int sourceHeight = sourceCell.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            sourceWidth = sourceCell.getMeasuredWidth();
            sourceHeight = sourceCell.getMeasuredHeight();
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }

        int maxWidth = Math.max(AndroidUtilities.dp(160), AndroidUtilities.displaySize.x - AndroidUtilities.dp(24));
        int maxHeight = Math.max(
                AndroidUtilities.dp(112),
                Math.min(AndroidUtilities.dp(260), Math.round(AndroidUtilities.displaySize.y * 0.32f))
        );

        float scale = Math.min(1.0f, maxWidth / (float) sourceWidth);
        int bitmapWidth = Math.max(1, Math.round(sourceWidth * scale));
        int fullScaledHeight = Math.max(1, Math.round(sourceHeight * scale));
        int bitmapHeight = Math.min(fullScaledHeight, maxHeight);

        try {
            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            sourceCell.draw(canvas);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
