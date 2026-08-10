package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Dev-only controller for the AuthorGram iOS-style message context preview.
 *
 * The selected message is rendered in a full-screen overlay behind Telegram's
 * ActionBarPopupWindow. It is therefore visually separate from the action list,
 * while the popup keeps its native actions, reactions and click handling.
 *
 * The implementation deliberately snapshots the already-bound ChatMessageCell
 * instead of re-binding MessageObject data. This keeps reply/link/media handling
 * isolated from the context-menu preview and avoids the historical chat crashes.
 */
public final class IOSMessageMenuPreview extends View {
    private static final int MAX_PREVIEW_WIDTH_DP = 360;
    private static final int SCREEN_EDGE_DP = 12;
    private static final int PREVIEW_MENU_GAP_DP = 8;
    private static final int AVATAR_SIZE_DP = 36;
    private static final int AVATAR_GAP_DP = 8;
    private static final int BACKGROUND_DOWNSCALE = 12;
    private static final int BACKGROUND_BLUR_RADIUS = 15;

    private final ViewGroup rootView;
    private final FrameLayout overlay;
    private final ImageView blurredBackgroundView;
    private final ImageView messagePreviewView;
    private final Bitmap messageSnapshot;
    private final BackupImageView avatarPreviewView;
    private Bitmap blurredBackground;
    private boolean cleanedUp;

    private IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag("AUTHORGRAM_IOS_MESSAGE_MENU_V4");
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
            avatarPreviewView = null;
            return;
        }

        rootView = (ViewGroup) root;

        MessageObject messageObject = sourceCell.getMessageObject();
        TLObject senderPeer = resolveSenderPeer(currentAccount, messageObject);
        boolean showStandaloneAvatar = senderPeer != null;

        SnapshotResult messageResult = captureNativeCell(
                sourceCell,
                rootView.getWidth(),
                rootView.getHeight(),
                showStandaloneAvatar
        );
        messageSnapshot = messageResult.bitmap;
        if (messageSnapshot == null) {
            overlay = null;
            blurredBackgroundView = null;
            messagePreviewView = null;
            avatarPreviewView = null;
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
            // Defensive fallback: menu functionality must survive a failed blur allocation.
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

        if (showStandaloneAvatar) {
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(AVATAR_SIZE_DP / 2));
            avatar.setVisibility(INVISIBLE);
            avatar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(currentAccount, senderPeer);
            avatar.setForUserOrChat(senderPeer, avatarDrawable, messageObject);

            overlay.addView(
                    avatar,
                    new FrameLayout.LayoutParams(
                            AndroidUtilities.dp(AVATAR_SIZE_DP),
                            AndroidUtilities.dp(AVATAR_SIZE_DP)
                    )
            );
            avatarPreviewView = avatar;
        } else {
            avatarPreviewView = null;
        }

        rootView.addView(
                overlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        // If popup creation is aborted before this controller is attached,
        // never leave the blur layer behind.
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
                currentAccount,
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

        /*
         * Telegram's popup layout contains an internal ScrollView. With many
         * AuthorGram/Nagram actions its WRAP_CONTENT height can exceed the
         * visible window, leaving the final actions below the navigation area.
         * Cap only this popup's ScrollView to the actual visible viewport. The
         * LinearLayout remains full-height inside it, so every action is still
         * reachable by native scrolling.
         */
        post(() -> {
            constrainMenuScrollToViewport();
            post(this::placeMessagePreviewOutsideMenu);
        });
    }

    /**
     * Keep the action list inside the visible window without changing any menu
     * items or their ordering. Short menus retain WRAP_CONTENT-like dimensions;
     * only overflowing menus receive a finite ScrollView height.
     */
    private void constrainMenuScrollToViewport() {
        if (!isUsable()) {
            return;
        }

        ScrollView menuScroll = findMenuScrollView();
        View menuContent = getParent() instanceof View ? (View) getParent() : null;
        if (menuScroll == null || menuContent == null) {
            return;
        }

        int contentHeight = menuContent.getHeight();
        if (contentHeight <= 0) {
            contentHeight = menuContent.getMeasuredHeight();
        }
        if (contentHeight <= 0) {
            menuScroll.post(this::constrainMenuScrollToViewport);
            return;
        }

        int[] scrollLocation = new int[2];
        menuScroll.getLocationOnScreen(scrollLocation);

        Rect visibleFrame = new Rect();
        rootView.getWindowVisibleDisplayFrame(visibleFrame);

        int edge = AndroidUtilities.dp(SCREEN_EDGE_DP);
        int maxVisibleHeight = visibleFrame.bottom - scrollLocation[1] - edge;
        if (maxVisibleHeight <= AndroidUtilities.dp(96)) {
            // Location can still be settling during the popup's first layout.
            menuScroll.post(this::constrainMenuScrollToViewport);
            return;
        }

        int desiredHeight = Math.min(contentHeight, maxVisibleHeight);
        ViewGroup.LayoutParams params = menuScroll.getLayoutParams();
        if (params != null && contentHeight > maxVisibleHeight && params.height != desiredHeight) {
            params.height = desiredHeight;
            menuScroll.setLayoutParams(params);
            menuScroll.setFillViewport(false);
            menuScroll.requestLayout();
        }
    }

    private ScrollView findMenuScrollView() {
        ViewParent parent = getParent();
        while (parent != null) {
            if (parent instanceof ScrollView) {
                return (ScrollView) parent;
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return null;
    }

    /**
     * The selected message is always a separate element above the visible menu.
     * If screen geometry is unusually tight, the complete snapshot is uniformly
     * scaled to the available space rather than cropped or allowed to overlap.
     */
    private void placeMessagePreviewOutsideMenu() {
        if (!isUsable() || !(getParent() instanceof View)) {
            return;
        }

        ScrollView menuScroll = findMenuScrollView();
        View menuViewport = menuScroll != null ? menuScroll : (View) getParent();
        if (menuViewport.getWidth() <= 0 || menuViewport.getHeight() <= 0) {
            menuViewport.post(this::placeMessagePreviewOutsideMenu);
            return;
        }

        int[] rootLocation = new int[2];
        int[] menuLocation = new int[2];
        rootView.getLocationOnScreen(rootLocation);
        menuViewport.getLocationOnScreen(menuLocation);

        Rect visibleFrame = new Rect();
        rootView.getWindowVisibleDisplayFrame(visibleFrame);

        FrameLayout.LayoutParams messageParams =
                (FrameLayout.LayoutParams) messagePreviewView.getLayoutParams();

        int edge = AndroidUtilities.dp(SCREEN_EDGE_DP);
        int gap = AndroidUtilities.dp(PREVIEW_MENU_GAP_DP);
        int avatarGap = avatarPreviewView == null ? 0 : AndroidUtilities.dp(AVATAR_GAP_DP);
        int avatarSize = avatarPreviewView == null ? 0 : AndroidUtilities.dp(AVATAR_SIZE_DP);

        int minY = Math.max(
                edge,
                visibleFrame.top - rootLocation[1] + edge
        );
        int menuY = menuLocation[1] - rootLocation[1];
        int availableAbove = Math.max(1, menuY - gap - minY);

        int naturalMessageWidth = messageParams.width;
        int naturalMessageHeight = messageParams.height;
        int naturalGroupHeight = Math.max(naturalMessageHeight, avatarSize);

        float fitScale = Math.min(1.0f, availableAbove / (float) naturalGroupHeight);
        int fittedMessageWidth = Math.max(1, Math.round(naturalMessageWidth * fitScale));
        int fittedMessageHeight = Math.max(1, Math.round(naturalMessageHeight * fitScale));
        int fittedAvatarSize = avatarPreviewView == null
                ? 0
                : Math.max(1, Math.round(avatarSize * fitScale));
        int fittedAvatarGap = avatarPreviewView == null
                ? 0
                : Math.max(1, Math.round(avatarGap * fitScale));

        if (messageParams.width != fittedMessageWidth || messageParams.height != fittedMessageHeight) {
            messageParams.width = fittedMessageWidth;
            messageParams.height = fittedMessageHeight;
        }

        int groupWidth = fittedMessageWidth + fittedAvatarSize + fittedAvatarGap;
        int x = menuLocation[0] - rootLocation[0];
        x = clamp(x, edge, Math.max(edge, rootView.getWidth() - groupWidth - edge));

        int groupHeight = Math.max(fittedMessageHeight, fittedAvatarSize);
        int y = Math.max(minY, menuY - groupHeight - gap);

        messageParams.leftMargin = x + fittedAvatarSize + fittedAvatarGap;
        messageParams.topMargin = y + groupHeight - fittedMessageHeight;
        messagePreviewView.setLayoutParams(messageParams);
        messagePreviewView.setVisibility(VISIBLE);

        if (avatarPreviewView != null) {
            FrameLayout.LayoutParams avatarParams =
                    (FrameLayout.LayoutParams) avatarPreviewView.getLayoutParams();
            avatarParams.width = fittedAvatarSize;
            avatarParams.height = fittedAvatarSize;
            avatarParams.leftMargin = x;
            avatarParams.topMargin = y + groupHeight - fittedAvatarSize;
            avatarPreviewView.setRoundRadius(fittedAvatarSize / 2);
            avatarPreviewView.setLayoutParams(avatarParams);
            avatarPreviewView.setVisibility(VISIBLE);
        }
    }

    private static TLObject resolveSenderPeer(int currentAccount, MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }

        long fromId = messageObject.getFromChatId();
        if (fromId == 0 && messageObject.isOutOwner()) {
            fromId = UserConfig.getInstance(currentAccount).getClientUserId();
        }
        if (fromId == 0) {
            return null;
        }

        MessagesController controller = MessagesController.getInstance(currentAccount);
        if (fromId > 0) {
            TLRPC.User user = controller.getUser(fromId);
            if (user == null && fromId == UserConfig.getInstance(currentAccount).getClientUserId()) {
                user = UserConfig.getInstance(currentAccount).getCurrentUser();
            }
            return user;
        }
        return controller.getChat(-fromId);
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
     * Snapshot exactly the already-bound native ChatMessageCell. The crop starts
     * at the bubble itself; sender avatar is rendered as a real BackupImageView
     * beside it so private/outgoing messages also show the actual profile photo.
     */
    private static SnapshotResult captureNativeCell(
            ChatMessageCell sourceCell,
            int rootWidth,
            int rootHeight,
            boolean reserveAvatarSpace
    ) {
        final int sourceWidth = sourceCell.getWidth();
        final int sourceHeight = sourceCell.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return SnapshotResult.EMPTY;
        }

        int left = Math.max(
                0,
                sourceCell.getBackgroundDrawableLeft() - AndroidUtilities.dp(8)
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

        int reservedWidth = reserveAvatarSpace
                ? AndroidUtilities.dp(AVATAR_SIZE_DP + AVATAR_GAP_DP)
                : 0;
        int horizontalRoom = Math.max(
                AndroidUtilities.dp(120),
                rootWidth - AndroidUtilities.dp(SCREEN_EDGE_DP * 2) - reservedWidth
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
        if (avatarPreviewView != null) {
            avatarPreviewView.setImageDrawable(null);
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
