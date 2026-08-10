package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Main/dev-only controller for the AuthorGram iOS-style message context preview.
 *
 * The controller itself is a GONE child of Telegram's popup layout, so it never
 * consumes menu height. The actual selected-message preview is rendered in a
 * full-screen overlay behind the ActionBarPopupWindow. That keeps the message
 * visually separate from the actions, prevents the menu from being clipped and
 * allows the complete chat background to be blurred without touching Telegram's
 * message/reply/link processing pipeline.
 */
public final class IOSMessageMenuPreview extends View {
    private static final int MAX_PREVIEW_WIDTH_DP = 360;
    private static final int SCREEN_EDGE_DP = 12;
    private static final int PREVIEW_MENU_GAP_DP = 8;
    private static final int BACKGROUND_DOWNSCALE = 12;
    private static final int BACKGROUND_BLUR_RADIUS = 15;

    private final ViewGroup rootView;
    private final FrameLayout overlay;
    private final ImageView blurredBackgroundView;
    private final ImageView messagePreviewView;
    private final Bitmap messageSnapshot;
    private Bitmap blurredBackground;
    private boolean cleanedUp;

    private IOSMessageMenuPreview(
            Context context,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag("AUTHORGRAM_IOS_MESSAGE_MENU_V3");
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        View root = sourceCell == null ? null : sourceCell.getRootView();
        if (!(root instanceof ViewGroup)
                || root.getWidth() <= 0
                || root.getHeight() <= 0
                || sourceCell.getWidth() <= 0
                || sourceCell.getHeight() <= 0) {
            rootView = null;
            overlay = null;
            blurredBackgroundView = null;
            messagePreviewView = null;
            messageSnapshot = null;
            return;
        }

        rootView = (ViewGroup) root;

        SnapshotResult messageResult = captureNativeCell(
                sourceCell,
                rootView.getWidth(),
                rootView.getHeight()
        );
        messageSnapshot = messageResult.bitmap;
        if (messageSnapshot == null) {
            overlay = null;
            blurredBackgroundView = null;
            messagePreviewView = null;
            return;
        }

        blurredBackground = captureBlurredBackground(rootView);

        overlay = new FrameLayout(context);
        overlay.setTag("AUTHORGRAM_IOS_MESSAGE_MENU_BLUR_OVERLAY");
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        blurredBackgroundView = new ImageView(context);
        blurredBackgroundView.setScaleType(ImageView.ScaleType.FIT_XY);
        blurredBackgroundView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (blurredBackground != null) {
            blurredBackgroundView.setImageBitmap(blurredBackground);
        } else {
            // A defensive fallback: never crash the context menu if native blur
            // allocation fails. The dim layer still separates the selection.
            blurredBackgroundView.setBackgroundColor(
                    Theme.multAlpha(
                            Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                            0.55f
                    )
            );
        }
        overlay.addView(
                blurredBackgroundView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        View dim = new View(context);
        dim.setBackgroundColor(Color.argb(52, 0, 0, 0));
        dim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.addView(
                dim,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        messagePreviewView = new ImageView(context);
        messagePreviewView.setScaleType(ImageView.ScaleType.FIT_XY);
        messagePreviewView.setAdjustViewBounds(false);
        messagePreviewView.setImageBitmap(messageSnapshot);
        messagePreviewView.setVisibility(INVISIBLE);
        messagePreviewView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.addView(
                messagePreviewView,
                new FrameLayout.LayoutParams(messageResult.width, messageResult.height)
        );

        rootView.addView(
                overlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        // If popup creation is aborted before this controller is ever attached,
        // do not leave the blur overlay on screen.
        overlay.postDelayed(() -> {
            if (!isAttachedToWindow()) {
                cleanup();
            }
        }, 2500L);
    }

    public static IOSMessageMenuPreview create(
            Context context,
            int currentAccount,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        IOSMessageMenuPreview preview = new IOSMessageMenuPreview(
                context,
                sourceCell,
                resourcesProvider
        );
        return preview.isUsable() ? preview : null;
    }

    public boolean isUsable() {
        return !cleanedUp
                && rootView != null
                && overlay != null
                && messagePreviewView != null
                && messageSnapshot != null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        View parent = getParent() instanceof View ? (View) getParent() : null;
        if (parent != null) {
            parent.post(this::placeMessagePreviewOutsideMenu);
        } else {
            post(this::placeMessagePreviewOutsideMenu);
        }
    }

    /**
     * Align the selected native message with the popup's left edge and place it
     * just above the actions. If there is not enough room above, place it just
     * below. The message bitmap is never clipped by the popup because it lives
     * in the root overlay instead of the popup layout.
     */
    private void placeMessagePreviewOutsideMenu() {
        if (!isUsable() || !(getParent() instanceof View)) {
            return;
        }

        View menu = (View) getParent();
        if (menu.getWidth() <= 0 || menu.getHeight() <= 0) {
            menu.post(this::placeMessagePreviewOutsideMenu);
            return;
        }

        int[] rootLocation = new int[2];
        int[] menuLocation = new int[2];
        rootView.getLocationOnScreen(rootLocation);
        menu.getLocationOnScreen(menuLocation);

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) messagePreviewView.getLayoutParams();

        int edge = AndroidUtilities.dp(SCREEN_EDGE_DP);
        int gap = AndroidUtilities.dp(PREVIEW_MENU_GAP_DP);
        int x = menuLocation[0] - rootLocation[0];
        x = clamp(x, edge, Math.max(edge, rootView.getWidth() - params.width - edge));

        int menuY = menuLocation[1] - rootLocation[1];
        int aboveY = menuY - params.height - gap;
        int belowY = menuY + menu.getHeight() + gap;
        int minY = Math.max(edge, AndroidUtilities.statusBarHeight + edge);
        int maxY = Math.max(minY, rootView.getHeight() - params.height - edge);

        int y;
        if (aboveY >= minY) {
            y = aboveY;
        } else if (belowY <= maxY) {
            y = belowY;
        } else {
            // Last-resort fit for very tall messages/menus. The snapshot was
            // already scaled to the viewport, so clamping preserves it whole.
            y = clamp(aboveY, minY, maxY);
        }

        params.leftMargin = x;
        params.topMargin = y;
        messagePreviewView.setLayoutParams(params);
        messagePreviewView.setVisibility(VISIBLE);
    }

    private static Bitmap captureBlurredBackground(View rootView) {
        int sourceWidth = rootView.getWidth();
        int sourceHeight = rootView.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return null;
        }

        int width = Math.max(1, sourceWidth / BACKGROUND_DOWNSCALE);
        int height = Math.max(1, sourceHeight / BACKGROUND_DOWNSCALE);
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(width / (float) sourceWidth, height / (float) sourceHeight);
            rootView.draw(canvas);
            Utilities.stackBlurBitmap(bitmap, BACKGROUND_BLUR_RADIUS);
            return bitmap;
        } catch (Throwable ignored) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            return null;
        }
    }

    /**
     * Snapshot exactly the already-bound native ChatMessageCell, including its
     * avatar/sender styling when Telegram paints those elements. This preserves
     * Telegram's own reply/media/text rendering and avoids creating a fake card.
     */
    private static SnapshotResult captureNativeCell(
            ChatMessageCell sourceCell,
            int rootWidth,
            int rootHeight
    ) {
        final int sourceWidth = sourceCell.getWidth();
        final int sourceHeight = sourceCell.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return SnapshotResult.EMPTY;
        }

        int left = Math.max(
                0,
                sourceCell.getBackgroundDrawableLeft()
                        - (sourceCell.getAvatarImage() == null
                        ? AndroidUtilities.dp(8)
                        : AndroidUtilities.dp(52))
        );
        int right = Math.min(
                sourceWidth,
                sourceCell.getBackgroundDrawableRight() + AndroidUtilities.dp(8)
        );
        int top = Math.max(
                0,
                sourceCell.getBackgroundDrawableTop() - AndroidUtilities.dp(6)
        );
        int bottom = Math.min(
                sourceHeight,
                sourceCell.getBackgroundDrawableBottom() + AndroidUtilities.dp(6)
        );

        if (right <= left || bottom <= top) {
            left = 0;
            right = sourceWidth;
            top = 0;
            bottom = sourceHeight;
        }

        final int cropWidth = Math.max(1, right - left);
        final int cropHeight = Math.max(1, bottom - top);

        int horizontalRoom = Math.max(
                AndroidUtilities.dp(120),
                rootWidth - AndroidUtilities.dp(SCREEN_EDGE_DP * 2)
        );
        int maxWidth = Math.min(AndroidUtilities.dp(MAX_PREVIEW_WIDTH_DP), horizontalRoom);
        int verticalRoom = Math.max(
                AndroidUtilities.dp(120),
                rootHeight - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(48)
        );

        float scale = Math.min(1.0f, maxWidth / (float) cropWidth);
        if (cropHeight * scale > verticalRoom) {
            scale = Math.min(scale, verticalRoom / (float) cropHeight);
        }

        int targetWidth = Math.max(1, Math.round(cropWidth * scale));
        int targetHeight = Math.max(1, Math.round(cropHeight * scale));

        try {
            Bitmap bitmap = Bitmap.createBitmap(
                    targetWidth,
                    targetHeight,
                    Bitmap.Config.ARGB_8888
            );
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            canvas.translate(-left, -top);
            sourceCell.draw(canvas);
            return new SnapshotResult(bitmap, targetWidth, targetHeight);
        } catch (Throwable ignored) {
            return SnapshotResult.EMPTY;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDetachedFromWindow() {
        cleanup();
        super.onDetachedFromWindow();
    }

    private void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;

        if (messagePreviewView != null) {
            messagePreviewView.setImageDrawable(null);
        }
        if (blurredBackgroundView != null) {
            blurredBackgroundView.setImageDrawable(null);
        }
        if (overlay != null && overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }

        if (messageSnapshot != null && !messageSnapshot.isRecycled()) {
            messageSnapshot.recycle();
        }
        if (blurredBackground != null && !blurredBackground.isRecycled()) {
            blurredBackground.recycle();
        }
        blurredBackground = null;
    }

    private static final class SnapshotResult {
        static final SnapshotResult EMPTY = new SnapshotResult(null, 0, 0);

        final Bitmap bitmap;
        final int width;
        final int height;

        SnapshotResult(Bitmap bitmap, int width, int height) {
            this.bitmap = bitmap;
            this.width = width;
            this.height = height;
        }
    }
}
