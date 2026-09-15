#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java"
text = PATH.read_text(encoding="utf-8")


def remove_if_block_containing(source: str, token: str, label: str) -> str:
    token_pos = source.find(token)
    if token_pos < 0:
        raise RuntimeError(f"{label}: token not found: {token}")

    # Find the nearest enclosing if at the same AuthorGram/Ayu menu indentation.
    if_pos = source.rfind("\n            if (", 0, token_pos)
    if if_pos < 0:
        raise RuntimeError(f"{label}: enclosing if not found")
    if_pos += 1

    open_brace = source.find("{", if_pos, token_pos + 512)
    if open_brace < 0:
        raise RuntimeError(f"{label}: opening brace not found")

    depth = 0
    in_string = False
    in_char = False
    escaped = False
    i = open_brace
    while i < len(source):
        ch = source[i]
        if escaped:
            escaped = False
        elif ch == "\\" and (in_string or in_char):
            escaped = True
        elif ch == '"' and not in_char:
            in_string = not in_string
        elif ch == "'" and not in_string:
            in_char = not in_char
        elif not in_string and not in_char:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    if end < len(source) and source[end] == "\r":
                        end += 1
                    if end < len(source) and source[end] == "\n":
                        end += 1
                    return source[:if_pos] + source[end:]
        i += 1

    raise RuntimeError(f"{label}: closing brace not found")


# 1) Edit-history menu backed by retained message revisions is Spy-only.
text = remove_if_block_containing(
    text,
    "AyuMessagesController.getInstance().hasAnyRevisions",
    "Ayu edit-history menu",
)

# 2) The whole !isAyuDeleted menu block contains TTL/view-once saving and
#    Ghost Read Message. Remove it as source, rather than gating it false.
outer = "            if (!isAyuDeleted) {"
start = text.find(outer)
if start < 0:
    raise RuntimeError("Ayu TTL/Ghost menu block not found")
open_brace = text.find("{", start)
depth = 0
in_string = False
in_char = False
escaped = False
i = open_brace
end = None
while i < len(text):
    ch = text[i]
    if escaped:
        escaped = False
    elif ch == "\\" and (in_string or in_char):
        escaped = True
    elif ch == '"' and not in_char:
        in_string = not in_string
    elif ch == "'" and not in_string:
        in_char = not in_char
    elif not in_string and not in_char:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                if end < len(text) and text[end] == "\r":
                    end += 1
                if end < len(text) and text[end] == "\n":
                    end += 1
                break
    i += 1
if end is None:
    raise RuntimeError("Ayu TTL/Ghost menu block closing brace not found")
text = text[:start] + text[end:]

# These menu-entry tokens must now be absent from ChatActivity. Handler code is
# audited separately by the main sanitizer/build; the UI cannot expose Spy actions.
for forbidden in (
    "AuthorGramSpyPolicy.isSpyDisabled",
    "AyuMessagesController.getInstance().hasAnyRevisions",
    "GhostReadMessage",
):
    if forbidden in text:
        raise RuntimeError(f"ChatActivity still contains Spy menu token: {forbidden}")

PATH.write_text(text, encoding="utf-8", newline="")
print("Removed edit-history, TTL/view-once save and Ghost Read menu blocks from Play ChatActivity")
