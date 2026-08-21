# AuthorGram — Глибокий статичний аудит кодової бази

> **Дата:** 2026-08-21  
> **Аудитор:** Automated Senior Android/C++ Static Analysis  
> **Репозиторій:** `AuthorGramProject/AuthorGram` (private)  
> **Гілки:** `dev` (основна), `play-market`  
> **Статистика проєкту:** ~32 267 файлів | ~826 МБ | Java: 3315 | Kotlin: 117 | C/C++: 5556 | XML: 1821 | PNG: 5653

---

## Executive Summary

AuthorGram — це форк Telegram для Android, побудований на базі **ExteraGram**, з інтегрованими модулями **AyuGram** (Spy/Ghost), **NekoX** (конфігурація), та **Nagram** (NextAlone). Кодова база добре структурована, але має три категорії проблем:

| Категорія | Severity | Кількість |
|---|---|---|
| 🔴 Play-market залишки вирізаних фіч | Critical | **223+ посилань** на видалений функціонал |
| 🟠 Баги та якість коду | High | 7 конкретних проблем |
| 🟡 Продуктивність UI | Medium | 5 вузьких місць |
| 🟢 Мертвий код (dev) | Low | Мінімальний (~3 знахідки) |

**Ключовий висновок:** Гілка `dev` загалом чиста і добре підтримана. Гілка `play-market`, навпаки, містить **масивну кількість мертвого коду** — 87 посилань на iOS UI, 74 на Ghost/Spy, 62 на Crypto в ядрі Telegram, плюс 33 файли-стаби AyuGram та повний пакет Crypto (15 класів). Код компілюється тільки завдяки stub-класам з порожніми тілами методів.

---

## Частина 1: Dev Branch Issues

### 1.1 Dead Code & Unused Files

> [!NOTE]
> Гілка `dev` відносно чиста. Більшість кастомних класів активно використовуються через `AGSettingsRouter` та UI.

#### Мертві методи

| Файл | Метод | Статус |
|---|---|---|
| [`AGFilterCache.java`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/filters/AGFilterCache.java) | `invalidateGroup(long dialogId, long groupId)` | **DEAD** — 0 зовнішніх викликів |

#### Невикористані PNG ресурси

| Ресурс | Розташування | Статус |
|---|---|---|
| `actions_addadmin.png` | `res/drawable-*/` | **DEAD** — 0 посилань у коді/XML |
| `msg_prpr.png` | `res/drawable-*/` | **DEAD** — 0 посилань у коді/XML |

#### Архітектурні спостереження

- **Дублювання конфігурації:** `ExteraGram.AiConfig` дублює стан через зручні статичні поля, а потім синхронізує їх із `NekoConfig.ConfigItem` через `syncFields()`. Це створює ризик розсинхронізації.
- **Перекриття пакетів:** `toss.authorgram.settings` переімплементує UI налаштувань, який вже є в `NekoX`, залишаючи NekoX фактично бібліотекою стану.
- **God-класи:** Settings-активності (`AGSettingsActivity`, `AGChatSettingsActivity`) є великими монолітними класами з inline UI-логікою.

---

### 1.2 Critical Bugs & Code Quality

#### 🔴 HIGH: Витік ресурсів (Resource Leak)

**Файл:** [`AGSettingsActivity.java:566-576`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java#L566-L576)

```java
final InputStream inputStream = ApplicationLoader.applicationContext
    .getContentResolver().openInputStream(uri);
if (inputStream != null) {
    OutputStream outputStream = new FileOutputStream(file);
    // ... read and write ...
    inputStream.close();    // ← Не буде виконано при exception!
    outputStream.flush();
    outputStream.close();   // ← Не буде виконано при exception!
}
```

**Ризик:** Якщо `read()`/`write()` кинуть виключення, потоки не закриються → витік файлових дескрипторів.  
**Фікс:** Використати `try-with-resources`.

---

#### 🔴 HIGH: NullPointerException ризик

**Файл:** [`AGEmojiSettingsActivity.java:309`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGEmojiSettingsActivity.java#L309)

```java
try (InputStream is = getParentActivity().getContentResolver().openInputStream(uri)) {
```

**Ризик:** `getParentActivity()` повертає `null`, якщо Fragment від'єднаний → `NullPointerException`.  
**Фікс:** Перевіряти `getParentActivity() != null` або використовувати `ApplicationLoader.applicationContext`.

---

#### 🟠 MEDIUM: Thread Safety — небезпечні статичні колекції

**Файл:** [`AGFilter.java:35-42`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/filters/AGFilter.java#L35-L42)

```java
private static volatile ArrayList<FilterModel> filterModels;
private static volatile HashMap<Long, HashSet<String>> excludedSharedFilterIdsByDialog;
```

**Файл:** [`PillStackConfig.java:43-44`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/com/exteragram/messenger/pillstack/core/PillStackConfig.java#L43-L44)

```java
public static ArrayList<Integer> activePills = new ArrayList<>();
```

**Ризик:** `volatile` захищає лише присвоєння посилання, не внутрішній стан колекції. При доступі з кількох потоків → `ConcurrentModificationException`.  
**Фікс:** Використати `CopyOnWriteArrayList` / `ConcurrentHashMap`.

---

#### 🟡 LOW: Порожні catch-блоки

**Файл:** [`AGSettingsActivity.java:579-580`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java#L579-L580)

```java
} catch (Exception ignore) {
}
```

**Ризик:** Помилки імпорту налаштувань повністю ковтаються.  
**Фікс:** Додати `FileLog.e(ignore)`.

---

#### 🟡 LOW: Deprecated API suppression

**Файл:** [`AGTranslatorSettingsActivity.java:764`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGTranslatorSettingsActivity.java#L764)

```java
/** @noinspection deprecation*/
private void showConfigDialog(...) {
```

**Фікс:** Замінити deprecated API-виклики на сучасні еквіваленти.

---

### 1.3 Performance Bottlenecks

#### 🔴 Синхронний `commit()` на UI-потоці

**Файл:** [`AGSettingsActivity.java:393-397`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGSettingsActivity.java#L393-L397)

```java
ApplicationLoader.applicationContext.getSharedPreferences("nekocloud", Activity.MODE_PRIVATE)
    .edit().clear().commit();  // ← блокує UI-потік!
ApplicationLoader.applicationContext.getSharedPreferences("nekox_config", Activity.MODE_PRIVATE)
    .edit().clear().commit();  // ← блокує UI-потік!
```

**Вплив:** Блокує main thread під час disk I/O → можливий ANR.  
**Фікс:** Замінити `.commit()` на `.apply()`.

---

#### 🟠 `notifyDataSetChanged()` замість granular notify

**Файли:**
- [`AGChatSettingsActivity.java:504`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java#L504)
- [`PillStackPreferencesActivity.java:187`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/com/exteragram/messenger/pillstack/ui/PillStackPreferencesActivity.java#L187)
- Та інші `*SettingsActivity.java`

**Вплив:** Перемальовує весь список при зміні одного перемикача.  
**Фікс:** `notifyItemChanged(position)` або `DiffUtil`.

---

#### 🟠 Тяжкі операції в `onBindViewHolder`

**Файл:** [`AGTranslatorSettingsActivity.java:1113-1114`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/toss/authorgram/settings/AGTranslatorSettingsActivity.java#L1113-L1114)

```java
CharSequence text = formatModelNameForList(item.text);
text = highlightQueryInText(text, query, highlightColor);
```

**Вплив:** Regex + алокації Spannable під час скролінгу → jank.  
**Фікс:** Кешувати форматований текст у моделях даних.

---

#### 🟠 Нескінченний `invalidate()` в `onDraw`

**Файл:** [`ResponseAlert.java:1028`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/com/exteragram/messenger/ai/ui/ResponseAlert.java#L1028)

```java
@Override
protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    drawable.draw(canvas);
    if (drawable.isStarted()) invalidate(); // ← нескінченний цикл!
}
```

**Вплив:** Заливає message queue головного потоку → високе навантаження CPU.  
**Фікс:** Використати `Drawable.Callback` або `ValueAnimator`.

---

#### 🟠 ValueAnimator не скасовується при detach

**Файл:** [`PillStackView.java:33`](file:///Users/vadym/Downloads/AuthorGram/TMessagesProj/src/main/java/com/exteragram/messenger/pillstack/ui/PillStackView.java#L33)

```java
private ValueAnimator currentAnimator;
// Немає onDetachedFromWindow() → витік пам'яті + zombie анімація
```

**Фікс:**
```java
@Override
protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    if (currentAnimator != null && currentAnimator.isRunning()) {
        currentAnimator.cancel();
    }
}
```

---

## Частина 2: PlayMarket Branch Issues

> [!CAUTION]
> Гілка `play-market` містить **критичну кількість мертвого коду** від вирізаних функцій. Хоча `AuthorGramPlayPolicy` коректно блокує ці функції через runtime, код залишається в APK, збільшуючи його розмір і поверхню для reverse engineering.

### 2.1 Масштаб залишків

| Вирізана фіча | Кількість посилань | Файли-стаби | Повних класів |
|---|---|---|---|
| **iOS Message Input** | 87 refs у `ChatActivityEnterView.java` | — | Код inline |
| **Ghost/Spy Mode** | 74 refs у кастомних пакетах | 3 стаби | 33 AyuGram файли |
| **Crypto/Encryption** | 62 refs у ядрі Telegram | — | 15 повних класів |
| **Local Premium/PeerColor** | ~10 refs | 2 стаби | 2 Kotlin файли |

### 2.2 iOS Message Input — Мертвий код (87 посилань)

> [!IMPORTANT]
> `AuthorGramPlayPolicy` блокує `iOSMessageInputField` через `LOCKED_CONFIGS`, тому `isIOSInputStyle()` **завжди повертає `false`** у Play збірці. Але весь UI-код залишається:

**Файл:** [`ChatActivityEnterView.java`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java) — **87+ умовних гілок** типу:

```java
// Лінія 2841 — мертва гілка (isIOSInputStyle() завжди false у Play)
messageEditTextContainer.addView(emojiButton, LayoutHelper.createFrame(
    DEFAULT_HEIGHT, DEFAULT_HEIGHT,
    isIOSInputStyle() ? Gravity.BOTTOM | Gravity.RIGHT : Gravity.BOTTOM | Gravity.LEFT,
    isIOSInputStyle() ? 0 : 2, 0, isIOSInputStyle() ? 3 : 0, 0));

// Лінія 2889 — повністю мертвий блок
if (isIOSInputStyle()) {
    // ... весь цей код ніколи не виконується в Play
}
```

**Файл:** [`ChatActivityEnterViewAnimatedIconView.java`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java) — ще 10+ посилань, включно з коментарем:

```java
// AUTHORGRAM_IOS_INPUT_NO_MENU_GLYPH: the media slot is voice/video only.
```

**Конфігураційний прапорець:**
```java
// NekoConfig.java:111
public static ConfigItem iOSMessageInputField = addConfig("iOSMessageInputField", configTypeBool, false);
```

### 2.3 Ghost/Spy Mode — Стаби + Повний AyuGram пакет (74 посилання)

#### Стаб-класи (порожні тіла, збережені для компіляції):

| Клас | Шлях |
|---|---|
| `GhostModeActivity` | [`toss/authorgram/settings/GhostModeActivity.java`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/java/toss/authorgram/settings/GhostModeActivity.java) |
| `AGSpySettingsActivity` | [`toss/authorgram/settings/AGSpySettingsActivity.java`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/java/toss/authorgram/settings/AGSpySettingsActivity.java) |
| `AGPrivacySettingsActivity` | [`toss/authorgram/settings/AGPrivacySettingsActivity.java`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/java/toss/authorgram/settings/AGPrivacySettingsActivity.java) |

#### Повний AyuGram пакет (33 файли!):

Хоча методи мають порожні тіла, **33 файли** залишаються з повними імпортами, DAO-інтерфейсами, entity-класами:

```
com/radolyn/ayugram/
├── AyuConstants.java
├── AyuForward.java
├── AyuUtils.java
├── controllers/
│   ├── AyuAttachments.java
│   └── AyuMapper.java
├── database/
│   ├── AyuData.java          ← getDatabase() повертає null
│   ├── AyuDatabase.java      ← abstract RoomDatabase
│   ├── dao/ (3 DAO інтерфейси)
│   └── entities/ (6 entity класів)
├── messages/
│   ├── AyuMessagesController.java  ← всі методи порожні
│   └── AyuSavePreferences.java
├── ui/
│   └── DummyView.java
└── utils/
    ├── AyuGhostPreferences.java   ← ПОВНА ІМПЛЕМЕНТАЦІЯ!
    ├── AyuGhostUtils.java         ← стаб
    ├── AyuMessageUtils.java       ← 1200+ рядків коду
    ├── LastSeenHelper.java        ← стаб
    ├── PeekOnlineHelper.java      ← активні посилання на LastSeenHelper
    └── seq/ (4 файли)
```

> [!WARNING]
> **`AyuGhostPreferences.java`** — НЕ стаб! Це **повна імплементація** з `ConcurrentHashMap`, що зберігає ghost mode exclusions у `SharedPreferences`. Цей клас не потрібен у Play збірці.

> [!WARNING]
> **`AyuMessageUtils.java`** — це файл на **1200+ рядків** з повною логікою маппінгу, зберігання медіа, і обробки повідомлень для Spy функціоналу. Він використовує `AyuMessagesController` (який повертає порожні значення в Play), але весь цей код потрапляє в APK.

#### Ghost Mode у NekoConfig:

```java
// NekoConfig.java:192, 296-325
public static ConfigItem showGhostModeStatus = addConfig("showGhostModeStatus", configTypeBool, false);

public static boolean isGhostModeActive() { ... }    // Заблоковано PlayPolicy
public static void setGhostMode(boolean enabled) { ... }
public static void toggleGhostMode() { ... }
```

#### Ghost Mode у LastSeenPill:

```java
// LastSeenPill.java:170, 388
if (!AuthorGramPlayPolicy.isPlayBuild() && NekoConfig.sendOfflinePacketAfterOnline.Bool()) {
    AyuGhostUtils.performStatusRequest(true);  // Мертва гілка в Play
}
if (!AuthorGramPlayPolicy.isPlayBuild() && NekoConfig.isGhostModeActive()) { ... }
```

### 2.4 Crypto/Encryption — Повний робочий код (62 посилання в ядрі)

> [!IMPORTANT]
> На відміну від Ghost/Spy, **Crypto-класи НЕ є стабами** — вони містять повну робочу імплементацію шифрування. `AuthorGramPlayPolicy` дозволяє per-chat ключі навіть у Play!

**15 повних класів у** `org.telegram.messenger.authorgram`:

| Клас | Призначення |
|---|---|
| `AuthorGramCrypto.java` | AES шифрування/дешифрування |
| `AuthorGramCryptoInterceptor.java` | Перехоплення відправки/отримання повідомлень |
| `AuthorGramChatCrypto.java` | Per-chat шифрування |
| `AuthorGramChatKeyStore.java` | Зберігання ключів |
| `AuthorGramKeyProtector.java` | Захист ключів |
| `AuthorGramKeyDialog.java` | UI для керування ключами |
| `AuthorGramChatState.java` | Стан шифрування чату |
| `AuthorGramPassphraseKdf.java` | KDF для паролів |
| `AuthorGramMessageMeta.java` | Метадані повідомлень |
| `AuthorGramMessageSplitter.java` | Розбиття довгих повідомлень |
| `AuthorGramPlayPolicy.java` | Політика для Play збірки |
| `AuthorGramDefaults.java` | Дефолтні налаштування |
| `AuthorGramAuthorBadge.java` | Бейдж автора |
| `AuthorGramBuildIntegrity.java` | Перевірка цілісності збірки |
| `PlayDefaultsMigrationProvider.java` | Міграція дефолтних значень |

**62 виклики `AuthorGramCryptoInterceptor`** вбудовані у ядро Telegram:

```java
// SendMessagesHelper.java:7995
if (!AuthorGramCryptoInterceptor.prepareOutgoingRequest(currentAccount, req, msgObj)) {

// MessageObject.java:1887, 1960
AuthorGramCryptoInterceptor.decryptIncomingMessage(...)

// MessagesController.java — 9+ місць (L12224, L15627, L16361, L16369, L18387, L18792, L18796, L18800, L19732, L19744)
AuthorGramCryptoInterceptor.decryptIncomingMessage(currentAccount, message);
```

### 2.5 Local Premium/PeerColor — Стаби з посиланнями

**Файли:**
- [`LocalPeerColorHelper.kt`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPeerColorHelper.kt) — стаб, `apply()` порожній
- [`LocalPremiumStatusHelper.kt`](file:///Users/vadym/Downloads/AuthorGram-playmarket/TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/helper/LocalPremiumStatusHelper.kt) — стаб, `apply()` порожній

**Посилання в ядрі Telegram:**
```java
// PeerColorActivity.java:1800
LocalPeerColorHelper.apply(namePage.selectedColor, ...);

// MessagesController.java:2730
LocalPremiumStatusHelper.apply(new_emoji_status);
```

### 2.6 Файли, видалені з dev → play-market

Ці 6 файлів присутні в `dev`, але відсутні в `play-market`:

| Файл | Функціонал |
|---|---|
| `AyuHistoryHook.java` | Перехоплення історії повідомлень |
| `AyuMessageHistory.java` | UI для перегляду збережених повідомлень |
| `AyuViewDeleted.java` | UI для перегляду видалених повідомлень |
| `AuthorGramSpyPolicy.java` | Політика Spy режиму |
| `IOSMessageMenuPreview.java` | iOS-style контекстне меню |
| `GhostModeExclusionPopupWrapper.java` | Popup для виключень Ghost Mode |

### 2.7 Scripts — Очищені правильно

Скрипти `play-market` гілки очищені від spy/ios файлів:
- ❌ Видалені: `finalize_ios_message_menu_v6.py`, `restore_authorgram_ios_ui*.py`, `strip_authorgram_play_runtime.py`, `fix_authorgram_spy_compile.py`, `apply_authorgram_exact_icon_and_spy.py`
- ❌ Видалена: `scripts/play_stubs/` (14 файлів-стабів)
- ✅ Залишені: `patch_authorgram_play_policy.py`, `cleanup_authorgram_actions.py` — потрібні для Play збірки

---

## Частина 3: Action Plan

### Phase 1: 🔴 Критичні фікси (dev) — 1-2 години

1. **Виправити витік ресурсів** у `AGSettingsActivity.java:566-576`
   - Замінити на `try-with-resources`

2. **Виправити NPE ризик** у `AGEmojiSettingsActivity.java:309`
   - Додати null-check для `getParentActivity()`

3. **Замінити `.commit()` на `.apply()`** у `AGSettingsActivity.java:393-397`

4. **Виправити ValueAnimator leak** у `PillStackView.java`
   - Додати `onDetachedFromWindow()` з `cancel()`

### Phase 2: 🟠 Очищення play-market гілки — 4-6 годин

> [!IMPORTANT]
> Це найбільш трудомістка, але важлива частина. Play збірка не повинна містити мертвий код.

**Крок 1: iOS Input очищення**
- У `ChatActivityEnterView.java` — видалити всі 87+ `isIOSInputStyle()` гілок, залишивши лише `else`-блоки
- У `ChatActivityEnterViewAnimatedIconView.java` — видалити `iosInput()` та пов'язані гілки
- Видалити `iOSMessageInputField` з `NekoConfig.java` (або залишити для PlayPolicy lock)

**Крок 2: AyuGram очищення**
- **Видалити повністю:** `AyuGhostPreferences.java` (не стаб, повна імплементація)
- **Видалити або мінімізувати:** `AyuMessageUtils.java` (1200+ рядків мертвого коду)
- **Залишити мінімальні стаби:** `AyuGhostUtils.java`, `AyuData.java`, `AyuMessagesController.java`, `LastSeenHelper.java` — вони потрібні для компіляції
- **Оцінити:** DAO-інтерфейси та entity-класи — якщо `AyuDatabase` ніколи не ініціалізується, їх можна видалити

**Крок 3: Ghost Mode UI очищення**
- Оцінити чи потрібні `isGhostModeActive()`, `setGhostMode()`, `toggleGhostMode()` у `NekoConfig.java`
- Очистити мертві гілки `!AuthorGramPlayPolicy.isPlayBuild() &&` у `LastSeenPill.java`

**Крок 4: Оцінка Crypto коду**
- Crypto — навмисно залишений і працює через per-chat ключі
- Якщо Crypto має бути в Play — залишити
- Якщо ні — видалення потребує рефакторингу 62+ місць у ядрі Telegram

### Phase 3: 🟡 Продуктивність (dev) — 2-3 години

1. Замінити `notifyDataSetChanged()` на `notifyItemChanged(position)` у всіх Settings-активностях
2. Кешувати форматований текст у `AGTranslatorSettingsActivity` для `onBindViewHolder`
3. Замінити `invalidate()` у `ResponseAlert.onDraw()` на `Drawable.Callback`
4. Замінити volatile `ArrayList`/`HashMap` на thread-safe аналоги в `AGFilter.java` та `PillStackConfig.java`

### Phase 4: 🟢 Рефакторинг (dev) — ongoing

1. Видалити мертвий метод `AGFilterCache.invalidateGroup()`
2. Видалити невикористані PNG: `actions_addadmin.png`, `msg_prpr.png`
3. Розглянути уніфікацію конфігурації: `AiConfig.syncFields()` → пряме використання `ConfigItem`
4. Додати `FileLog.e()` замість порожніх `catch (Exception ignore)`

---

> **Примітка:** Цей звіт базується на статичному аналізі через `grep`/`find`. Для повного виявлення мертвого коду рекомендується запуск Android Lint (`./gradlew lint`) та ProGuard/R8 mapping аналіз.
