#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(relative):
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError("Missing required file: " + relative)
    return path.read_text(encoding="utf-8")


def write(relative, content):
    path = ROOT / relative
    previous = path.read_text(encoding="utf-8")
    if previous == content:
        return False
    path.write_text(content, encoding="utf-8", newline="")
    return True


def patch_defaults():
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramDefaults.java"
    content = read(relative)
    if "AuthorGramPlayPolicy.applyStartupPolicy(context);" in content:
        return False
    marker = "        if (context == null) {\n            return;\n        }\n"
    replacement = marker + "\n        if (AuthorGramPlayPolicy.isPlayBuild()) {\n            AuthorGramPlayPolicy.applyStartupPolicy(context);\n            return;\n        }\n"
    if content.count(marker) != 1:
        raise RuntimeError("AuthorGramDefaults.apply marker changed")
    return write(relative, content.replace(marker, replacement, 1))


def patch_config_item():
    relative = "TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java"
    content = read(relative)
    changed = False

    import_line = "import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;\n"
    if import_line not in content:
        marker = "import org.telegram.messenger.FileLog;\n"
        if content.count(marker) != 1:
            raise RuntimeError("ConfigItem import marker changed")
        content = content.replace(marker, marker + import_line, 1)
        changed = True

    replacements = [
        ("    public void changed(Object o) {\n        value = o;\n    }\n", "    public void changed(Object o) {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, o);\n    }\n"),
        ("    public boolean toggleConfigBool() {\n        value = !this.Bool();\n        saveConfig();\n        return this.Bool(); // return value after toggle\n    }\n", "    public boolean toggleConfigBool() {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, !this.Bool());\n        saveConfig();\n        return this.Bool(); // return value after policy enforcement\n    }\n"),
        ("    public void setConfigBool(boolean v) {\n        value = v;\n        saveConfig();\n    }\n", "    public void setConfigBool(boolean v) {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, v);\n        saveConfig();\n    }\n"),
        ("    public void setConfigInt(int v) {\n        value = v;\n        saveConfig();\n    }\n", "    public void setConfigInt(int v) {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, v);\n        saveConfig();\n    }\n"),
        ("    public void setConfigLong(Long v) {\n        value = v;\n        saveConfig();\n    }\n", "    public void setConfigLong(Long v) {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, v);\n        saveConfig();\n    }\n"),
        ("    public void setConfigFloat(Float v) {\n        value = v;\n        saveConfig();\n    }\n", "    public void setConfigFloat(Float v) {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, v);\n        saveConfig();\n    }\n"),
        ("    public void setConfigString(String v) {\n        value = Objects.requireNonNullElse(v, \"\");\n        saveConfig();\n    }\n", "    public void setConfigString(String v) {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, Objects.requireNonNullElse(v, \"\"));\n        saveConfig();\n    }\n"),
        ("    public void saveConfig() {\n        synchronized (NekoConfig.sync) {\n", "    public void saveConfig() {\n        value = AuthorGramPlayPolicy.sanitizeConfigValue(key, value);\n        synchronized (NekoConfig.sync) {\n"),
    ]

    for old, new in replacements:
        if new in content:
            continue
        if content.count(old) != 1:
            raise RuntimeError("ConfigItem marker changed: " + old.splitlines()[0])
        content = content.replace(old, new, 1)
        changed = True

    return write(relative, content) if changed else False


def patch_router():
    relative = "TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsRouter.java"
    content = read(relative)
    changed = False

    import_line = "import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;\n"
    if import_line not in content:
        marker = "import org.telegram.messenger.R;\n"
        if content.count(marker) != 1:
            raise RuntimeError("AGSettingsRouter import marker changed")
        content = content.replace(marker, marker + import_line, 1)
        changed = True

    old = "                case \"ghostmode\":\n                case \"ghost\":\n                    fragment = agxFragment = new GhostModeActivity();\n                    break;\n"
    new = "                case \"ghostmode\":\n                case \"ghost\":\n                    if (AuthorGramPlayPolicy.isPlayBuild()) {\n                        unknown.run();\n                        return;\n                    }\n                    fragment = agxFragment = new GhostModeActivity();\n                    break;\n"
    if new not in content:
        if content.count(old) != 1:
            raise RuntimeError("AGSettingsRouter Ghost Mode marker changed")
        content = content.replace(old, new, 1)
        changed = True

    return write(relative, content) if changed else False


def patch_messages_controller():
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java"
    content = read(relative)
    changed = False

    signature = "    public void deleteMessages(ArrayList<Integer> messages, ArrayList<Long> randoms, TLRPC.EncryptedChat encryptedChat, long dialogId, boolean forAll, int mode, boolean cacheOnly, long taskId, TLObject taskRequest, int topicId, boolean movedToScheduled, int movedToScheduledMessageId) {\n"
    guarded = signature + "        if (!org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canDelete(dialogId)) {\n            FileLog.d(\"AuthorGram Play: blocked message deletion in protected dialog\");\n            return;\n        }\n"
    if guarded not in content:
        if content.count(signature) != 1:
            raise RuntimeError("Core deleteMessages signature changed: " + str(content.count(signature)))
        content = content.replace(signature, guarded, 1)
        changed = True

    signature = "    protected void deleteDialog(long did, int first, int onlyHistory, int max_id, boolean revoke, TLRPC.InputPeer peer, long taskId) {\n"
    guarded = signature + "        if (!org.telegram.messenger.authorgram.AuthorGramPlayPolicy.canDelete(did)) {\n            FileLog.d(\"AuthorGram Play: blocked chat/history deletion in protected dialog\");\n            return;\n        }\n"
    if guarded not in content:
        if content.count(signature) != 1:
            raise RuntimeError("Core deleteDialog signature changed: " + str(content.count(signature)))
        content = content.replace(signature, guarded, 1)
        changed = True

    return write(relative, content) if changed else False


def patch_build_integrity():
    relative = "TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramBuildIntegrity.java"
    content = read(relative)
    replacement = "    public static boolean canUseSystemKey() {\n        if (!AuthorGramPlayPolicy.hasEmbeddedSystemKey()) {\n            return false;\n        }\n        if (!BuildConfig.OFFICIAL_BUILD) {\n"
    if replacement in content:
        return False
    marker = "    public static boolean canUseSystemKey() {\n        if (!BuildConfig.OFFICIAL_BUILD) {\n"
    if content.count(marker) != 1:
        raise RuntimeError("AuthorGramBuildIntegrity marker changed")
    return write(relative, content.replace(marker, replacement, 1))


def validate():
    policy = read("TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java")
    for item in ('values.put("hideSponsoredMessage", false)', 'values.put("HideProxySponsorChannel", false)', 'values.put("localPremium", false)', 'values.put("EnableSaveDeletedMessages", false)', 'values.put("EnableSaveEditsHistory", false)', 'values.put("sendReadMessagePackets", true)', 'values.put("ignoreContentRestrictions", false)', "OWNER_DIALOG_ID = 6316376597L"):
        if item not in policy:
            raise RuntimeError("Play policy validation failed: " + item)

    controller = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
    for item in ("blocked message deletion in protected dialog", "blocked chat/history deletion in protected dialog"):
        if controller.count(item) != 1:
            raise RuntimeError("MessagesController validation failed: " + item)

    config = read("TMessagesProj/src/main/java/tw/nekomimi/nekogram/config/ConfigItem.java")
    if config.count("AuthorGramPlayPolicy.sanitizeConfigValue") < 8:
        raise RuntimeError("ConfigItem does not centrally enforce Play locks")


def main():
    operations = sum(int(operation()) for operation in (patch_defaults, patch_config_item, patch_router, patch_messages_controller, patch_build_integrity))
    validate()
    print("AuthorGram strict Play policy patch passed; changed files: " + str(operations))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
