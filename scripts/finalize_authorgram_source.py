#!/usr/bin/env python3
"""Finalize AuthorGram source branding, package identity, and encrypted-reply safety.

The script is intentionally idempotent. It is used by the single final release
workflow after all automatic build workflows have been removed.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAY_PACKAGE = "toss.authorgram.apk"
MAIN_PACKAGE = "fork.risin42.nagramx"

LEGACY_BRAND = re.compile(
    r"(?<![A-Za-z0-9_])(?:NekoGram|Nekogram|Nagram|Ngram)(?:\s*X(?:F)?)?(?![A-Za-z0-9_])"
)
LEGACY_MISC = re.compile(r"(?<![A-Za-z0-9_])(?:TOSS|NASAtings)(?![A-Za-z0-9_])")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')
XML_QUOTED = re.compile(r'"([^"\n]*)"')
XML_TEXT = re.compile(r">([^<]+)<")
URL = re.compile(r"https?://[^\s)>\"]+")

SKIP_PARTS = {
    ".git",
    ".gradle",
    ".idea",
    "build",
    "generated",
    "third_party",
    "third-party",
}
LEGAL_FILENAMES = {
    "LICENSE",
    "LICENSE.txt",
    "COPYING",
    "COPYING.txt",
    "NOTICE",
    "NOTICE.txt",
}


def read(path: str) -> str:
    target = ROOT / path
    if not target.is_file():
        raise RuntimeError(f"Missing required file: {path}")
    return target.read_text(encoding="utf-8")


def write(path: str, content: str) -> bool:
    target = ROOT / path
    previous = target.read_text(encoding="utf-8") if target.exists() else None
    if previous == content:
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8", newline="")
    return True


def replace_brand(value: str) -> str:
    value = LEGACY_BRAND.sub("AuthorGram", value)
    value = LEGACY_MISC.sub("AuthorGram", value)
    return value


def replace_outside_urls(value: str) -> str:
    parts: list[str] = []
    cursor = 0
    for match in URL.finditer(value):
        parts.append(replace_brand(value[cursor:match.start()]))
        parts.append(match.group(0))
        cursor = match.end()
    parts.append(replace_brand(value[cursor:]))
    return "".join(parts)


def should_skip(path: Path) -> bool:
    if path.name in LEGAL_FILENAMES:
        return True
    return any(part in SKIP_PARTS for part in path.parts)


def set_gradle_property(package_id: str) -> bool:
    path = "gradle.properties"
    content = read(path)
    updated, count = re.subn(
        r"(?m)^APP_PACKAGE=.*$",
        f"APP_PACKAGE={package_id}",
        content,
        count=1,
    )
    if count != 1:
        raise RuntimeError("gradle.properties must contain exactly one APP_PACKAGE line")
    return write(path, updated)


def ensure_android_build_gradle(role: str) -> bool:
    path = "TMessagesProj/build.gradle"
    content = read(path)
    changed = False

    if "def telegramAdBlockingEnabled = APP_PACKAGE != 'toss.authorgram.apk'" not in content:
        needle = "def officialCode = APP_VERSION_CODE\n"
        if needle not in content:
            raise RuntimeError("Unable to locate officialCode in TMessagesProj/build.gradle")
        content = content.replace(
            needle,
            needle + "def telegramAdBlockingEnabled = APP_PACKAGE != 'toss.authorgram.apk'\n",
            1,
        )
        changed = True

    ad_field = (
        "        buildConfigField 'boolean', 'TELEGRAM_AD_BLOCKING_ENABLED', "
        "telegramAdBlockingEnabled.toString()\n"
    )
    if "'TELEGRAM_AD_BLOCKING_ENABLED'" not in content:
        needle = "        buildConfigField 'boolean', 'OFFICIAL_BUILD', 'false'\n"
        if needle not in content:
            raise RuntimeError("Unable to locate OFFICIAL_BUILD BuildConfig field")
        content = content.replace(needle, needle + ad_field, 1)
        changed = True

    artifact_name = "AuthorGram-Play" if role == "play" else "AuthorGram-Main"
    updated, count = re.subn(
        r"String gramName = 'AuthorGram(?:-[A-Za-z]+)?'",
        f"String gramName = '{artifact_name}'",
        content,
        count=1,
    )
    if count != 1:
        raise RuntimeError("Unable to normalize AuthorGram artifact name")
    if updated != content:
        content = updated
        changed = True

    if changed:
        return write(path, content)
    return False


def patch_encrypted_quote_reply() -> bool:
    path = "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java"
    content = read(path)
    marker = "AuthorGram encrypted messages always use a normal reply without a plaintext quote"
    if marker in content:
        return False

    signature = (
        "    public TLRPC.InputReplyTo createReplyInput(TLRPC.InputPeer sendToPeer, "
        "int replyToMsgId, int topMessageId, ChatActivity.ReplyQuote replyQuote) {\n"
    )
    if signature not in content:
        raise RuntimeError("Unable to locate SendMessagesHelper.createReplyInput")

    safeguard = signature + (
        "        /* AuthorGram encrypted messages always use a normal reply without a plaintext quote. */\n"
        "        if (replyQuote != null\n"
        "                && replyQuote.message != null\n"
        "                && org.telegram.messenger.authorgram.AuthorGramMessageMeta.isKnownEncrypted(\n"
        "                        currentAccount,\n"
        "                        replyQuote.message\n"
        "                )) {\n"
        "            replyQuote = null;\n"
        "        }\n"
    )
    content = content.replace(signature, safeguard, 1)
    return write(path, content)


def rebrand_xml(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")

    def text_node(match: re.Match[str]) -> str:
        value = match.group(1)
        if "://" in value:
            return match.group(0)
        return ">" + replace_brand(value) + "<"

    # Attribute values include resource IDs, class names and other internal contracts.
    # Rebrand only text nodes so the UI changes without breaking resource references.
    updated = XML_TEXT.sub(text_node, content)
    if updated == content:
        return False
    path.write_text(updated, encoding="utf-8", newline="")
    return True


def rebrand_source_literals(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")

    def literal(match: re.Match[str]) -> str:
        token = match.group(0)
        value = token[1:-1]
        if "://" in value or "/" in value or "\\" in value:
            return token
        return '"' + replace_brand(value) + '"'

    updated = STRING_LITERAL.sub(literal, content)
    if updated == content:
        return False
    path.write_text(updated, encoding="utf-8", newline="")
    return True


def rebrand_document(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")
    updated = replace_outside_urls(content)
    if updated == content:
        return False
    path.write_text(updated, encoding="utf-8", newline="")
    return True


def rebrand_visible_sources() -> int:
    changed = 0
    for path in ROOT.rglob("*"):
        if not path.is_file() or should_skip(path):
            continue
        suffix = path.suffix.lower()
        try:
            if suffix == ".xml" and "res" in path.parts:
                changed += int(rebrand_xml(path))
            elif suffix in {".java", ".kt", ".kts"} and "TMessagesProj" in path.parts:
                changed += int(rebrand_source_literals(path))
            elif suffix in {".md", ".txt"}:
                changed += int(rebrand_document(path))
        except UnicodeDecodeError:
            continue
    return changed


def update_parity_guard() -> bool:
    path = "scripts/authorgram_parity_guard.py"
    content = read(path)
    content = content.replace(
        'ALLOWED_DIFFERENCES = {"gradle.properties"}',
        'ALLOWED_DIFFERENCES = {"gradle.properties", "TMessagesProj/release.keystore"}',
    )
    content = content.replace("APP_PACKAGE=top.authorche.authorgram", f"APP_PACKAGE={MAIN_PACKAGE}")
    content = content.replace(
        '"APP_PACKAGE=top.authorche.authorgram", "APP_PACKAGE=AUTHORGRAM_PACKAGE"',
        f'"APP_PACKAGE={MAIN_PACKAGE}", "APP_PACKAGE=AUTHORGRAM_PACKAGE"',
    )
    return write(path, content)


def update_guard() -> bool:
    path = "scripts/authorgram_guard.py"
    content = read(path)
    quote_check = (
        '    send_helper = read("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")\n'
        '    require(\n'
        '        "AuthorGram encrypted messages always use a normal reply without a plaintext quote" in send_helper,\n'
        '        "Encrypted-message quote prevention is missing from SendMessagesHelper",\n'
        '        failures,\n'
        '    )\n\n'
    )
    if "Encrypted-message quote prevention is missing from SendMessagesHelper" not in content:
        needle = "    interceptor = read(\n        \"TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java\"\n    )\n\n"
        if needle not in content:
            raise RuntimeError("Unable to extend scripts/authorgram_guard.py")
        content = content.replace(needle, needle + quote_check, 1)

    if "TELEGRAM_AD_BLOCKING_ENABLED" not in content:
        needle = "    require(\"String gramName = 'AuthorGram\" in build_gradle, \"Artifact name is not AuthorGram\", failures)\n"
        replacement = needle + (
            "    require(\"TELEGRAM_AD_BLOCKING_ENABLED\" in build_gradle, "
            "\"Compile-time Telegram ad policy is missing\", failures)\n"
        )
        if needle not in content:
            raise RuntimeError("Unable to extend build.gradle guard")
        content = content.replace(needle, replacement, 1)

    return write(path, content)


def remove_play_keystore(role: str) -> bool:
    if role != "play":
        return False
    target = ROOT / "TMessagesProj/release.keystore"
    if not target.exists():
        return False
    target.unlink()
    return True


def ensure_gitignore() -> bool:
    path = ROOT / ".gitignore"
    content = path.read_text(encoding="utf-8") if path.exists() else ""
    required = "TMessagesProj/release.keystore"
    lines = content.splitlines()
    if required in lines:
        return False
    if content and not content.endswith("\n"):
        content += "\n"
    content += required + "\n"
    path.write_text(content, encoding="utf-8", newline="")
    return True


def scan_legacy_visible_brand() -> list[str]:
    failures: list[str] = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or should_skip(path):
            continue
        suffix = path.suffix.lower()
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue

        candidates: list[str] = []
        if suffix == ".xml" and "res" in path.parts:
            candidates.extend(
                value for value in XML_TEXT.findall(content) if "://" not in value
            )
        elif suffix in {".java", ".kt", ".kts"} and "TMessagesProj" in path.parts:
            for token in STRING_LITERAL.findall(content):
                value = token[1:-1]
                if "://" not in value and "/" not in value and "\\" not in value:
                    candidates.append(value)
        elif suffix in {".md", ".txt"}:
            candidates.append(URL.sub("", content))
        else:
            continue

        for value in candidates:
            if LEGACY_BRAND.search(value) or LEGACY_MISC.search(value):
                failures.append(f"{path.relative_to(ROOT)}: {value[:160]!r}")
                if len(failures) >= 50:
                    return failures
    return failures


def validate(role: str, package_id: str) -> None:
    failures: list[str] = []
    properties = read("gradle.properties")
    if f"APP_PACKAGE={package_id}" not in properties:
        failures.append(f"Wrong APP_PACKAGE for {role}: expected {package_id}")

    build_gradle = read("TMessagesProj/build.gradle")
    expected_artifact = "AuthorGram-Play" if role == "play" else "AuthorGram-Main"
    if f"String gramName = '{expected_artifact}'" not in build_gradle:
        failures.append(f"Wrong artifact brand for {role}")
    if "TELEGRAM_AD_BLOCKING_ENABLED" not in build_gradle:
        failures.append("TELEGRAM_AD_BLOCKING_ENABLED BuildConfig field is missing")

    send_helper = read("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")
    if "AuthorGram encrypted messages always use a normal reply without a plaintext quote" not in send_helper:
        failures.append("Encrypted reply quote safeguard is missing")

    legacy = scan_legacy_visible_brand()
    failures.extend("Visible legacy brand remains in " + item for item in legacy)

    key_dialog = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramKeyDialog.java"
    )
    for forbidden in ("AuthorGramGenerateKey", "AuthorGramExportKey", "ClipboardManager", "ClipData"):
        if forbidden in key_dialog:
            failures.append(f"Obsolete raw-key UI remains: {forbidden}")

    if role == "play" and (ROOT / "TMessagesProj/release.keystore").exists():
        failures.append("Play branch must not track the Main release keystore")

    if failures:
        raise RuntimeError("\n".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", choices=("dev", "main", "play"), required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    expected = PLAY_PACKAGE if args.role == "play" else MAIN_PACKAGE
    if args.package != expected:
        raise SystemExit(f"Package {args.package!r} is invalid for role {args.role!r}; expected {expected!r}")

    if not args.check:
        changes = 0
        changes += int(set_gradle_property(args.package))
        changes += int(ensure_android_build_gradle(args.role))
        changes += int(patch_encrypted_quote_reply())
        changes += rebrand_visible_sources()
        changes += int(update_parity_guard())
        changes += int(update_guard())
        changes += int(remove_play_keystore(args.role))
        changes += int(ensure_gitignore())
        print(f"AuthorGram finalizer changed {changes} file operation(s) for {args.role}")

    validate(args.role, args.package)
    print(f"AuthorGram source validation passed for {args.role} ({args.package})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"AuthorGram finalization failed:\n{exc}", file=sys.stderr)
        raise SystemExit(1)
