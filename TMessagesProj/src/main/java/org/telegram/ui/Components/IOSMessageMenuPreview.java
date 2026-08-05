package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Main-only native message preview used by the iOS-style long-press menu.
 *
 * This class does not rebuild a message from text fields. It snapshots the real
 * ChatMessageCell, including Telegram's avatar, sender, reply block, media,
 * bubble, timestamp and delivery state, and draws that exact native rendering
 * over the blurred chat. The action panel is separated by a transparent gap in
 * ChatActivity/ActionBarPopupWindow.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";

    private final NativeCellSnapshotView snapshotView;

    public IOSMessageMenuPreview(
            Context context,
            View blurSource,
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

        // Cover the popup's ordinary submenu paint with the same live blurred
        // chat background used by Telegram surfaces. The real message snapshot
        // then remains an independent bubble, not a header inside the menu.
        if (blurSource != null && blurSource != this) {
            BluredView blurredView = new BluredView(context, blurSource, resourcesProvider);
            addView(blurredView, LayoutHelper.createFrame(
                    LayoutHelper.MATCH_PARENT,
                    LayoutHelper.MATCH_PARENT
            ));
        }

        View dim = new View(context);
        dim.setBackgroundColor(0x16000000);
        addView(dim, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));

        snapshotView = new NativeCellSnapshotView(context, sourceCell);
        addView(snapshotView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));
    }

    private static final class NativeCellSnapshotView extends View {
        private static final int ALPHA_THRESHOLD = 8;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
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
                setMeasuredDimension(availableWidth, AndroidUtilities.dp(64));
                return;
            }

            int horizontalInset = AndroidUtilities.dp(2);
            int targetWidth = Math.min(snapshot.getWidth(), availableWidth - horizontalInset * 2);
            float scale = targetWidth / (float) snapshot.getWidth();
            int targetHeight = Math.max(1, Math.round(snapshot.getHeight() * scale));
            int measuredHeight = targetHeight + AndroidUtilities.dp(4);
            setMeasuredDimension(availableWidth, measuredHeight);
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
            int left = (getWidth() - targetWidth) / 2;
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
