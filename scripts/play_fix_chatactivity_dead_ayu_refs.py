#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
PROPS = ROOT / "gradle.properties"

if "APP_PACKAGE=toss.authorgram.apk" not in PROPS.read_text(encoding="utf-8"):
    raise SystemExit("Refusing to patch a non-Play source tree")

text = TARGET.read_text(encoding="utf-8")
original = text

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
text = text.replace(
    hook_call,
    "                // Play: no deleted-history hook is executed.\n",
)

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

forbidden_runtime_markers = (
    "import com.radolyn.ayugram.proprietary.AyuHistoryHook;",
    "import com.radolyn.ayugram.ui.AyuMessageHistory;",
    "import com.radolyn.ayugram.ui.AyuViewDeleted;",
    "AyuHistoryHook.",
    "new AyuMessageHistory(",
    "new AyuViewDeleted(",
)
for forbidden in forbidden_runtime_markers:
    if forbidden in text:
        raise SystemExit(f"Play ChatActivity still has live removed-class reference: {forbidden}")

if text == original:
    print("Play ChatActivity dead Ayu references already removed")
else:
    TARGET.write_text(text, encoding="utf-8")
    print("Removed dead Ayu history/view references from Play ChatActivity")
