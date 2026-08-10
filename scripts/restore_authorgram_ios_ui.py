#!/usr/bin/env python3
"""Compatibility entry point for the corrected AuthorGram iOS UI restoration."""

import restore_authorgram_ios_ui_v2 as impl


def validate_existing_authorgram_features() -> None:
    failures = []

    for rel in (
        "TMessagesProj/src/release/res/values/authorgram_brand.xml",
        "TMessagesProj/src/debug/res/values/authorgram_brand.xml",
        "TMessagesProj/src/staging/res/values/authorgram_brand.xml",
    ):
        if '<string name="AppName">AuthorGram+</string>' not in impl.read(rel):
            failures.append(f"Branding mismatch in {rel}: AppName must be AuthorGram+")

    neko = impl.read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java")
    for marker in ('addConfig("iOSMessageInputField"', 'addConfig("iOSMessageMenu"'):
        if marker not in neko:
            failures.append(f"Missing UI config: {marker}")

    settings = impl.read("TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java")
    for marker in (
        "private AbstractConfigCell appendIOSMessageMenuRow()",
        "private final AbstractConfigCell iOSMessageMenuRow = appendIOSMessageMenuRow();",
    ):
        if marker not in settings:
            failures.append(f"Chat settings missing: {marker}")

    defaults = impl.read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java")
    for marker in (
        "AUTHORGRAM_UI_CONFIG_EPOCH_20260810",
        "resetUiConfigPreservingCredentials(context);",
        '{"iOSMessageInputField", true}',
        '{"iOSMessageMenu", true}',
    ):
        if marker not in defaults:
            failures.append(f"Defaults/migration missing: {marker}")

    chat = impl.read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
    for marker in (
        "AUTHORGRAM_IOS_MESSAGE_MENU_V2",
        "NekoConfig.iOSMessageMenu.Bool()",
        "AuthorGramPlayPolicy.canUseIosUi()",
        "AUTHORGRAM_NATIVE_CHAT_HEADER",
    ):
        if marker not in chat:
            failures.append(f"ChatActivity missing: {marker}")

    animated = impl.read(
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
            failures.append(f"Composer state guard missing: {marker}")

    formatting = impl.read("TMessagesProj/src/main/java/org/telegram/ui/Components/EditTextEmoji.java")
    for marker in ("shownFormatButton", "formatOptions", "R.drawable.msg_edit", "emojiButton"):
        if marker not in formatting:
            failures.append(f"Extended formatting path missing: {marker}")

    ai_editor = impl.read("TMessagesProj/src/main/java/org/telegram/ui/Components/AIEditorAlert.java")
    if "AIEditorAlert" not in ai_editor:
        failures.append("AI edit component missing")

    history = impl.read("TMessagesProj/src/main/java/org/telegram/ui/iv/RichEditorHistory.java").lower()
    if "undo" not in history:
        failures.append("Rich editor undo path missing")

    policy = impl.read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java")
    for marker in (
        'values.put("iOSMessageInputField", false)',
        'values.put("iOSMessageMenu", false)',
        "return !isPlayBuild();",
    ):
        if marker not in policy:
            failures.append(f"Play boundary missing: {marker}")

    if failures:
        raise RuntimeError("\n".join(failures))


impl.validate = validate_existing_authorgram_features


if __name__ == "__main__":
    try:
        raise SystemExit(impl.main())
    except RuntimeError as exc:
        print(f"AuthorGram iOS UI restoration failed:\n{exc}")
        raise SystemExit(1)
