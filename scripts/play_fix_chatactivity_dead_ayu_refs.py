#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
PROPS = ROOT / "gradle.properties"

if "APP_PACKAGE=toss.authorgram.apk" not in PROPS.read_text(encoding="utf-8"):
    raise SystemExit("Refusing to patch a non-Play source tree")

text = TARGET.read_text(encoding="utf-8")
original = text

# Deleted-message/history UI classes are physically absent from Play. Remove every
# remaining caller/import so they cannot be restored by a runtime flag.
for line in (
    "import com.radolyn.ayugram.proprietary.AyuHistoryHook;\n",
    "import com.radolyn.ayugram.ui.AyuMessageHistory;\n",
    "import com.radolyn.ayugram.ui.AyuViewDeleted;\n",
):
    text = text.replace(line, "")

text = text.replace(
    "            Pair<Integer, Integer> msgIds = AyuHistoryHook.getMinAndMaxIds(messArr);\n",
    "            // Play: deleted-message history restoration is physically absent.\n"
    "            Pair<Integer, Integer> msgIds = new Pair<>(minVal, minVal);\n",
)

hook_call = (
    "                AyuHistoryHook.doHookAsync(currentAccount, startId, endId, dialogId, limit, "
    "topicId, load_type, isChannelComment, threadMessageId, isTopic);\n"
)
text = text.replace(hook_call, "                // Play: no deleted-history hook is executed.\n")

text = text.replace(
    "            case AyuConstants.OPTION_HISTORY:\n"
    "                presentFragment(new AyuMessageHistory(selectedObject));\n"
    "                break;\n",
    "",
)

view_deleted_block = (
    "        if (showViewDeleted) {\n"
    "            ActionBarMenuSubItem viewDeletedItem = ActionBarMenuItem.addItem(ayuLayout, R.drawable.msg_view_file, getString(R.string.ViewDeleted), false, getResourceProvider());\n"
    "            viewDeletedItem.setOnClickListener(v -> {\n"
    "                dismissMenu.run();\n"
    "                AndroidUtilities.runOnUIThread(() -> presentFragment(new AyuViewDeleted(dialog_id)), 50);\n"
    "            });\n"
    "        }\n\n"
)
text = text.replace(view_deleted_block, "")

text = text.replace(
    "        } else if (id == agbtn_viewDeleted) {\n"
    "            presentFragment(new AyuViewDeleted(dialog_id));\n"
    "        } else if (id == agbtn_bookmarks_manager) {\n",
    "        } else if (id == agbtn_bookmarks_manager) {\n",
)

# Play has no custom encryption-key editor and no outgoing AuthorGram encryption.
# Remove the actual ChatActivity entry point instead of restoring a dormant show()
# API on AuthorGramKeyDialog.
key_click_block = (
    "                // AUTHORGRAM_STEP4_TOGGLE_CLICK\n"
    "                if (id == AUTHORGRAM_KEY_SETTINGS) {\n"
    "                    if (!canUseAuthorGramProtection()) {\n"
    "                        return;\n"
    "                    }\n"
    "                    org.telegram.messenger.authorgram.AuthorGramKeyDialog.show(\n"
    "                            getParentActivity(),\n"
    "                            currentAccount,\n"
    "                            dialog_id,\n"
    "                            ChatActivity.this::refreshAuthorGramProtectionUi\n"
    "                    );\n"
    "                    return;\n"
    "                }\n\n"
)
text = text.replace(key_click_block, "")

key_menu_block = (
    "            // AUTHORGRAM_STEP4_MENU_ITEM\n"
    "            if (canUseAuthorGramProtection()) {\n"
    "                authorGramCryptoItem =\n"
    "                        headerItem.lazilyAddSubItem(\n"
    "                                AUTHORGRAM_KEY_SETTINGS,\n"
    "                                R.drawable.msg_secret,\n"
    "                                getAuthorGramToggleText()\n"
    "                        );\n"
    "            }\n\n"
)
text = text.replace(key_menu_block, "")

text = text.replace(
    "    // AUTHORGRAM_STEP4_UI_FIELDS\n"
    "    private static final int AUTHORGRAM_KEY_SETTINGS = 0x6A470002;\n"
    "    private ActionBarMenuItem.Item authorGramCryptoItem;\n",
    "",
)

# The menu item no longer exists in Play. Keep this helper compile-safe for any
# shared call sites while making it incapable of exposing/re-enabling key UI.
refresh_block = (
    "    private void refreshAuthorGramProtectionUi() {\n"
    "        if (authorGramCryptoItem != null) {\n"
    "            authorGramCryptoItem.setText(\n"
    "                    getAuthorGramToggleText()\n"
    "            );\n"
    "            authorGramCryptoItem.setIcon(\n"
    "                    R.drawable.msg_secret\n"
    "            );\n"
    "        }\n\n"
    "        updateTitle(false);\n"
    "    }\n"
)
text = text.replace(refresh_block, "    private void refreshAuthorGramProtectionUi() {\n        updateTitle(false);\n    }\n")

forbidden_runtime_markers = (
    "import com.radolyn.ayugram.proprietary.AyuHistoryHook;",
    "import com.radolyn.ayugram.ui.AyuMessageHistory;",
    "import com.radolyn.ayugram.ui.AyuViewDeleted;",
    "AyuHistoryHook.",
    "new AyuMessageHistory(",
    "new AyuViewDeleted(",
    "AuthorGramKeyDialog.show(",
    "AUTHORGRAM_KEY_SETTINGS",
    "authorGramCryptoItem",
)
for forbidden in forbidden_runtime_markers:
    if forbidden in text:
        raise SystemExit(f"Play ChatActivity still has live removed-feature reference: {forbidden}")

if text == original:
    print("Play ChatActivity removed-feature references already absent")
else:
    TARGET.write_text(text, encoding="utf-8")
    print("Removed dead Ayu history/view and AuthorGram key-UI references from Play ChatActivity")
