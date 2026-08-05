#!/usr/bin/env python3
"""Apply final AuthorGram 12.9.2 UI repairs idempotently.

The release workflow runs this patch before building Main and Play. Every change
is tied to a narrow source anchor and validated after application. Branding,
existing feature names and the Main/Play policy split are not changed.
"""

from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def write(relative: str, text: str) -> None:
    (ROOT / relative).write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def replace_all(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count < 1:
        raise SystemExit(f"{label}: expected at least one anchor, found 0")
    print(f"{label}: patched {count} path(s)")
    return text.replace(old, new)


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
    for expression in ("currentDialogId", "chat.id", "user.id"):
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

    # Only the overflow button remains in the action bar. Search is the first
    # overflow command, so it cannot overlap a long AuthorGram title.
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
        "                        textCell.setTextAndIcon(getString(R.string.BuiltInFolders), R.drawable.msg_folders, true);\n"
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


def patch_ios_message_menu_setting() -> None:
    config_path = "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java"
    config = read(config_path)
    if 'addConfig("iOSMessageMenu"' not in config:
        config = replace_once(
            config,
            '    public static ConfigItem iOSMessageInputField = addConfig("iOSMessageInputField", configTypeBool, false);\n',
            '    public static ConfigItem iOSMessageInputField = addConfig("iOSMessageInputField", configTypeBool, false);\n'
            '    public static ConfigItem iOSMessageMenu = addConfig("iOSMessageMenu", configTypeBool, true);\n',
            "iOS message menu config",
        )
        write(config_path, config)

    strings_path = "TMessagesProj/src/main/res/values/strings_neko.xml"
    strings = read(strings_path)
    if 'name="iOSMessageMenu"' not in strings:
        strings = replace_once(
            strings,
            '    <string name="iOSMessageInputFieldNotice">Moves the attachment icon to the left and splits buttons into separate bubbles. Overrides the attach-enter-menu mode while enabled</string>\n',
            '    <string name="iOSMessageInputFieldNotice">Moves the attachment icon to the left and splits buttons into separate bubbles. Overrides the attach-enter-menu mode while enabled</string>\n'
            '    <string name="iOSMessageMenu">iOS Message Menu</string>\n'
            '    <string name="iOSMessageMenuNotice">Shows the selected message with its sender and avatar above a blurred, adaptive action menu. Main version only</string>\n',
            "iOS message menu strings",
        )
        write(strings_path, strings)

    settings_path = "TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java"
    settings = read(settings_path)

    input_helper = (
        "    private AbstractConfigCell appendIOSMessageInputFieldRow() {\n"
        "        if (AuthorGramPlayPolicy.isPlayBuild()) {\n"
        "            return null;\n"
        "        }\n"
        "        return cellGroup.appendCell(new ConfigCellTextCheck(\n"
        "                NekoConfig.iOSMessageInputField,\n"
        "                getString(R.string.iOSMessageInputFieldNotice)\n"
        "        ));\n"
        "    }\n"
    )
    helpers = input_helper + (
        "\n"
        "    private AbstractConfigCell appendIOSMessageMenuRow() {\n"
        "        if (AuthorGramPlayPolicy.isPlayBuild()) {\n"
        "            return null;\n"
        "        }\n"
        "        return cellGroup.appendCell(new ConfigCellTextCheck(\n"
        "                NekoConfig.iOSMessageMenu,\n"
        "                getString(R.string.iOSMessageMenuNotice)\n"
        "        ));\n"
        "    }\n"
    )

    if "appendIOSMessageMenuRow()" not in settings:
        if input_helper not in settings:
            raise SystemExit("iOS settings helper anchor changed")
        settings = settings.replace(input_helper, helpers, 1)

    if "private final AbstractConfigCell iOSMessageMenuRow" not in settings:
        settings = replace_once(
            settings,
            "    private final AbstractConfigCell groupedMessageMenuRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getGroupedMessageMenu(), getString(R.string.GroupedMessageMenuNotice)));\n",
            "    private final AbstractConfigCell groupedMessageMenuRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getGroupedMessageMenu(), getString(R.string.GroupedMessageMenuNotice)));\n"
            "    private final AbstractConfigCell iOSMessageMenuRow = appendIOSMessageMenuRow();\n",
            "iOS message menu settings row",
        )
    write(settings_path, settings)


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
        "        // over the chat avatar. Remove rendering and touch interception whenever\n"
        "        // the send button owns this slot.\n"
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
        "        }\n\n"
    )
    text = replace_once(text, anchor, guard + anchor, "iOS input ghost menu guard")
    write(path, text)


def patch_ios_input_policy_gate() -> None:
    path = "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java"
    text = read(path)
    import_line = "import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;\n"
    if import_line not in text:
        text = replace_once(
            text,
            "import org.telegram.messenger.R;\n",
            "import org.telegram.messenger.R;\n" + import_line,
            "iOS input policy import",
        )
    old = "        return NekoConfig.iOSMessageInputField.Bool();\n"
    new = "        return AuthorGramPlayPolicy.canUseIosUi() && NekoConfig.iOSMessageInputField.Bool();\n"
    if new not in text:
        text = replace_once(text, old, new, "iOS input Main-only policy gate")
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
            "                // overflow actions remain available as normal scrollable rows.\n"
            "                if (compactIndices.size() > 8) {\n"
            "                    int compactCount = 0;\n"
            "                    for (int index = 0; index < items.size(); index++) {\n"
            "                        if (compactIndices.contains(index) && ++compactCount > 8) {\n"
            "                            compactIndices.remove(Integer.valueOf(index));\n"
            "                        }\n"
            "                    }\n"
            "                }\n"
            "                GridLayout compactIconBar = null;\n"
            "                if (!compactIndices.isEmpty()) {\n"
            "                    int n = compactIndices.size();\n"
        )
        text = replace_all(text, anchor, replacement, "compact message menu icon limit")

    preview_comment = (
        "                // AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW\n"
        "                // Telegram-iOS-style targeted preview: selected author and\n"
        "                // bounded message content are shown before the action list.\n"
    )
    legacy_preview_start = preview_comment + "                if (selectedObject != null) {\n"
    gated_preview_start = preview_comment + (
        "                if (selectedObject != null\n"
        "                        && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
        "                        && tw.nekomimi.nekogram.NekoConfig.iOSMessageMenu.Bool()) {\n"
    )

    if legacy_preview_start in text:
        text = text.replace(legacy_preview_start, gated_preview_start)

    if "AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW" not in text:
        anchor = "                scrimPopupWindowItems = new ActionBarMenuSubItem[items.size()];\n"
        preview = gated_preview_start + (
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
            "                }\n\n"
        )
        text = replace_all(text, anchor, preview + anchor, "iOS message menu preview")

    write(path, text)


def validate() -> None:
    dialog = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/DialogCell.java")
    settings = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java")
    chat_settings = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java")
    config = read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java")
    strings = read("TMessagesProj/src/main/res/values/strings_neko.xml")
    enter = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
    icon = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java")
    chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    preview = read("TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java")

    required = {
        "protected dialog badges": "AUTHORGRAM_PROTECTED_DIALOG_BADGE" in dialog
        and dialog.count("AuthorGramAuthorBadge.matches(") >= 3,
        "search in overflow": "overflowItem.addSubItem(MENU_SEARCH" in settings
        and "menu.addItem(MENU_SEARCH" not in settings,
        "local folders entry": "AUTHORGRAM_LOCAL_FOLDERS_ROW" in settings
        and "new FiltersSetupActivity()" in settings
        and "R.string.BuiltInFolders" in settings,
        "iOS message menu config": 'addConfig("iOSMessageMenu", configTypeBool, true)' in config,
        "iOS message menu strings": 'name="iOSMessageMenu"' in strings
        and 'name="iOSMessageMenuNotice"' in strings,
        "Main-only iOS settings": "appendIOSMessageMenuRow()" in chat_settings
        and chat_settings.count("AuthorGramPlayPolicy.isPlayBuild()") >= 2,
        "iOS input guard": "AUTHORGRAM_IOS_INPUT_MENU_GUARD" in enter,
        "iOS input policy": "AuthorGramPlayPolicy.canUseIosUi() && NekoConfig.iOSMessageInputField.Bool()" in icon,
        "compact menu cap": chat.count("AUTHORGRAM_COMPACT_MENU_LIMIT") >= 1
        and "compactIndices.remove(Integer.valueOf(index))" in chat
        and "compactIndices.remove(index);" not in chat,
        "Main-only iOS message preview": chat.count("AUTHORGRAM_IOS_MESSAGE_MENU_PREVIEW") >= 1
        and chat.count("AuthorGramPlayPolicy.canUseIosUi()") >= 1
        and chat.count("NekoConfig.iOSMessageMenu.Bool()") >= 1,
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
    patch_ios_message_menu_setting()
    patch_chat_input()
    patch_ios_input_policy_gate()
    patch_message_menu()
    validate()


if __name__ == "__main__":
    main()
