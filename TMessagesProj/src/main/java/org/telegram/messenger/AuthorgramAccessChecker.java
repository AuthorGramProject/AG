package org.telegram.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AuthorgramAccessChecker {
    private static final String ALLOW_URL = "https://authorche.top/allow.txt";
    private static final String PREFS_NAME = "authorgram_access";
    private static final String KEY_ALLOWED_IDS = "allowed_ids";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    
    // Стиль проекту (золото/бежевий)
    private static final int COLOR_BG = Color.parseColor("#1A1614");
    private static final int COLOR_CARD = Color.parseColor("#2A2420");
    private static final int COLOR_ACCENT = Color.parseColor("#D4AF37");
    private static final int COLOR_TEXT = Color.parseColor("#F5E6D3");
    private static final int COLOR_TEXT_DIM = Color.parseColor("#8B7355");
    private static final int COLOR_ERROR = Color.parseColor("#FF6B6B");

    private static Set<Long> allowedIds = null;

    // Основний метод — збирає ВСІ неавторизовані акаунти
    public static void checkAndEnforceAccess(Activity activity) {
        loadAllowedIds();

        List<Long> unauthorizedIds = new ArrayList<>();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (!UserConfig.getInstance(i).isClientActivated()) continue;
            long userId = UserConfig.getInstance(i).getClientUserId();
            if (userId == 0) continue;
            if (!isAllowed(userId)) {
                unauthorizedIds.add(userId);
            }
        }

        if (!unauthorizedIds.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                showAccessDeniedDialog(activity, unauthorizedIds);
            });
        }
    }

    // Стильний діалог з відліком 10 секунд
    private static void showAccessDeniedDialog(Activity activity, List<Long> unauthorizedIds) {
        // Головний контейнер
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(20));
        root.setBackgroundColor(COLOR_CARD);

        // Іконка + Заголовок
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(16));

        TextView icon = new TextView(activity);
        icon.setText("🔒");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconParams.setMarginEnd(dp(12));
        header.addView(icon, iconParams);

        TextView title = new TextView(activity);
        title.setText("Доступ не придбано");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(COLOR_ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        root.addView(header);

        // Опис
        TextView desc = new TextView(activity);
        desc.setText("Наступні акаунти не мають дозволу на використання AuthorGram:");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        desc.setTextColor(COLOR_TEXT_DIM);
        desc.setPadding(0, 0, 0, dp(16));
        root.addView(desc);

        // Список ID
        LinearLayout idList = new LinearLayout(activity);
        idList.setOrientation(LinearLayout.VERTICAL);
        idList.setBackgroundColor(COLOR_BG);
        idList.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.bottomMargin = dp(20);

        for (Long id : unauthorizedIds) {
            TextView idView = new TextView(activity);
            idView.setText("•  ID: " + id.toString());
            idView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            idView.setTextColor(COLOR_TEXT);
            idView.setTypeface(Typeface.MONOSPACE);
            idView.setPadding(0, dp(4), 0, dp(4));
            idList.addView(idView);
        }
        root.addView(idList, listParams);

        // Роздільник
        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_TEXT_DIM);
        divider.setAlpha(0.2f);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 1);
        divParams.bottomMargin = dp(16);
        root.addView(divider, divParams);

        // Таймер (великий, центральний)
        TextView timerText = new TextView(activity);
        timerText.setText("10");
        timerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        timerText.setTextColor(COLOR_ACCENT);
        timerText.setTypeface(Typeface.DEFAULT_BOLD);
        timerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timerParams.bottomMargin = dp(4);
        root.addView(timerText, timerParams);

        // Підпис під таймером
        TextView timerLabel = new TextView(activity);
        timerLabel.setText("Додаток буде закрито через");
        timerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        timerLabel.setTextColor(COLOR_TEXT_DIM);
        timerLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.bottomMargin = dp(16);
        root.addView(timerLabel, labelParams);

        // Прогрес-бар
        ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(10000);
        progressBar.setProgress(10000);
        progressBar.getProgressDrawable().setColorFilter(
            COLOR_ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.bottomMargin = dp(16);
        root.addView(progressBar, progressParams);

        // Контакти автора
        TextView contactText = new TextView(activity);
        contactText.setText("Для отримання доступу зверніться:\nauthorche.top/cu");
        contactText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        contactText.setTextColor(COLOR_TEXT_DIM);
        contactText.setGravity(Gravity.CENTER);
        contactText.setLineSpacing(dp(2), 1f);
        root.addView(contactText);

        // Створення діалогу
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false)
            .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        // Відлік 10 секунд
        CountDownTimer countdown = new CountDownTimer(10000, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) Math.ceil(millisUntilFinished / 1000.0);
                timerText.setText(String.valueOf(seconds));
                progressBar.setProgress((int) millisUntilFinished);
            }

            @Override
            public void onFinish() {
                timerText.setText("0");
                progressBar.setProgress(0);
                
                // Logout всіх неавторизованих акаунтів
                new Thread(() -> {
                    for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                        if (!UserConfig.getInstance(i).isClientActivated()) continue;
                        long userId = UserConfig.getInstance(i).getClientUserId();
                        if (userId == 0) continue;
                        if (unauthorizedIds.contains(userId)) {
                            try {
                                MessagesController.getInstance(i).performLogout(2);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    
                    AndroidUtilities.runOnUIThread(() -> {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        activity.finishAffinity();
                    });
                }).start();
            }
        };

        dialog.setOnShowListener(d -> countdown.start());
        dialog.show();
    }

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    // Перевірка по ID
    public static boolean isAllowed(long userId) {
        return allowedIds != null && allowedIds.contains(userId);
    }

    // Завантаження списку — завжди робимо HTTP запит
    public static void loadAllowedIds() {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
        String savedIds = prefs.getString(KEY_ALLOWED_IDS, "");
        String lastModified = prefs.getString(KEY_LAST_MODIFIED, "");

        try {
            URL url = new URL(ALLOW_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            if (!lastModified.isEmpty()) {
                conn.setRequestProperty("If-Modified-Since", lastModified);
            }

            int responseCode = conn.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                conn.disconnect();
                if (!savedIds.isEmpty()) {
                    allowedIds = parseIds(savedIds);
                } else {
                    allowedIds = new HashSet<>();
                }
                return;
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();

                allowedIds = parseIds(sb.toString());
                String newLastModified = conn.getHeaderField("Last-Modified");
                if (newLastModified == null) newLastModified = "";

                prefs.edit()
                    .putString(KEY_ALLOWED_IDS, sb.toString())
                    .putString(KEY_LAST_MODIFIED, newLastModified)
                    .apply();

                conn.disconnect();
                return;
            }

            conn.disconnect();
            if (!savedIds.isEmpty()) {
                allowedIds = parseIds(savedIds);
            } else {
                allowedIds = new HashSet<>();
            }

        } catch (Exception e) {
            if (!savedIds.isEmpty()) {
                allowedIds = parseIds(savedIds);
            } else {
                allowedIds = new HashSet<>();
            }
        }
    }

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
