#!/usr/bin/env python3
"""Connect protected AuthorGram badges to UI surfaces with custom badge renderers."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT_HEADER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java"
MARKER = "AUTHORGRAM_PROTECTED_CHAT_HEADER_BADGE"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_chat_header() -> None:
    text = CHAT_HEADER.read_text(encoding="utf-8")
    if MARKER in text:
        validate_chat_header(text)
        return

    text = replace_once(
        text,
        "import org.telegram.messenger.ApplicationLoader;\n",
        "import org.telegram.messenger.ApplicationLoader;\n"
        "import org.telegram.messenger.authorgram.AuthorGramAuthorBadge;\n",
        "ChatAvatarContainer badge import",
    )

    text = replace_once(
        text,
        "        titleTextView.setText(value);\n"
        "        titleTextView.setScrollNonFitText(scrollable || isCentered());\n\n"
        "        if (scam || fake) {\n",
        "        titleTextView.setText(value);\n"
        "        titleTextView.setScrollNonFitText(scrollable || isCentered());\n\n"
        "        // AUTHORGRAM_PROTECTED_CHAT_HEADER_BADGE\n"
        "        long authorBadgeObjectId = 0;\n"
        "        if (parentFragment != null) {\n"
        "            TLRPC.User badgeUser = parentFragment.getCurrentUser();\n"
        "            TLRPC.Chat badgeChat = parentFragment.getCurrentChat();\n"
        "            if (badgeUser != null) {\n"
        "                authorBadgeObjectId = badgeUser.id;\n"
        "            } else if (badgeChat != null) {\n"
        "                authorBadgeObjectId = badgeChat.id;\n"
        "            }\n"
        "        }\n"
        "        boolean authorBadge = AuthorGramAuthorBadge.matches(authorBadgeObjectId);\n\n"
        "        if (scam || fake) {\n",
        "ChatAvatarContainer badge target",
    )

    text = replace_once(
        text,
        "        } else if (verified) {\n"
        "            verifiedBackground = getResources().getDrawable(R.drawable.verified_area).mutate();\n",
        "        } else if (authorBadge) {\n"
        "            if (authorBadgeDrawable == null) {\n"
        "                authorBadgeDrawable = getResources().getDrawable(R.drawable.ic_author_badge).mutate();\n"
        "            }\n"
        "            titleTextView.setRightDrawable2(authorBadgeDrawable);\n"
        "            rightDrawableIsScamOrVerified = true;\n"
        "            rightDrawable2ContentDescription = getString(R.string.AccDescrVerified);\n"
        "        } else if (verified) {\n"
        "            verifiedBackground = getResources().getDrawable(R.drawable.verified_area).mutate();\n",
        "ChatAvatarContainer custom badge branch",
    )

    text = replace_once(
        text,
        "    private Drawable emojiStatusDefaultDrawable;\n"
        "    private Drawable verifiedBackground;\n"
        "    private Drawable verifiedCheck;\n",
        "    private Drawable emojiStatusDefaultDrawable;\n"
        "    private Drawable authorBadgeDrawable;\n"
        "    private Drawable verifiedBackground;\n"
        "    private Drawable verifiedCheck;\n",
        "ChatAvatarContainer badge drawable field",
    )

    validate_chat_header(text)
    CHAT_HEADER.write_text(text, encoding="utf-8", newline="")


def validate_chat_header(text: str) -> None:
    required = (
        MARKER,
        "import org.telegram.messenger.authorgram.AuthorGramAuthorBadge;",
        "AuthorGramAuthorBadge.matches(authorBadgeObjectId)",
        "R.drawable.ic_author_badge",
        "private Drawable authorBadgeDrawable;",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"Chat header author_badge validation failed: {missing}")
    if text.count("AUTHORGRAM_PROTECTED_CHAT_HEADER_BADGE") != 1:
        raise SystemExit("Chat header author_badge marker must occur exactly once")


def main() -> None:
    patch_chat_header()
    print("Protected AuthorGram chat-header badge patch passed")


if __name__ == "__main__":
    main()
