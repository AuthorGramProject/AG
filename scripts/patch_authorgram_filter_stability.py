#!/usr/bin/env python3
"""Bound AuthorGram regex execution time and filter-cache memory.

The regex filter is evaluated from chat/dialog rendering paths. Java's backtracking
regex engine has no native timeout, so one pathological user expression can pin
the UI thread. The guard below gives the complete filter pass for one message a
small shared time budget. It uses a deadline-aware CharSequence, which lets the
Java Pattern engine abort during backtracking instead of spawning uninterruptible
worker threads.

The old AGFilterCache bounded messages per dialog but kept an unbounded map of
dialog caches. A long-lived process visiting many chats therefore retained one
LRU pair per dialog. Replace it with a globally bounded dialog LRU.
"""

from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILTER = ROOT / "TMessagesProj/src/main/java/toss/authorgram/filters/AGFilter.java"
CACHE = ROOT / "TMessagesProj/src/main/java/toss/authorgram/filters/AGFilterCache.java"

REGEX_MARKER = "AUTHORGRAM_BOUNDED_REGEX_EVALUATION"
CACHE_MARKER = "AUTHORGRAM_BOUNDED_FILTER_DIALOG_CACHE"

CACHE_SOURCE = '''package toss.authorgram.filters;

import androidx.collection.LruCache;

import org.telegram.messenger.MessageObject;

/**
 * Runtime verdict cache for AuthorGram filters.
 *
 * AUTHORGRAM_BOUNDED_FILTER_DIALOG_CACHE
 *
 * Both levels are bounded. The previous implementation bounded entries inside
 * each dialog but retained an unlimited ConcurrentHashMap of dialog caches,
 * allowing memory use to grow for the lifetime of the process.
 */
final class AGFilterCache {
    private static final int DIALOG_LIMIT = 32;
    private static final int PER_DIALOG_LIMIT = 1000;
    private static final int PER_DIALOG_GROUP_LIMIT = 500;
    private static final Object CACHE_LOCK = new Object();
    private static final LruCache<Long, DialogCache> dialogCaches =
            new LruCache<>(DIALOG_LIMIT);

    private AGFilterCache() {
    }

    private static final class DialogCache {
        final LruCache<Integer, Boolean> messages = new LruCache<>(PER_DIALOG_LIMIT);
        final LruCache<Long, Boolean> groups = new LruCache<>(PER_DIALOG_GROUP_LIMIT);
    }

    private static DialogCache getDialogCache(long dialogId, boolean create) {
        synchronized (CACHE_LOCK) {
            DialogCache cache = dialogCaches.get(dialogId);
            if (cache == null && create) {
                cache = new DialogCache();
                dialogCaches.put(dialogId, cache);
            }
            return cache;
        }
    }

    static Boolean get(long dialogId, MessageObject msg, MessageObject.GroupedMessages group) {
        if (msg == null) {
            return null;
        }
        DialogCache cache = getDialogCache(dialogId, false);
        if (cache == null) {
            return null;
        }

        long groupId = group != null ? group.groupId : msg.getGroupId();
        // Group-aware evaluation is higher-confidence than a message-only result.
        if (groupId != 0 && group != null) {
            synchronized (cache.groups) {
                Boolean value = cache.groups.get(groupId);
                if (value != null) {
                    return value;
                }
            }
        }

        synchronized (cache.messages) {
            Boolean value = cache.messages.get(msg.getId());
            if (value != null) {
                return value;
            }
        }

        if (groupId != 0 && group == null) {
            synchronized (cache.groups) {
                return cache.groups.get(groupId);
            }
        }
        return null;
    }

    static void put(long dialogId, MessageObject msg, MessageObject.GroupedMessages group, boolean value) {
        if (msg == null) {
            return;
        }
        DialogCache cache = getDialogCache(dialogId, true);
        synchronized (cache.messages) {
            cache.messages.put(msg.getId(), value);
        }

        long groupId = group != null ? group.groupId : msg.getGroupId();
        if (groupId != 0) {
            synchronized (cache.groups) {
                cache.groups.put(groupId, value);
            }
        }
    }

    static void invalidate(long dialogId, int msgId) {
        DialogCache cache = getDialogCache(dialogId, false);
        if (cache == null) {
            return;
        }
        synchronized (cache.messages) {
            cache.messages.remove(msgId);
        }
    }

    static void invalidateGroup(long dialogId, long groupId) {
        if (groupId == 0) {
            return;
        }
        DialogCache cache = getDialogCache(dialogId, false);
        if (cache == null) {
            return;
        }
        synchronized (cache.groups) {
            cache.groups.remove(groupId);
        }
    }

    static void clearDialog(long dialogId) {
        synchronized (CACHE_LOCK) {
            dialogCaches.remove(dialogId);
        }
    }

    static void clearAll() {
        synchronized (CACHE_LOCK) {
            dialogCaches.evictAll();
        }
    }
}
'''


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: anchor count is {count}, expected 1")
    return text.replace(old, new, 1)


def patch_regex_filter() -> None:
    text = read(FILTER)
    if REGEX_MARKER in text:
        return

    old_fields = """public class AGFilter {\n    private static final Object cacheLock = new Object();\n"""
    new_fields = """public class AGFilter {\n    private static final Object cacheLock = new Object();\n\n    // AUTHORGRAM_BOUNDED_REGEX_EVALUATION\n    // One pathological Java regex must never monopolize chat/dialog rendering.\n    // The deadline is shared by all filters evaluated for one message.\n    private static final long REGEX_EVALUATION_BUDGET_NANOS = 8_000_000L;\n    private static final int MAX_FILTER_MATCH_TEXT_CHARS = 65_536;\n    private static final int MAX_FILTER_PATTERN_CHARS = 4_096;\n\n    private static final class RegexBudgetExceededException extends RuntimeException {\n        private static final long serialVersionUID = 1L;\n    }\n\n    private static final class BudgetedCharSequence implements CharSequence {\n        private final CharSequence source;\n        private final long deadlineNanos;\n        private int operations;\n\n        BudgetedCharSequence(CharSequence source, long deadlineNanos) {\n            this.source = source;\n            this.deadlineNanos = deadlineNanos;\n        }\n\n        private void checkBudget() {\n            // nanoTime() on every charAt is unnecessary overhead. Backtracking\n            // performs many charAt calls, so checking every 64 operations still\n            // aborts a runaway expression quickly.\n            if ((++operations & 63) == 0 && System.nanoTime() > deadlineNanos) {\n                throw new RegexBudgetExceededException();\n            }\n        }\n\n        @Override\n        public int length() {\n            if (System.nanoTime() > deadlineNanos) {\n                throw new RegexBudgetExceededException();\n            }\n            return source.length();\n        }\n\n        @Override\n        public char charAt(int index) {\n            checkBudget();\n            return source.charAt(index);\n        }\n\n        @Override\n        public CharSequence subSequence(int start, int end) {\n            if (System.nanoTime() > deadlineNanos) {\n                throw new RegexBudgetExceededException();\n            }\n            return new BudgetedCharSequence(source.subSequence(start, end), deadlineNanos);\n        }\n\n        @Override\n        public String toString() {\n            if (System.nanoTime() > deadlineNanos) {\n                throw new RegexBudgetExceededException();\n            }\n            return source.toString();\n        }\n    }\n"""
    text = replace_once(text, old_fields, new_fields, "regex runtime fields")

    old_match = """    private static boolean isFilterMatch(FilterModel filter, CharSequence text) {\n        if (filter == null || !filter.enabled || filter.pattern == null || TextUtils.isEmpty(text)) {\n            return false;\n        }\n        boolean matched = filter.pattern.matcher(text).find();\n        return filter.reversed ? !matched : matched;\n    }\n\n    private static boolean isFilteredInternal(CharSequence text, long dialogId) {\n"""
    new_match = """    private static boolean isFilterMatch(\n            FilterModel filter,\n            CharSequence text,\n            long deadlineNanos\n    ) {\n        if (filter == null || !filter.enabled || filter.pattern == null || TextUtils.isEmpty(text)) {\n            return false;\n        }\n        if (System.nanoTime() > deadlineNanos) {\n            return false;\n        }\n\n        CharSequence matchText = text;\n        if (matchText.length() > MAX_FILTER_MATCH_TEXT_CHARS) {\n            matchText = matchText.subSequence(0, MAX_FILTER_MATCH_TEXT_CHARS);\n        }\n\n        try {\n            boolean matched = filter.pattern\n                    .matcher(new BudgetedCharSequence(matchText, deadlineNanos))\n                    .find();\n            return filter.reversed ? !matched : matched;\n        } catch (RegexBudgetExceededException timeout) {\n            // Fail open for visibility: a broken filter may miss this message,\n            // but it can never freeze the application or invert into a match.\n            if (!filter.runtimeBudgetWarningLogged) {\n                filter.runtimeBudgetWarningLogged = true;\n                FileLog.e(\n                        \"AuthorGram: regex filter exceeded the rendering budget and was skipped: \"\n                                + (TextUtils.isEmpty(filter.id) ? \"<unknown>\" : filter.id)\n                );\n            }\n            return false;\n        } catch (StackOverflowError regexStackOverflow) {\n            // Some deeply nested Java regexes recurse before the time guard can\n            // observe enough charAt calls. Never let such a filter crash Android.\n            if (!filter.runtimeBudgetWarningLogged) {\n                filter.runtimeBudgetWarningLogged = true;\n                FileLog.e(\n                        \"AuthorGram: regex filter overflowed the matcher stack and was skipped: \"\n                                + (TextUtils.isEmpty(filter.id) ? \"<unknown>\" : filter.id)\n                );\n            }\n            return false;\n        }\n    }\n\n    private static boolean isFilteredInternal(CharSequence text, long dialogId) {\n        final long deadlineNanos = System.nanoTime() + REGEX_EVALUATION_BUDGET_NANOS;\n"""
    text = replace_once(text, old_match, new_match, "regex matcher")

    old_calls = "isFilterMatch(pattern, text)"
    call_count = text.count(old_calls)
    if call_count != 2:
        raise SystemExit(f"regex matcher call count is {call_count}, expected 2")
    text = text.replace(old_calls, "isFilterMatch(pattern, text, deadlineNanos)")

    old_model = """        public boolean reversed;\n        public Pattern pattern;\n\n        // Legacy fields for deserialization migration only\n"""
    new_model = """        public boolean reversed;\n        public Pattern pattern;\n        transient boolean runtimeBudgetWarningLogged;\n\n        // Legacy fields for deserialization migration only\n"""
    text = replace_once(text, old_model, new_model, "regex model runtime state")

    old_build = """        public void buildPattern() {\n            var flags = Pattern.MULTILINE;\n            if (caseInsensitive) {\n                flags |= Pattern.CASE_INSENSITIVE;\n            }\n            try {\n                pattern = Pattern.compile(regex, flags);\n            } catch (Exception e) {\n                pattern = null;\n                FileLog.e(e);\n            }\n        }\n"""
    new_build = """        public void buildPattern() {\n            if (regex != null && regex.length() > MAX_FILTER_PATTERN_CHARS) {\n                pattern = null;\n                FileLog.e(\"AuthorGram: oversized regex filter was disabled: \" + regex.length());\n                return;\n            }\n            var flags = Pattern.MULTILINE;\n            if (caseInsensitive) {\n                flags |= Pattern.CASE_INSENSITIVE;\n            }\n            try {\n                pattern = Pattern.compile(regex, flags);\n            } catch (Exception e) {\n                pattern = null;\n                FileLog.e(e);\n            }\n        }\n"""
    text = replace_once(text, old_build, new_build, "regex pattern size guard")
    write(FILTER, text)


def patch_filter_cache() -> None:
    current = read(CACHE)
    if CACHE_MARKER in current and current == CACHE_SOURCE:
        return
    write(CACHE, CACHE_SOURCE)


def validate() -> None:
    filter_text = read(FILTER)
    cache_text = read(CACHE)

    required_filter = (
        REGEX_MARKER,
        "REGEX_EVALUATION_BUDGET_NANOS = 8_000_000L",
        "MAX_FILTER_MATCH_TEXT_CHARS = 65_536",
        "MAX_FILTER_PATTERN_CHARS = 4_096",
        "new BudgetedCharSequence(matchText, deadlineNanos)",
        "catch (RegexBudgetExceededException timeout)",
        "catch (StackOverflowError regexStackOverflow)",
        "final long deadlineNanos = System.nanoTime() + REGEX_EVALUATION_BUDGET_NANOS;",
        "isFilterMatch(pattern, text, deadlineNanos)",
        "transient boolean runtimeBudgetWarningLogged;",
        "regex.length() > MAX_FILTER_PATTERN_CHARS",
    )
    for token in required_filter:
        if token not in filter_text:
            raise SystemExit(f"bounded regex invariant missing: {token}")
    if "filter.pattern.matcher(text).find()" in filter_text:
        raise SystemExit("unguarded direct regex match remains")

    required_cache = (
        CACHE_MARKER,
        "DIALOG_LIMIT = 32",
        "LruCache<Long, DialogCache> dialogCaches",
        "dialogCaches.evictAll();",
    )
    for token in required_cache:
        if token not in cache_text:
            raise SystemExit(f"bounded filter-cache invariant missing: {token}")
    forbidden_cache = (
        "ConcurrentHashMap<Long, LruCache<Integer, Boolean>>",
        "ConcurrentHashMap<Long, LruCache<Long, Boolean>>",
    )
    for token in forbidden_cache:
        if token in cache_text:
            raise SystemExit(f"unbounded per-dialog cache registry remains: {token}")

    print("AuthorGram filter runtime stability passed")


def apply() -> None:
    patch_regex_filter()
    patch_filter_cache()
    validate()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("apply", "validate"), default="apply")
    args = parser.parse_args()
    if args.mode == "apply":
        apply()
    else:
        validate()


if __name__ == "__main__":
    main()
