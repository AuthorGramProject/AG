package org.telegram.messenger.authorgram;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.ui.ActionBar.AlertDialog;

public class AuthorGramChangelog {
    private static final String PREF_NAME = "authorgram_changelog";
    private static final String KEY_LAST_VERSION = "last_version_code";

    public static void checkAndShow(Activity activity) {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int lastVersion = prefs.getInt(KEY_LAST_VERSION, 0);
        int currentVersion = BuildConfig.VERSION_CODE;

        if (lastVersion == 0) {
            // First launch ever, just save version
            prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply();
            return;
        }

        if (currentVersion > lastVersion) {
            showChangelog(activity, currentVersion);
            prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply();
        }
    }

    private static void showChangelog(Activity activity, int newVersion) {
        String changelog = "";
        
        if (newVersion >= 7038) { // 12.10.1 update
            changelog = "Оновлення AuthorGram 12.10.1\n\n" +
                        "• База Telegram оновлена до 12.10.1.\n" +
                        "• Додано можливість масового збереження фото/відео в Галерею.\n" +
                        "• Покращено стабільність AuthorGram і роботу камери.\n" +
                        "• Підвищено надійність доставки сповіщень.\n" +
                        "• Вбудовано нову систему захисту та перевірки ліцензій.\n" +
                        "• Виправлено помилки та оптимізовано продуктивність.";
        } else {
            changelog = "AuthorGram оновлено до нової версії!\n\nВиправлено помилки та покращено стабільність.";
        }

        if (TextUtils.isEmpty(changelog)) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Оновлення AuthorGram");
        builder.setMessage(changelog);
        builder.setPositiveButton("Чудово!", null);
        builder.show();
    }
}
