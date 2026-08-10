#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

TARGET = Path("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")
MARKER_V5 = "AUTHORGRAM_IOS_MESSAGE_MENU_V5_VADYM_REFERENCE"
MARKER_V6 = "AUTHORGRAM_IOS_MESSAGE_MENU_V6_FINAL"


def must_replace(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def must_regex(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected one regex match, found {count}")
    return updated


def validate(text: str) -> None:
    required = (
        MARKER_V6,
        "private TextView senderNameView;",
        "private static final int MAX_MENU_VIEWPORT_DP = 220;",
        "private static final int PREVIEW_EXTRA_WIDTH_DP = 24;",
        "previewHost.setClipChildren(true);",
        "previewHost.setClipToPadding(true);",
        "previewHost.setVisibility(INVISIBLE);",
        "int previewWidth = calculatePreviewWidth(menuWidth);",
        "int desiredMenuViewport = Math.min(",
        "name.setText(senderName);",
        "if (!previewRevealed) {",
    )
    for needle in required:
        if needle not in text:
            raise SystemExit(f"validation failed: missing {needle!r}")
    if "scrimContainer.postDelayed(this::reflowPreviewAndMenu, 32L);" in text:
        raise SystemExit("validation failed: delayed second reflow still present")


def patch(text: str) -> str:
    if MARKER_V6 in text:
        validate(text)
        return text
    if MARKER_V5 not in text:
        raise SystemExit("unexpected IOSMessageMenuPreview baseline")

    text = must_replace(
        text,
        "import android.graphics.Rect;\nimport android.view.View;",
        "import android.graphics.Rect;\nimport android.text.TextUtils;\nimport android.util.TypedValue;\nimport android.view.View;",
        "text imports",
    )
    text = must_replace(text, "import android.widget.ScrollView;\n", "import android.widget.ScrollView;\nimport android.widget.TextView;\n", "TextView import")
    text = must_replace(text, "import org.telegram.messenger.UserConfig;\n", "import org.telegram.messenger.UserConfig;\nimport org.telegram.messenger.UserObject;\n", "UserObject import")

    for old, new in (
        ("PREVIEW_TOP_GAP_DP = 4", "PREVIEW_TOP_GAP_DP = 10"),
        ("PREVIEW_MENU_GAP_DP = 8", "PREVIEW_MENU_GAP_DP = 12"),
        ("MIN_PREVIEW_VIEWPORT_DP = 96", "MIN_PREVIEW_VIEWPORT_DP = 104"),
        ("MAX_PREVIEW_VIEWPORT_DP = 220", "MAX_PREVIEW_VIEWPORT_DP = 260"),
        ("MIN_MENU_VIEWPORT_DP = 200", "MIN_MENU_VIEWPORT_DP = 150"),
    ):
        text = must_replace(text, old, new, old)

    text = must_replace(
        text,
        "    private static final int PREVIEW_MENU_GAP_DP = 12;\n",
        "    private static final int PREVIEW_MENU_GAP_DP = 12;\n    private static final int PREVIEW_EXTRA_WIDTH_DP = 24;\n",
        "preview extra width constant",
    )
    text = must_replace(
        text,
        "    private static final int AVATAR_GAP_DP = 8;\n",
        "    private static final int AVATAR_GAP_DP = 8;\n    private static final int SENDER_NAME_HEIGHT_DP = 20;\n    private static final int SENDER_NAME_BOTTOM_GAP_DP = 4;\n",
        "sender name constants",
    )
    text = must_replace(
        text,
        "    private static final int MIN_MENU_VIEWPORT_DP = 150;\n",
        "    private static final int MIN_MENU_VIEWPORT_DP = 150;\n    private static final int MAX_MENU_VIEWPORT_DP = 220;\n",
        "menu max constant",
    )

    text = must_replace(text, "    private BackupImageView avatarPreviewView;\n", "    private BackupImageView avatarPreviewView;\n    private TextView senderNameView;\n", "sender field")
    text = must_replace(text, "    private int currentGroupHeight;\n    private boolean cleanedUp;\n", "    private int currentGroupHeight;\n    private boolean previewRevealed;\n    private boolean cleanedUp;\n", "preview reveal field")
    text = must_replace(text, MARKER_V5, MARKER_V6, "V6 marker")

    text = must_replace(
        text,
        """        MessageObject messageObject = sourceCell.getMessageObject();
        TLObject senderPeer = resolveSenderPeer(currentAccount, messageObject);

        createBlurOverlay(context, resourcesProvider);
        createPreviewViews(context, currentAccount, messageObject, senderPeer);
""",
        """        MessageObject messageObject = sourceCell.getMessageObject();
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
""",
        "constructor preview args",
    )

    text = must_replace(
        text,
        """    private void createPreviewViews(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            TLObject senderPeer
    ) {
""",
        """    private void createPreviewViews(
            Context context,
            int currentAccount,
            MessageObject messageObject,
            TLObject senderPeer,
            boolean showExternalSenderName,
            Theme.ResourcesProvider resourcesProvider
    ) {
""",
        "preview signature",
    )
    text = must_replace(
        text,
        """        previewHost.setBackgroundColor(Color.TRANSPARENT);
        previewHost.setClipChildren(false);
        previewHost.setClipToPadding(false);
""",
        """        previewHost.setBackgroundColor(Color.TRANSPARENT);
        previewHost.setClipChildren(true);
        previewHost.setClipToPadding(true);
        previewHost.setVisibility(INVISIBLE);
""",
        "preview clipping",
    )

    text = must_replace(
        text,
        """        previewContent.addView(
                messagePreviewView,
                new FrameLayout.LayoutParams(snapshotWidth, snapshotHeight)
        );

        if (senderPeer != null) {
""",
        """        previewContent.addView(
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
""",
        "sender name creation",
    )

    text = must_regex(
        text,
        r"            removeLegacyTopGap\(\);\n            attachPreviewBetweenReactionsAndMenu\(\);\n            if \(scrimContainer != null\) \{\n                scrimContainer\.requestLayout\(\);\n                scrimContainer\.post\(this::reflowPreviewAndMenu\);\n                scrimContainer\.postDelayed\(this::reflowPreviewAndMenu, 32L\);\n            \}\n",
        "            removeLegacyTopGap();\n            attachPreviewBetweenReactionsAndMenu();\n",
        "single initial reflow",
    )

    text = must_regex(
        text,
        r"        int initialHeight = Math\.min\(\n                Math\.max\(snapshotHeight, AndroidUtilities\.dp\(AVATAR_SIZE_DP\)\),\n                AndroidUtilities\.dp\(MAX_PREVIEW_VIEWPORT_DP\)\n        \);\n        LinearLayout\.LayoutParams params = new LinearLayout\.LayoutParams\(\n                ViewGroup\.LayoutParams\.MATCH_PARENT,\n                Math\.max\(1, initialHeight\)\n        \);\n        params\.topMargin = AndroidUtilities\.dp\(PREVIEW_TOP_GAP_DP\);\n        params\.bottomMargin = AndroidUtilities\.dp\(PREVIEW_MENU_GAP_DP\);\n        scrimContainer\.addView\(previewHost, menuIndex, params\);",
        """        int menuWidth = menuDirectChild.getMeasuredWidth();
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
        reflowPreviewAndMenu();""",
        "pre-sized attach",
    )

    text = must_replace(
        text,
        "        int groupHeight = configurePreviewContentForWidth(menuWidth);\n",
        "        int previewWidth = calculatePreviewWidth(menuWidth);\n        int groupHeight = configurePreviewContentForWidth(previewWidth);\n",
        "preview width reflow",
    )

    text = must_regex(
        text,
        r"        int previewViewportHeight;\n        int menuViewportLimit;\n        if \(groupHeight \+ naturalMenuHeight <= stackCapacity\) \{.*?\n        \}\n\n        if \(hostParams\.height != previewViewportHeight\)",
        """        int desiredMenuViewport = Math.min(
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

        if (hostParams.height != previewViewportHeight)""",
        "compact menu policy",
    )

    text = must_replace(text, "        scrollParams.width = menuWidth;\n", "        scrollParams.width = previewWidth;\n", "preview viewport width")

    text = must_regex(
        text,
        r"    private int configurePreviewContentForWidth\(int menuWidth\) \{.*?\n    \}\n\n    private int getFixedScrimChildrenHeight\(\)",
        """    private int configurePreviewContentForWidth(int previewWidth) {
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

    private int getFixedScrimChildrenHeight()""",
        "preview geometry method",
    )

    text = must_replace(
        text,
        "        int left = menuLocation[0] - hostLocation[0];\n",
        "        int left = menuLocation[0] - hostLocation[0]\n                - Math.max(0, params.width - menuDirectChild.getWidth()) / 2;\n",
        "center wider preview",
    )

    text = must_replace(
        text,
        """        if (currentGroupHeight <= previewScrollView.getHeight()) {
            previewScrollView.scrollTo(0, 0);
        }
    }

    private static TLObject resolveSenderPeer(int currentAccount, MessageObject messageObject) {
""",
        """        if (currentGroupHeight <= previewScrollView.getHeight()) {
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
""",
        "sender resolver and reveal",
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

    updated = patch(source)
    if updated != source:
        TARGET.write_text(updated, encoding="utf-8")
        print("AuthorGram iOS message menu V6 applied")
    else:
        print("AuthorGram iOS message menu V6 already applied")


if __name__ == "__main__":
    main()
