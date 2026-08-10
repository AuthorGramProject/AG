#!/usr/bin/env python3
"""Restore AuthorGram Main iOS UI features without touching Telegram message logic.

This patch is deliberately idempotent. It modifies only UI/config boundaries:
- iOS input toggle/default and the animated media-slot state machine;
- iOS message-menu snapshot insertion into Telegram's existing popup;
- native Telegram chat header regardless of global title centering;
- one-time UI config reset while preserving API/credential values.

Play remains protected by AuthorGramPlayPolicy.canUseIosUi()/locked configs.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"Missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def write(relative: str, content: str) -> bool:
    path = ROOT / relative
    old = path.read_text(encoding="utf-8")
    if old == content:
        return False
    path.write_text(content, encoding="utf-8", newline="")
    return True


def replace_once(content: str, old: str, new: str, description: str) -> str:
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{description}: expected one anchor, found {count}")
    return content.replace(old, new, 1)


def patch_neko_config() -> bool:
    relative = "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java"
    content = read(relative)
    if 'addConfig("iOSMessageMenu"' in content:
        return False
    old = '    public static ConfigItem iOSMessageInputField = addConfig("iOSMessageInputField", configTypeBool, false);\n'
    new = old + '    public static ConfigItem iOSMessageMenu = addConfig("iOSMessageMenu", configTypeBool, false);\n'
    return write(relative, replace_once(content, old, new, "NekoConfig iOS menu config"))


def patch_strings() -> bool:
    relative = "TMessagesProj/src/main/res/values/strings_neko.xml"
    content = read(relative)
    if 'name="iOSMessageMenu"' in content:
        return False
    insertion = (
        '    <string name="iOSMessageMenu">iOS Message Menu</string>\n'
        '    <string name="iOSMessageMenuNotice">Shows the selected message above the actions and keeps long menus scrollable.</string>\n'
    )
    if "</resources>" not in content:
        raise RuntimeError("strings_neko.xml: missing </resources>")
    return write(relative, content.replace("</resources>", insertion + "</resources>", 1))


def patch_chat_settings() -> bool:
    relative = "TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java"
    content = read(relative)
    changed = False

    if "appendIOSMessageMenuRow" not in content:
        anchor = (
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
        addition = anchor + (
            "\n"
            "    private AbstractConfigCell appendIOSMessageMenuRow() {\n"
            "        if (!AuthorGramPlayPolicy.canUseIosUi()) {\n"
            "            return null;\n"
            "        }\n"
            "        return cellGroup.appendCell(new ConfigCellTextCheck(\n"
            "                NekoConfig.iOSMessageMenu,\n"
            "                getString(R.string.iOSMessageMenuNotice)\n"
            "        ));\n"
            "    }\n"
        )
        content = replace_once(content, anchor, addition, "AGChatSettingsActivity menu helper")
        changed = True

    if "iOSMessageMenuRow" not in content:
        anchor = "    private final AbstractConfigCell iOSMessageInputFieldRow = appendIOSMessageInputFieldRow();\n"
        addition = anchor + "    private final AbstractConfigCell iOSMessageMenuRow = appendIOSMessageMenuRow();\n"
        content = replace_once(content, anchor, addition, "AGChatSettingsActivity menu row")
        changed = True

    return write(relative, content) if changed else False


def patch_defaults() -> bool:
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java"
    content = read(relative)
    changed = False

    if "AUTHORGRAM_UI_CONFIG_EPOCH_20260810" not in content:
        anchor = "public final class AuthorGramDefaults {\n\n"
        addition = (
            "public final class AuthorGramDefaults {\n\n"
            "    private static final String UI_CONFIG_RESET_MARKER =\n"
            "            \"AUTHORGRAM_UI_CONFIG_EPOCH_20260810\";\n\n"
        )
        content = replace_once(content, anchor, addition, "AuthorGramDefaults reset marker")
        changed = True

    if "resetUiConfigPreservingCredentials(context);" not in content:
        anchor = (
            "        if (AuthorGramPlayPolicy.isPlayBuild()) {\n"
            "            AuthorGramPlayPolicy.applyStartupPolicy(context);\n"
            "            return;\n"
            "        }\n\n"
        )
        addition = anchor + "        resetUiConfigPreservingCredentials(context);\n\n"
        content = replace_once(content, anchor, addition, "AuthorGramDefaults reset call")
        changed = True

    if '{"iOSMessageInputField", true}' not in content:
        anchor = '                {"CenterActionBarTitleType", 1},\n'
        addition = (
            anchor
            + '                {"iOSMessageInputField", true},\n'
            + '                {"iOSMessageMenu", true},\n'
        )
        content = replace_once(content, anchor, addition, "AuthorGramDefaults iOS defaults")
        changed = True

    if "private static void resetUiConfigPreservingCredentials" not in content:
        anchor = "    private static void applyDefaults(\n"
        helper = r'''    /**
     * Reset only the AuthorGram/Nagram UI preference namespace once for this
     * migration. Telegram accounts, message databases, downloads, E2EE keys and
     * other user data live elsewhere and are intentionally untouched.
     *
     * API credentials are copied out before clear() and restored verbatim.
     */
    private static void resetUiConfigPreservingCredentials(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE);
        if (preferences.getBoolean(UI_CONFIG_RESET_MARKER, false)) {
            return;
        }

        java.util.Map<String, ?> previous =
                new java.util.LinkedHashMap<>(preferences.getAll());
        SharedPreferences.Editor editor = preferences.edit().clear();

        for (java.util.Map.Entry<String, ?> entry : previous.entrySet()) {
            if (!isCredentialPreference(entry.getKey())) {
                continue;
            }
            putPreferenceValue(editor, entry.getKey(), entry.getValue());
        }

        editor.putBoolean(UI_CONFIG_RESET_MARKER, true);
        editor.commit();
    }

    private static boolean isCredentialPreference(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("key")
                || normalized.contains("apikey")
                || normalized.contains("credential")
                || normalized.contains("token")
                || normalized.contains("secret");
    }

    @SuppressWarnings("unchecked")
    private static void putPreferenceValue(
            SharedPreferences.Editor editor,
            String key,
            Object value
    ) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof java.util.Set) {
            editor.putStringSet(
                    key,
                    new java.util.HashSet<>((java.util.Set<String>) value)
            );
        }
    }

'''
        content = replace_once(content, anchor, helper + anchor, "AuthorGramDefaults reset helper")
        changed = True

    return write(relative, content) if changed else False


def patch_chat_activity() -> bool:
    relative = "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
    content = read(relative)
    changed = False

    if "AUTHORGRAM_IOS_MESSAGE_MENU_V2" not in content:
        anchor = (
            "            popupLayout.setBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));\n"
            "            MessageSeenView messageSeenView = null;\n"
        )
        addition = (
            "            popupLayout.setBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));\n"
            "            // AUTHORGRAM_IOS_MESSAGE_MENU_V2: isolated visual snapshot only.\n"
            "            final org.telegram.ui.Components.IOSMessageMenuPreview authorGramIosMessagePreview =\n"
            "                    optionsView == null\n"
            "                            && org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canUseIosUi()\n"
            "                            && NekoConfig.iOSMessageMenu.Bool()\n"
            "                            && v instanceof org.telegram.ui.Cells.ChatMessageCell\n"
            "                            ? org.telegram.ui.Components.IOSMessageMenuPreview.create(\n"
            "                                    contentView.getContext(),\n"
            "                                    currentAccount,\n"
            "                                    (org.telegram.ui.Cells.ChatMessageCell) v,\n"
            "                                    themeDelegate\n"
            "                            )\n"
            "                            : null;\n"
            "            if (authorGramIosMessagePreview != null) {\n"
            "                LinearLayout.LayoutParams authorGramPreviewParams = LayoutHelper.createLinear(\n"
            "                        LayoutHelper.MATCH_PARENT,\n"
            "                        LayoutHelper.WRAP_CONTENT\n"
            "                );\n"
            "                authorGramPreviewParams.leftMargin = AndroidUtilities.dp(8);\n"
            "                authorGramPreviewParams.topMargin = AndroidUtilities.dp(8);\n"
            "                authorGramPreviewParams.rightMargin = AndroidUtilities.dp(8);\n"
            "                authorGramPreviewParams.bottomMargin = AndroidUtilities.dp(8);\n"
            "                popupLayout.addView(authorGramIosMessagePreview, authorGramPreviewParams);\n"
            "                popupLayout.addView(\n"
            "                        new ActionBarPopupWindow.GapView(contentView.getContext(), themeDelegate),\n"
            "                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8)\n"
            "                );\n"
            "            }\n"
            "            MessageSeenView messageSeenView = null;\n"
        )
        content = replace_once(content, anchor, addition, "ChatActivity iOS menu insertion")
        changed = True

    if "AUTHORGRAM_IOS_MENU_FULL_BLUR" not in content:
        anchor = "            dimBehindView(v, true);\n            hideHints(false);\n"
        addition = (
            "            // AUTHORGRAM_IOS_MENU_FULL_BLUR: snapshot replaces the elevated source cell.\n"
            "            if (authorGramIosMessagePreview != null) {\n"
            "                dimBehindView(null, true, true);\n"
            "            } else {\n"
            "                dimBehindView(v, true);\n"
            "            }\n"
            "            hideHints(false);\n"
        )
        content = replace_once(content, anchor, addition, "ChatActivity full blur")
        changed = True

    if "AUTHORGRAM_NATIVE_CHAT_HEADER" not in content:
        pattern = re.compile(
            r"    private boolean canShowCenteredTitle\(ChatActivity parentFragment\) \{\n"
            r".*?\n"
            r"    \}\n\n"
            r"    public MessageObject getMessageForTranslate\(\) \{",
            re.DOTALL,
        )
        replacement = (
            "    private boolean canShowCenteredTitle(ChatActivity parentFragment) {\n"
            "        // AUTHORGRAM_NATIVE_CHAT_HEADER: global centering remains available outside chats.\n"
            "        // Chats always keep Telegram's ordinary avatar/title/overflow geometry.\n"
            "        return false;\n"
            "    }\n\n"
            "    public MessageObject getMessageForTranslate() {"
        )
        content, count = pattern.subn(replacement, content, count=1)
        if count != 1:
            raise RuntimeError(f"ChatActivity native chat header: expected one method, found {count}")
        changed = True

    return write(relative, content) if changed else False


def validate() -> None:
    failures: list[str] = []

    required_files = (
        "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/EditTextEmoji.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/AIEditorAlert.java",
        "TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorToolbar.java",
        "TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorHistory.java",
    )
    for relative in required_files:
        if not (ROOT / relative).is_file():
            failures.append(f"Missing UI dependency: {relative}")

    chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    for marker in (
        "AUTHORGRAM_IOS_MESSAGE_MENU_V2",
        "AUTHORGRAM_IOS_MENU_FULL_BLUR",
        "AUTHORGRAM_NATIVE_CHAT_HEADER",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "NekoConfig.iOSMessageMenu.Bool()",
    ):
        if marker not in chat:
            failures.append(f"ChatActivity marker missing: {marker}")

    neko = read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java")
    for config in ('addConfig("iOSMessageInputField"', 'addConfig("iOSMessageMenu"'):
        if config not in neko:
            failures.append(f"NekoConfig missing: {config}")

    settings = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java")
    for marker in ("appendIOSMessageInputFieldRow", "appendIOSMessageMenuRow", "iOSMessageMenuRow"):
        if marker not in settings:
            failures.append(f"Chat settings missing: {marker}")

    defaults = read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java")
    for marker in (
        "AUTHORGRAM_UI_CONFIG_EPOCH_20260810",
        "resetUiConfigPreservingCredentials(context)",
        '{"iOSMessageInputField", true}',
        '{"iOSMessageMenu", true}',
    ):
        if marker not in defaults:
            failures.append(f"Config migration/default missing: {marker}")

    animated = read(
        "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java"
    )
    for marker in (
        "drawableIosMode",
        "stateMap.clear()",
        "setVisibility(VISIBLE)",
        "setAlpha(1.0f)",
        "AuthorGramPlayPolicy.canUseIosUi()",
    ):
        if marker not in animated:
            failures.append(f"iOS input state guard missing: {marker}")

    # iOS mode must stay isolated from the giant composer implementation. This is
    # what protects extended formatting, undo/redo and AI-editor action views.
    enter_view = read(
        "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java"
    )
    if "iOSMessageInputField" in enter_view or "iOSMessageMenu" in enter_view:
        failures.append(
            "iOS UI leaked into ChatActivityEnterView; formatting/undo/AI isolation is broken"
        )

    edit_text_emoji = read(
        "TMessagesProj/src/main/java/org/telegram/ui/Components/EditTextEmoji.java"
    )
    if "formatButton" not in edit_text_emoji:
        failures.append("Extended-formatting button path is missing from EditTextEmoji")

    if "AIEditorAlert" not in read(
        "TMessagesProj/src/main/java/org/telegram/ui/Components/AIEditorAlert.java"
    ):
        failures.append("AI editor component is missing")

    rich_history = read("TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorHistory.java")
    if "undo" not in rich_history.lower():
        failures.append("Rich-editor undo history path is missing")

    policy = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java"
    )
    for marker in (
        'values.put("iOSMessageInputField", false)',
        'values.put("iOSMessageMenu", false)',
        "return !isPlayBuild();",
    ):
        if marker not in policy:
            failures.append(f"Play iOS UI boundary missing: {marker}")

    if failures:
        raise RuntimeError("\n".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    changed = 0
    if not args.check:
        changed += int(patch_neko_config())
        changed += int(patch_strings())
        changed += int(patch_chat_settings())
        changed += int(patch_defaults())
        changed += int(patch_chat_activity())

    validate()
    print(f"AuthorGram iOS UI restoration validated; changed files: {changed}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"AuthorGram iOS UI restoration failed:\n{exc}")
        raise SystemExit(1)
