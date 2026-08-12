#!/usr/bin/env python3
import re
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

# Play has no custom encryption-key editor or outgoing AuthorGram encryption.
# Remove the actual menu entry/click path instead of restoring a dormant show() API.
text, click_count = re.subn(
    r"\n\s*// AUTHORGRAM_STEP4_TOGGLE_CLICK\n"
    r"\s*if \(id == AUTHORGRAM_KEY_SETTINGS\) \{.*?\n\s*\}\n\n"
    r"(?=\s*if \(id == -1\))",
    "\n",
    text,
    count=1,
    flags=re.S,
)

text, menu_count = re.subn(
    r"\n\s*// AUTHORGRAM_STEP4_MENU_ITEM\n"
    r"\s*if \(canUseAuthorGramProtection\(\)\) \{.*?\n\s*\}\n\n"
    r"(?=\s*if \(currentUser != null && currentUser\.self && chatMode != MODE_SAVED\))",
    "\n",
    text,
    count=1,
    flags=re.S,
)

# Field/ID are now dead in Play.
text = re.sub(r"\n\s*// AUTHORGRAM_STEP4_UI_FIELDS\n", "\n", text, count=1)
text = re.sub(r"^\s*private static final int AUTHORGRAM_KEY_SETTINGS = 0x6A470002;\n", "", text, count=1, flags=re.M)
text = re.sub(r"^\s*private ActionBarMenuItem\.Item authorGramCryptoItem;\n", "", text, count=1, flags=re.M)

# Remove the stale menu-item refresh body while preserving title refresh behavior.
text, refresh_count = re.subn(
    r"(private void refreshAuthorGramProtectionUi\(\) \{)\n"
    r"\s*if \(authorGramCryptoItem != null\) \{.*?\n\s*\}\n\n"
    r"(\s*updateTitle\(false\);)",
    r"\1\n\2",
    text,
    count=1,
    flags=re.S,
)

# On an already-sanitized ChatActivity the counts may be zero; if the live key UI
# call is still present, however, its structural block must have been removed now.
for forbidden in (
    "import com.radolyn.ayugram.proprietary.AyuHistoryHook;",
    "import com.radolyn.ayugram.ui.AyuMessageHistory;",
    "import com.radolyn.ayugram.ui.AyuViewDeleted;",
    "AyuHistoryHook.",
    "new AyuMessageHistory(",
    "new AyuViewDeleted(",
    "AuthorGramKeyDialog.show(",
    "AUTHORGRAM_KEY_SETTINGS",
    "authorGramCryptoItem",
):
    if forbidden in text:
        raise SystemExit(f"Play ChatActivity still has live removed-feature reference: {forbidden}")

if text == original:
    print("Play ChatActivity removed-feature references already absent")
else:
    TARGET.write_text(text, encoding="utf-8")
    print(
        "Removed Play-only forbidden callers from ChatActivity "
        f"(keyClick={click_count}, keyMenu={menu_count}, refresh={refresh_count})"
    )
