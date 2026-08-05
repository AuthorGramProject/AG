#!/usr/bin/env python3
"""Connect protected AuthorGram badges to UI surfaces with custom renderers."""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAT_HEADER = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/Components/ChatAvatarContainer.java"
PROFILE = ROOT / "TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java"
CHAT_HEADER_MARKER = "AUTHORGRAM_PROTECTED_CHAT_HEADER_BADGE"
PROFILE_MARKER = "AUTHORGRAM_PROTECTED_PROFILE_BADGE"

FORBIDDEN_RAW_IDS = (
    "6316376597",
    "2021861896",
    "2815463434",
    "6802848305",
    "6822670748",
    "8470484374",
    "8154455619",
    "7913929703",
    "8856346711",
    "8357439344",
    "8548193112",
    "8395237407",
    "8925149503",
    "3781500049",
    "4297907963",
)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one anchor, found {count}")
    return text.replace(old, new, 1)


def add_import(text: str, label: str) -> str:
    import_line = "import org.telegram.messenger.authorgram.AuthorGramAuthorBadge;\n"
    if import_line in text:
        return text
    return replace_once(
        text,
        "import org.telegram.messenger.ApplicationLoader;\n",
        "import org.telegram.messenger.ApplicationLoader;\n" + import_line,
        label,
    )


def patch_chat_header() -> None:
    text = CHAT_HEADER.read_text(encoding="utf-8")
    if CHAT_HEADER_MARKER in text:
        validate_chat_header(text)
        return

    text = add_import(text, "ChatAvatarContainer badge import")

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
        "        boolean authorBadge = AuthorGramAuthorBadge.matches(authorBadgeObjectId);\n"
        "        if (!authorBadge\n"
        "                && authorBadgeDrawable != null\n"
        "                && titleTextView.getRightDrawable2() == authorBadgeDrawable) {\n"
        "            titleTextView.setRightDrawable2(null);\n"
        "            rightDrawableIsScamOrVerified = false;\n"
        "            rightDrawable2ContentDescription = null;\n"
        "        }\n\n"
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


def patch_profile() -> None:
    text = PROFILE.read_text(encoding="utf-8")
    if PROFILE_MARKER in text:
        validate_profile(text)
        return

    text = add_import(text, "ProfileActivity badge import")

    legacy_set = (
        "    // AuthorGram: декоративний бейдж розробника\n"
        "    private static final java.util.Set<Long> AUTHOR_BADGE_IDS = new java.util.HashSet<>();\n"
        "    static {\n"
        "        AUTHOR_BADGE_IDS.add(6316376597L);\n"
        "        AUTHOR_BADGE_IDS.add(2021861896L);\n"
        "        AUTHOR_BADGE_IDS.add(2815463434L);\n"
        "    }\n"
    )
    text = replace_once(
        text,
        legacy_set,
        "    // AUTHORGRAM_PROTECTED_PROFILE_BADGE: IDs are resolved by signed-build policy.\n",
        "ProfileActivity legacy raw badge set",
    )

    text, replacements = re.subn(
        r"AUTHOR_BADGE_IDS\.contains\(([^)\n]+)\)",
        r"AuthorGramAuthorBadge.matches(\1)",
        text,
    )
    if replacements < 1:
        raise SystemExit("ProfileActivity contains no author badge render branch to protect")

    validate_profile(text)
    PROFILE.write_text(text, encoding="utf-8", newline="")


def validate_chat_header(text: str) -> None:
    required = (
        CHAT_HEADER_MARKER,
        "import org.telegram.messenger.authorgram.AuthorGramAuthorBadge;",
        "AuthorGramAuthorBadge.matches(authorBadgeObjectId)",
        "titleTextView.getRightDrawable2() == authorBadgeDrawable",
        "R.drawable.ic_author_badge",
        "private Drawable authorBadgeDrawable;",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"Chat header author_badge validation failed: {missing}")
    if text.count(CHAT_HEADER_MARKER) != 1:
        raise SystemExit("Chat header author_badge marker must occur exactly once")


def validate_profile(text: str) -> None:
    required = (
        PROFILE_MARKER,
        "import org.telegram.messenger.authorgram.AuthorGramAuthorBadge;",
        "AuthorGramAuthorBadge.matches(",
        "R.drawable.ic_author_badge",
        "private Drawable authorBadgeDrawable;",
    )
    missing = [item for item in required if item not in text]
    if missing:
        raise SystemExit(f"Profile author_badge validation failed: {missing}")
    if "AUTHOR_BADGE_IDS" in text:
        raise SystemExit("ProfileActivity still contains the legacy author badge set")
    leaked = [raw for raw in FORBIDDEN_RAW_IDS if raw in text]
    if leaked:
        raise SystemExit(f"ProfileActivity still contains raw author badge IDs: {leaked}")


def main() -> None:
    patch_chat_header()
    patch_profile()
    print("Protected AuthorGram profile and chat-header badge patch passed")


if __name__ == "__main__":
    main()
