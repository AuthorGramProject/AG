package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

public class AuthorGramChangelog {
    private static final String PREF_NAME = "authorgram_changelog";
    private static final String KEY_LAST_VERSION = "last_version_code";

    public static void checkAndShow() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int lastVersion = prefs.getInt(KEY_LAST_VERSION, 0);
        int currentVersion = BuildConfig.VERSION_CODE;

        if (lastVersion == 0) {
            // First launch, just save version
            prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply();
            return;
        }

        if (currentVersion > lastVersion) {
            showChangelog(currentVersion);
            prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply();
        }
    }

    private static void showChangelog(int newVersion) {
        String changelog = "";
        
        if (newVersion >= 7038) { // 12.10.1 update
            changelog = "Оновлення AuthorGram 12.10.1\n\n" +
                        "• База Telegram оновлена до 12.10.1.\n" +
                        "• Додано можливість масового збереження фото/відео в Галерею (шукайте в меню виділення повідомлень).\n" +
                        "• Покращено стабільність AuthorGram і роботу камери.\n" +
                        "• Підвищено надійність доставки сповіщень.\n" +
                        "• Вбудовано нову систему захисту та перевірки ліцензій.\n" +
                        "• Виправлено помилки та оптимізовано продуктивність.";
        } else {
            changelog = "AuthorGram оновлено до нової версії!\n\nВиправлено помилки та покращено стабільність.";
        }

        if (TextUtils.isEmpty(changelog)) return;

        final String finalChangelog = changelog;
        
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated()) {
                final int account = i;
                Utilities.stageQueue.postRunnable(() -> {
                    try {
                        TL_update.TL_updateServiceNotification update = new TL_update.TL_updateServiceNotification();
                        update.popup = false;
                        update.flags = 2; // has message
                        update.inbox_date = (int) (System.currentTimeMillis() / 1000);
                        update.message = finalChangelog;
                        update.type = "announcement";
                        update.media = new TLRPC.TL_messageMediaEmpty();

                        TLRPC.TL_updates updates = new TLRPC.TL_updates();
                        updates.updates.add(update);

                        MessagesController.getInstance(account).processUpdates(updates, false);
                    } catch (Exception ignore) {}
                });
            }
        }
    }
}
