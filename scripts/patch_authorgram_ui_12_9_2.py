#!/usr/bin/env python3
"""Apply the final AuthorGram 12.9.2 UI repairs idempotently.

The large Telegram/Nagram sources are patched from tightly scoped anchors so the
release workflow can validate and commit the exact generated source before build.
No package names, product names, existing features or Main/Play policy are changed.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, text: str) -> None:
    (ROOT / relative).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_dialog_badges() -> None:
    path = "TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java"
    text = read(path)
    marker = "AUTHORGRAM_PROTECTED_DIALOG_BADGE"
    if marker in text:
        return

    text = replace_once(
        text,
        "import org.telegram.messenger.ApplicationLoader;\n",
        "import org.telegram.messenger.ApplicationLoader;\n"
        "import org.telegram.messenger.authorgram.AuthorGramAuthorBadge;\n",
        "DialogCell author badge import",
    )
    text = replace_once(
        text,
        "    // AuthorGram: декоративний бейдж розробника\n"
        "    private static final java.util.Set<Long> AUTHOR_BADGE_IDS = new java.util.HashSet<>();\n"
        "    static {\n"
        "        AUTHOR_BADGE_IDS.add(6316376597L);\n"
        "        AUTHOR_BADGE_IDS.add(2021861896L);\n"
        "        AUTHOR_BADGE_IDS.add(2815463434L);\n"
        "    }\n",
        "    // AUTHORGRAM_PROTECTED_DIALOG_BADGE: IDs are resolved by the signed-build policy.\n",
        "DialogCell legacy raw badge set",
    )
    for expression in (
        "currentDialogId",
        "chat.id",
        "user.id",
    ):
        text = replace_once(
            text,
            f"AUTHOR_BADGE_IDS.contains({expression})",
            f"AuthorGramAuthorBadge.matches({expression})",
            f"DialogCell protected badge match for {expression}",
        )

    for raw in (
        "6316376597", "2021861896", "2815463434",
        "6802848305", "6822670748", "8470484374", "8154455619",
        "7913929703", "8856346711", "8357439344", "8548193112",
        "8395237407", "8925149503", "3781500049", "4297907963",
    ):
        if raw in text:
            raise SystemExit(f"DialogCell still contains raw author badge ID {raw}")
    write(path, text)


def patch_authorgram_settings() -> None:
    path = "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java"
    text = read(path)
    marker = "AUTHORGRAM_LOCAL_FOLDERS_ROW"
    if marker in text:
        return

    text = replace_once(
        text,
        "import org.telegram.ui.DocumentSelectActivity;\n",
        "import org.telegram.ui.DocumentSelectActivity;\n"
        "import org.telegram.ui.FiltersSetupActivity;\n",
        "AuthorGram settings local folders import",
    )
    text = replace_once(
        text,
        "    private int chatRow;\n",
        "    private int chatRow;\n"
        "    private int localFoldersRow; // AUTHORGRAM_LOCAL_FOLDERS_ROW\n",
        "AuthorGram settings local folders field",
    )
    text = replace_once(
        text,
        "        chatRow = addRow();\n",
        "        chatRow = addRow();\n"
        "        localFoldersRow = addRow();\n",
        "AuthorGram settings local folders row",
    )

    # Keep only the overflow button in the action bar. Search remains available as
    # the first overflow action and therefore can never overlap a long title.
    text = replace_once(
        text,
        "        menu.addItem(MENU_SEARCH, R.drawable.ic_ab_search, resourcesProvider);\n"
        "        overflowItem = menu.addItem(MENU_OVERFLOW, R.drawable.ic_ab_other, resourcesProvider);\n"
        "        overflowItem.setContentDescription(getString(R.string.AccDescrMoreOptions));\n"
        "        overflowItem.addSubItem(MENU_IMPORT, R.drawable.import_solar, getString(R.string.ImportSettings));\n",
        "        overflowItem = menu.addItem(MENU_OVERFLOW, R.drawable.ic_ab_other, resourcesProvider);\n"
        "        overflowItem.setContentDescription(getString(R.string.AccDescrMoreOptions));\n"
        "        overflowItem.addSubItem(MENU_SEARCH, R.drawable.ic_ab_search, getString(R.string.Search));\n"
        "        overflowItem.addColoredGap();\n"
        "        overflowItem.addSubItem(MENU_IMPORT, R.drawable.import_solar, getString(R.string.ImportSettings));\n",
        "AuthorGram settings overflow search",
    )
    text = replace_once(
        text,
        "        if (position == chatRow) {\n"
        "            presentFragment(new AGChatSettingsActivity());\n"
        "        } else if (position == generalRow) {\n",
        "        if (position == chatRow) {\n"
        "            presentFragment(new AGChatSettingsActivity());\n"
        "        } else if (position == localFoldersRow) {\n"
        "            presentFragment(new FiltersSetupActivity());\n"
        "        } else if (position == generalRow) {\n",
        "AuthorGram settings local folders click",
    )
    text = replace_once(
        text,
        "                    if (position == chatRow) {\n"
        "                        textCell.setTextAndIcon(getString(R.string.Chat), R.drawable.msg_discussion, true);\n"
        "                    } else if (position == generalRow) {\n",
        "                    if (position == chatRow) {\n"
        "                        textCell.setTextAndIcon(getString(R.string.Chat), R.drawable.msg_discussion, true);\n"
        "                    } else if (position == localFoldersRow) {\n"
        "                        textCell.setTextAndIcon(getString(R.string.Filters), R.drawable.msg_folders, true);\n"
        "                    } else if (position == generalRow) {\n",
        "AuthorGram settings local folders cell",
    )
    text = replace_once(
        text,
        "            } else if (position == chatRow || position == generalRow || position == appearanceRow || position == spyRow || position == passcodeRow || position == experimentRow || position == translatorRow ||\n",
        "            } else if (position == chatRow || position == localFoldersRow || position == generalRow || position == appearanceRow || position == spyRow || position == passcodeRow || position == experimentRow || position == translatorRow ||\n",
        "AuthorGram settings local folders view type",
    )
    write(path, text)


def patch_chat_input() -> None:
    path = "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
    text = read(path)
    marker = "AUTHORGRAM_IOS_INPUT_MENU_GUARD"
    if marker in text:
        return

    anchor = "        if (isStories && suggestButton != null) {\n"
    guard = (
        "        // AUTHORGRAM_IOS_INPUT_MENU_GUARD\n"
        "        // A delayed MENU-state animation could leave the media container translated\n"
        "        // over the chat avatar while text is present.  Remove both rendering and\n"
        "        // touch interception whenever the send button owns this slot.\n"
        "        if (isIOSInputStyle() && shownSendButton && audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.animate().cancel();\n"
        "            audioVideoButtonContainer.setVisibility(GONE);\n"
        "            audioVideoButtonContainer.setAlpha(0.0f);\n"
        "            audioVideoButtonContainer.setClickable(false);\n"
        "            audioVideoButtonContainer.setEnabled(false);\n"
        "            audioVideoButtonContainer.setTranslationX(0.0f);\n"
        "            audioVideoButtonContainer.setTranslationY(0.0f);\n"
        "        } else if (audioVideoButtonContainer != null) {\n"
        "            audioVideoButtonContainer.setClickable(true);\n"
        "            audioVideoButtonContainer.setEnabled(true);\n"
        "        }\n"
        "\n"
    )
    text = replace_once(text, anchor, guard + anchor, "iOS input ghost menu guard")
    write(path, text)


def patch_message_menu() -> None:
    path = "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
    text = read(path)

    if "AUTHORGRAM_COMPACT_MENU_LIMIT" not in text:
        anchor = (
            "                GridLayout compactIconBar = null;\n"
            "                if (!compactIndices.isEmpty()) {\n"
            "                    int n = compactIndices.size();\n"
        )
        replacement = (
            "                // AUTHORGRAM_COMPACT_MENU_LIMIT: at most two rows of four icons;\n"
            "                // remaining options stay as normal scrollable text actions.\n"
            "                if (compactIndices.size() > 8) {\n"
            "                    int compactCount = 0;\n"
            "                    for (int index = 0; index < items.size(); index++) {\n"
            "                        if (compactIndices.contains(index) && ++compactCount > 8) {\n"
            "                            compactIndices.remove(index);\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "                GridLayout compactIconBar = null;\n"
            "                if (!compactIndices.isEmpty()) {\n"
            "                    int n = compactIndices.size();\n"
        )
        text = replace_once(text, anchor, replacement, "compact message menu icon limit")

    if "AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW" not in text:
        anchor = "                scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];\n"
        preview = (
            "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
            "                // Mirror Telegram-iOS' targeted preview: the selected message's\n"
            "                // author and bounded content are shown before the action list.\n"
            "                if (selectedObject != null) {\n"
            "                    org.telegram.ui.Components.IOSMessageMenuPreview iosPreview =\n"
            "                            new org.telegram.ui.Components.IOSMessageMenuPreview(\n"
            "                                    getParentActivity(),\n"
            "                                    contentView,\n"
            "                                    currentAccount,\n"
            "                                    selectedObject,\n"
            "                                    themeDelegate\n"
            "                            );\n"
            "                    LinearLayout.LayoutParams iosPreviewParams = LayoutHelper.createLinear(\n"
            "                            LayoutHelper.MATCH_PARENT,\n"
            "                            LayoutHelper.WRAP_CONTENT\n"
            "                    );\n"
            "                    iosPreviewParams.leftMargin = AndroidUtilities.dp(6);\n"
            "                    iosPreviewParams.rightMargin = AndroidUtilities.dp(6);\n"
            "                    iosPreviewParams.topMargin = AndroidUtilities.dp(6);\n"
            "                    iosPreviewParams.bottomMargin = AndroidUtilities.dp(8);\n"
            "                    popupLayout.addView(iosPreview, iosPreviewParams);\n"
            "                }\n"
            "\n"
        )
        text = replace_once(text, anchor, preview + anchor, "iOS message menu preview")

    write(path, text)


def validate() -> None:
    dialog = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java")
    settings = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java")
    enter = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
    chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    preview = read("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")

    required = {
        "protected dialog badges": "AUTHORGRAM_PROTECTED_DIALOG_BADGE" in dialog
        and dialog.count("AuthorGramAuthorBadge.matches(") >= 3,
        "search in overflow": "overflowItem.addSubItem(MENU_SEARCH" in settings
        and "menu.addItem(MENU_SEARCH" not in settings,
        "local folders entry": "AUTHORGRAM_LOCAL_FOLDERS_ROW" in settings
        and "new FiltersSetupActivity()" in settings,
        "iOS input guard": "AUTHORGRAM_IOS_INPUT_MENU_GUARD" in enter,
        "compact menu cap": "AUTHORGRAM_COMPACT_MENU_LIMIT" in chat,
        "iOS message preview integration": "AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW" in chat,
        "iOS message preview implementation": "class IOSMessageMenuPreview" in preview
        and "BluredView" in preview,
    }
    failed = [name for name, ok in required.items() if not ok]
    if failed:
        raise SystemExit("UI repair validation failed: " + ", ".join(failed))
    print("AuthorGram 12.9.2 UI repair validation passed")


def main() -> None:
    patch_dialog_badges()
    patch_authorgram_settings()
    patch_chat_input()
    patch_message_menu()
    validate()


if __name__ == "__main__":
    main()
