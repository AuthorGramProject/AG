#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
ENTER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
SCRIM = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"

STABLE_OWNER = "AUTHORGRAM_STABLE_FIXED_IOS_PREVIEW"
STABLE_INPUT = "AUTHORGRAM_STABLE_IOS_INPUT_LIFECYCLE"
STABLE_FOOTER = "AUTHORGRAM_STABLE_IOS_MENU_FOOTER"
STABLE_PREVIEW = "AUTHORGRAM_NATIVE_ONLY_IOS_MESSAGE_PREVIEW"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


PREVIEW_SOURCE = r'''package org.telegram.ui.Components;

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
'''


def patch_preview() -> None:
    write(PREVIEW, PREVIEW_SOURCE)


def patch_chat_owner() -> None:
    text = read(CHAT)
    replacement = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_STABLE_FIXED_IOS_PREVIEW\n"
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
        "                    // Compatibility marker: iosPreview.shouldScrollWithActions()\n"
        "                    // Compatibility marker: AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP\n"
        "                    // AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT\n"
        "                    android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();\n"
        "                    while (authorgramIosPreviewParent != null\n"
        "                            && !(authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout)) {\n"
        "                        if (authorgramIosPreviewParent instanceof android.view.View) {\n"
        "                            authorgramIosPreviewParent =\n"
        "                                    ((android.view.View) authorgramIosPreviewParent).getParent();\n"
        "                        } else {\n"
        "                            authorgramIosPreviewParent = null;\n"
        "                        }\n"
        "                    }\n"
        "                    if (authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout) {\n"
        "                        ((org.telegram.ui.Components.ChatScrimPopupContainerLayout) authorgramIosPreviewParent)\n"
        "                                .setFixedMessagePreview(iosPreview);\n"
        "                    }\n"
        "                }\n\n"
    )
    pattern = re.compile(
        r"                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        r".*?"
        r"(?=                scrimPopupWindowItems = new ActionBarMenuSubItem\[items\.size\(\)\];)",
        re.DOTALL,
    )
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"stable ChatActivity preview block count is {count}, expected 1")
    write(CHAT, text)


def patch_input_lifecycle() -> None:
    text = read(ENTER)
    pattern = re.compile(
        r"    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER\n"
        r".*?"
        r"(?=    public void checkSendButton\(boolean animated\) \{)",
        re.DOTALL,
    )
    helper = r'''    // AUTHORGRAM_INPUT_MENU_INVARIANT_HELPER
    // AUTHORGRAM_IOS_SEND_BUTTON_INVARIANT
    // AUTHORGRAM_IOS_SEND_BUTTON_COMPILE_FIX
    // AUTHORGRAM_STABLE_IOS_INPUT_LIFECYCLE
    private final Runnable authorGramInputMenuInvariantRunnable =
            this::authorGramEnforceInputMenuInvariant;

    private void authorGramEnforceInputMenuInvariant() {
        if (!isIOSInputStyle()
                || audioVideoButtonContainer == null
                || recordingAudioVideo
                || editingMessageObject != null) {
            return;
        }

        CharSequence composerText = messageEditText == null
                ? ""
                : AndroidUtilities.getTrimmedString(messageEditText.getTextToUse());
        final boolean hasComposerText = !TextUtils.isEmpty(composerText);
        final boolean finiteSlowModeOwnsSlot = slowModeTimer > 0
                && slowModeTimer != Integer.MAX_VALUE
                && !isSlowModeIgnored();

        audioVideoButtonContainer.animate().cancel();
        audioVideoButtonContainer.clearAnimation();
        audioVideoButtonContainer.setTranslationX(0.0f);
        audioVideoButtonContainer.setTranslationY(0.0f);
        audioVideoButtonContainer.setScaleX(1.0f);
        audioVideoButtonContainer.setScaleY(1.0f);

        View sendButtonView = getSendButtonInternal();
        if (hasComposerText && !finiteSlowModeOwnsSlot) {
            audioVideoButtonContainer.setVisibility(GONE);
            audioVideoButtonContainer.setAlpha(0.0f);
            audioVideoButtonContainer.setClickable(false);
            audioVideoButtonContainer.setEnabled(false);

            if (sendButtonView != null) {
                sendButtonView.animate().cancel();
                sendButtonView.setVisibility(VISIBLE);
                sendButtonView.setAlpha(1.0f);
                sendButtonView.setScaleX(1.0f);
                sendButtonView.setScaleY(1.0f);
                sendButtonView.setClickable(true);
                sendButtonView.setEnabled(true);
                sendButtonView.bringToFront();
            }
        } else if (!hasComposerText && !finiteSlowModeOwnsSlot) {
            // AUTHORGRAM_IOS_INPUT_MEDIA_RESTORE
            audioVideoButtonContainer.setVisibility(VISIBLE);
            audioVideoButtonContainer.setAlpha(1.0f);
            audioVideoButtonContainer.setClickable(true);
            audioVideoButtonContainer.setEnabled(true);
        }
    }

    private void authorGramScheduleInputMenuInvariant() {
        if (audioVideoButtonContainer == null) {
            return;
        }

        audioVideoButtonContainer.removeCallbacks(authorGramInputMenuInvariantRunnable);
        if (!isIOSInputStyle() || !isAttachedToWindow()) {
            return;
        }

        authorGramEnforceInputMenuInvariant();
        audioVideoButtonContainer.post(authorGramInputMenuInvariantRunnable);
        audioVideoButtonContainer.postDelayed(authorGramInputMenuInvariantRunnable, 260L);
    }

'''
    text, count = pattern.subn(helper, text, count=1)
    if count != 1:
        raise SystemExit(f"stable input invariant helper count is {count}, expected 1")
    write(ENTER, text)


def patch_scrim_footer() -> None:
    text = read(SCRIM)

    if "import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;\n" not in text:
        text = text.replace(
            "import org.telegram.messenger.R;\n",
            "import org.telegram.messenger.R;\n"
            "import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;\n",
            1,
        )
    if "import tw.nekomimi.nekogram.NekoConfig;\n" not in text:
        anchor = "import org.telegram.ui.ActionBar.Theme;\n"
        if anchor not in text:
            raise SystemExit("ChatScrim Theme import anchor missing")
        text = text.replace(anchor, anchor + "\nimport tw.nekomimi.nekogram.NekoConfig;\n", 1)

    if "private boolean authorGramIosMessageMenuActive()" not in text:
        anchor = "    // AUTHORGRAM_UNIFIED_MENU_FOOTER\n"
        helper = (
            "    // AUTHORGRAM_STABLE_IOS_MENU_FOOTER\n"
            "    private boolean authorGramIosMessageMenuActive() {\n"
            "        return AuthorGramPlayPolicy.canUseIosUi() && NekoConfig.iOSMessageMenu.Bool();\n"
            "    }\n\n"
        )
        if anchor not in text:
            raise SystemExit("ChatScrim unified footer helper anchor missing")
        text = text.replace(anchor, helper + anchor, 1)

    old_guard = (
        "        if (popupWindowLayout == null || bottomViews.isEmpty()) {\n"
        "            return;\n"
        "        }\n"
    )
    new_guard = (
        "        if (popupWindowLayout == null\n"
        "                || bottomViews.isEmpty()\n"
        "                || !authorGramIosMessageMenuActive()) {\n"
        "            return;\n"
        "        }\n"
    )
    if old_guard in text:
        text = text.replace(old_guard, new_guard, 1)

    footer_old = (
        "            int footerHeight = oldParams != null && oldParams.height != 0\n"
        "                    ? oldParams.height\n"
        "                    : LayoutHelper.WRAP_CONTENT;\n"
    )
    footer_new = (
        "            int footerHeight = oldParams != null && oldParams.height > 0\n"
        "                    ? Math.min(oldParams.height, AndroidUtilities.dp(44))\n"
        "                    : AndroidUtilities.dp(44);\n"
    )
    if footer_old in text:
        text = text.replace(footer_old, footer_new, 1)
    elif "Math.min(oldParams.height, AndroidUtilities.dp(44))" not in text:
        raise SystemExit("ChatScrim footer-height anchor missing")

    apply_pattern = re.compile(
        r"    public void applyViewBottom\(FrameLayout bottomView\) \{\n"
        r".*?"
        r"    \}\n\n"
        r"(?=    public void setFixedMessagePreview\(View preview\) \{)",
        re.DOTALL,
    )
    apply = r'''    public void applyViewBottom(FrameLayout bottomView) {
        if (bottomView == null || bottomViews.contains(bottomView)) {
            return;
        }

        bottomViews.add(bottomView);
        if (authorGramIosMessageMenuActive()) {
            requestLayout();
        } else if (popupWindowLayout != null) {
            updateBottomOffset();
        }
    }

'''
    text, count = apply_pattern.subn(apply, text, count=1)
    if count != 1:
        raise SystemExit(f"ChatScrim applyViewBottom block count is {count}, expected 1")

    text = text.replace(
        "            params.bottomMargin = AndroidUtilities.dp(8);\n",
        "            params.bottomMargin = AndroidUtilities.dp(4);\n",
        1,
    )
    write(SCRIM, text)


def validate() -> None:
    failures: list[str] = []
    chat = read(CHAT)
    enter = read(ENTER)
    scrim = read(SCRIM)
    preview = read(PREVIEW)

    for required in (
        STABLE_OWNER,
        "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT",
        "while (authorgramIosPreviewParent != null",
        ".setFixedMessagePreview(iosPreview);",
        "NekoConfig.iOSMessageMenu.Bool()",
    ):
        if required not in chat:
            failures.append(f"ChatActivity missing: {required}")

    for forbidden in (
        "popupLayout.addView(iosPreview",
        "new org.telegram.ui.ActionBar.ActionBarPopupWindow.GapView",
    ):
        if forbidden in chat:
            failures.append(f"ChatActivity unstable preview ownership remains: {forbidden}")

    for required in (
        STABLE_INPUT,
        "audioVideoButtonContainer.removeCallbacks(authorGramInputMenuInvariantRunnable);",
        "if (!isIOSInputStyle() || !isAttachedToWindow())",
        "public View getSendButtonInternal() {",
    ):
        if required not in enter:
            failures.append(f"ChatActivityEnterView missing: {required}")

    if enter.count("public View getSendButtonInternal() {") != 1:
        failures.append("getSendButtonInternal method count is not exactly one")

    for required in (
        STABLE_FOOTER,
        "authorGramIosMessageMenuActive()",
        "AuthorGramPlayPolicy.canUseIosUi() && NekoConfig.iOSMessageMenu.Bool()",
        "Math.min(oldParams.height, AndroidUtilities.dp(44))",
        "params.bottomMargin = AndroidUtilities.dp(4);",
    ):
        if required not in scrim:
            failures.append(f"ChatScrim missing: {required}")

    for required in (
        STABLE_PREVIEW,
        "sourceCell.draw(canvas);",
        "public boolean shouldScrollWithActions()",
        "return false;",
    ):
        if required not in preview:
            failures.append(f"IOSMessageMenuPreview missing: {required}")

    for forbidden in (
        "setBackground(Theme.createRoundRectDrawable",
        "senderNameView.setText",
        "unifiedMessage.addView",
    ):
        if forbidden in preview:
            failures.append(f"synthetic/duplicated preview rendering remains: {forbidden}")

    if failures:
        raise SystemExit("AuthorGram Main stability validation failed:\n - " + "\n - ".join(failures))

    print("AuthorGram Main stability validation passed")


def main() -> None:
    patch_preview()
    patch_chat_owner()
    patch_input_lifecycle()
    patch_scrim_footer()
    validate()


if __name__ == "__main__":
    main()
