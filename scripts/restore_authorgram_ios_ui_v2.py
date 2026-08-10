#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def p(relative: str) -> Path:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"Missing required file: {relative}")
    return path


def read(relative: str) -> str:
    return p(relative).read_text(encoding="utf-8")


def write(relative: str, content: str) -> bool:
    path = p(relative)
    old = path.read_text(encoding="utf-8")
    if old == content:
        return False
    path.write_text(content, encoding="utf-8")
    return True


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_neko_config() -> bool:
    rel = "TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java"
    text = read(rel)
    exact = 'public static ConfigItem iOSMessageMenu = addConfig("iOSMessageMenu", configTypeBool, false);'
    if exact in text:
        return False
    anchor = '    public static ConfigItem iOSMessageInputField = addConfig("iOSMessageInputField", configTypeBool, false);\n'
    text = replace_once(
        text,
        anchor,
        anchor + '    public static ConfigItem iOSMessageMenu = addConfig("iOSMessageMenu", configTypeBool, false);\n',
        "NekoConfig iOSMessageMenu",
    )
    return write(rel, text)


def patch_strings() -> bool:
    rel = "TMessagesProj/src/main/res/values/strings_neko.xml"
    text = read(rel)
    if 'name="iOSMessageMenu"' in text:
        return False
    block = (
        '    <string name="iOSMessageMenu">iOS Message Menu</string>\n'
        '    <string name="iOSMessageMenuNotice">Shows the selected message above the actions and keeps long menus scrollable.</string>\n'
    )
    text = replace_once(text, "</resources>", block + "</resources>", "strings_neko.xml closing tag")
    return write(rel, text)


def patch_chat_settings() -> bool:
    rel = "TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java"
    text = read(rel)
    changed = False

    method_signature = "    private AbstractConfigCell appendIOSMessageMenuRow() {\n"
    if method_signature not in text:
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
        menu_method = (
            anchor
            + "\n"
            + "    private AbstractConfigCell appendIOSMessageMenuRow() {\n"
            + "        if (!AuthorGramPlayPolicy.canUseIosUi()) {\n"
            + "            return null;\n"
            + "        }\n"
            + "        return cellGroup.appendCell(new ConfigCellTextCheck(\n"
            + "                NekoConfig.iOSMessageMenu,\n"
            + "                getString(R.string.iOSMessageMenuNotice)\n"
            + "        ));\n"
            + "    }\n"
        )
        text = replace_once(text, anchor, menu_method, "AGChatSettingsActivity iOS menu method")
        changed = True

    exact_field = "    private final AbstractConfigCell iOSMessageMenuRow = appendIOSMessageMenuRow();\n"
    if exact_field not in text:
        anchor = "    private final AbstractConfigCell iOSMessageInputFieldRow = appendIOSMessageInputFieldRow();\n"
        text = replace_once(text, anchor, anchor + exact_field, "AGChatSettingsActivity iOS menu field")
        changed = True

    return write(rel, text) if changed else False


def patch_defaults() -> bool:
    rel = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java"
    text = read(rel)
    changed = False

    if "AUTHORGRAM_UI_CONFIG_EPOCH_20260810" not in text:
        anchor = "public final class AuthorGramDefaults {\n\n"
        text = replace_once(
            text,
            anchor,
            anchor
            + "    private static final String UI_CONFIG_RESET_MARKER =\n"
            + "            \"AUTHORGRAM_UI_CONFIG_EPOCH_20260810\";\n\n",
            "AuthorGramDefaults migration marker",
        )
        changed = True

    reset_call = "        resetUiConfigPreservingCredentials(context);\n\n"
    if reset_call not in text:
        anchor = (
            "        if (AuthorGramPlayPolicy.isPlayBuild()) {\n"
            "            AuthorGramPlayPolicy.applyStartupPolicy(context);\n"
            "            return;\n"
            "        }\n\n"
        )
        text = replace_once(text, anchor, anchor + reset_call, "AuthorGramDefaults migration call")
        changed = True

    ios_input_default = '                {"iOSMessageInputField", true},\n'
    ios_menu_default = '                {"iOSMessageMenu", true},\n'
    if ios_input_default not in text or ios_menu_default not in text:
        anchor = '                {"CenterActionBarTitleType", 1},\n'
        addition = anchor
        if ios_input_default not in text:
            addition += ios_input_default
        if ios_menu_default not in text:
            addition += ios_menu_default
        text = replace_once(text, anchor, addition, "AuthorGramDefaults iOS defaults")
        changed = True

    helper_signature = "    private static void resetUiConfigPreservingCredentials(Context context) {\n"
    if helper_signature not in text:
        anchor = "    private static void applyDefaults(\n"
        helper = '''    /**
     * One-time reset of AuthorGram/Nagram UI preferences only.
     * Telegram accounts, dialogs, messages, files and encryption state are not
     * stored in nkmrcfg and are not touched. Credential-like values are copied
     * out and restored verbatim before defaults are applied.
     */
    private static void resetUiConfigPreservingCredentials(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE);
        if (preferences.getBoolean(UI_CONFIG_RESET_MARKER, false)) {
            return;
        }

        java.util.Map<String, ?> oldValues =
                new java.util.LinkedHashMap<>(preferences.getAll());
        SharedPreferences.Editor editor = preferences.edit().clear();

        for (java.util.Map.Entry<String, ?> entry : oldValues.entrySet()) {
            if (isCredentialPreference(entry.getKey())) {
                putPreferenceValue(editor, entry.getKey(), entry.getValue());
            }
        }

        editor.putBoolean(UI_CONFIG_RESET_MARKER, true);
        if (!editor.commit()) {
            throw new IllegalStateException("Unable to persist AuthorGram UI preference migration");
        }
    }

    private static boolean isCredentialPreference(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith("key")
                || normalized.contains("apikey")
                || normalized.contains("api_key")
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
            editor.putStringSet(key, new java.util.HashSet<>((java.util.Set<String>) value));
        }
    }

'''
        text = replace_once(text, anchor, helper + anchor, "AuthorGramDefaults migration helper")
        changed = True

    return write(rel, text) if changed else False


def patch_chat_activity() -> bool:
    rel = "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
    text = read(rel)
    changed = False

    if "AUTHORGRAM_IOS_MESSAGE_MENU_V2" not in text:
        anchor = (
            "            popupLayout.setBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));\n"
            "            MessageSeenView messageSeenView = null;\n"
        )
        block = (
            "            popupLayout.setBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));\n"
            "            // AUTHORGRAM_IOS_MESSAGE_MENU_V2: visual-only native-cell snapshot.\n"
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
            "                        LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);\n"
            "                authorGramPreviewParams.leftMargin = AndroidUtilities.dp(8);\n"
            "                authorGramPreviewParams.topMargin = AndroidUtilities.dp(8);\n"
            "                authorGramPreviewParams.rightMargin = AndroidUtilities.dp(8);\n"
            "                authorGramPreviewParams.bottomMargin = AndroidUtilities.dp(8);\n"
            "                popupLayout.addView(authorGramIosMessagePreview, authorGramPreviewParams);\n"
            "                popupLayout.addView(\n"
            "                        new ActionBarPopupWindow.GapView(contentView.getContext(), themeDelegate),\n"
            "                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8));\n"
            "            }\n"
            "            MessageSeenView messageSeenView = null;\n"
        )
        text = replace_once(text, anchor, block, "ChatActivity iOS message preview insertion")
        changed = True

    if "AUTHORGRAM_NATIVE_CHAT_HEADER" not in text:
        pattern = re.compile(
            r"    private boolean canShowCenteredTitle\(ChatActivity parentFragment\) \{\n.*?\n    \}\n\n    public MessageObject getMessageForTranslate\(\) \{",
            re.DOTALL,
        )
        replacement = (
            "    private boolean canShowCenteredTitle(ChatActivity parentFragment) {\n"
            "        // AUTHORGRAM_NATIVE_CHAT_HEADER: chats always keep Telegram's native header geometry.\n"
            "        return false;\n"
            "    }\n\n"
            "    public MessageObject getMessageForTranslate() {"
        )
        text, count = pattern.subn(replacement, text, count=1)
        if count != 1:
            raise RuntimeError(f"ChatActivity native chat header: expected one method, found {count}")
        changed = True

    return write(rel, text) if changed else False


def validate_branding(failures: list[str]) -> None:
    for rel in (
        "TMessagesProj/src/release/res/values/authorgram_brand.xml",
        "TMessagesProj/src/debug/res/values/authorgram_brand.xml",
        "TMessagesProj/src/staging/res/values/authorgram_brand.xml",
    ):
        if '<string name="AppName">AuthorGram+</string>' not in read(rel):
            failures.append(f"Branding mismatch in {rel}: AppName must be AuthorGram+")


def validate() -> None:
    failures: list[str] = []

    required = (
        "TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/EditTextEmoji.java",
        "TMessagesProj/src/main/java/org/telegram/ui/Components/AIEditorAlert.java",
        "TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorToolbar.java",
        "TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorHistory.java",
    )
    for rel in required:
        if not (ROOT / rel).is_file():
            failures.append(f"Missing dependency: {rel}")

    validate_branding(failures)

    neko = read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java")
    if 'addConfig("iOSMessageInputField"' not in neko:
        failures.append("Missing iOSMessageInputField config")
    if 'addConfig("iOSMessageMenu"' not in neko:
        failures.append("Missing iOSMessageMenu config")

    settings = read("TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java")
    for marker in (
        "private AbstractConfigCell appendIOSMessageMenuRow()",
        "private final AbstractConfigCell iOSMessageMenuRow = appendIOSMessageMenuRow();",
    ):
        if marker not in settings:
            failures.append(f"Chat settings missing: {marker}")

    defaults = read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java")
    for marker in (
        "AUTHORGRAM_UI_CONFIG_EPOCH_20260810",
        "resetUiConfigPreservingCredentials(context);",
        '{"iOSMessageInputField", true}',
        '{"iOSMessageMenu", true}',
    ):
        if marker not in defaults:
            failures.append(f"Defaults/migration missing: {marker}")

    chat = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    for marker in (
        "AUTHORGRAM_IOS_MESSAGE_MENU_V2",
        "NekoConfig.iOSMessageMenu.Bool()",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "AUTHORGRAM_NATIVE_CHAT_HEADER",
    ):
        if marker not in chat:
            failures.append(f"ChatActivity missing: {marker}")

    animated = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java")
    for marker in (
        "drawableIosMode",
        "stateMap.clear()",
        "setVisibility(VISIBLE)",
        "setAlpha(1.0f)",
        "AuthorGramPlayPolicy.canUseIosUi()",
    ):
        if marker not in animated:
            failures.append(f"Composer state guard missing: {marker}")

    enter_view = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
    if "iOSMessageInputField" in enter_view or "iOSMessageMenu" in enter_view:
        failures.append("iOS customization leaked into ChatActivityEnterView; format/undo/AI isolation violated")

    edit_emoji = read("TMessagesProj/src/main/java/org/telegram/ui/Components/EditTextEmoji.java")
    if "formatButton" not in edit_emoji:
        failures.append("Extended formatting button path missing")

    ai_editor = read("TMessagesProj/src/main/java/org/telegram/ui/Components/AIEditorAlert.java")
    if "AIEditorAlert" not in ai_editor:
        failures.append("AI edit component missing")

    history = read("TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorHistory.java").lower()
    if "undo" not in history:
        failures.append("Rich editor undo path missing")

    policy = read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java")
    for marker in (
        'values.put("iOSMessageInputField", false)',
        'values.put("iOSMessageMenu", false)',
        "return !isPlayBuild();",
    ):
        if marker not in policy:
            failures.append(f"Play boundary missing: {marker}")

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
    print(f"AuthorGram iOS UI v2 validated; changed files: {changed}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"AuthorGram iOS UI v2 failed:\n{exc}")
        raise SystemExit(1)
