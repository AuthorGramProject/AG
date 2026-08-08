package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Main-only native selected-message preview for the iOS-style context menu.
 *
 * AUTHORGRAM_UNIFIED_IOS_MESSAGE_BLOCK
 * AUTHORGRAM_ADAPTIVE_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_FINAL_PREVIEW_COMPAT
 * AUTHORGRAM_IOS_MESSAGE_SENDER_IDENTITY
 * AUTHORGRAM_NATIVE_ONLY_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_WEB_PREVIEW_SAFE_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW
 * AUTHORGRAM_REFERENCE_IOS_MENU_GEOMETRY
 *
 * Reference structure: reactions -> native selected message -> separate action
 * card. A fresh Telegram ChatMessageCell renders avatar, sender, reply/quote,
 * media and message text. Tall content is bounded and scrolls inside this
 * independent preview; it is never inserted into the action-card ScrollView.
 */
public final class IOSMessageMenuPreview extends FrameLayout {
    public static final String NATIVE_PREVIEW_TAG = "AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW";

    private final ChatMessageCell previewCell;
    private final ScrollView previewScroll;
    private final int maxPreviewHeight;

    public IOSMessageMenuPreview(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            ChatMessageCell sourceCell,
            Theme.ResourcesProvider resourcesProvider
    ) {
        super(context);
        setTag(NATIVE_PREVIEW_TAG);
        setClipChildren(true);
        setClipToPadding(true);

        int viewportHeight = Math.max(AndroidUtilities.dp(320), AndroidUtilities.displaySize.y);
        maxPreviewHeight = Math.max(
                AndroidUtilities.dp(120),
                Math.min(AndroidUtilities.dp(300), Math.round(viewportHeight * 0.34f))
        );

        if (!AuthorGramPlayPolicy.canUseIosUi()
                || !NekoConfig.iOSMessageMenu.Bool()
                || messageObject == null) {
            setVisibility(GONE);
            previewCell = null;
            previewScroll = null;
            return;
        }

        previewScroll = new ScrollView(context);
        previewScroll.setFillViewport(false);
        previewScroll.setVerticalScrollBarEnabled(false);
        previewScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        previewScroll.setClipToPadding(false);
        previewScroll.setNestedScrollingEnabled(true);

        previewCell = new ChatMessageCell(context, currentAccount);
        previewCell.setTag(NATIVE_PREVIEW_TAG);
        previewCell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        previewCell.setClickable(false);
        previewCell.setLongClickable(false);
        previewCell.setFocusable(false);
        previewCell.setEnabled(false);
        previewCell.isChat = sourceCell != null && sourceCell.isChat;
        previewCell.setFullyDraw(true);
        previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            @Override
            public boolean canPerformActions() {
                return false;
            }
        });
        previewCell.setMessageObject(messageObject, null, false, false, false);

        previewScroll.addView(previewCell, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        addView(previewScroll, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        ));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentSize = MeasureSpec.getSize(heightMeasureSpec);
        int cap = parentMode == MeasureSpec.UNSPECIFIED || parentSize <= 0
                ? maxPreviewHeight
                : Math.min(parentSize, maxPreviewHeight);
        super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(Math.max(1, cap), MeasureSpec.AT_MOST)
        );
    }

    /** Compatibility API: selected-message content never joins action rows. */
    public boolean shouldScrollWithActions() {
        return false;
    }
}
