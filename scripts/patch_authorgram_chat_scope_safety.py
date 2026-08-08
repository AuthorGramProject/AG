#!/usr/bin/env python3
"""Repair and validate AuthorGram Main iOS message-preview ownership.

The selected-message preview is a sibling of the action card owned by
ChatScrimPopupContainerLayout. It must never be inserted into popupLayout,
including as a fallback for unexpected hierarchy changes.

Pre-apply mode is strictly read-only. Apply mode canonicalizes the whole iOS
preview block after all legacy generators have run and materializes a bounded,
native ChatMessageCell preview. Validate mode rejects every known regression
that can put the quoted message inside the action list again.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
PREVIEW = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java"

SAFE_MARKER = "AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT"
CANONICAL_MARKER = "AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW"
BOUNDED_MARKER = "AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW"
UNSAFE_FIXED_PREFIX = "scrimPopupContainerLayout.setFixedMessagePreview("
UNSAFE_BOTTOM = "scrimPopupContainerLayout.getBottomOffset()"
FORBIDDEN_OLD_RECEIVER = "chatActivityEnterView.setFixedMessagePreview("
LEGACY_DIRECT_PARENT_HINT = (
    "// popupLayout is in this lexical scope; its direct parent is the native"
)

UNSAFE_FIXED_RE = re.compile(
    r"(?m)^(?P<indent>[ \t]*)scrimPopupContainerLayout\.setFixedMessagePreview\("
    r"(?P<preview>iosPreview|popupMessagePreview)\);[ \t]*$"
)

UNSAFE_BOTTOM_RE = re.compile(
    r"[ \t]*-[ \t]*\(\([ \t]*iosMenuMode[ \t]*&&[ \t]*!BUILD_FOR_PLAY_MARKET"
    r"[ \t]*\)[ \t]*\?[ \t]*0[ \t]*:[ \t]*"
    r"scrimPopupContainerLayout\.getBottomOffset\(\)[ \t]*\)"
)

OLD_DIRECT_SAFE_RE = re.compile(
    r"(?m)^(?P<indent>[ \t]*)// AUTHORGRAM_SCOPE_SAFE_IOS_PREVIEW_PARENT\n"
    r"(?P=indent)// popupLayout is in this lexical scope; its direct parent is the native\n"
    r"(?P=indent)// ChatScrimPopupContainerLayout that owns reactions and fixed previews\.\n"
    r"(?P=indent)android\.view\.ViewParent authorgramIosPreviewParent = popupLayout\.getParent\(\);\n"
    r"(?P=indent)if \(authorgramIosPreviewParent instanceof "
    r"org\.telegram\.ui\.Components\.ChatScrimPopupContainerLayout\) \{\n"
    r"(?P=indent)    \(\(org\.telegram\.ui\.Components\.ChatScrimPopupContainerLayout\) "
    r"authorgramIosPreviewParent\)\n"
    r"(?P=indent)            \.setFixedMessagePreview\((?P<preview>iosPreview|popupMessagePreview)\);\n"
    r"(?P=indent)\} else \{\n"
    r"(?P=indent)    // Defensive fallback: keep the preview visible and reachable rather\n"
    r"(?P=indent)    // than crash if an upstream layout wrapper ever changes parentage\.\n"
    r"(?P=indent)    LinearLayout\.LayoutParams authorgramFallbackPreviewParams = "
    r"LayoutHelper\.createLinear\(\n"
    r"(?P=indent)            LayoutHelper\.MATCH_PARENT,\n"
    r"(?P=indent)            LayoutHelper\.WRAP_CONTENT\n"
    r"(?P=indent)    \);\n"
    r"(?P=indent)    popupLayout\.addView\((?P=preview), 0, authorgramFallbackPreviewParams\);\n"
    r"(?P=indent)\}"
)

IOS_PREVIEW_BLOCK_RE = re.compile(
    r"                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
    r".*?"
    r"(?=                scrimPopupWindowItems = new ActionBarMenuSubItem\[items\.size\(\)\];)",
    re.DOTALL,
)

PREVIEW_SOURCE = r'''package org.telegram.ui.Components;

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
 * AUTHORGRAM_NATIVE_ONLY_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_WEB_PREVIEW_SAFE_IOS_MESSAGE_PREVIEW
 * AUTHORGRAM_BOUNDED_NATIVE_IOS_PREVIEW
 *
 * The preview is always a sibling of the action card. A fresh native Telegram
 * ChatMessageCell renders avatar, sender, reply/quote, media and text exactly as
 * the chat does. Tall messages are bounded here and scroll inside this preview;
 * they are never re-parented into the action list.
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
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
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

    /** Compatibility API for old validators. The preview never joins actions. */
    public boolean shouldScrollWithActions() {
        return false;
    }
}
'''


def read_chat() -> str:
    if not CHAT.is_file():
        raise SystemExit(f"Missing ChatActivity.java: {CHAT}")
    return CHAT.read_text(encoding="utf-8")


def write_chat(text: str) -> None:
    CHAT.write_text(text, encoding="utf-8", newline="")


def read_preview() -> str:
    if not PREVIEW.is_file():
        raise SystemExit(f"Missing IOSMessageMenuPreview.java: {PREVIEW}")
    return PREVIEW.read_text(encoding="utf-8")


def write_preview(text: str) -> None:
    PREVIEW.write_text(text, encoding="utf-8", newline="")


def inventory_legacy_calls(text: str) -> tuple[int, int, int]:
    if FORBIDDEN_OLD_RECEIVER in text:
        raise SystemExit(
            "pre-apply failed: obsolete chatActivityEnterView fixed-preview receiver remains"
        )

    fixed_total = text.count(UNSAFE_FIXED_PREFIX)
    fixed_known = len(UNSAFE_FIXED_RE.findall(text))
    if fixed_total != fixed_known:
        raise SystemExit(
            "pre-apply failed: unknown scrimPopupContainerLayout fixed-preview back-call "
            f"exists (known={fixed_known}, total={fixed_total})"
        )
    if fixed_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one legacy fixed-preview call, found {fixed_known}"
        )

    bottom_total = text.count(UNSAFE_BOTTOM)
    bottom_known = len(UNSAFE_BOTTOM_RE.findall(text))
    if bottom_total != bottom_known:
        raise SystemExit(
            "pre-apply failed: unknown scrimPopupContainerLayout bottom-offset back-call "
            f"exists (known={bottom_known}, total={bottom_total})"
        )
    if bottom_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one legacy bottom-offset call, found {bottom_known}"
        )

    direct_total = text.count(LEGACY_DIRECT_PARENT_HINT)
    direct_known = len(OLD_DIRECT_SAFE_RE.findall(text))
    if direct_total != direct_known:
        raise SystemExit(
            "pre-apply failed: unknown legacy direct-parent preview block exists "
            f"(known={direct_known}, total={direct_total})"
        )
    if direct_known > 1:
        raise SystemExit(
            f"pre-apply failed: expected at most one direct-parent preview block, found {direct_known}"
        )

    if (fixed_known or bottom_known or direct_known) and "popupLayout" not in text:
        raise SystemExit("pre-apply failed: popupLayout owner anchor is unavailable")

    return fixed_known, bottom_known, direct_known


def pre_apply_check() -> None:
    """Read-only guard that runs before any patch generator mutates ChatActivity."""
    fixed_known, bottom_known, direct_known = inventory_legacy_calls(read_chat())
    print(
        "AuthorGram ChatActivity pre-apply legacy scan passed: "
        f"legacyFixedPreview={fixed_known}, "
        f"legacyBottomOffset={bottom_known}, "
        f"legacyDirectParent={direct_known}"
    )


def _scope_safe_fixed_preview(match: re.Match[str]) -> str:
    indent = match.group("indent")
    preview = match.group("preview")
    return (
        f"{indent}// {SAFE_MARKER}\n"
        f"{indent}// popupLayout is the stable local view in this createMenu block. Walk\n"
        f"{indent}// its actual parent chain until the native scrim owner is reached.\n"
        f"{indent}android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();\n"
        f"{indent}while (authorgramIosPreviewParent != null\n"
        f"{indent}        && !(authorgramIosPreviewParent instanceof "
        "org.telegram.ui.Components.ChatScrimPopupContainerLayout)) {\n"
        f"{indent}    if (authorgramIosPreviewParent instanceof android.view.View) {{\n"
        f"{indent}        authorgramIosPreviewParent =\n"
        f"{indent}                ((android.view.View) authorgramIosPreviewParent).getParent();\n"
        f"{indent}    }} else {{\n"
        f"{indent}        authorgramIosPreviewParent = null;\n"
        f"{indent}    }}\n"
        f"{indent}}}\n"
        f"{indent}if (authorgramIosPreviewParent instanceof "
        "org.telegram.ui.Components.ChatScrimPopupContainerLayout) {\n"
        f"{indent}    ((org.telegram.ui.Components.ChatScrimPopupContainerLayout) "
        "authorgramIosPreviewParent)\n"
        f"{indent}            .setFixedMessagePreview({preview});\n"
        f"{indent}}} else {{\n"
        f"{indent}    // Never re-parent a selected message into the action card.\n"
        f"{indent}    {preview}.setVisibility(android.view.View.GONE);\n"
        f"{indent}    org.telegram.messenger.FileLog.e(\"AuthorGram: iOS preview owner not found\");\n"
        f"{indent}}}"
    )


def canonical_preview_block() -> str:
    return (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // AUTHORGRAM_IOS_NATIVE_MESSAGE_PREVIEW\n"
        "                // AUTHORGRAM_FIXED_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_ADAPTIVE_IOS_PREVIEW_OWNER\n"
        "                // AUTHORGRAM_STABLE_FIXED_IOS_PREVIEW\n"
        "                // AUTHORGRAM_CANONICAL_SEPARATE_IOS_PREVIEW\n"
        "                // Selected message and action menu are separate siblings.\n"
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
        "                    } else {\n"
        "                        // Keep actions usable rather than corrupting their child list.\n"
        "                        iosPreview.setVisibility(android.view.View.GONE);\n"
        "                        org.telegram.messenger.FileLog.e(\"AuthorGram: iOS preview owner not found\");\n"
        "                    }\n"
        "                }\n\n"
    )


def apply() -> None:
    """Canonicalize the final generated Main source and immediately validate it."""
    pre_apply_check()
    text = read_chat()

    if "// AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n" in text:
        text, owner_count = IOS_PREVIEW_BLOCK_RE.subn(canonical_preview_block(), text, count=1)
        if owner_count != 1:
            raise SystemExit(
                f"iOS preview owner canonicalization expected one block, found {owner_count}"
            )
    else:
        owner_count = 0

    text, direct_count = OLD_DIRECT_SAFE_RE.subn(_scope_safe_fixed_preview, text)
    text, fixed_count = UNSAFE_FIXED_RE.subn(_scope_safe_fixed_preview, text)
    text, bottom_count = UNSAFE_BOTTOM_RE.subn("", text)

    write_chat(text)
    write_preview(PREVIEW_SOURCE)
    print(
        "AuthorGram ChatActivity scope repair applied: "
        f"canonicalOwner={owner_count}, "
        f"directParent={direct_count}, "
        f"fixedPreview={fixed_count}, "
        f"bottomOffset={bottom_count}"
    )
    validate()


def validate() -> None:
    text = read_chat()
    preview = read_preview()
    failures: list[str] = []

    if UNSAFE_FIXED_PREFIX in text:
        failures.append("legacy out-of-scope scrim fixed-preview call remains")
    if UNSAFE_BOTTOM in text:
        failures.append("legacy out-of-scope scrim bottom-offset call remains")
    if FORBIDDEN_OLD_RECEIVER in text:
        failures.append("obsolete chatActivityEnterView fixed-preview receiver remains")
    if LEGACY_DIRECT_PARENT_HINT in text:
        failures.append("legacy direct-parent fixed-preview block remains")

    if "AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW" in text:
        for required in (
            CANONICAL_MARKER,
            SAFE_MARKER,
            "android.view.ViewParent authorgramIosPreviewParent = popupLayout.getParent();",
            "while (authorgramIosPreviewParent != null",
            "authorgramIosPreviewParent instanceof org.telegram.ui.Components.ChatScrimPopupContainerLayout",
            "((android.view.View) authorgramIosPreviewParent).getParent();",
            ".setFixedMessagePreview(iosPreview);",
        ):
            if required not in text:
                failures.append(f"scope-safe fixed-preview invariant missing: {required}")

        for forbidden in (
            "popupLayout.addView(iosPreview",
            "popupLayout.addView(popupMessagePreview",
            "iosPreview.shouldScrollWithActions()",
            "AUTHORGRAM_IOS_LONG_MESSAGE_ACTION_GAP",
            "AUTHORGRAM_IOS_MESSAGE_ACTION_GAP",
        ):
            if forbidden in text:
                failures.append(f"preview/action ownership regression remains: {forbidden}")

    if "? 0 : scrimPopupContainerLayout" in text:
        failures.append("legacy conditional scrim bottom-offset geometry remains")

    for required in (
        BOUNDED_MARKER,
        "new ChatMessageCell(context, currentAccount)",
        "new ScrollView(context)",
        "maxPreviewHeight",
        "previewCell.setMessageObject(messageObject, null, false, false, false);",
        "return false;",
    ):
        if required not in preview:
            failures.append(f"native preview invariant missing: {required}")

    for forbidden in (
        "Bitmap.createBitmap",
        "sourceCell.draw(",
        "getPixels(",
        "NativeCellSnapshotView",
    ):
        if forbidden in preview:
            failures.append(f"bitmap/synthetic preview regression remains: {forbidden}")

    if failures:
        raise SystemExit("ChatActivity scope validation failed:\n - " + "\n - ".join(failures))

    print("AuthorGram ChatActivity scope + bounded native preview validation passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--mode",
        choices=("pre-apply", "apply", "validate"),
        default="apply",
    )
    args = parser.parse_args()

    if args.mode == "pre-apply":
        pre_apply_check()
    elif args.mode == "validate":
        validate()
    else:
        apply()


if __name__ == "__main__":
    main()
