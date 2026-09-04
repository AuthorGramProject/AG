package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * AuthorGram defaults and vanilla Telegram baseline configuration.
 *
 * Defaults are loaded from assets/authorgram_defaults.json.
 * Enforces vanilla Telegram behavior by default (no forced tabs, no centered title,
 * no forced stories disabling, standard drawer and input fields).
 */
public final class AuthorGramDefaults {

    private static final String VANILLA_MIGRATION_MARKER =
            "AUTHORGRAM_VANILLA_DEFAULTS_EPOCH_20260904";
    private static final String SYSTEM_ACCOUNT_DEFAULT_MIGRATION_MARKER =
            "AUTHORGRAM_SYSTEM_ACCOUNT_DEFAULT_EPOCH_20260817";
    private static final String NEKO_PREFERENCES = "nkmrcfg";
    private static final String ASSET_DEFAULTS_FILE = "authorgram_defaults.json";

    private AuthorGramDefaults() {
    }

    public static void apply(Context context) {
        apply(context, false);
    }

    public static void apply(Context context, boolean forceReset) {
        if (context == null) {
            return;
        }

        if (AuthorGramPlayPolicy.isPlayBuild()) {
            AuthorGramPlayPolicy.applyStartupPolicy(context);
        }

        SharedPreferences nkmrPrefs = context.getSharedPreferences(NEKO_PREFERENCES, Context.MODE_PRIVATE);
        boolean needsVanillaMigration = !nkmrPrefs.getBoolean(VANILLA_MIGRATION_MARKER, false);

        if (forceReset || needsVanillaMigration) {
            cleanUpAggressiveModOverrides(context);
        }

        migrateSystemAccountDefault(context);
        loadAndApplyJsonDefaults(context, forceReset);

        if (forceReset || needsVanillaMigration) {
            nkmrPrefs.edit().putBoolean(VANILLA_MIGRATION_MARKER, true).commit();
        }
    }

    /**
     * Reverts aggressive mod UI overrides so the app behaves and looks like vanilla Telegram by default.
     */
    private static void cleanUpAggressiveModOverrides(Context context) {
        SharedPreferences nkmrPrefs = context.getSharedPreferences(NEKO_PREFERENCES, Context.MODE_PRIVATE);
        SharedPreferences.Editor nkmrEditor = nkmrPrefs.edit();

        nkmrEditor.putInt("MainTabsDisplayMode", 0);
        nkmrEditor.remove("MainTabsOrder");
        nkmrEditor.remove("MainTabsHideTitles");
        nkmrEditor.remove("MainTabsHideContacts");
        nkmrEditor.putBoolean("CenterActionBarTitle", false);
        nkmrEditor.putInt("CenterActionBarTitleType", 0);
        nkmrEditor.putBoolean("iOSMessageInputField", false);
        nkmrEditor.putBoolean("iOSMessageMenu", false);
        nkmrEditor.putBoolean("DisableStories", false);
        nkmrEditor.putBoolean("DrawerItemCalls", true);
        nkmrEditor.putBoolean("DrawerItemSaved", true);
        nkmrEditor.putBoolean("DrawerItemRecentChats", false);
        nkmrEditor.putBoolean("DrawerItemNewGroup", true);

        nkmrEditor.commit();

        SharedPreferences mainPrefs = context.getSharedPreferences("mainconfig", Context.MODE_PRIVATE);
        SharedPreferences.Editor mainEditor = mainPrefs.edit();
        mainEditor.remove("useThreeLinesLayout");
        mainEditor.remove("archiveHidden");
        mainEditor.commit();
    }

    /**
     * One-time repair for installations that inherited the historical broken
     * true default. Later user choices are preserved by the migration marker.
     */
    private static void migrateSystemAccountDefault(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(NEKO_PREFERENCES, Context.MODE_PRIVATE);
        if (preferences.getBoolean(
                SYSTEM_ACCOUNT_DEFAULT_MIGRATION_MARKER,
                false
        )) {
            return;
        }
        preferences.edit()
                .putBoolean("DisableSystemAccount", false)
                .putBoolean(SYSTEM_ACCOUNT_DEFAULT_MIGRATION_MARKER, true)
                .commit();
    }

    /**
     * Loads settings from assets/authorgram_defaults.json and applies them to SharedPreferences.
     */
    private static void loadAndApplyJsonDefaults(Context context, boolean forceReset) {
        try (InputStream is = context.getAssets().open(ASSET_DEFAULTS_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject root = new JSONObject(sb.toString());
            Iterator<String> spKeys = root.keys();
            while (spKeys.hasNext()) {
                String spName = spKeys.next();
                JSONObject spObj = root.optJSONObject(spName);
                if (spObj == null) {
                    continue;
                }

                SharedPreferences preferences = context.getSharedPreferences(spName, Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();

                Iterator<String> configKeys = spObj.keys();
                while (configKeys.hasNext()) {
                    String rawKey = configKeys.next();
                    String actualKey = rawKey;
                    boolean isExplicitLong = false;
                    boolean isExplicitFloat = false;

                    if (rawKey.endsWith("_long")) {
                        actualKey = rawKey.substring(0, rawKey.length() - 5);
                        isExplicitLong = true;
                    } else if (rawKey.endsWith("_float")) {
                        actualKey = rawKey.substring(0, rawKey.length() - 6);
                        isExplicitFloat = true;
                    }

                    if (!forceReset && preferences.contains(actualKey)) {
                        continue;
                    }

                    Object val = spObj.get(rawKey);
                    if (val instanceof Boolean) {
                        editor.putBoolean(actualKey, (Boolean) val);
                    } else if (isExplicitLong) {
                        if (val instanceof Number) {
                            editor.putLong(actualKey, ((Number) val).longValue());
                        } else {
                            editor.putLong(actualKey, Long.parseLong(val.toString()));
                        }
                    } else if (isExplicitFloat) {
                        if (val instanceof Number) {
                            editor.putFloat(actualKey, ((Number) val).floatValue());
                        } else {
                            editor.putFloat(actualKey, Float.parseFloat(val.toString()));
                        }
                    } else if (val instanceof Integer) {
                        editor.putInt(actualKey, (Integer) val);
                    } else if (val instanceof Long) {
                        editor.putLong(actualKey, (Long) val);
                    } else if (val instanceof Double || val instanceof Float) {
                        editor.putFloat(actualKey, ((Number) val).floatValue());
                    } else if (val instanceof String) {
                        editor.putString(actualKey, (String) val);
                    }
                }
                editor.commit();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
