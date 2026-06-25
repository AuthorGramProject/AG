package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

public class AuthorgramAccessChecker {
    private static final String ALLOW_URL = "https://authorche.top/allow.txt";
    private static final String PREFS_NAME = "authorgram_access";
    private static final String KEY_ALLOWED_IDS = "allowed_ids";
    private static final String KEY_LAST_CHECK = "last_check";
    // 3 дні в мілісекундах (3 * 24 * 60 * 60 * 1000)
    private static final long CHECK_INTERVAL = 259200000L;

    private static Set<Long> allowedIds = null;

    // Основний метод перевірки
    public static void checkAndEnforceAccess(int currentAccount, Activity activity) {
        long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (userId == 0) return; // Користувач ще не увійшов

        if (!isAllowed(userId)) {
            AndroidUtilities.runOnUIThread(() -> {
                Toast.makeText(activity, "Ви не придбали доступ", Toast.LENGTH_LONG).show();
                // Виконуємо локальний logout (type=2) без запиту на сервер
                new Thread(() -> {
                    try {
                        MessagesController.getInstance(currentAccount).performLogout(2);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }).start();
            });
        }
    }

    // Перевірка по ID
    public static boolean isAllowed(long userId) {
        loadAllowedIds();
        return allowedIds != null && allowedIds.contains(userId);
    }

    // Завантаження списку з кешем
    public static void loadAllowedIds() {
        if (allowedIds != null) return; // Список вже завантажено в пам'ять

        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
        long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        long now = System.currentTimeMillis();

        String savedIds = prefs.getString(KEY_ALLOWED_IDS, "");
        
        // Якщо минуло менше 3 днів і є кеш — використовуємо його
        if (now - lastCheck < CHECK_INTERVAL && !savedIds.isEmpty()) {
            allowedIds = parseIds(savedIds);
            return;
        }

        // Завантажуємо свіжий список
        try {
            URL url = new URL(ALLOW_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            allowedIds = parseIds(sb.toString());
            // Зберігаємо в кеш
            prefs.edit().putString(KEY_ALLOWED_IDS, sb.toString()).putLong(KEY_LAST_CHECK, now).apply();
        } catch (Exception e) {
            // Помилка мережі: використовуємо старий кеш, якщо він є
            if (!savedIds.isEmpty()) {
                allowedIds = parseIds(savedIds);
            } else {
                allowedIds = new HashSet<>(); // Порожній список, якщо кешу немає
            }
        }
    }

    // Парсинг тексту (один ID на рядок)
    private static Set<Long> parseIds(String text) {
        Set<Long> ids = new HashSet<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                try {
                    ids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {}
            }
        }
        return ids;
    }
}
