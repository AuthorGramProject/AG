package org.telegram.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AuthorgramLockScreen extends Activity {

    private static final String PASS_TXT_URL = "https://authorche.top/pass.txt";
    private static final String VERIFY_URL = "https://authorche.top/api/otpcode/verify";
    private static final String PREFS_NAME = "authorgram_prefs";
    private static final String KEY_UNLOCKED = "authorgram_unlocked";

    private EditText codeInput;
    private Button verifyButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Повноекранний режим без заголовка
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, 
                            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        // Перевірка чи потрібне блокування
        if (!shouldShowLockScreen()) {
            // Запускаємо LaunchActivity знову
            Intent launchIntent = new Intent(this, LaunchActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
            finish();
            return;
        }
        
        setContentView(createLockScreenView());
    }

    private boolean shouldShowLockScreen() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isUnlocked = prefs.getBoolean(KEY_UNLOCKED, false);
        
        // Перевірка --force в pass.txt
        Boolean forceReauth = checkForceReauth();
        if (forceReauth != null && forceReauth) {
            // --force знайдено, показуємо екран
            return true;
        }
        
        // Якщо offline і вже розблоковано - дозволяємо вхід
        if (forceReauth == null && isUnlocked) {
            return false;
        }
        
        // Якщо не розблоковано - показуємо екран
        return !isUnlocked;
    }

    private Boolean checkForceReauth() {
        try {
            URL url = new URL(PASS_TXT_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().equalsIgnoreCase("pass --force")) {
                    reader.close();
                    conn.disconnect();
                    return true;
                }
            }
            reader.close();
            conn.disconnect();
            return false;
        } catch (Exception e) {
            // Offline або помилка мережі
            return null;
        }
    }

    private View createLockScreenView() {
        // Темна тема з золотими/бежевими кольорами (тайлван стиль)
        int backgroundColor = Color.parseColor("#1A1614"); // Темний бежевий
        int accentColor = Color.parseColor("#D4AF37"); // Золотий
        int textColor = Color.parseColor("#F5E6D3"); // Світлий бежевий
        int inputBgColor = Color.parseColor("#2A2420"); // Трохи світліший фон
        
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(backgroundColor);
        
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setGravity(android.view.Gravity.CENTER);
        contentLayout.setPadding(64, 64, 64, 64);
        
        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        contentParams.gravity = android.view.Gravity.CENTER;
        
        // Логотип/іконка
        TextView logoText = new TextView(this);
        logoText.setText("🔒");
        logoText.setTextSize(64);
        logoText.setGravity(android.view.Gravity.CENTER);
        logoText.setTextColor(accentColor);
        
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        logoParams.bottomMargin = 32;
        
        // Заголовок
        TextView titleText = new TextView(this);
        titleText.setText("AuthorGram");
        titleText.setTextSize(32);
        titleText.setTextColor(accentColor);
        titleText.setGravity(android.view.Gravity.CENTER);
        titleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = 16;
        
        // Підзаголовок
        TextView subtitleText = new TextView(this);
        subtitleText.setText("Приватний доступ");
        subtitleText.setTextSize(16);
        subtitleText.setTextColor(textColor);
        subtitleText.setGravity(android.view.Gravity.CENTER);
        subtitleText.setAlpha(0.7f);
        
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.bottomMargin = 48;
        
        // Поле введення коду
        codeInput = new EditText(this);
        codeInput.setHint("Введіть одноразовий код");
        codeInput.setTextSize(18);
        codeInput.setTextColor(textColor);
        codeInput.setHintTextColor(Color.parseColor("#8B7355"));
        codeInput.setBackgroundColor(inputBgColor);
        codeInput.setPadding(32, 24, 32, 24);
        codeInput.setSingleLine(true);
        codeInput.setGravity(android.view.Gravity.CENTER);
        
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputParams.bottomMargin = 24;
        
        // Кнопка перевірки
        verifyButton = new Button(this);
        verifyButton.setText("Перевірити");
        verifyButton.setTextSize(16);
        verifyButton.setTextColor(backgroundColor);
        verifyButton.setBackgroundColor(accentColor);
        verifyButton.setPadding(32, 20, 32, 20);
        verifyButton.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        buttonParams.bottomMargin = 16;
        
        // Статус
        statusText = new TextView(this);
        statusText.setText("");
        statusText.setTextSize(14);
        statusText.setTextColor(Color.parseColor("#FF6B6B"));
        statusText.setGravity(android.view.Gravity.CENTER);
        
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.bottomMargin = 32;
        
        // Контакти
        TextView contactText = new TextView(this);
        contactText.setText("Немає доступу? authorche.top/cu");
        contactText.setTextSize(14);
        contactText.setTextColor(textColor);
        contactText.setGravity(android.view.Gravity.CENTER);
        contactText.setAlpha(0.6f);
        contactText.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://authorche.top/cu"));
                startActivity(intent);
            } catch (Exception e) {
                // Ігноруємо
            }
        });
        
        // Додаємо елементи
        contentLayout.addView(logoText, logoParams);
        contentLayout.addView(titleText, titleParams);
        contentLayout.addView(subtitleText, subtitleParams);
        contentLayout.addView(codeInput, inputParams);
        contentLayout.addView(verifyButton, buttonParams);
        contentLayout.addView(statusText, statusParams);
        contentLayout.addView(contactText);
        
        rootLayout.addView(contentLayout, contentParams);
        
        // Обробник кнопки
        verifyButton.setOnClickListener(v -> verifyCode());
        
        return rootLayout;
    }

    private void verifyCode() {
        String code = codeInput.getText().toString().trim();
        
        if (code.isEmpty()) {
            statusText.setText("Введіть код");
            statusText.setTextColor(Color.parseColor("#FF6B6B"));
            return;
        }
        
        verifyButton.setEnabled(false);
        verifyButton.setText("Перевірка...");
        statusText.setText("");
        
        new Thread(() -> {
            try {
                URL url = new URL(VERIFY_URL + "?code=" + code);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                conn.disconnect();
                
                String jsonResponse = response.toString();
                
                runOnUiThread(() -> {
                    if (jsonResponse.contains("\"ok\":true")) {
                        // Успіх - зберігаємо стан
                        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                        prefs.edit().putBoolean(KEY_UNLOCKED, true).apply();
                        
                        statusText.setText("✓ Доступ надано");
                        statusText.setTextColor(Color.parseColor("#4CAF50"));
                        
                        // Закриваємо екран через 1 секунду
                        new android.os.Handler().postDelayed(() -> finish(), 1000);
                    } else {
                        // Помилка
                        verifyButton.setEnabled(true);
                        verifyButton.setText("Перевірити");
                        
                        if (jsonResponse.contains("force-reauth")) {
                            statusText.setText("Потрібна повторна авторизація");
                        } else if (jsonResponse.contains("invalid-or-expired")) {
                            statusText.setText("Невірний або прострочений код");
                        } else {
                            statusText.setText("Помилка перевірки");
                        }
                        statusText.setTextColor(Color.parseColor("#FF6B6B"));
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    verifyButton.setEnabled(true);
                    verifyButton.setText("Перевірити");
                    statusText.setText("Помилка мережі");
                    statusText.setTextColor(Color.parseColor("#FF6B6B"));
                });
            }
        }).start();
    }
}
