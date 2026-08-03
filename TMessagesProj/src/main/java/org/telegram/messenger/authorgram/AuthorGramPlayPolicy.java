package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.UserConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Central policy boundary for the Play Market package. */
public final class AuthorGramPlayPolicy {
    public static final String PLAY_PACKAGE = "toss.authorgram.apk";
    public static final long OWNER_DIALOG_ID = 6316376597L;

    private static final String NEKO_PREFS = "nkmrcfg";
    private static final String CRYPTO_PREFS = "authorgram_crypto";
    private static final String CHAT_KEYS_PREFS = "authorgram_chat_keys_v1";
    private static final String CRYPTO_PREFIX = "e2ee_enabled_";

    private static final Map<String, Object> LOCKED_CONFIGS;

    static {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();

        // Telegram-sponsored content and proxy sponsor channels remain visible.
        values.put("hideSponsoredMessage", false);
        values.put("HideProxySponsorChannel", false);

        // Never emulate or unlock Telegram Premium locally.
        values.put("localPremium", false);
        values.put("HidePremiumSection", false);
        values.put("UnlimitedPinnedDialogs", false);
        values.put("UnlimitedFavoredStickers", false);

        // Disable Ayu/Spy interception and retention of deleted/edited content.
        values.put("EnableSaveDeletedMessages", false);
        values.put("EnableSaveEditsHistory", false);
        values.put("SaveLocalLastSeen", false);
        values.put("SaveDeletedMessageForBot", false);
        values.put("SaveDeletedMessageForBotUser", false);
        values.put("MessageSavingSaveMedia", false);

        // Restore ordinary Telegram network-presence behaviour.
        values.put("sendReadMessagePackets", true);
        values.put("sendReadStoriesPackets", true);
        values.put("sendOnlinePackets", true);
        values.put("sendUploadProgress", true);
        values.put("sendOfflinePacketAfterOnline", false);
        values.put("markReadAfterSend", true);
        values.put("showGhostInDrawer", false);
        values.put("showGhostModeStatus", false);

        // Do not bypass Telegram content restrictions in the Play package.
        values.put("ignoreContentRestrictions", false);

        LOCKED_CONFIGS = Collections.unmodifiableMap(values);
    }

    private AuthorGramPlayPolicy() {
    }

    public static boolean isPlayBuild() {
        return PLAY_PACKAGE.equals(BuildConfig.APPLICATION_ID);
    }

    public static boolean hasEmbeddedSystemKey() {
        String value = BuildConfig.AUTHORGRAM_SYSTEM_KEY_HEX;
        return !isPlayBuild() && value != null && value.length() == 64;
    }

    public static boolean isOwnerDialog(long dialogId) {
        return dialogId == OWNER_DIALOG_ID;
    }

    public static boolean isEncryptionForbidden(long dialogId) {
        return isPlayBuild() && isOwnerDialog(dialogId);
    }

    public static boolean canEnableEncryption(int account, long dialogId) {
        if (dialogId == 0 || isEncryptionForbidden(dialogId)) {
            return false;
        }
        return !isPlayBuild()
                || AuthorGramChatKeyStore.hasCustomKey(account, dialogId);
    }

    public static boolean canDelete(long dialogId) {
        return !isPlayBuild() || !isOwnerDialog(dialogId);
    }

    public static boolean isRestrictedSettingsSection(String section) {
        if (!isPlayBuild() || section == null) {
            return false;
        }
        String normalized = section.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("spy")
                || normalized.equals("ayuspy")
                || normalized.equals("privacy")
                || normalized.equals("ghost")
                || normalized.equals("ghostmode");
    }

    public static boolean isLockedConfig(String key) {
        return isPlayBuild() && key != null && LOCKED_CONFIGS.containsKey(key);
    }

    public static Object sanitizeConfigValue(String key, Object requested) {
        if (!isLockedConfig(key)) {
            return requested;
        }
        return LOCKED_CONFIGS.get(key);
    }

    /**
     * Runs before NekoConfig and NaConfig load. It also migrates existing Play
     * installations that received unsafe AuthorGram defaults in an older APK.
     */
    public static void applyStartupPolicy(Context context) {
        if (!isPlayBuild() || context == null) {
            return;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(NEKO_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, Object> entry : LOCKED_CONFIGS.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(entry.getKey(), (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(entry.getKey(), (Float) value);
            } else if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            }
        }
        editor.commit();

        SharedPreferences crypto =
                context.getSharedPreferences(CRYPTO_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor cryptoEditor = crypto.edit();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            cryptoEditor.remove(CRYPTO_PREFIX + account + "_" + OWNER_DIALOG_ID);
        }
        cryptoEditor.commit();

        SharedPreferences keys =
                context.getSharedPreferences(CHAT_KEYS_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor keysEditor = keys.edit();
        String suffix = "_" + OWNER_DIALOG_ID;
        for (String key : keys.getAll().keySet()) {
            if (key.contains(suffix)) {
                keysEditor.remove(key);
            }
        }
        keysEditor.commit();
    }
}
