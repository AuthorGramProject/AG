package org.telegram.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private static final String ALLOW_URL = "https://raw.githubusercontent.com/VadymYem/CheModules/refs/heads/main/allow.txt";
    private static final String PREFS_NAME = "authorgram_access";
    private static final String KEY_ALLOWED_IDS = "allowed_ids";
    private static final String KEY_LAST_MODIFIED = "last_modified";
    private static final String KEY_VERSION_CODE = "version_code";
    
    // Вбудований fallback список (основні ID розробників)
    private static final long[] FALLBACK_IDS = {
        6316376597L,
        1661748225L,
        953860978L
    };
    
    // MD3 кольори (золото/бежевий тайлван стиль)
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

        // Детальне логування
        FileLog.d("AuthorgramAccessChecker: === ACCESS CHECK START ===");
        FileLog.d("AuthorgramAccessChecker: " + "Loaded " + (allowedIds != null ? allowedIds.size() : 0) + " allowed IDs");
        
        if (allowedIds != null) {
            int idx = 0;
            for (Long id : allowedIds) {
                FileLog.d("AuthorgramAccessChecker: " + "  [" + idx++ + "] Allowed ID: " + id + " (type: Long)");
            }
        }

        List<Long> unauthorizedIds = new ArrayList<>();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (!UserConfig.getInstance(i).isClientActivated()) continue;
            long userId = UserConfig.getInstance(i).getClientUserId();
            if (userId == 0) continue;
            
            FileLog.d("AuthorgramAccessChecker: " + "Checking account " + i + " with user ID: " + userId + " (type: long)");
            
            // Перевірка з явним перетворенням
            boolean isAllowed = checkIdAllowed(userId);
            FileLog.d("AuthorgramAccessChecker: " + "  -> " + (isAllowed ? "OK (allowed)" : "UNAUTHORIZED (blocked)"));
            
            if (!isAllowed) {
                unauthorizedIds.add(userId);
            }
        }

        if (!unauthorizedIds.isEmpty()) {
            FileLog.d("AuthorgramAccessChecker: " + "Found " + unauthorizedIds.size() + " unauthorized accounts");
            AndroidUtilities.runOnUIThread(() -> {
                showFullScreenAccessDeniedDialog(activity, unauthorizedIds);
            });
        } else {
            FileLog.d("AuthorgramAccessChecker: All accounts authorized - access granted");
        }
        
        FileLog.d("AuthorgramAccessChecker: === ACCESS CHECK END ===");
    }

    // Надійна перевірка ID з явним перетворенням
    private static boolean checkIdAllowed(long userId) {
        if (allowedIds == null || allowedIds.isEmpty()) {
            FileLog.e("AuthorgramAccessChecker: allowedIds is null or empty - using fallback");
            // Якщо список порожній, перевіряємо fallback
            for (long fallbackId : FALLBACK_IDS) {
                if (userId == fallbackId) {
                    FileLog.d("AuthorgramAccessChecker: " + "Matched fallback ID: " + fallbackId);
                    return true;
                }
            }
            return false;
        }
        
        // Явне перетворення long -> Long
        Long userIdObj = Long.valueOf(userId);
        boolean result = allowedIds.contains(userIdObj);
        
        if (!result) {
            // Додаткова перевірка з перебором для діагностики
            FileLog.d("AuthorgramAccessChecker: Direct contains() returned false, checking manually...");
            for (Long allowedId : allowedIds) {
                FileLog.d("AuthorgramAccessChecker: " + "  Comparing userId=" + userId + " with allowedId=" + allowedId + 
                         " (userId==allowedId: " + (userId == allowedId) + ")");
                if (userId == allowedId.longValue()) {
                    FileLog.d("AuthorgramAccessChecker: Manual check found match!");
                    return true;
                }
            }
        }
        
        return result;
    }

    // Повноекранний MD3 діалог
    private static void showFullScreenAccessDeniedDialog(Activity activity, List<Long> unauthorizedIds) {
        ScrollView scrollView = new ScrollView(activity);
        scrollView.setBackgroundColor(COLOR_BG);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(40));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout.LayoutParams rootParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rootParams.gravity = Gravity.CENTER;

        // Іконка замка
        TextView lockIcon = new TextView(activity);
        lockIcon.setText("🔒");
        lockIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        lockIcon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        iconParams.bottomMargin = dp(24);
        root.addView(lockIcon, iconParams);

        // Заголовок
        TextView title = new TextView(activity);
        title.setText("Доступ не придбано");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTextColor(COLOR_ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = dp(12);
        root.addView(title, titleParams);

        // Підзаголовок
        TextView subtitle = new TextView(activity);
        subtitle.setText("AuthorGram — приватний доступ");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        subtitle.setTextColor(COLOR_TEXT_DIM);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.bottomMargin = dp(32);
        root.addView(subtitle, subtitleParams);

        // Картка з описом
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(COLOR_CARD);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(24);

        TextView desc = new TextView(activity);
        desc.setText("Наступні акаунти не мають дозволу на використання AuthorGram:");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        desc.setTextColor(COLOR_TEXT_DIM);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, dp(16));
        card.addView(desc);

        // Роздільник
        View divider1 = new View(activity);
        divider1.setBackgroundColor(COLOR_ACCENT);
        divider1.setAlpha(0.3f);
        LinearLayout.LayoutParams div1Params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        );
        div1Params.bottomMargin = dp(16);
        card.addView(divider1, div1Params);

        // Список ID
        LinearLayout idList = new LinearLayout(activity);
        idList.setOrientation(LinearLayout.VERTICAL);
        idList.setPadding(0, 0, 0, dp(8));

        for (int i = 0; i < unauthorizedIds.size(); i++) {
            Long id = unauthorizedIds.get(i);
            
            LinearLayout idRow = new LinearLayout(activity);
            idRow.setOrientation(LinearLayout.HORIZONTAL);
            idRow.setGravity(Gravity.CENTER_VERTICAL);
            idRow.setPadding(dp(12), dp(10), dp(12), dp(10));
            idRow.setBackgroundColor(COLOR_BG);
            
            if (i < unauthorizedIds.size() - 1) {
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rowParams.bottomMargin = dp(8);
                idRow.setLayoutParams(rowParams);
            }

            TextView bullet = new TextView(activity);
            bullet.setText("•");
            bullet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            bullet.setTextColor(COLOR_ACCENT);
            bullet.setPadding(0, 0, dp(12), 0);
            idRow.addView(bullet);

            TextView idLabel = new TextView(activity);
            idLabel.setText("ID:");
            idLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            idLabel.setTextColor(COLOR_TEXT_DIM);
            idRow.addView(idLabel);

            TextView idValue = new TextView(activity);
            idValue.setText(" " + id.toString());
            idValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            idValue.setTextColor(COLOR_TEXT);
            idValue.setTypeface(Typeface.MONOSPACE);
            idValue.setPadding(dp(4), 0, 0, 0);
            idRow.addView(idValue);

            idList.addView(idRow);
        }
        card.addView(idList);

        root.addView(card, cardParams);

        // Таймер
        TextView timerText = new TextView(activity);
        timerText.setText("10");
        timerText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        timerText.setTextColor(COLOR_ACCENT);
        timerText.setTypeface(Typeface.DEFAULT_BOLD);
        timerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams timerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        timerParams.bottomMargin = dp(8);
        root.addView(timerText, timerParams);

        TextView timerLabel = new TextView(activity);
        timerLabel.setText("Додаток буде закрито через");
        timerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        timerLabel.setTextColor(COLOR_TEXT_DIM);
        timerLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        labelParams.bottomMargin = dp(20);
        root.addView(timerLabel, labelParams);

        // Прогрес-бар
        ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(10000);
        progressBar.setProgress(10000);
        progressBar.getProgressDrawable().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_ATOP);
        progressBar.getIndeterminateDrawable().setColorFilter(COLOR_ACCENT, PorterDuff.Mode.SRC_ATOP);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(6)
        );
        progressParams.bottomMargin = dp(32);
        root.addView(progressBar, progressParams);

        // Роздільник
        View divider2 = new View(activity);
        divider2.setBackgroundColor(COLOR_TEXT_DIM);
        divider2.setAlpha(0.2f);
        LinearLayout.LayoutParams div2Params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        );
        div2Params.bottomMargin = dp(24);
        root.addView(divider2, div2Params);

        // Контакти
        TextView contactTitle = new TextView(activity);
        contactTitle.setText("Для отримання доступу:");
        contactTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        contactTitle.setTextColor(COLOR_TEXT_DIM);
        contactTitle.setGravity(Gravity.CENTER);
        contactTitle.setPadding(0, 0, 0, dp(8));
        root.addView(contactTitle);

        TextView contactUrl = new TextView(activity);
        contactUrl.setText("authorche.top/cu");
        contactUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        contactUrl.setTextColor(COLOR_ACCENT);
        contactUrl.setTypeface(Typeface.DEFAULT_BOLD);
        contactUrl.setGravity(Gravity.CENTER);
        contactUrl.setPadding(0, 0, 0, dp(4));
        root.addView(contactUrl);

        TextView contactSub = new TextView(activity);
        contactSub.setText("Telegram — зв'язатися з автором");
        contactSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        contactSub.setTextColor(COLOR_TEXT_DIM);
        contactSub.setGravity(Gravity.CENTER);
        root.addView(contactSub);

        scrollView.addView(root, rootParams);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        builder.setView(scrollView);
        builder.setCancelable(false);
        
        AlertDialog dialog = builder.create();
        
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
            dialog.getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
        }

        CountDownTimer countdown = new CountDownTimer(10000, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                int seconds = (int) Math.ceil(millisUntilFinished / 1000.0);
                timerText.setText(String.valueOf(seconds));
                progressBar.setProgress((int) millisUntilFinished);
                
                float scale = 1.0f + (0.1f * (seconds % 2));
                timerText.setScaleX(scale);
                timerText.setScaleY(scale);
            }

            @Override
            public void onFinish() {
                timerText.setText("0");
                progressBar.setProgress(0);
                timerText.setTextColor(COLOR_ERROR);
                
                new Thread(() -> {
                    for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                        if (!UserConfig.getInstance(i).isClientActivated()) continue;
                        long userId = UserConfig.getInstance(i).getClientUserId();
                        if (userId == 0) continue;
                        if (unauthorizedIds.contains(userId)) {
                            try {
                                FileLog.d("AuthorgramAccessChecker: " + "Performing logout for account " + i + " (userId: " + userId + ")");
                                MessagesController.getInstance(i).performLogout(2);
                            } catch (Exception e) {
                                FileLog.e("AuthorgramAccessChecker: " + "Logout error for account " + i, e);
                            }
                        }
                    }
                    
                    AndroidUtilities.runOnUIThread(() -> {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                        FileLog.d("AuthorgramAccessChecker: Calling finishAffinity()");
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

    public static boolean isAllowed(long userId) {
        return checkIdAllowed(userId);
    }

    // Надійне завантаження з перевіркою версії та fallback
    public static void loadAllowedIds() {
        if (allowedIds != null) {
            FileLog.d("AuthorgramAccessChecker: " + "Using cached allowedIds (size: " + allowedIds.size() + ")");
            return;
        }
        
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
        String savedIds = prefs.getString(KEY_ALLOWED_IDS, "");
        String lastModified = prefs.getString(KEY_LAST_MODIFIED, "");
        int savedVersionCode = prefs.getInt(KEY_VERSION_CODE, 0);
        int currentVersionCode = BuildConfig.VERSION_CODE;
        
        FileLog.d("AuthorgramAccessChecker: " + "Current version: " + currentVersionCode + ", Saved version: " + savedVersionCode);
        
        // Якщо версія змінилася — скидаємо кеш
        if (currentVersionCode != savedVersionCode) {
            FileLog.d("AuthorgramAccessChecker: Version changed - clearing cache");
            prefs.edit()
                .remove(KEY_ALLOWED_IDS)
                .remove(KEY_LAST_MODIFIED)
                .remove(KEY_VERSION_CODE)
                .apply();
            savedIds = "";
            lastModified = "";
        }

        try {
            FileLog.d("AuthorgramAccessChecker: " + "Fetching allow.txt from: " + ALLOW_URL);
            URL url = new URL(ALLOW_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            if (!lastModified.isEmpty()) {
                conn.setRequestProperty("If-Modified-Since", lastModified);
                FileLog.d("AuthorgramAccessChecker: " + "Using If-Modified-Since: " + lastModified);
            }

            int responseCode = conn.getResponseCode();
            FileLog.d("AuthorgramAccessChecker: " + "Response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                // 304 Not Modified — використовуємо кеш
                conn.disconnect();
                FileLog.d("AuthorgramAccessChecker: Server returned 304 - using cached data");
                if (!savedIds.isEmpty()) {
                    allowedIds = parseIds(savedIds);
                    FileLog.d("AuthorgramAccessChecker: " + "Loaded " + allowedIds.size() + " IDs from cache");
                } else {
                    allowedIds = new HashSet<>();
                    FileLog.d("AuthorgramAccessChecker: Cache is empty - using empty set");
                }
                return;
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 200 OK — завантажуємо нові дані
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                reader.close();
                
                String rawContent = sb.toString();
                FileLog.d("AuthorgramAccessChecker: " + "Raw content length: " + rawContent.length() + " bytes");
                FileLog.d("AuthorgramAccessChecker: " + "Raw content preview: " + rawContent.substring(0, Math.min(100, rawContent.length())).replace("\n", "\\n"));

                allowedIds = parseIds(rawContent);
                FileLog.d("AuthorgramAccessChecker: " + "Parsed " + allowedIds.size() + " IDs from server");
                
                String newLastModified = conn.getHeaderField("Last-Modified");
                if (newLastModified == null) newLastModified = "";

                prefs.edit()
                    .putString(KEY_ALLOWED_IDS, rawContent)
                    .putString(KEY_LAST_MODIFIED, newLastModified)
                    .putInt(KEY_VERSION_CODE, currentVersionCode)
                    .apply();
                
                FileLog.d("AuthorgramAccessChecker: " + "Cache saved with version " + currentVersionCode);

                conn.disconnect();
                return;
            }

            // Інший код відповіді
            FileLog.d("AuthorgramAccessChecker: " + "Unexpected response code: " + responseCode);
            conn.disconnect();
            if (!savedIds.isEmpty()) {
                allowedIds = parseIds(savedIds);
                FileLog.d("AuthorgramAccessChecker: Using cache due to HTTP error");
            } else {
                allowedIds = new HashSet<>();
                FileLog.d("AuthorgramAccessChecker: No cache available - using empty set");
            }

        } catch (Exception e) {
            FileLog.e("AuthorgramAccessChecker: " + "Network error: " + e.getMessage(), e);
            
            // Помилка мережі — використовуємо кеш або fallback
            if (!savedIds.isEmpty()) {
                allowedIds = parseIds(savedIds);
                FileLog.d("AuthorgramAccessChecker: Using cache due to network error");
            } else {
                // Fallback список
                FileLog.d("AuthorgramAccessChecker: No cache - using FALLBACK_IDS");
                allowedIds = new HashSet<>();
                for (long id : FALLBACK_IDS) {
                    allowedIds.add(id);
                }
                FileLog.d("AuthorgramAccessChecker: " + "Fallback IDs loaded: " + allowedIds.size());
            }
        }
    }

    // Надійний парсинг з логуванням
    private static Set<Long> parseIds(String text) {
        Set<Long> ids = new HashSet<>();
        if (text == null || text.isEmpty()) {
            FileLog.d("AuthorgramAccessChecker: parseIds: input is null or empty");
            return ids;
        }
        
        FileLog.d("AuthorgramAccessChecker: " + "parseIds: input length=" + text.length());
        
        // Видаляємо BOM (Byte Order Mark) якщо є
        if (text.startsWith("\uFEFF")) {
            FileLog.d("AuthorgramAccessChecker: parseIds: BOM detected and removed");
            text = text.substring(1);
        }
        
        String[] lines = text.split("\n");
        FileLog.d("AuthorgramAccessChecker: " + "parseIds: " + lines.length + " lines found");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Видаляємо \r, пробіли, табуляції з обох кінців
            String trimmed = line.trim().replace("\r", "").replaceAll("\\s+", "");
            
            if (trimmed.isEmpty()) {
                FileLog.d("AuthorgramAccessChecker: " + "parseIds: line " + i + " is empty after trim");
                continue;
            }
            
            // Видаляємо всі нецифрові символи (захист від сміття)
            String digitsOnly = trimmed.replaceAll("[^0-9]", "");
            if (digitsOnly.isEmpty()) {
                FileLog.d("AuthorgramAccessChecker: " + "parseIds: line " + i + " has no digits: '" + trimmed + "'");
                continue;
            }
            
            try {
                long id = Long.parseLong(digitsOnly);
                if (id > 0) {
                    ids.add(id);
                    FileLog.d("AuthorgramAccessChecker: " + "parseIds: parsed ID " + id + " from line " + i);
                } else {
                    FileLog.d("AuthorgramAccessChecker: " + "parseIds: ID <= 0 skipped: " + id);
                }
            } catch (NumberFormatException e) {
                FileLog.e("AuthorgramAccessChecker: " + "parseIds: failed to parse '" + trimmed + "' (digits: '" + digitsOnly + "')", e);
            }
        }
        
        FileLog.d("AuthorgramAccessChecker: " + "parseIds: total " + ids.size() + " valid IDs");
        return ids;
    }
}
