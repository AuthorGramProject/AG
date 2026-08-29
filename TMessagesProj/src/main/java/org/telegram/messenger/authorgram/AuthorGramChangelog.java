package org.telegram.messenger.authorgram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Shows a changelog as a service notification (from user 777000)
 * when the app is updated to a new version code.
 */
public class AuthorGramChangelog {
    private static final String PREF_NAME = "authorgram_changelog";
    private static final String KEY_LAST_VERSION = "last_version_code";

    public static void checkAndShow() {
        SharedPreferences prefs = ApplicationLoader.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int lastVersion = prefs.getInt(KEY_LAST_VERSION, 0);
        int currentVersion = BuildConfig.VERSION_CODE;

        if (lastVersion >= currentVersion) {
            return; // already shown or same version
        }

        // Save immediately to avoid double-sending
        prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply();

        // Don't show on very first install (never had any version)
        if (lastVersion == 0) {
            return;
        }

        String changelog = buildChangelog(currentVersion);
        if (TextUtils.isEmpty(changelog)) return;

        sendAsServiceNotification(changelog);
    }

    private static String buildChangelog(int newVersion) {
        String lang = LocaleController.getInstance().getCurrentLocale().getLanguage();

        if (newVersion >= 7038) { // 12.10.1
            if ("uk".equals(lang)) {
                return "🔄 Оновлення AuthorGram 12.10.1\n\n" +
                       "✨ Що нового:\n" +
                       "• Ядро Telegram оновлено до 12.10.1\n" +
                       "• Масове збереження фото/відео в Галерею — виділіть повідомлення → меню → \"Зберегти в Галерею\"\n" +
                       "• Яскравий анімований бейдж автора\n" +
                       "• Повний переклад налаштувань AuthorGram українською\n\n" +
                       "🛡 Безпека:\n" +
                       "• Нова система перевірки ліцензій\n" +
                       "• Захист від несанкціонованих збірок\n\n" +
                       "🐛 Виправлення:\n" +
                       "• Покращено стабільність та продуктивність\n" +
                       "• Виправлено роботу камери\n\n" +
                       "📢 Канал проєкту: @AuGrChannel\n" +
                       "🌐 authorche.top/cu";
            } else if ("de".equals(lang)) {
                return "🔄 AuthorGram 12.10.1 Update\n\n" +
                       "✨ Neuheiten:\n" +
                       "• Telegram-Kern auf 12.10.1 aktualisiert\n" +
                       "• Mehrere Fotos/Videos auf einmal in der Galerie speichern\n" +
                       "• Animiertes Autoren-Abzeichen\n" +
                       "• Vollständige ukrainische Übersetzung der Einstellungen\n\n" +
                       "🛡 Sicherheit:\n" +
                       "• Neues Lizenzprüfungssystem\n" +
                       "• Schutz vor unbefugten Builds\n\n" +
                       "🐛 Fehlerbehebungen:\n" +
                       "• Verbesserte Stabilität und Leistung\n\n" +
                       "📢 Projektkanal: @AuGrChannel\n" +
                       "🌐 authorche.top/cu";
            } else {
                return "🔄 AuthorGram 12.10.1 Update\n\n" +
                       "✨ What's new:\n" +
                       "• Telegram core updated to 12.10.1\n" +
                       "• Batch save photos/videos to Gallery — select messages → menu → \"Save to Gallery\"\n" +
                       "• Animated author badge\n" +
                       "• Full Ukrainian translation of AuthorGram settings\n\n" +
                       "🛡 Security:\n" +
                       "• New license verification system\n" +
                       "• Protection against unauthorized builds\n\n" +
                       "🐛 Bug fixes:\n" +
                       "• Improved stability and performance\n\n" +
                       "📢 Project channel: @AuGrChannel\n" +
                       "🌐 authorche.top/cu";
            }
        }

        return null;
    }

    private static void sendAsServiceNotification(String text) {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (!UserConfig.getInstance(i).isClientActivated()) continue;
            final int account = i;
            Utilities.stageQueue.postRunnable(() -> {
                try {
                    TL_update.TL_updateServiceNotification update =
                            new TL_update.TL_updateServiceNotification();
                    update.popup = false;
                    update.flags = 2;                           // FLAG_1 = has inbox_date
                    update.inbox_date = (int) (System.currentTimeMillis() / 1000);
                    update.type = "update_authorgram";
                    update.message = text;
                    update.media = new TLRPC.TL_messageMediaEmpty();
                    update.entities = new ArrayList<>();

                    TLRPC.TL_updates updates = new TLRPC.TL_updates();
                    updates.updates.add(update);

                    MessagesController.getInstance(account)
                            .processUpdates(updates, false);
                } catch (Exception e) {
                    org.telegram.messenger.FileLog.e("AuthorGramChangelog", e);
                }
            });
        }
    }
}
