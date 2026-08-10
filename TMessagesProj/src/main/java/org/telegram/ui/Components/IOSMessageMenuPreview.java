package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

/**
 * Dev-only controller for the AuthorGram iOS-style message context preview.
 *
 * Reference geometry is intentionally the iOS-style example with the message
 * from "Vadym Yemelianov":
 *
 *     reactions
 *     selected native Telegram message (avatar + bubble)
 *     action menu
 *
 * The selected message is NOT part of the action-menu background. It is placed
 * as a transparent sibling immediately before the popup menu inside
 * ChatScrimPopupContainerLayout, while a full-screen blurred snapshot remains
 * behind the complete popup composition.
 *
 * The source ChatMessageCell is only snapshotted. MessageObject is never rebound,
 * so reply/link/media/settings-link processing remains isolated from this UI.
 */
public final class IOSMessageMenuPreview extends View {
    private static final int SCREEN_EDGE_DP = 12;
    private static final int PREVIEW_TOP_GAP_DP = 10;
    private static final int PREVIEW_MENU_GAP_DP = 12;
    private static final int PREVIEW_EXTRA_WIDTH_DP = 24;
    private static final int AVATAR_SIZE_DP = 36;
    private static final int AVATAR_GAP_DP = 8;
    private static final int SENDER_NAME_HEIGHT_DP = 20;
    private static final int SENDER_NAME_BOTTOM_GAP_DP = 4;
    private static final int MIN_PREVIEW_VIEWPORT_DP = 104;
    private static final int MAX_PREVIEW_VIEWPORT_DP = 260;
    private static final int MIN_MENU_VIEWPORT_DP = 150;
    private static final int MAX_MENU_VIEWPORT_DP = 220;
    private static final int BACKGROUND_DOWNSCALE = 12;
    private static final int BACKGROUND_BLUR_RADIUS = 15;

    private ViewGroup rootView;
    private FrameLayout blurOverlay;
    private ImageView blurredBackgroundView;
    private Bitmap blurredBackground;

    private FrameLayout previewHost;
    private ScrollView previewScrollView;
    private FrameLayout previewContent;
    private ImageView messagePreviewView;
    private BackupImageView avatarPreviewView;
    private TextView senderNameView;

    private Bitmap messageSnapshot;
    private int snapshotWidth;
    private int snapshotHeight;

    private ChatScrimPopupContainerLayout scrimContainer;
    private View menuDirectChild;
    private int currentGroupHeight;
    private boolean previewRevealed;
    private boolean cleanedUp;

    private IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag("AUTHORGRAM_IOS_MESSAGE_MENU_V6_FINAL");
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        if (sourceCell == null
                || sourceCell.getWidth() <= 0
                || sourceCell.getHeight() <= 0
                || !(sourceCell.getRootView() instanceof ViewGroup)) {
            return;
        }

        View root = sourceCell.getRootView();
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        rootView = (ViewGroup) root;

        SnapshotResult result = captureNativeCell(sourceCell);
        messageSnapshot = result.bitmap;
        snapshotWidth = result.width;
        snapshotHeight = result.height;
        if (messageSnapshot == null || snapshotWidth <= 0 || snapshotHeight <= 0) {
            return;
        }

        MessageObject messageObject = sourceCell.getMessageObject();
        TLObject senderPeer = resolveSenderPeer(currentAccount, messageObject);
        boolean showExternalSenderName = sourceCell.getAvatarImage() == null;

        createBlurOverlay(context, resourcesProvider);
        createPreviewViews(
                context,
                currentAccount,
                messageObject,
                senderPeer,
                showExternalSenderName,
                resourcesProvider
        );

        // If popup creation is aborted before the controller reaches a window,
        // never leave the blur layer attached to the activity root.
        if (blurOverlay != null) {
            blurOverlay.postDelayed(() -> {
                if (!isAttachedToWindow()) {
                    cleanup();
                }
            }, 2500L);
        }
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
                && blurOverlay != null
                && previewHost != null
                && previewScrollView != null
                && previewContent != null
                && messagePreviewView != null
                && messageSnapshot != null;
    }

    private void createBlurOverlay(Context context, Theme.ResourcesProvider resourcesProvider) {
        blurredBackground = captureBlurredBackground(rootView);

        blurOverlay = new FrameLayout(context);
        blurOverlay.setTag("AUTHORGRAM_IOS_MESSAGE_MENU_BLUR_OVERLAY");
        blurOverlay.setClickable(false);
        blurOverlay.setFocusable(false);
        blurOverlay.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        blurredBackgroundView = new ImageView(context);
        blurredBackgroundView.setScaleType(ImageView.ScaleType.FIT_XY);
        blurredBackgroundView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (blurredBackground != null) {
            blurredBackgroundView.setImageBitmap(blurredBackground);
        } else {
            blurredBackgroundView.setBackgroundColor(
                    Theme.multAlpha(
                            Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider),
                            0.55f
                    )
            );
        }
        blurOverlay.addView(
                blurredBackgroundView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        View dim = new View(context);
        dim.setBackgroundColor(Color.argb(52, 0, 0, 0));
        dim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        blurOverlay.addView(
                dim,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        rootView.addView(
                blurOverlay,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );
    }

    private void createPreviewViews(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            TLObject senderPeer,
            boolean showExternalSenderName,
            Theme.ResourcesProvider resourcesProvider
    ) {
        previewHost = new FrameLayout(context);
        previewHost.setTag("AUTHORGRAM_IOS_MESSAGE_PREVIEW_HOST");
        previewHost.setBackgroundColor(Color.TRANSPARENT);
        previewHost.setClipChildren(true);
        previewHost.setClipToPadding(true);
        previewHost.setVisibility(INVISIBLE);
        previewHost.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        previewScrollView = new ScrollView(context);
        previewScrollView.setFillViewport(false);
        previewScrollView.setVerticalScrollBarEnabled(false);
        previewScrollView.setVerticalFadingEdgeEnabled(false);
        previewScrollView.setOverScrollMode(OVER_SCROLL_NEVER);
        previewScrollView.setClipChildren(true);
        previewScrollView.setClipToPadding(true);

        previewContent = new FrameLayout(context);
        previewContent.setClipChildren(false);
        previewContent.setClipToPadding(false);
        previewScrollView.addView(
                previewContent,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        previewHost.addView(
                previewScrollView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                )
        );

        messagePreviewView = new ImageView(context);
        messagePreviewView.setScaleType(ImageView.ScaleType.FIT_XY);
        messagePreviewView.setAdjustViewBounds(false);
        messagePreviewView.setImageBitmap(messageSnapshot);
        messagePreviewView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewContent.addView(
                messagePreviewView,
                new FrameLayout.LayoutParams(snapshotWidth, snapshotHeight)
        );

        if (showExternalSenderName) {
            String senderName = resolveSenderName(senderPeer);
            if (!TextUtils.isEmpty(senderName)) {
                TextView name = new TextView(context);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setText(senderName);
                name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                name.setTypeface(AndroidUtilities.bold());
                name.setTextColor(Theme.getColor(
                        Theme.key_actionBarDefaultSubmenuItem,
                        resourcesProvider
                ));
                name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                previewContent.addView(
                        name,
                        new FrameLayout.LayoutParams(
                                Math.max(1, snapshotWidth),
                                AndroidUtilities.dp(SENDER_NAME_HEIGHT_DP)
                        )
                );
                senderNameView = name;
            }
        }

        if (senderPeer != null) {
            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(AVATAR_SIZE_DP / 2));
            avatar.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(currentAccount, senderPeer);
            avatar.setForUserOrChat(senderPeer, avatarDrawable, messageObject);

            previewContent.addView(
                    avatar,
                    new FrameLayout.LayoutParams(
                            AndroidUtilities.dp(AVATAR_SIZE_DP),
                            AndroidUtilities.dp(AVATAR_SIZE_DP)
                    )
            );
            avatarPreviewView = avatar;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(() -> {
            if (!isUsable()) {
                return;
            }

            removeLegacyTopGap();
            attachPreviewBetweenReactionsAndMenu();
        });
    }

    /**
     * Older V4 integration inserted an 8dp GapView after this controller. The
     * V5 preview is now a true sibling between reactions and menu, so that old
     * separator must not consume space at the top of the action block.
     */
    private void removeLegacyTopGap() {
        ViewParent parent = getParent();
        if (!(parent instanceof LinearLayout)) {
            return;
        }
        LinearLayout list = (LinearLayout) parent;
        int index = list.indexOfChild(this);
        if (index < 0 || index + 1 >= list.getChildCount()) {
            return;
        }
        View next = list.getChildAt(index + 1);
        if (!(next instanceof ActionBarPopupWindow.GapView)) {
            return;
        }
        ViewGroup.LayoutParams params = next.getLayoutParams();
        if (params == null
                || params.height == AndroidUtilities.dp(8)
                || params.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            list.removeView(next);
        }
    }

    /**
     * Moves only the visible preview host into the popup's vertical scrim flow:
     * reactions -> preview -> menu. The hidden controller remains in the native
     * popup layout solely to share the popup lifecycle.
     */
    private void attachPreviewBetweenReactionsAndMenu() {
        if (!isUsable() || previewHost.getParent() != null) {
            return;
        }

        scrimContainer = findScrimAncestor();
        if (scrimContainer == null) {
            return;
        }

        menuDirectChild = findDirectChildBelowScrim(scrimContainer);
        if (menuDirectChild == null) {
            return;
        }

        int menuIndex = scrimContainer.indexOfChild(menuDirectChild);
        if (menuIndex < 0) {
            return;
        }

        int menuWidth = menuDirectChild.getMeasuredWidth();
        if (menuWidth <= 0) {
            menuWidth = menuDirectChild.getWidth();
        }
        if (menuWidth <= 0) {
            scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);
            return;
        }

        int previewWidth = calculatePreviewWidth(menuWidth);
        currentGroupHeight = configurePreviewContentForWidth(previewWidth);
        int initialHeight = Math.min(
                Math.max(1, currentGroupHeight),
                AndroidUtilities.dp(MAX_PREVIEW_VIEWPORT_DP)
        );

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                initialHeight
        );
        params.topMargin = AndroidUtilities.dp(PREVIEW_TOP_GAP_DP);
        params.bottomMargin = AndroidUtilities.dp(PREVIEW_MENU_GAP_DP);

        FrameLayout.LayoutParams scrollParams =
                (FrameLayout.LayoutParams) previewScrollView.getLayoutParams();
        scrollParams.width = previewWidth;
        scrollParams.height = initialHeight;
        previewScrollView.setLayoutParams(scrollParams);

        scrimContainer.addView(previewHost, menuIndex, params);
        reflowPreviewAndMenu();
    }

    private ChatScrimPopupContainerLayout findScrimAncestor() {
        ViewParent parent = getParent();
        while (parent != null) {
            if (parent instanceof ChatScrimPopupContainerLayout) {
                return (ChatScrimPopupContainerLayout) parent;
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return null;
    }

    private View findDirectChildBelowScrim(ChatScrimPopupContainerLayout scrim) {
        View node = this;
        ViewParent parent = node.getParent();
        while (parent instanceof View && parent != scrim) {
            node = (View) parent;
            parent = node.getParent();
        }
        return parent == scrim ? node : null;
    }

    private void reflowPreviewAndMenu() {
        if (!isUsable()
                || scrimContainer == null
                || menuDirectChild == null
                || previewHost.getParent() != scrimContainer) {
            return;
        }

        int menuWidth = menuDirectChild.getMeasuredWidth();
        if (menuWidth <= 0) {
            menuWidth = menuDirectChild.getWidth();
        }
        if (menuWidth <= 0) {
            scrimContainer.post(this::reflowPreviewAndMenu);
            return;
        }

        int previewWidth = calculatePreviewWidth(menuWidth);
        int groupHeight = configurePreviewContentForWidth(previewWidth);
        if (groupHeight <= 0) {
            return;
        }
        currentGroupHeight = groupHeight;

        ScrollView menuScroll = findMenuScrollView();
        int naturalMenuHeight = getNaturalMenuHeight(menuScroll);
        if (naturalMenuHeight <= 0) {
            naturalMenuHeight = Math.max(1, menuDirectChild.getMeasuredHeight());
        }

        Rect visibleFrame = new Rect();
        rootView.getWindowVisibleDisplayFrame(visibleFrame);
        int visibleHeight = visibleFrame.height();
        if (visibleHeight <= 0) {
            visibleHeight = rootView.getHeight();
        }

        int fixedHeight = getFixedScrimChildrenHeight();
        LinearLayout.LayoutParams hostParams =
                (LinearLayout.LayoutParams) previewHost.getLayoutParams();
        int hostMargins = hostParams.topMargin + hostParams.bottomMargin;

        int stackCapacity = Math.max(
                AndroidUtilities.dp(MIN_PREVIEW_VIEWPORT_DP + 96),
                visibleHeight
                        - AndroidUtilities.dp(SCREEN_EDGE_DP * 2)
                        - fixedHeight
                        - hostMargins
        );

        int desiredMenuViewport = Math.min(
                naturalMenuHeight,
                AndroidUtilities.dp(MAX_MENU_VIEWPORT_DP)
        );
        int minimumMenuViewport = Math.min(
                naturalMenuHeight,
                AndroidUtilities.dp(MIN_MENU_VIEWPORT_DP)
        );
        int minimumPreviewViewport = Math.min(
                groupHeight,
                AndroidUtilities.dp(MIN_PREVIEW_VIEWPORT_DP)
        );
        int previewCeiling = Math.min(
                groupHeight,
                AndroidUtilities.dp(MAX_PREVIEW_VIEWPORT_DP)
        );

        int previewViewportHeight = Math.min(
                previewCeiling,
                Math.max(minimumPreviewViewport, stackCapacity - minimumMenuViewport)
        );
        previewViewportHeight = Math.min(
                previewViewportHeight,
                Math.max(1, stackCapacity - AndroidUtilities.dp(96))
        );

        int menuViewportLimit = Math.min(
                desiredMenuViewport,
                Math.max(AndroidUtilities.dp(96), stackCapacity - previewViewportHeight)
        );
        if (menuViewportLimit < minimumMenuViewport
                && previewViewportHeight > minimumPreviewViewport) {
            int giveBack = Math.min(
                    previewViewportHeight - minimumPreviewViewport,
                    minimumMenuViewport - menuViewportLimit
            );
            previewViewportHeight -= giveBack;
            menuViewportLimit += giveBack;
        }

        if (hostParams.height != previewViewportHeight) {
            hostParams.height = previewViewportHeight;
            previewHost.setLayoutParams(hostParams);
        }

        FrameLayout.LayoutParams scrollParams =
                (FrameLayout.LayoutParams) previewScrollView.getLayoutParams();
        scrollParams.width = previewWidth;
        scrollParams.height = previewViewportHeight;
        previewScrollView.setLayoutParams(scrollParams);

        constrainMenuScroll(menuScroll, menuViewportLimit);

        previewHost.requestLayout();
        scrimContainer.requestLayout();
        scrimContainer.post(this::alignPreviewWithMenu);
    }

    /**
     * Applies exactly one scale factor and only for width. Height never affects
     * scale. A tall message therefore keeps its real proportions and becomes
     * vertically scrollable inside previewScrollView.
     */
    private int configurePreviewContentForWidth(int previewWidth) {
        int avatarSize = avatarPreviewView == null ? 0 : AndroidUtilities.dp(AVATAR_SIZE_DP);
        int avatarGap = avatarPreviewView == null ? 0 : AndroidUtilities.dp(AVATAR_GAP_DP);
        int senderNameHeight = senderNameView == null ? 0 : AndroidUtilities.dp(SENDER_NAME_HEIGHT_DP);
        int senderNameGap = senderNameView == null ? 0 : AndroidUtilities.dp(SENDER_NAME_BOTTOM_GAP_DP);

        int availableMessageWidth = Math.max(
                AndroidUtilities.dp(96),
                previewWidth - avatarSize - avatarGap
        );
        float widthScale = Math.min(1.0f, availableMessageWidth / (float) snapshotWidth);

        int messageWidth = Math.max(1, Math.round(snapshotWidth * widthScale));
        int messageHeight = Math.max(1, Math.round(snapshotHeight * widthScale));
        int groupWidth = messageWidth + avatarSize + avatarGap;
        int groupLeft = Math.max(0, (previewWidth - groupWidth) / 2);
        int bodyTop = senderNameHeight + senderNameGap;
        int bodyHeight = Math.max(messageHeight, avatarSize);
        int groupHeight = bodyTop + bodyHeight;

        FrameLayout.LayoutParams messageParams =
                (FrameLayout.LayoutParams) messagePreviewView.getLayoutParams();
        messageParams.width = messageWidth;
        messageParams.height = messageHeight;
        messageParams.leftMargin = groupLeft + avatarSize + avatarGap;
        messageParams.topMargin = bodyTop + bodyHeight - messageHeight;
        messagePreviewView.setLayoutParams(messageParams);

        if (senderNameView != null) {
            FrameLayout.LayoutParams nameParams =
                    (FrameLayout.LayoutParams) senderNameView.getLayoutParams();
            nameParams.width = messageWidth;
            nameParams.height = senderNameHeight;
            nameParams.leftMargin = groupLeft + avatarSize + avatarGap;
            nameParams.topMargin = 0;
            senderNameView.setLayoutParams(nameParams);
        }

        if (avatarPreviewView != null) {
            FrameLayout.LayoutParams avatarParams =
                    (FrameLayout.LayoutParams) avatarPreviewView.getLayoutParams();
            avatarParams.width = avatarSize;
            avatarParams.height = avatarSize;
            avatarParams.leftMargin = groupLeft;
            avatarParams.topMargin = bodyTop + bodyHeight - avatarSize;
            avatarPreviewView.setRoundRadius(avatarSize / 2);
            avatarPreviewView.setLayoutParams(avatarParams);
        }

        ViewGroup.LayoutParams contentParams = previewContent.getLayoutParams();
        contentParams.width = previewWidth;
        contentParams.height = groupHeight;
        previewContent.setLayoutParams(contentParams);
        return groupHeight;
    }

    private int calculatePreviewWidth(int menuWidth) {
        int maxAvailable = Math.max(
                menuWidth,
                rootView.getWidth() - AndroidUtilities.dp(SCREEN_EDGE_DP * 2)
        );
        return Math.min(
                maxAvailable,
                menuWidth + AndroidUtilities.dp(PREVIEW_EXTRA_WIDTH_DP)
        );
    }

    private int getFixedScrimChildrenHeight() {
        int total = 0;
        for (int i = 0; i < scrimContainer.getChildCount(); i++) {
            View child = scrimContainer.getChildAt(i);
            if (child == previewHost
                    || child == menuDirectChild
                    || child.getVisibility() == GONE) {
                continue;
            }
            int height = child.getMeasuredHeight();
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                height += margins.topMargin + margins.bottomMargin;
            }
            total += Math.max(0, height);
        }
        return total;
    }

    private int getNaturalMenuHeight(ScrollView menuScroll) {
        if (menuScroll == null) {
            return menuDirectChild == null ? 0 : menuDirectChild.getMeasuredHeight();
        }

        int contentHeight = 0;
        if (menuScroll.getChildCount() > 0) {
            View content = menuScroll.getChildAt(0);
            contentHeight = content.getMeasuredHeight();
            if (contentHeight <= 0) {
                contentHeight = content.getHeight();
            }
        }

        View popup = findPopupLayoutAncestor();
        int verticalPadding = popup == null ? 0 : popup.getPaddingTop() + popup.getPaddingBottom();
        return Math.max(0, contentHeight) + verticalPadding;
    }

    private void constrainMenuScroll(ScrollView menuScroll, int menuViewportLimit) {
        if (menuScroll == null || menuViewportLimit <= 0) {
            return;
        }

        int contentHeight = 0;
        if (menuScroll.getChildCount() > 0) {
            View content = menuScroll.getChildAt(0);
            contentHeight = content.getMeasuredHeight();
            if (contentHeight <= 0) {
                contentHeight = content.getHeight();
            }
        }
        if (contentHeight <= 0) {
            return;
        }

        View popup = findPopupLayoutAncestor();
        int verticalPadding = popup == null ? 0 : popup.getPaddingTop() + popup.getPaddingBottom();
        int maxScrollHeight = Math.max(
                AndroidUtilities.dp(96),
                menuViewportLimit - verticalPadding
        );

        ViewGroup.LayoutParams params = menuScroll.getLayoutParams();
        if (params == null) {
            return;
        }

        int desiredHeight = contentHeight > maxScrollHeight
                ? maxScrollHeight
                : ViewGroup.LayoutParams.WRAP_CONTENT;
        if (params.height != desiredHeight) {
            params.height = desiredHeight;
            menuScroll.setLayoutParams(params);
            menuScroll.setFillViewport(false);
            menuScroll.requestLayout();
        }
    }

    private View findPopupLayoutAncestor() {
        ViewParent parent = getParent();
        while (parent != null && parent != scrimContainer) {
            if (parent instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
                return (View) parent;
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return null;
    }

    private ScrollView findMenuScrollView() {
        ViewParent parent = getParent();
        while (parent != null && parent != scrimContainer) {
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

    /** Align the transparent preview viewport to the actual popup block. */
    private void alignPreviewWithMenu() {
        if (!isUsable()
                || previewHost.getWidth() <= 0
                || menuDirectChild == null
                || menuDirectChild.getWidth() <= 0) {
            return;
        }

        int[] hostLocation = new int[2];
        int[] menuLocation = new int[2];
        previewHost.getLocationOnScreen(hostLocation);
        menuDirectChild.getLocationOnScreen(menuLocation);

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) previewScrollView.getLayoutParams();
        int left = menuLocation[0] - hostLocation[0]
                - Math.max(0, params.width - menuDirectChild.getWidth()) / 2;
        left = clamp(
                left,
                0,
                Math.max(0, previewHost.getWidth() - params.width)
        );
        if (params.leftMargin != left) {
            params.leftMargin = left;
            previewScrollView.setLayoutParams(params);
        }

        // Short messages show in full. Long messages start at the top and can be
        // scrolled to the bottom without changing scale or cropping the bitmap.
        if (currentGroupHeight <= previewScrollView.getHeight()) {
            previewScrollView.scrollTo(0, 0);
        }

        if (!previewRevealed) {
            previewScrollView.scrollTo(0, 0);
            previewHost.setVisibility(VISIBLE);
            previewRevealed = true;
        }
    }

    private static String resolveSenderName(TLObject senderPeer) {
        if (senderPeer instanceof TLRPC.User) {
            return UserObject.getUserName((TLRPC.User) senderPeer);
        }
        if (senderPeer instanceof TLRPC.Chat) {
            return ((TLRPC.Chat) senderPeer).title;
        }
        return null;
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
     * Captures the complete native Telegram bubble at source resolution. No
     * height-based scale or crop is applied. Width adaptation happens later,
     * against the real menu width.
     *
     * Telegram can draw an outgoing bubble background through parent-assisted
     * rendering. For own messages we therefore explicitly paint the native
     * Theme.MessageDrawable before drawing the already-bound ChatMessageCell.
     */
    private static SnapshotResult captureNativeCell(ChatMessageCell sourceCell) {
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

        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-left, -top);

            MessageObject messageObject = sourceCell.getMessageObject();
            if (messageObject != null
                    && messageObject.isOutOwner()
                    && !messageObject.isAnimatedEmojiStickers()) {
                Theme.MessageDrawable bubble = sourceCell.getCurrentBackgroundDrawable(true);
                if (bubble != null) {
                    bubble.setBounds(
                            sourceCell.getBackgroundDrawableLeft(),
                            sourceCell.getBackgroundDrawableTop(),
                            sourceCell.getBackgroundDrawableRight(),
                            sourceCell.getBackgroundDrawableBottom()
                    );
                    bubble.setDrawFullBubble(true);
                    bubble.draw(canvas);
                    bubble.setDrawFullBubble(false);
                }
            }

            sourceCell.draw(canvas);
            return new SnapshotResult(bitmap, cropWidth, cropHeight);
        } catch (Throwable ignored) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
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

        if (previewHost != null && previewHost.getParent() instanceof ViewGroup) {
            ((ViewGroup) previewHost.getParent()).removeView(previewHost);
        }
        if (blurOverlay != null && blurOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) blurOverlay.getParent()).removeView(blurOverlay);
        }

        if (messageSnapshot != null && !messageSnapshot.isRecycled()) {
            messageSnapshot.recycle();
        }
        messageSnapshot = null;

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
