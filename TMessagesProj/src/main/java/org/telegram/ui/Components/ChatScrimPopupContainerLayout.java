package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.List;

public class ChatScrimPopupContainerLayout extends LinearLayout {
    private float bottomViewReactionsOffset;
    private float bottomViewYOffset;
    private final List<FrameLayout> bottomViews = new ArrayList<>();
    private boolean authorGramUnifiedFooterSeparatorAdded; // AUTHORGRAM_UNIFIED_MENU_FOOTER
    private float currentPopupAlpha = 1.0f;
    private float expandSize;
    private float lastReactionsTransitionProgress;
    private int maxHeight;
    private View fixedMessagePreview; // AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW
    private float popupLayoutLeftOffset;
    private ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout;
    private float progressToSwipeBack;
    private ReactionsContainerLayout reactionsLayout;

    public ChatScrimPopupContainerLayout(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateBottomViewPosition();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // AUTHORGRAM_ADAPTIVE_POPUP_BOUNDS
        // Some OEM/window combinations pass an effectively unbounded measure spec.
        // Always cap the menu to the real display/work-area height so the internal
        // ScrollView scrolls instead of the popup escaping below the screen.
        int parentMode = MeasureSpec.getMode(heightMeasureSpec);
        int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
        int displayHeight = Math.max(AndroidUtilities.dp(240), AndroidUtilities.displaySize.y);
        int availableHeight = parentMode == MeasureSpec.UNSPECIFIED || parentHeight <= 0
                ? displayHeight
                : Math.min(parentHeight, displayHeight);
        availableHeight = Math.max(AndroidUtilities.dp(160), availableHeight - AndroidUtilities.dp(16));
        int effectiveMaxHeight = maxHeight > 0
                ? Math.min(maxHeight, availableHeight)
                : availableHeight;
        int constrainedHeightSpec = MeasureSpec.makeMeasureSpec(effectiveMaxHeight, MeasureSpec.AT_MOST);
        int adjustedWidthSpec = widthMeasureSpec;

        authorGramAttachPendingBottomViews(); // AUTHORGRAM_UNIFIED_MENU_FOOTER

        // Reset a previous viewport cap before measuring natural popup content.
        if (popupWindowLayout != null) {
            LinearLayout.LayoutParams popupParams =
                    (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();
            if (popupParams.height != LayoutHelper.WRAP_CONTENT) {
                popupParams.height = LayoutHelper.WRAP_CONTENT;
            }
        }
        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
        if (popupWindowLayout == null) {
            return;
        }

        // AUTHORGRAM_FIXED_IOS_MESSAGE_PREVIEW
        // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_SCROLL
        // Top-level children (reactions and, for short messages, the preview)
        // remain fixed. The popup receives exactly the remaining viewport.
        // When a long preview is inside popupLayout, it scrolls with all actions.
        int occupiedHeight = getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == popupWindowLayout || child.getVisibility() == GONE) {
                continue;
            }
            LinearLayout.LayoutParams childParams =
                    (LinearLayout.LayoutParams) child.getLayoutParams();
            occupiedHeight += child.getMeasuredHeight()
                    + childParams.topMargin
                    + childParams.bottomMargin;
        }
        // AUTHORGRAM_STRICT_MENU_VIEWPORT
        // Never force the popup beyond the real work area. Content that does
        // not fit belongs to ActionBarPopupWindowLayout's internal ScrollView.
        int availableForActions = Math.max(
                1,
                effectiveMaxHeight - occupiedHeight
        );
        LinearLayout.LayoutParams popupParams =
                (LinearLayout.LayoutParams) popupWindowLayout.getLayoutParams();
        int desiredPopupHeight = popupWindowLayout.getMeasuredHeight();
        if (desiredPopupHeight > availableForActions) {
            popupParams.height = availableForActions;
            super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
        }

        if (fixedMessagePreview != null) {
            int popupWidthForPreview = popupWindowLayout.getMeasuredWidth();
            LinearLayout.LayoutParams previewParams =
                    (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();
            if (popupWidthForPreview > 0 && previewParams.width != popupWidthForPreview) {
                previewParams.width = popupWidthForPreview;
                super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
            }
        }

        if (reactionsLayout != null) {
            reactionsLayout.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
            ((LinearLayout.LayoutParams) reactionsLayout.getLayoutParams()).rightMargin = 0;
        }

        int maxWidth = reactionsLayout != null ? reactionsLayout.getMeasuredWidth() : 0;
        if (popupWindowLayout.getSwipeBack() != null && popupWindowLayout.getSwipeBack().getMeasuredWidth() > maxWidth) {
            maxWidth = popupWindowLayout.getSwipeBack().getMeasuredWidth();
        }
        if (popupWindowLayout.getMeasuredWidth() > maxWidth) {
            maxWidth = popupWindowLayout.getMeasuredWidth();
        }

        if (reactionsLayout != null && reactionsLayout.showCustomEmojiReaction()) {
            adjustedWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }

        boolean needsRemeasure = false;
        if (reactionsLayout != null) {
            reactionsLayout.measureHint();
            int totalWidth = reactionsLayout.getTotalWidth();
            View menuContainer = (popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack() : popupWindowLayout).getChildAt(0);
            int maxReactionsLayoutWidth = menuContainer.getMeasuredWidth() + AndroidUtilities.dp(16) + AndroidUtilities.dp(16) + AndroidUtilities.dp(36);
            int hintTextWidth = reactionsLayout.getHintTextWidth();
            if (hintTextWidth > maxReactionsLayoutWidth) {
                maxReactionsLayoutWidth = hintTextWidth;
            } else if (maxReactionsLayoutWidth > maxWidth) {
                maxReactionsLayoutWidth = maxWidth;
            }
            reactionsLayout.bigCircleOffset = AndroidUtilities.dp(36);
            if (reactionsLayout.showCustomEmojiReaction()) {
                if (reactionsLayout.getLayoutParams().width != totalWidth) {
                    reactionsLayout.getLayoutParams().width = totalWidth;
                    needsRemeasure = true;
                }
                reactionsLayout.bigCircleOffset = Math.max(totalWidth - menuContainer.getMeasuredWidth() - AndroidUtilities.dp(36), AndroidUtilities.dp(36));
            } else if (totalWidth > maxReactionsLayoutWidth) {
                int maxFullCount = ((maxReactionsLayoutWidth - AndroidUtilities.dp(16)) / AndroidUtilities.dp(36)) + 1;
                int newWidth = maxFullCount * AndroidUtilities.dp(36) + AndroidUtilities.dp(8);
                if (hintTextWidth + AndroidUtilities.dp(24) > newWidth) {
                    newWidth = hintTextWidth + AndroidUtilities.dp(24);
                }
                if (newWidth <= totalWidth && maxFullCount != reactionsLayout.getItemsCount()) {
                    totalWidth = newWidth;
                }
                if (reactionsLayout.getLayoutParams().width != totalWidth) {
                    reactionsLayout.getLayoutParams().width = totalWidth;
                    needsRemeasure = true;
                }
            } else if (reactionsLayout.getLayoutParams().width != LayoutHelper.WRAP_CONTENT) {
                reactionsLayout.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
                needsRemeasure = true;
            }

            if (reactionsLayout.getMeasuredWidth() != maxWidth || !reactionsLayout.showCustomEmojiReaction()) {
                int widthDiff = popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack().getMeasuredWidth() - popupWindowLayout.getSwipeBack().getChildAt(0).getMeasuredWidth() : 0;
                if (reactionsLayout.getLayoutParams().width != LayoutHelper.WRAP_CONTENT && reactionsLayout.getLayoutParams().width + widthDiff > maxWidth) {
                    widthDiff = maxWidth - reactionsLayout.getLayoutParams().width + AndroidUtilities.dp(8);
                }
                if (widthDiff < 0) {
                    widthDiff = 0;
                }
                LinearLayout.LayoutParams reactionsParams = (LinearLayout.LayoutParams) reactionsLayout.getLayoutParams();
                if (reactionsParams.rightMargin != widthDiff) {
                    reactionsParams.rightMargin = widthDiff;
                    needsRemeasure = true;
                }
                popupLayoutLeftOffset = 0.0f;
            } else {
                float offset = (maxWidth - menuContainer.getMeasuredWidth()) * 0.25f;
                popupLayoutLeftOffset = offset;
                reactionsLayout.bigCircleOffset -= (int) offset;
                if (reactionsLayout.bigCircleOffset < AndroidUtilities.dp(36)) {
                    popupLayoutLeftOffset = 0.0f;
                    reactionsLayout.bigCircleOffset = AndroidUtilities.dp(36);
                }
            }
        }

        int foregroundWidth = (popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack() : popupWindowLayout).getChildAt(0).getMeasuredWidth();
        int popupWidth = popupWindowLayout.getMeasuredWidth();
        int swipeBackWidthDiff = popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack().getMeasuredWidth() - foregroundWidth : 0;
        int safeSwipeBackWidthDiff = Math.max(0, swipeBackWidthDiff);
        for (FrameLayout view : bottomViews) {
            if (view == null) {
                continue;
            }
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) view.getLayoutParams();
            // AUTHORGRAM_MENU_FOOTER_WIDTH_PARITY
            // Bottom quick-action blocks must exactly match the menu card width.
            int newWidth = popupWidth > 0 ? popupWidth : foregroundWidth;
            int newSideMargin = popupWindowLayout.getSwipeBack() != null ? AndroidUtilities.dp(36) + safeSwipeBackWidthDiff : AndroidUtilities.dp(36);
            int currentSideMargin = LocaleController.isRTL ? layoutParams.leftMargin : layoutParams.rightMargin;
            if (layoutParams.width != newWidth || currentSideMargin != newSideMargin) {
                layoutParams.width = newWidth;
                if (LocaleController.isRTL) {
                    layoutParams.leftMargin = newSideMargin;
                } else {
                    layoutParams.rightMargin = newSideMargin;
                }
                needsRemeasure = true;
            }
            if (progressToSwipeBack > 0.0f) {
                view.setAlpha((1.0f - progressToSwipeBack) * ((reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress) * currentPopupAlpha);
            }
        }

        updatePopupTranslation();
        if (needsRemeasure) {
            super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
        }
    }

    private void updatePopupTranslation() {
        float translationX = (1.0f - progressToSwipeBack) * popupLayoutLeftOffset;
        popupWindowLayout.setTranslationX(translationX);
        float bottomAlpha = (reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress;
        for (FrameLayout view : bottomViews) {
            if (view != null) {
                view.setTranslationX(translationX);
                view.setAlpha((1.0f - progressToSwipeBack) * bottomAlpha * currentPopupAlpha);
            }
        }
    }

    // AUTHORGRAM_UNIFIED_MENU_FOOTER
    // Move quick actions into the same ActionBarPopupWindowLayout content as the
    // normal action rows. ActionBarPopupWindowLayout.addView() routes these views
    // into its internal LinearLayout/ScrollView, making the entire card reachable.
    private void authorGramAttachPendingBottomViews() {
        if (popupWindowLayout == null || bottomViews.isEmpty()) {
            return;
        }

        ArrayList<FrameLayout> pendingBottomViews = new ArrayList<>(bottomViews);
        bottomViews.clear();

        if (!authorGramUnifiedFooterSeparatorAdded) {
            View authorGramFooterSeparator = new View(getContext());
            // AUTHORGRAM_MENU_FOOTER_SEPARATOR
            authorGramFooterSeparator.setBackgroundColor(Theme.getColor(Theme.key_divider));
            popupWindowLayout.addView(
                    authorGramFooterSeparator,
                    new LinearLayout.LayoutParams(
                            LayoutHelper.MATCH_PARENT,
                            AndroidUtilities.dp(1)
                    )
            );
            authorGramUnifiedFooterSeparatorAdded = true;
        }

        for (FrameLayout bottomView : pendingBottomViews) {
            if (bottomView == null) {
                continue;
            }
            ViewGroup.LayoutParams oldParams = bottomView.getLayoutParams();
            int footerHeight = oldParams != null && oldParams.height != 0
                    ? oldParams.height
                    : LayoutHelper.WRAP_CONTENT;

            if (bottomView.getParent() instanceof ViewGroup) {
                ((ViewGroup) bottomView.getParent()).removeView(bottomView);
            }

            // The popup owns the single rounded card background. Keeping a second
            // footer background here would recreate the visually detached block.
            bottomView.setBackground(null);
            bottomView.setAlpha(1.0f);
            bottomView.setTranslationX(0.0f);
            bottomView.setTranslationY(0.0f);
            bottomView.setScaleX(1.0f);
            bottomView.setScaleY(1.0f);

            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    LayoutHelper.MATCH_PARENT,
                    footerHeight
            );
            footerParams.leftMargin = 0;
            footerParams.rightMargin = 0;
            footerParams.topMargin = 0;
            footerParams.bottomMargin = 0;
            popupWindowLayout.addView(bottomView, footerParams);
        }
    }

    public void applyViewBottom(FrameLayout bottomView) {
        if (bottomView != null && !bottomViews.contains(bottomView)) {
            // AUTHORGRAM_UNIFIED_MENU_FOOTER
            // Queue until measure: by then all normal menu rows are present,
            // so the footer is appended last inside the popup ScrollView.
            bottomViews.add(bottomView);
            requestLayout();
        }
    }

    public void setFixedMessagePreview(View preview) {
        if (fixedMessagePreview == preview) {
            return;
        }
        if (fixedMessagePreview != null && fixedMessagePreview.getParent() == this) {
            removeView(fixedMessagePreview);
        }
        fixedMessagePreview = preview;
        if (preview != null) {
            if (preview.getParent() instanceof ViewGroup) {
                ((ViewGroup) preview.getParent()).removeView(preview);
            }
            int popupIndex = popupWindowLayout == null
                    ? getChildCount()
                    : indexOfChild(popupWindowLayout);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT
            );
            params.bottomMargin = AndroidUtilities.dp(8);
            addView(preview, Math.max(0, popupIndex), params);
        }
        requestLayout();
    }

    public void setReactionsLayout(ReactionsContainerLayout reactionsLayout) {
        this.reactionsLayout = reactionsLayout;
        if (reactionsLayout != null) {
            reactionsLayout.setChatScrimView(this);
        }
    }

    private void updateBottomOffset() {
        bottomViewYOffset = popupWindowLayout.getVisibleHeight() - popupWindowLayout.getMeasuredHeight();
        updateBottomViewPosition();
    }

    public void setPopupWindowLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout) {
        this.popupWindowLayout = popupWindowLayout;
        if (fixedMessagePreview != null) {
            int popupIndex = indexOfChild(popupWindowLayout);
            int previewIndex = indexOfChild(fixedMessagePreview);
            if (popupIndex >= 0 && previewIndex > popupIndex) {
                LinearLayout.LayoutParams previewParams =
                        (LinearLayout.LayoutParams) fixedMessagePreview.getLayoutParams();
                removeView(fixedMessagePreview);
                addView(fixedMessagePreview, popupIndex, previewParams);
            }
        }
        popupWindowLayout.setOnSizeChangedListener(this::updateBottomOffset);
        if (popupWindowLayout.getSwipeBack() != null) {
            popupWindowLayout.getSwipeBack().addOnSwipeBackProgressListener((layout, toProgress, progress) -> {
                float bottomAlpha = (reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress;
                for (FrameLayout view : bottomViews) {
                    if (view != null) {
                        view.setAlpha((1.0f - progress) * bottomAlpha * currentPopupAlpha);
                    }
                }
                progressToSwipeBack = progress;
                updatePopupTranslation();
            });
        }
    }

    private void updateBottomViewPosition() {
        float bottomAlpha = (reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress;
        for (FrameLayout view : bottomViews) {
            if (view == null) {
                continue;
            }
            if (bottomAlpha < 1.0f && view.getMeasuredHeight() > 0) {
                bottomViewReactionsOffset = -view.getMeasuredHeight() * (1.0f - bottomAlpha);
            } else {
                bottomViewReactionsOffset = 0.0f;
            }
            float alpha = bottomAlpha < 1.0f ? bottomAlpha : 1.0f;
            if (progressToSwipeBack > 0.0f) {
                alpha *= 1.0f - progressToSwipeBack;
            }
            view.setAlpha(alpha * currentPopupAlpha);
            view.setTranslationY(bottomViewYOffset + expandSize + bottomViewReactionsOffset);
        }
    }

    public void setMaxHeight(int maxHeight) {
        int safeDisplayHeight = Math.max(AndroidUtilities.dp(160), AndroidUtilities.displaySize.y - AndroidUtilities.dp(16));
        this.maxHeight = maxHeight > 0 ? Math.min(maxHeight, safeDisplayHeight) : safeDisplayHeight;
        requestLayout();
    }

    public void setExpandSize(float expandSize) {
        popupWindowLayout.setTranslationY(expandSize);
        this.expandSize = expandSize;
        updateBottomViewPosition();
    }

    public void setPopupAlpha(float alpha) {
        currentPopupAlpha = alpha;
        popupWindowLayout.setAlpha(alpha);
        for (FrameLayout view : bottomViews) {
            if (view != null) {
                view.setAlpha(alpha);
            }
        }
    }

    public void setReactionsTransitionProgress(float progress) {
        lastReactionsTransitionProgress = progress;
        popupWindowLayout.setReactionsTransitionProgress(progress);
        float visibleProgress = reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 ? 1.0f : progress;
        for (FrameLayout view : bottomViews) {
            if (view != null) {
                if (progressToSwipeBack == 0.0f) {
                    view.setAlpha(visibleProgress);
                }
                float scale = visibleProgress * 0.5f + 0.5f;
                view.setPivotX(view.getMeasuredWidth());
                view.setPivotY(0.0f);
                view.setScaleX(scale);
                view.setScaleY(scale);
            }
        }
        updateBottomViewPosition();
    }
}
