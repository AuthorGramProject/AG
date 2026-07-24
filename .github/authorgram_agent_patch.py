from pathlib import Path
import re

ROOT = Path.cwd()


def require(path: str) -> Path:
    p = ROOT / path
    if not p.is_file():
        raise RuntimeError(f"Missing required file: {path}")
    return p


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def add_strings(path: str, values: dict[str, str]) -> None:
    p = require(path)
    text = p.read_text("utf-8")
    additions = []
    for key, value in values.items():
        if re.search(rf'<string\s+name="{re.escape(key)}"\b', text):
            continue
        escaped = (value.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("'", "\\'"))
        additions.append(f'    <string name="{key}">{escaped}</string>')
    if additions:
        text = replace_once(
            text,
            "</resources>",
            "\n" + "\n".join(additions) + "\n</resources>",
            f"append strings to {path}",
        )
        p.write_text(text, "utf-8")


interceptor = require(
    "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"
)
text = interceptor.read_text("utf-8")
text = replace_once(
    text,
    """                    encryptOutgoingText(\n                            sendRequest.message,\n""",
    """                    encryptOutgoingText(\n                            account,\n                            dialogId,\n                            sendRequest.message,\n""",
    "send encryption call",
)
text = replace_once(
    text,
    """                    encryptOutgoingText(\n                            editRequest.message,\n""",
    """                    encryptOutgoingText(\n                            account,\n                            dialogId,\n                            editRequest.message,\n""",
    "edit encryption call",
)
text = replace_once(
    text,
    """                AuthorGramCrypto.decryptTextOrNull(\n                        message.message\n                );\n""",
    """                AuthorGramChatCrypto.decryptTextOrNull(\n                        account,\n                        MessageObject.getDialogId(message),\n                        message.message\n                );\n""",
    "incoming dialog-aware decryption",
)
text = replace_once(
    text,
    """    private static boolean encryptOutgoingText(\n            String plaintext,\n            EncryptedTextConsumer consumer\n    ) {\n""",
    """    private static boolean encryptOutgoingText(\n            int account,\n            long dialogId,\n            String plaintext,\n            EncryptedTextConsumer consumer\n    ) {\n""",
    "encrypt helper signature",
)
text = replace_once(
    text,
    """                AuthorGramCrypto.encryptText(\n                        plaintext\n                );\n""",
    """                AuthorGramChatCrypto.encryptText(\n                        account,\n                        dialogId,\n                        plaintext\n                );\n""",
    "encrypt helper routing",
)
interceptor.write_text(text, "utf-8")

chat = require("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
text = chat.read_text("utf-8")
text = replace_once(
    text,
    "private static final int AUTHORGRAM_CRYPTO_TOGGLE = 0x6A470001;",
    """private static final int AUTHORGRAM_CRYPTO_TOGGLE = 0x6A470001;\n    private static final int AUTHORGRAM_KEY_SETTINGS = 0x6A470002;""",
    "AuthorGram key menu constant",
)
menu_anchor = """                authorGramCryptoItem =\n                        headerItem.lazilyAddSubItem(\n                                AUTHORGRAM_CRYPTO_TOGGLE,\n                                R.drawable.msg_secret,\n                                getAuthorGramToggleText()\n                        );\n"""
menu_replacement = menu_anchor + """                headerItem.lazilyAddSubItem(\n                        AUTHORGRAM_KEY_SETTINGS,\n                        R.drawable.authorgram_key,\n                        LocaleController.getString(R.string.AuthorGramKeySettings)\n                );\n"""
text = replace_once(text, menu_anchor, menu_replacement, "AuthorGram key menu item")

needle = "AuthorGramChatState.toggle("
toggle_call = text.find(needle)
if toggle_call < 0:
    raise RuntimeError("AuthorGram toggle call not found in ChatActivity")
branch_start = text.rfind("AUTHORGRAM_CRYPTO_TOGGLE", 0, toggle_call)
if branch_start < 0:
    raise RuntimeError("AuthorGram toggle branch not found")
open_brace = text.find("{", branch_start, toggle_call)
if open_brace < 0:
    raise RuntimeError("AuthorGram toggle opening brace not found")


def matching_brace(source: str, opening: int) -> int:
    depth = 0
    state = "code"
    index = opening
    while index < len(source):
        current = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if current == "/" and following == "/":
                state = "line"
                index += 2
                continue
            if current == "/" and following == "*":
                state = "block"
                index += 2
                continue
            if current == '"':
                state = "string"
            elif current == "'":
                state = "char"
            elif current == "{":
                depth += 1
            elif current == "}":
                depth -= 1
                if depth == 0:
                    return index
        elif state == "line":
            if current == "\n":
                state = "code"
        elif state == "block":
            if current == "*" and following == "/":
                state = "code"
                index += 2
                continue
        elif state == "string":
            if current == "\\":
                index += 2
                continue
            if current == '"':
                state = "code"
        elif state == "char":
            if current == "\\":
                index += 2
                continue
            if current == "'":
                state = "code"
        index += 1
    raise RuntimeError("Matching brace not found")


close_brace = matching_brace(text, open_brace)
if "AUTHORGRAM_KEY_SETTINGS" in text[close_brace:close_brace + 500]:
    raise RuntimeError("AuthorGram key click handler already appears nearby")
indent_start = text.rfind("\n", 0, branch_start) + 1
indent = text[indent_start:branch_start]
handler = (
    " else if (id == AUTHORGRAM_KEY_SETTINGS) {\n"
    + indent + "    org.telegram.messenger.authorgram.AuthorGramKeyDialog.show(\n"
    + indent + "            getParentActivity(),\n"
    + indent + "            currentAccount,\n"
    + indent + "            dialog_id\n"
    + indent + "    );\n"
    + indent + "}"
)
text = text[:close_brace + 1] + handler + text[close_brace + 1:]
chat.write_text(text, "utf-8")

EN = {
    "AuthorGramKeySettings": "AuthorGram encryption key",
    "AuthorGramSystemKeyLocked": "This chat always uses the AuthorGram system key. A custom key cannot be configured here.",
    "AuthorGramCustomKeyActive": "A custom 256-bit key is active for this chat. Old rotated keys are kept locally so earlier messages remain readable.",
    "AuthorGramSystemKeyActive": "This chat currently uses the AuthorGram system key.",
    "AuthorGramGenerateKey": "Generate custom key",
    "AuthorGramGenerateKeyInfo": "A random 256-bit key will be created for this chat. The other participant must use the same key.",
    "AuthorGramRotateKey": "Rotate custom key",
    "AuthorGramRotateKeyWarning": "A new key will replace the current key. The previous key remains in local history for decrypting older messages.",
    "AuthorGramImportKey": "Import custom key",
    "AuthorGramImportKeyInfo": "Enter a 256-bit key as 64 hexadecimal characters.",
    "AuthorGramExportKey": "Show and copy custom key",
    "AuthorGramRemoveKey": "Remove custom keys",
    "AuthorGramRemoveKeyWarning": "The current key and its local history will be deleted. Messages encrypted with those keys may no longer be readable.",
    "AuthorGramKeyInputHint": "64-character hex key",
    "AuthorGramKeySaved": "AuthorGram key saved",
    "AuthorGramKeyRemoved": "Custom AuthorGram keys removed",
    "AuthorGramInvalidKey": "Invalid key. Use exactly 256 bits.",
    "AuthorGramNoCustomKey": "No custom key is configured",
    "AuthorGramKeyOperationFailed": "The key operation failed",
}
UK = {
    "AuthorGramKeySettings": "Ключ шифрування AuthorGram",
    "AuthorGramSystemKeyLocked": "Цей чат завжди використовує системний ключ AuthorGram. Власний ключ тут налаштувати неможливо.",
    "AuthorGramCustomKeyActive": "Для цього чату активний власний 256-бітний ключ. Попередні ключі зберігаються локально для читання старих повідомлень.",
    "AuthorGramSystemKeyActive": "Цей чат зараз використовує системний ключ AuthorGram.",
    "AuthorGramGenerateKey": "Створити власний ключ",
    "AuthorGramGenerateKeyInfo": "Для цього чату буде створено випадковий 256-бітний ключ. Співрозмовник має використовувати той самий ключ.",
    "AuthorGramRotateKey": "Замінити власний ключ",
    "AuthorGramRotateKeyWarning": "Новий ключ замінить поточний. Попередній залишиться в локальній історії для розшифрування старих повідомлень.",
    "AuthorGramImportKey": "Імпортувати власний ключ",
    "AuthorGramImportKeyInfo": "Введіть 256-бітний ключ як 64 шістнадцяткові символи.",
    "AuthorGramExportKey": "Показати й скопіювати ключ",
    "AuthorGramRemoveKey": "Видалити власні ключі",
    "AuthorGramRemoveKeyWarning": "Поточний ключ і його локальну історію буде видалено. Повідомлення, зашифровані цими ключами, можуть більше не читатися.",
    "AuthorGramKeyInputHint": "64-символьний hex-ключ",
    "AuthorGramKeySaved": "Ключ AuthorGram збережено",
    "AuthorGramKeyRemoved": "Власні ключі AuthorGram видалено",
    "AuthorGramInvalidKey": "Некоректний ключ. Потрібно рівно 256 біт.",
    "AuthorGramNoCustomKey": "Власний ключ не налаштовано",
    "AuthorGramKeyOperationFailed": "Операцію з ключем не виконано",
}
DE = {
    "AuthorGramKeySettings": "AuthorGram-Verschlüsselungsschlüssel",
    "AuthorGramSystemKeyLocked": "Dieser Chat verwendet immer den AuthorGram-Systemschlüssel. Ein eigener Schlüssel kann hier nicht eingerichtet werden.",
    "AuthorGramCustomKeyActive": "Für diesen Chat ist ein eigener 256-Bit-Schlüssel aktiv. Frühere Schlüssel bleiben lokal erhalten, damit ältere Nachrichten lesbar bleiben.",
    "AuthorGramSystemKeyActive": "Dieser Chat verwendet derzeit den AuthorGram-Systemschlüssel.",
    "AuthorGramGenerateKey": "Eigenen Schlüssel erzeugen",
    "AuthorGramGenerateKeyInfo": "Für diesen Chat wird ein zufälliger 256-Bit-Schlüssel erzeugt. Die andere Person muss denselben Schlüssel verwenden.",
    "AuthorGramRotateKey": "Eigenen Schlüssel wechseln",
    "AuthorGramRotateKeyWarning": "Ein neuer Schlüssel ersetzt den aktuellen. Der vorherige Schlüssel bleibt lokal zum Entschlüsseln älterer Nachrichten gespeichert.",
    "AuthorGramImportKey": "Eigenen Schlüssel importieren",
    "AuthorGramImportKeyInfo": "Gib einen 256-Bit-Schlüssel als 64 Hex-Zeichen ein.",
    "AuthorGramExportKey": "Schlüssel anzeigen und kopieren",
    "AuthorGramRemoveKey": "Eigene Schlüssel entfernen",
    "AuthorGramRemoveKeyWarning": "Der aktuelle Schlüssel und sein lokaler Verlauf werden gelöscht. Damit verschlüsselte Nachrichten sind danach möglicherweise nicht mehr lesbar.",
    "AuthorGramKeyInputHint": "64-stelliger Hex-Schlüssel",
    "AuthorGramKeySaved": "AuthorGram-Schlüssel gespeichert",
    "AuthorGramKeyRemoved": "Eigene AuthorGram-Schlüssel entfernt",
    "AuthorGramInvalidKey": "Ungültiger Schlüssel. Es werden genau 256 Bit benötigt.",
    "AuthorGramNoCustomKey": "Kein eigener Schlüssel eingerichtet",
    "AuthorGramKeyOperationFailed": "Schlüsselvorgang fehlgeschlagen",
}
add_strings("TMessagesProj/src/main/res/values/strings.xml", EN)
add_strings("TMessagesProj/src/main/res/values-uk/strings.xml", UK)
add_strings("TMessagesProj/src/main/res/values-de/strings.xml", DE)

final_interceptor = interceptor.read_text("utf-8")
if final_interceptor.count("AuthorGramChatCrypto.encryptText(") != 1:
    raise RuntimeError("Dialog-aware outgoing crypto routing validation failed")
if final_interceptor.count("AuthorGramChatCrypto.decryptTextOrNull(") != 1:
    raise RuntimeError("Dialog-aware incoming crypto routing validation failed")
final_chat = chat.read_text("utf-8")
if final_chat.count("AUTHORGRAM_KEY_SETTINGS") != 3:
    raise RuntimeError(
        "Expected key settings constant, menu item and click handler; found "
        + str(final_chat.count("AUTHORGRAM_KEY_SETTINGS"))
    )

print("AUTHORGRAM PER-CHAT KEY PATCH: PASS")
