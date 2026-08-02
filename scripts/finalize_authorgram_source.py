#!/usr/bin/env python3
"""Finalize and validate AuthorGram source without touching build outputs or native trees."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN_PACKAGE = "fork.risin42.nagramx"
PLAY_PACKAGE = "toss.authorgram.apk"
LEGACY_BRAND = re.compile(
    r"(?<![A-Za-z0-9_])(?:NekoGram|Nekogram|Nagram|Ngram)(?:\s*X(?:F)?)?(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
LEGACY_MISC = re.compile(r"(?<![A-Za-z0-9_])(?:TOSS|NASAtings)(?![A-Za-z0-9_])")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')
XML_TEXT = re.compile(r">([^<]+)<")
INTERNAL_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+")
DYNAMIC_ARTIFACT_LINE = (
    "            String gramName = APP_PACKAGE == 'toss.authorgram.apk' "
    "? 'AuthorGram-Play' : 'AuthorGram-Main'"
)


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"Missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def write(relative: str, value: str) -> bool:
    path = ROOT / relative
    previous = path.read_text(encoding="utf-8")
    if previous == value:
        return False
    path.write_text(value, encoding="utf-8", newline="")
    return True


def replace_brand(value: str) -> str:
    value = LEGACY_BRAND.sub("AuthorGram", value)
    return LEGACY_MISC.sub("AuthorGram", value)


def patch_package(package_id: str) -> bool:
    content = read("gradle.properties")
    updated, count = re.subn(
        r"(?m)^APP_PACKAGE=.*$",
        f"APP_PACKAGE={package_id}",
        content,
        count=1,
    )
    if count != 1:
        raise RuntimeError("gradle.properties must contain exactly one APP_PACKAGE line")
    return write("gradle.properties", updated)


def patch_build_gradle() -> bool:
    relative = "TMessagesProj/build.gradle"
    content = read(relative)
    updated, count = re.subn(
        r"(?m)^\s*String gramName = .*$",
        DYNAMIC_ARTIFACT_LINE,
        content,
        count=1,
    )
    if count != 1:
        raise RuntimeError("Unable to locate the AuthorGram artifact-name assignment")
    if "def telegramAdBlockingEnabled = APP_PACKAGE != 'toss.authorgram.apk'" not in updated:
        needle = "def officialCode = APP_VERSION_CODE\n"
        if needle not in updated:
            raise RuntimeError("Unable to locate officialCode in TMessagesProj/build.gradle")
        updated = updated.replace(
            needle,
            needle + "def telegramAdBlockingEnabled = APP_PACKAGE != 'toss.authorgram.apk'\n",
            1,
        )
    if "'TELEGRAM_AD_BLOCKING_ENABLED'" not in updated:
        needle = "        buildConfigField 'boolean', 'OFFICIAL_BUILD', 'false'\n"
        if needle not in updated:
            raise RuntimeError("Unable to locate OFFICIAL_BUILD BuildConfig field")
        updated = updated.replace(
            needle,
            needle
            + "        buildConfigField 'boolean', 'TELEGRAM_AD_BLOCKING_ENABLED', "
            + "telegramAdBlockingEnabled.toString()\n",
            1,
        )
    return write(relative, updated)


def patch_encrypted_reply() -> bool:
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java"
    content = read(relative)
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
    return write(relative, content.replace(signature, safeguard, 1))


def source_roots() -> list[Path]:
    roots: list[Path] = []
    for candidate in (
        ROOT / "TMessagesProj/src/main/res",
        ROOT / "TMessagesProj/src/debug/res",
        ROOT / "TMessagesProj/src/staging/res",
        ROOT / "TMessagesProj/src/release/res",
        ROOT / "TMessagesProj/src/main/java",
        ROOT / "TMessagesProj/src/main/kotlin",
    ):
        if candidate.exists():
            roots.append(candidate)
    return roots


def rebrand_xml(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")

    def replace_node(match: re.Match[str]) -> str:
        value = match.group(1)
        if "://" in value or INTERNAL_IDENTIFIER.fullmatch(value.strip()):
            return match.group(0)
        return ">" + replace_brand(value) + "<"

    updated = XML_TEXT.sub(replace_node, content)
    if updated == content:
        return False
    path.write_text(updated, encoding="utf-8", newline="")
    return True


def rebrand_source(path: Path) -> bool:
    content = path.read_text(encoding="utf-8")

    def replace_literal(match: re.Match[str]) -> str:
        token = match.group(0)
        value = token[1:-1]
        if (
            "://" in value
            or "/" in value
            or "\\" in value
            or INTERNAL_IDENTIFIER.fullmatch(value)
            or (path.name == "SessionCell.java" and ("Nagram X" in value or "NagramX" in value))
        ):
            return token
        return '"' + replace_brand(value) + '"'

    updated = STRING_LITERAL.sub(replace_literal, content)
    if updated == content:
        return False
    path.write_text(updated, encoding="utf-8", newline="")
    return True


def rebrand_visible_text() -> int:
    changed = 0
    for root in source_roots():
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            try:
                if path.suffix.lower() == ".xml" and "res" in path.parts:
                    changed += int(rebrand_xml(path))
                elif path.suffix.lower() in {".java", ".kt", ".kts"}:
                    changed += int(rebrand_source(path))
            except UnicodeDecodeError:
                continue
    return changed


def visible_legacy_hits() -> list[str]:
    hits: list[str] = []
    for root in source_roots():
        for path in root.rglob("*"):
            if not path.is_file():
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            candidates: list[str] = []
            if path.suffix.lower() == ".xml" and "res" in path.parts:
                candidates.extend(XML_TEXT.findall(content))
            elif path.suffix.lower() in {".java", ".kt", ".kts"}:
                for token in STRING_LITERAL.findall(content):
                    value = token[1:-1]
                    if (
                        "://" not in value
                        and "/" not in value
                        and "\\" not in value
                        and not INTERNAL_IDENTIFIER.fullmatch(value)
                        and not (
                            path.name == "SessionCell.java"
                            and ("Nagram X" in value or "NagramX" in value)
                        )
                    ):
                        candidates.append(value)
            for value in candidates:
                if LEGACY_BRAND.search(value) or LEGACY_MISC.search(value):
                    hits.append(f"{path.relative_to(ROOT)}: {value[:180]!r}")
                    if len(hits) >= 50:
                        return hits
    return hits


def build_type_release_body(build_gradle: str) -> str | None:
    build_types_start = build_gradle.find("\n    buildTypes {")
    if build_types_start < 0:
        return None
    release_start = build_gradle.find("\n        release {", build_types_start)
    if release_start < 0:
        return None
    body_start = release_start + len("\n        release {")
    release_end = build_gradle.find("\n        }", body_start)
    if release_end < 0:
        return None
    return build_gradle[body_start:release_end]


def validate(role: str, package_id: str) -> None:
    failures: list[str] = []
    if f"APP_PACKAGE={package_id}" not in read("gradle.properties"):
        failures.append(f"Wrong APP_PACKAGE for {role}: expected {package_id}")

    build_gradle = read("TMessagesProj/build.gradle")
    if DYNAMIC_ARTIFACT_LINE.strip() not in build_gradle:
        failures.append("Main/Play artifact naming is not package-driven from common source")
    if "TELEGRAM_AD_BLOCKING_ENABLED" not in build_gradle:
        failures.append("Compile-time Telegram ad policy is missing")
    release_body = build_type_release_body(build_gradle)
    if release_body is None:
        failures.append("buildTypes.release is missing")
    else:
        for required in (
            "debuggable = false",
            "minifyEnabled = true",
            "shrinkResources = true",
            "signingConfig = signingConfigs.release",
        ):
            if required not in release_body:
                failures.append(f"Release invariant missing: {required}")

    send_helper = read("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")
    if "AuthorGram encrypted messages always use a normal reply without a plaintext quote" not in send_helper:
        failures.append("Encrypted-message quote prevention is missing from SendMessagesHelper")

    interceptor = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramCryptoInterceptor.java"
    )
    for required in ("quote_text = null", "quote_entities.clear()", "quote_offset = 0"):
        if required not in interceptor:
            failures.append(f"Encrypted-reply sanitizer is missing: {required}")

    key_dialog = read(
        "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramKeyDialog.java"
    )
    for forbidden in (
        "AuthorGramGenerateKey",
        "AuthorGramExportKey",
        "ClipboardManager",
        "ClipData",
    ):
        if forbidden in key_dialog:
            failures.append(f"Obsolete raw-key UI remains: {forbidden}")

    failures.extend("Visible legacy brand remains in " + hit for hit in visible_legacy_hits())

    if role == "play" and (ROOT / "TMessagesProj/release.keystore").exists():
        failures.append("Play source tree must not contain the Main release keystore")

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
        raise RuntimeError(
            f"Package {args.package!r} is invalid for role {args.role!r}; expected {expected!r}"
        )

    operations = 0
    if not args.check:
        operations += int(patch_package(args.package))
        operations += int(patch_build_gradle())
        operations += int(patch_encrypted_reply())
        operations += rebrand_visible_text()

    validate(args.role, args.package)
    print(
        f"AuthorGram source validation passed for {args.role} ({args.package}); "
        f"changed operations: {operations}"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(f"AuthorGram finalization failed:\n{exc}", file=sys.stderr)
        raise SystemExit(1)
