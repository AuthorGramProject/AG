#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
V6_MARKER = 'AUTHORGRAM_IOS_MESSAGE_MENU_V6_FINAL'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def validate(text: str) -> None:
    required = [
        V6_MARKER,
        "private TextView senderNameView;",
        "private static final int MAX_MENU_VIEWPORT_DP = 220;",
        "private static final int PREVIEW_EXTRA_WIDTH_DP = 24;",
        "previewHost.setClipChildren(true);",
        "previewHost.setClipToPadding(true);",
        "previewHost.setVisibility(INVISIBLE);",
        "int previewWidth = calculatePreviewWidth(menuWidth);",
        "int desiredMenuViewport = Math.min(",
        "senderNameView.setText(senderName);",
        "if (!previewRevealed) {",
    ]
    for needle in required:
        if needle not in text:
            raise SystemExit(f"validation failed: missing {needle!r}")
    if "scrimContainer.postDelayed(this::reflowPreviewAndMenu, 32L);" in text:
        raise SystemExit("validation failed: delayed second reflow is still present")


def patch(text: str) -> str:
    if V6_MARKER in text:
        validate(text)
        return text
    if 'AUTHORGRAM_IOS_MESSAGE_MENU_V5_VADYM_REFERENCE' not in text:
        raise SystemExit("unexpected IOSMessageMenuPreview baseline; V5 marker not found")

    text = replace_once(
        text,
        "import android.graphics.Rect;\nimport android.view.View;",
        "import android.graphics.Rect;\nimport android.text.TextUtils;\nimport android.util.TypedValue;\nimport android.view.View;",
        "text imports",
    )
    text = replace_once(
        text,
        "import android.widget.ScrollView;\n",
        "import android.widget.ScrollView;\nimport android.widget.TextView;\n",
        "TextView import",
    )
    text = replace_once(
        text,
        "import org.telegram.messenger.UserConfig;\n",
        "import org.telegram.messenger.UserConfig;\nimport org.telegram.messenger.UserObject;\n",
        "UserObject import",
    )

    text = replace_once(
        text,
        """    private static final int SCREEN_EDGE_DP = 12;\n    private static final int PREVIEW_TOP_GAP_DP = 4;\n    private static final int PREVIEW_MENU_GAP_DP = 8;\n    private static final int AVATAR_SIZE_DP = 36;\n    private static final int AVATAR_GAP_DP = 8;\n    private static final int MIN_PREVIEW_VIEWPORT_DP = 96;\n    private static final int MAX_PREVIEW_VIEWPORT_DP = 220;\n    private static final int MIN_MENU_VIEWPORT_DP = 200;\n    private static final int BACKGROUND_DOWNSCALE = 12;\n""",
        """    private static final int SCREEN_EDGE_DP = 12;\n    private static final int PREVIEW_TOP_GAP_DP = 10;\n    private static final int PREVIEW_MENU_GAP_DP = 12;\n    private static final int PREVIEW_EXTRA_WIDTH_DP = 24;\n    private static final int AVATAR_SIZE_DP = 36;\n    private static final int AVATAR_GAP_DP = 8;\n    private static final int SENDER_NAME_HEIGHT_DP = 20;\n    private static final int SENDER_NAME_BOTTOM_GAP_DP = 4;\n    private static final int MIN_PREVIEW_VIEWPORT_DP = 104;\n    private static final int MAX_PREVIEW_VIEWPORT_DP = 260;\n    private static final int MIN_MENU_VIEWPORT_DP = 150;\n    private static final int MAX_MENU_VIEWPORT_DP = 220;\n    private static final int BACKGROUND_DOWNSCALE = 12;\n""",
        "layout constants",
    )

    text = replace_once(
        text,
        """    private ImageView messagePreviewView;\n    private BackupImageView avatarPreviewView;\n\n    private Bitmap messageSnapshot;\n""",
        """    private ImageView messagePreviewView;\n    private BackupImageView avatarPreviewView;\n    private TextView senderNameView;\n\n    private Bitmap messageSnapshot;\n""",
        "sender name field",
    )
    text = replace_once(
        text,
        """    private View menuDirectChild;\n    private int currentGroupHeight;\n    private boolean cleanedUp;\n""",
        """    private View menuDirectChild;\n    private int currentGroupHeight;\n    private boolean previewRevealed;\n    private boolean cleanedUp;\n""",
        "preview reveal field",
    )
    text = replace_once(
        text,
        'setTag("AUTHORGRAM_IOS_MESSAGE_MENU_V5_VADYM_REFERENCE");',
        f'setTag("{V6_MARKER}");',
        "V6 marker",
    )

    text = replace_once(
        text,
        """        MessageObject messageObject = sourceCell.getMessageObject();\n        TLObject senderPeer = resolveSenderPeer(currentAccount, messageObject);\n\n        createBlurOverlay(context, resourcesProvider);\n        createPreviewViews(context, currentAccount, messageObject, senderPeer);\n""",
        """        MessageObject messageObject = sourceCell.getMessageObject();\n        TLObject senderPeer = resolveSenderPeer(currentAccount, messageObject);\n        boolean showExternalSenderName = sourceCell.getAvatarImage() == null;\n\n        createBlurOverlay(context, resourcesProvider);\n        createPreviewViews(\n                context,\n                currentAccount,\n                messageObject,\n                senderPeer,\n                showExternalSenderName,\n                resourcesProvider\n        );\n""",
        "preview construction",
    )

    text = replace_once(
        text,
        """    private void createPreviewViews(\n            Context context,\n            int currentAccount,\n            MessageObject messageObject,\n            TLObject senderPeer\n    ) {\n        previewHost = new FrameLayout(context);\n        previewHost.setTag("AUTHORGRAM_IOS_MESSAGE_PREVIEW_HOST");\n        previewHost.setBackgroundColor(Color.TRANSPARENT);\n        previewHost.setClipChildren(false);\n        previewHost.setClipToPadding(false);\n        previewHost.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);\n""",
        """    private void createPreviewViews(\n            Context context,\n            int currentAccount,\n            MessageObject messageObject,\n            TLObject senderPeer,\n            boolean showExternalSenderName,\n            Theme.ResourcesProvider resourcesProvider\n    ) {\n        previewHost = new FrameLayout(context);\n        previewHost.setTag("AUTHORGRAM_IOS_MESSAGE_PREVIEW_HOST");\n        previewHost.setBackgroundColor(Color.TRANSPARENT);\n        previewHost.setClipChildren(true);\n        previewHost.setClipToPadding(true);\n        previewHost.setVisibility(INVISIBLE);\n        previewHost.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);\n""",
        "preview host clipping",
    )

    text = replace_once(
        text,
        """        previewContent.addView(\n                messagePreviewView,\n                new FrameLayout.LayoutParams(snapshotWidth, snapshotHeight)\n        );\n\n        if (senderPeer != null) {\n""",
        """        previewContent.addView(\n                messagePreviewView,\n                new FrameLayout.LayoutParams(snapshotWidth, snapshotHeight)\n        );\n\n        if (showExternalSenderName) {\n            String senderName = resolveSenderName(senderPeer);\n            if (!TextUtils.isEmpty(senderName)) {\n                TextView name = new TextView(context);\n                name.setSingleLine(true);\n                name.setEllipsize(TextUtils.TruncateAt.END);\n                name.setText(senderName);\n                name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);\n                name.setTypeface(AndroidUtilities.bold());\n                name.setTextColor(Theme.getColor(\n                        Theme.key_actionBarDefaultSubmenuItem,\n                        resourcesProvider\n                ));\n                name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);\n                previewContent.addView(\n                        name,\n                        new FrameLayout.LayoutParams(\n                                Math.max(1, snapshotWidth),\n                                AndroidUtilities.dp(SENDER_NAME_HEIGHT_DP)\n                        )\n                );\n                senderNameView = name;\n            }\n        }\n\n        if (senderPeer != null) {\n""",
        "sender name view",
    )

    text = replace_once(
        text,
        """            removeLegacyTopGap();\n            attachPreviewBetweenReactionsAndMenu();\n            if (scrimContainer != null) {\n                scrimContainer.requestLayout();\n                scrimContainer.post(this::reflowPreviewAndMenu);\n                scrimContainer.postDelayed(this::reflowPreviewAndMenu, 32L);\n            }\n""",
        """            removeLegacyTopGap();\n            attachPreviewBetweenReactionsAndMenu();\n""",
        "single stable initial layout",
    )

    text = replace_once(
        text,
        """        int initialHeight = Math.min(\n                Math.max(snapshotHeight, AndroidUtilities.dp(AVATAR_SIZE_DP)),\n                AndroidUtilities.dp(MAX_PREVIEW_VIEWPORT_DP)\n        );\n        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT,\n                Math.max(1, initialHeight)\n        );\n        params.topMargin = AndroidUtilities.dp(PREVIEW_TOP_GAP_DP);\n        params.bottomMargin = AndroidUtilities.dp(PREVIEW_MENU_GAP_DP);\n        scrimContainer.addView(previewHost, menuIndex, params);\n""",
        """        int menuWidth = menuDirectChild.getMeasuredWidth();\n        if (menuWidth <= 0) {\n            menuWidth = menuDirectChild.getWidth();\n        }\n        if (menuWidth <= 0) {\n            scrimContainer.post(this::attachPreviewBetweenReactionsAndMenu);\n            return;\n        }\n\n        int previewWidth = calculatePreviewWidth(menuWidth);\n        currentGroupHeight = configurePreviewContentForWidth(previewWidth);\n        int initialHeight = Math.min(\n                Math.max(1, currentGroupHeight),\n                AndroidUtilities.dp(MAX_PREVIEW_VIEWPORT_DP)\n        );\n\n        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(\n                ViewGroup.LayoutParams.MATCH_PARENT,\n                initialHeight\n        );\n        params.topMargin = AndroidUtilities.dp(PREVIEW_TOP_GAP_DP);\n        params.bottomMargin = AndroidUtilities.dp(PREVIEW_MENU_GAP_DP);\n\n        FrameLayout.LayoutParams scrollParams =\n                (FrameLayout.LayoutParams) previewScrollView.getLayoutParams();\n        scrollParams.width = previewWidth;\n        scrollParams.height = initialHeight;\n        previewScrollView.setLayoutParams(scrollParams);\n\n        scrimContainer.addView(previewHost, menuIndex, params);\n        reflowPreviewAndMenu();\n""",
        "pre-sized preview attach",
    )

    text = replace_once(
        text,
        """        int groupHeight = configurePreviewContentForWidth(menuWidth);\n        if (groupHeight <= 0) {\n            return;\n        }\n        currentGroupHeight = groupHeight;\n""",
        """        int previewWidth = calculatePreviewWidth(menuWidth);\n        int groupHeight = configurePreviewContentForWidth(previewWidth);\n        if (groupHeight <= 0) {\n            return;\n        }\n        currentGroupHeight = groupHeight;\n""",
        "preview width reflow",
    )

    text = replace_once(
        text,
        """        int previewViewportHeight;\n        int menuViewportLimit;\n        if (groupHeight + naturalMenuHeight <= stackCapacity) {\n            previewViewportHeight = groupHeight;\n            menuViewportLimit = naturalMenuHeight;\n        } else {\n            int minMenu = Math.min(\n                    naturalMenuHeight,\n                    AndroidUtilities.dp(MIN_MENU_VIEWPORT_DP)\n            );\n            int maxHeightForPreview = Math.max(\n                    AndroidUtilities.dp(MIN_PREVIEW_VIEWPORT_DP),\n                    stackCapacity - minMenu\n            );\n            maxHeightForPreview = Math.min(\n                    maxHeightForPreview,\n                    AndroidUtilities.dp(MAX_PREVIEW_VIEWPORT_DP)\n            );\n            previewViewportHeight = Math.min(groupHeight, maxHeightForPreview);\n            menuViewportLimit = Math.max(\n                    AndroidUtilities.dp(96),\n                    stackCapacity - previewViewportHeight\n            );\n        }\n""",
        """        int desiredMenuViewport = Math.min(\n                naturalMenuHeight,\n                AndroidUtilities.dp(MAX_MENU_VIEWPORT_DP)\n        );\n        int minimumMenuViewport = Math.min(\n                naturalMenuHeight,\n                AndroidUtilities.dp(MIN_MENU_VIEWPORT_DP)\n        );\n        int minimumPreviewViewport = Math.min(\n                groupHeight,\n                AndroidUtilities.dp(MIN_PREVIEW_VIEWPORT_DP)\n        );\n        int previewCeiling = Math.min(\n                groupHeight,\n                AndroidUtilities.dp(MAX_PREVIEW_VIEWPORT_DP)\n        );\n\n        int previewViewportHeight = Math.min(\n                previewCeiling,\n                Math.max(\n                        minimumPreviewViewport,\n                        stackCapacity - minimumMenuViewport\n                )\n        );\n        previewViewportHeight = Math.min(\n                previewViewportHeight,\n                Math.max(1, stackCapacity - AndroidUtilities.dp(96))\n        );\n\n        int menuViewportLimit = Math.min(\n                desiredMenuViewport,\n                Math.max(\n                        AndroidUtilities.dp(96),\n                        stackCapacity - previewViewportHeight\n                )\n        );\n\n        if (menuViewportLimit < minimumMenuViewport\n                && previewViewportHeight > minimumPreviewViewport) {\n            int giveBack = Math.min(\n                    previewViewportHeight - minimumPreviewViewport,\n                    minimumMenuViewport - menuViewportLimit\n            );\n            previewViewportHeight -= giveBack;\n            menuViewportLimit += giveBack;\n        }\n""",
        "compact menu viewport policy",
    )

    text = replace_once(
        text,
        """        scrollParams.width = menuWidth;\n        scrollParams.height = previewViewportHeight;\n""",
        """        scrollParams.width = previewWidth;\n        scrollParams.height = previewViewportHeight;\n""",
        "preview viewport width",
    )

    old_configure = """    private int configurePreviewContentForWidth(int menuWidth) {\n        int avatarSize = avatarPreviewView == null\n                ? 0\n                : AndroidUtilities.dp(AVATAR_SIZE_DP);\n        int avatarGap = avatarPreviewView == null\n                ? 0\n                : AndroidUtilities.dp(AVATAR_GAP_DP);\n\n        int availableMessageWidth = Math.max(\n                AndroidUtilities.dp(96),\n                menuWidth - avatarSize - avatarGap\n        );\n        float widthScale = Math.min(1.0f, availableMessageWidth / (float) snapshotWidth);\n\n        int messageWidth = Math.max(1, Math.round(snapshotWidth * widthScale));\n        int messageHeight = Math.max(1, Math.round(snapshotHeight * widthScale));\n        int groupWidth = messageWidth + avatarSize + avatarGap;\n        int groupHeight = Math.max(messageHeight, avatarSize);\n\n        FrameLayout.LayoutParams messageParams =\n                (FrameLayout.LayoutParams) messagePreviewView.getLayoutParams();\n        messageParams.width = messageWidth;\n        messageParams.height = messageHeight;\n        messageParams.leftMargin = avatarSize + avatarGap;\n        messageParams.topMargin = groupHeight - messageHeight;\n        messagePreviewView.setLayoutParams(messageParams);\n\n        if (avatarPreviewView != null) {\n            FrameLayout.LayoutParams avatarParams =\n                    (FrameLayout.LayoutParams) avatarPreviewView.getLayoutParams();\n            avatarParams.width = avatarSize;\n            avatarParams.height = avatarSize;\n            avatarParams.leftMargin = 0;\n            // Matches the reference screenshot: avatar is bottom-aligned to the bubble.\n            avatarParams.topMargin = groupHeight - avatarSize;\n            avatarPreviewView.setRoundRadius(avatarSize / 2);\n            avatarPreviewView.setLayoutParams(avatarParams);\n        }\n\n        ViewGroup.LayoutParams contentParams = previewContent.getLayoutParams();\n        contentParams.width = groupWidth;\n        contentParams.height = groupHeight;\n        previewContent.setLayoutParams(contentParams);\n\n        return groupHeight;\n    }\n"""
    new_configure = """    private int configurePreviewContentForWidth(int previewWidth) {\n        int avatarSize = avatarPreviewView == null\n                ? 0\n                : AndroidUtilities.dp(AVATAR_SIZE_DP);\n        int avatarGap = avatarPreviewView == null\n                ? 0\n                : AndroidUtilities.dp(AVATAR_GAP_DP);\n        int senderNameHeight = senderNameView == null\n                ? 0\n                : AndroidUtilities.dp(SENDER_NAME_HEIGHT_DP);\n        int senderNameGap = senderNameView == null\n                ? 0\n                : AndroidUtilities.dp(SENDER_NAME_BOTTOM_GAP_DP);\n\n        int availableMessageWidth = Math.max(\n                AndroidUtilities.dp(96),\n                previewWidth - avatarSize - avatarGap\n        );\n        float widthScale = Math.min(1.0f, availableMessageWidth / (float) snapshotWidth);\n\n        int messageWidth = Math.max(1, Math.round(snapshotWidth * widthScale));\n        int messageHeight = Math.max(1, Math.round(snapshotHeight * widthScale));\n        int groupWidth = messageWidth + avatarSize + avatarGap;\n        int groupLeft = Math.max(0, (previewWidth - groupWidth) / 2);\n        int bodyTop = senderNameHeight + senderNameGap;\n        int bodyHeight = Math.max(messageHeight, avatarSize);\n        int groupHeight = bodyTop + bodyHeight;\n\n        FrameLayout.LayoutParams messageParams =\n                (FrameLayout.LayoutParams) messagePreviewView.getLayoutParams();\n        messageParams.width = messageWidth;\n        messageParams.height = messageHeight;\n        messageParams.leftMargin = groupLeft + avatarSize + avatarGap;\n        messageParams.topMargin = bodyTop + bodyHeight - messageHeight;\n        messagePreviewView.setLayoutParams(messageParams);\n\n        if (senderNameView != null) {\n            FrameLayout.LayoutParams nameParams =\n                    (FrameLayout.LayoutParams) senderNameView.getLayoutParams();\n            nameParams.width = messageWidth;\n            nameParams.height = senderNameHeight;\n            nameParams.leftMargin = groupLeft + avatarSize + avatarGap;\n            nameParams.topMargin = 0;\n            senderNameView.setLayoutParams(nameParams);\n        }\n\n        if (avatarPreviewView != null) {\n            FrameLayout.LayoutParams avatarParams =\n                    (FrameLayout.LayoutParams) avatarPreviewView.getLayoutParams();\n            avatarParams.width = avatarSize;\n            avatarParams.height = avatarSize;\n            avatarParams.leftMargin = groupLeft;\n            avatarParams.topMargin = bodyTop + bodyHeight - avatarSize;\n            avatarPreviewView.setRoundRadius(avatarSize / 2);\n            avatarPreviewView.setLayoutParams(avatarParams);\n        }\n\n        ViewGroup.LayoutParams contentParams = previewContent.getLayoutParams();\n        contentParams.width = previewWidth;\n        contentParams.height = groupHeight;\n        previewContent.setLayoutParams(contentParams);\n\n        return groupHeight;\n    }\n\n    private int calculatePreviewWidth(int menuWidth) {\n        int maxAvailable = Math.max(\n                menuWidth,\n                rootView.getWidth() - AndroidUtilities.dp(SCREEN_EDGE_DP * 2)\n        );\n        return Math.min(\n                maxAvailable,\n                menuWidth + AndroidUtilities.dp(PREVIEW_EXTRA_WIDTH_DP)\n        );\n    }\n"""
    text = replace_once(text, old_configure, new_configure, "preview content geometry")

    text = replace_once(
        text,
        """        int left = menuLocation[0] - hostLocation[0];\n        left = clamp(\n                left,\n                0,\n                Math.max(0, previewHost.getWidth() - params.width)\n        );\n""",
        """        int left = menuLocation[0] - hostLocation[0]\n                - Math.max(0, params.width - menuDirectChild.getWidth()) / 2;\n        left = clamp(\n                left,\n                0,\n                Math.max(0, previewHost.getWidth() - params.width)\n        );\n""",
        "center wider preview",
    )

    text = replace_once(
        text,
        """        if (currentGroupHeight <= previewScrollView.getHeight()) {\n            previewScrollView.scrollTo(0, 0);\n        }\n    }\n\n    private static TLObject resolveSenderPeer(int currentAccount, MessageObject messageObject) {\n""",
        """        if (currentGroupHeight <= previewScrollView.getHeight()) {\n            previewScrollView.scrollTo(0, 0);\n        }\n\n        if (!previewRevealed) {\n            previewScrollView.scrollTo(0, 0);\n            previewHost.setVisibility(VISIBLE);\n            previewRevealed = true;\n        }\n    }\n\n    private static String resolveSenderName(TLObject senderPeer) {\n        if (senderPeer instanceof TLRPC.User) {\n            return UserObject.getUserName((TLRPC.User) senderPeer);\n        }\n        if (senderPeer instanceof TLRPC.Chat) {\n            return ((TLRPC.Chat) senderPeer).title;\n        }\n        return null;\n    }\n\n    private static TLObject resolveSenderPeer(int currentAccount, MessageObject messageObject) {\n""",
        "sender name resolver and reveal",
    )

    validate(text)
    return text


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    source = TARGET.read_text(encoding="utf-8")
    if args.check:
        validate(source)
        print("AuthorGram iOS message menu V6: OK")
        return

    patched = patch(source)
    if patched != source:
        TARGET.write_text(patched, encoding="utf-8")
        print("AuthorGram iOS message menu V6 applied")
    else:
        print("AuthorGram iOS message menu V6 already applied")


if __name__ == "__main__":
    main()
