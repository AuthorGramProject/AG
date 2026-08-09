# IOS_NOTES — аудит поточної реалізації AuthorGram перед відкатом

Дата аудиту: 2026-08-09  
Репозиторій: `AuthorGramProject/AuthorGram`  
Основна гілка аудиту: `dev`  
HEAD `dev`: `b01577919ed4e1d0c8e5e2b2473aa6eeca3e7a73` — `guard: allow validated final explicit sender header`

## 1. Важливе розходження гілок

Поточний вихідний код iOS-меню не є однаковим у трьох робочих гілках:

- `dev`: `b01577919ed4e1d0c8e5e2b2473aa6eeca3e7a73`.
  - `IOSMessageMenuPreview.java`: blob `40a888d96d9a985baff93aa078a11670493021e1`, 119 рядків.
  - Це проміжний native-preview без фінального явного sender header.
- `main`: `033d0f730e0fa104106c96942ddb148e6f46e294` — `[skip ci] Synchronize crash-safe AuthorGram Main source`.
  - `IOSMessageMenuPreview.java`: blob `2626e03b445107753c608149f207d6b715c7f22c`, 356 рядків.
  - Це згенерований release-проходом варіант з окремим аватаром, ім’ям і bounded-scroll для тіла повідомлення.
- `play-market`: `74964618c1e9306c6d889fa7ea891541d28852d7`.
  - Містить частину iOS-коду, але `AuthorGramPlayPolicy` примусово вимикає iOS Input та iOS Message Menu.

`.github/workflows/release.yml` і `scripts/final_main_stable_release_12_9_2.sh` під час release не просто збирають наявний Java-код. Вони запускають каскад Python-патчерів, переписують Java-файли, створюють локальний dev snapshot, переносять його в окремий worktree `main` і пушать згенерований стан у `main`. Тому відкат лише Java-файлів без видалення або перебудови активного patch-chain не буде справжнім: старі генератори знову відтворять проблемну реалізацію.

## 2. iOS Input — поточна реалізація

### 2.1. Увімкнення і межі функції

Налаштування:

- `NekoConfig.iOSMessageInputField` — ключ `iOSMessageInputField`, default `false`.
- Рядок налаштування створюється програмно в `AGChatSettingsActivity.appendIOSMessageInputFieldRow()`.
- `AuthorGramPlayPolicy.canUseIosUi()` дозволяє функцію лише не-Play збіркам.
- У Play policy ключ `iOSMessageInputField` примусово дорівнює `false`.

Центральний gate — `ChatActivityEnterView.computeIOSInputStyle()`. iOS Input вимикається для:

- Play-збірки;
- вимкненого `NekoConfig.iOSMessageInputField`;
- Stories;
- відсутнього `parentFragment`;
- preview mode;
- broadcast-каналу без необхідних прав.

У `dev` метод `isIOSInputStyle()` ще може повертати кешований `iosLayoutMode`. У release-generated `main` патчер `patch_authorgram_main_stability.py` замінює це на live-обчислення через `computeIOSInputStyle()`.

### 2.2. View-ієрархія

Окремого XML-layout для iOS Input немає. Усе створюється програмно в `ChatActivityEnterView.java`.

Фактична структура:

1. `ChatActivityEnterView`.
2. `textFieldContainer: FrameLayout` — `MATCH_PARENT × WRAP_CONTENT`, прив’язаний до низу.
3. Усередині `textFieldContainer`:
   - `attachBubble: FrameLayout` зліва, `DEFAULT_HEIGHT × DEFAULT_HEIGHT`;
     - `attachButton: ImageView`, 38×38 dp, іконка `msg_input_attach2_solar`;
   - `messageEditTextContainer: FrameLayout` у центрі, `MATCH_PARENT × WRAP_CONTENT`, з left margin приблизно 10 dp і right margin 48 dp;
     - поле `messageEditText`;
     - `emojiButton: ChatActivityEnterViewAnimatedIconView` справа;
     - `attachLayout: LinearLayout` справа для notify/scheduled/bot/suggest та інших службових кнопок;
     - додаткові стани rich draft / recorded audio;
   - `aiButton`, `richButton`, `aiHint` як окремі програмні елементи;
   - right-side `sendButtonContainer`, у якому перемикаються `sendButton`, `audioVideoButtonContainer`, cancel/slow-mode елементи.
4. `audioVideoButtonContainer` містить `ChatActivityEnterViewAnimatedIconView` для VOICE/VIDEO; класичний стан MENU для iOS mode заборонений.

### 2.3. Розміри, multiline і позиціонування

- `messageEditTextContainer` має `WRAP_CONTENT`; мінімальна виміряна висота — 44 dp.
- Багаторядковий ріст успадковано від NagramXF/Telegram через вимірювання `messageEditText` і контейнера.
- Поріг показу AI/Rich controls для iOS mode — понад 3 рядки замість 2 у classic mode.
- Side bubbles позиціонуються через `ChatInputViewsContainer.setInputBubbleOffsets()`, `setLeftBubbleBounds()`, `setRightBubbleBounds()`.
- Окремої нової IME-insets архітектури для iOS Input немає: використовуються наявні Telegram `SizeNotifierFrameLayout` / keyboard lifecycle і поточний layout `ChatActivityEnterView`.

### 2.4. Авторські стабілізатори, додані після інтеграції

1. `ChatActivityEnterViewAnimatedIconView.iosInput()`:
   - перевіряє Main-only policy та setting;
   - підміняє VOICE↔VIDEO lottie resources;
   - у `setState()` примусово перетворює `State.MENU` на `State.VOICE`;
   - очищає stale drawable/animation, щоб три крапки не залишалися поверх chat avatar/header.

2. `authorGramEnforceInputMenuInvariant()`:
   - при наявному тексті ховає media container і примусово показує send button;
   - при порожньому тексті повертає media container;
   - нормалізує alpha/scale/translation/clickability;
   - запускається негайно, через `post()` і через `postDelayed(..., 160L)`.

3. `authorGramStabilizeIOSInputGeometry()`:
   - обнуляє `translationY` у text field, edit container, attach bubble, send/media container, AI/Rich controls;
   - запускається з `onLayout()`, негайно, через `post()` і `postDelayed(..., 320L)`;
   - у згенерованому `main` викликає `updateSideBubbles()` лише після ненульової ширини attach/send controls.

4. Voice-draft restore:
   - окремий patch повертає attach/media ownership після paused voice draft.

### 2.5. Ризики поточного iOS Input

- Стабілізація зроблена не локально в переходах станів, а повторними delayed callbacks з `onLayout()`.
- Helper примусово змінює visibility, alpha, scale, translation і clickability після штатних Telegram-анімацій.
- У `dev` є кеш `iosLayoutMode`, а у `main` live gate; тому поведінка dev-source і фактичного Main APK різна.
- `updateSideBubbles()` сам має retry через `View.post()` при нульовій ширині; окремий runtime-safety patch був доданий пізніше саме через ризик нескінченного post-loop.
- Саме iOS Input не повинен змінювати chat header. Єдиний потрібний header/menu fix — не дозволяти classic MENU glyph і typing-state перекривати/перехоплювати кнопку меню чату.

## 3. iOS Message Menu — поточна реалізація

### 3.1. Увімкнення

- `NekoConfig.iOSMessageMenu` — ключ `iOSMessageMenu`, default `true`.
- Рядок налаштування створюється програмно в `AGChatSettingsActivity.appendIOSMessageMenuRow()`.
- `AuthorGramPlayPolicy.canUseIosUi()` робить функцію Main-only.
- XML-layout для меню немає. Java View створюються програмно.
- Тексти:
  - `values/strings_neko.xml`;
  - `values-uk/authorgram_ios.xml`.

### 3.2. Фактична ієрархія popup

Поточна release-generated Main-композиція:

1. `ChatScrimPopupContainerLayout` — вертикальний top-level контейнер.
2. `ReactionsContainerLayout` — Telegram reactions.
3. `IOSMessageMenuPreview` — окремий sibling, доданий через `setFixedMessagePreview()` перед action card.
4. `ActionBarPopupWindow.ActionBarPopupWindowLayout` — окрема card зі штатним Telegram `ScrollView` і вертикальним списком action rows.
5. Quick-action/footer блоки переносяться в той самий action-card `LinearLayout` нижче divider.

`ChatActivity` створює preview лише коли:

- є `selectedObject`;
- long-pressed View є `ChatMessageCell`;
- дозволено Main-only iOS UI;
- увімкнено `iOSMessageMenu`.

Після створення preview його owner шукається від `popupLayout` через `View.post()`, коли popup уже attached. Потім викликається `ChatScrimPopupContainerLayout.setFixedMessagePreview(iosPreview)`. Якщо owner не знайдений, preview ховається.

### 3.3. Final Main preview: avatar/name/message

Фінальний release-pass `patch_authorgram_final_ios_sender_header.py` повністю переписує `IOSMessageMenuPreview.java`.

Усередині preview:

1. `LinearLayout content`, vertical.
2. `senderHeader: LinearLayout`, 50 dp:
   - `BackupImageView` 38×38 dp, radius 19 dp;
   - `TextView senderNameView`, bold, single-line, end ellipsis, 44 dp height.
3. `BoundedScrollView previewScroll`.
4. Усередині scroll:
   - fresh `ChatMessageCell`, або raw-text fallback при exception.

Sender resolution:

- спочатку `messageObject.getFromChatId()`;
- для outgoing/zero sender — current user;
- далі `messageObject.getDialogId()`;
- user/chat береться з `MessagesController`;
- fallback — `messageObject.customName`;
- останній fallback — `Unknown`;
- avatar береться через `BackupImageView.setForUserOrChat()`, інакше генерується `AvatarDrawable`.

Message data:

- використовується оригінальний `MessageObject`;
- створюється новий Telegram `ChatMessageCell`;
- `cell.isChat = false`;
- викликається `setMessageObject(messageObject, null, false, false, false)`;
- source-cell width/height/layout params не копіюються;
- source cell використовується лише для `ResourcesProvider`.

Fallback при помилці показує лише raw `messageText` або `messageOwner.message` у простій round-rect бульбашці. Це не повноцінне Telegram rich-message rendering.

### 3.4. Вимірювання й скрол

Final Main:

- висота sender header — 50 dp;
- message viewport:
  `max(120dp, min(340dp, 38% висоти екрана))`;
- message body завжди знаходиться у власному `BoundedScrollView`;
- action rows мають окремий Telegram `ScrollView`.

`ChatScrimPopupContainerLayout.onMeasure()`:

- обмежує весь popup реальною висотою екрана/work area;
- рахує висоту reactions + preview як `occupiedHeight`;
- action card отримує лише `max(1, effectiveMaxHeight - occupiedHeight)`;
- якщо action rows не влазять, їхній власний ScrollView має прокручувати footer і останні дії.

У `dev` проміжний preview інший:

- explicit sender header відсутній;
- fresh `ChatMessageCell` має `isChat`, скопійований лише з source cell;
- viewport `max(120dp, min(300dp, 34% екрана))`;
- `ChatScrimPopupContainerLayout` ще може прирівнювати ширину fixed preview до ширини action card.

### 3.5. Blur

`patch_authorgram_full_screen_ios_blur.py` змінює лише відомий exact anchor у `ChatActivity`:

- для iOS Message Menu викликається `dimBehindView(null, true, true)`;
- для classic menu лишається штатний `dimBehindView(v, true)`;
- preview-local `BluredView` заборонений.

Це робить blur повноекранним, але сам selected-message preview є окремою overlay-композицією.

### 3.6. Чому поточний вигляд не відповідає вимозі

- Аватар та ім’я намальовані вручну в окремому header над повідомленням, а не Telegram-груповим `ChatMessageCell`.
- `cell.isChat = false` вимикає природну групову sender/avatar геометрію всередині native cell.
- Аватар зараз зверху, а не внизу біля бульбашки, як в потрібній Telegram-груповій композиції.
- Body завжди в окремому bounded scroll. Header лишається нерухомим, поки message content скролиться окремо.
- Rich message / grouped media / link preview може втратити source group context. Попередні патчі копіювали `getCurrentMessagesGroup()`, spoiler attach index і видиму частину, але фінальний postpass це відкидає.
- При exception rich preview деградує до raw text, тобто не показується повністю так, як його малює Telegram.
- Preview і action list мають два незалежні scroll owners; allocation `max(1, remainingHeight)` може залишити action card практично без видимої висоти й створити враження обрізаного низу.
- У source лишилися compatibility-гілки `ActionBarPopupWindow`, які розраховані на preview як перший child внутрішнього action list, хоча фінальна архітектура тримає preview окремим sibling. Це ускладнює підтримку й створює кілька конкуруючих моделей ownership.

## 4. Відомі регресії

Підтверджені користувачем:

1. Відкриття чату може зависати, якщо останнє повідомлення — внутрішнє settings-посилання виду `https://t.me/authorgram_apk/...?...=...`.
2. Replies стали нестабільними після серії iOS menu/runtime patches.
3. iOS Message Menu може не показувати потрібне повідомлення або обрізати нижню дію.
4. У довгих/rich повідомленнях header лишається окремо, а body прокручується у власному bounded viewport.
5. Поточний avatar/name layout не відповідає Telegram group-message rendering.
6. При введенні тексту classic MENU/three-dots glyph міг з’являтися поверх avatar/header і ламати доступ до chat menu.

Точний stack trace для settings-link зависання в цьому аудиті відсутній, тому не можна чесно назвати один доведений рядок причиною. Але встановлено порушення межі функції: release patch-chain, створений під час iOS-робіт, почав змінювати не лише popup/composer, а й unrelated hot paths:

- `AuthorGramCryptoInterceptor.java` — reply ownership/decryption;
- `MessageSettingsPreviewCell.java` — settings preview reply model;
- `AGSettingsRouter.java` і `BaseAGSettingsActivity.java` — settings deep links;
- `DialogsAdapter.java` — `.me` URL DiffUtil comparison;
- `AGFilter.java` / `AGFilterCache.java` — regex/filter rendering;
- Ayu synchronous waiters;
- blocked-channel settings path.

Коміт `39f2166f2c24475cb584b318b0d16930da00f71f` прямо об’єднав iOS menu/composer та ці hot-path зміни в один stability pass. Саме такі cross-cutting правки не можна автоматично переносити у нову реалізацію меню.

Reply chronology:

- `98fdd59bf89d47de1f56a2e31ee086d5aba443e2` — додано recursive reply-target decryption;
- `14510a187486fc338a2e84e6bf68182277792a18` — повернено Play-stable модель без recursive nested reply mutation;
- `0b714079ae536e1767cee0c9d883005e08ce67f6` — reply і iOS menu runtime repair об’єднані в patch-chain.

Новий menu-only код не повинен змінювати криптографію, reply ownership, deep-link routing, dialog diffing, filters, blocked-channel storage або Ayu waiters.

## 5. Файли, які реально беруть участь

### Runtime / config / UI

- `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java`
  - long-press menu creation;
  - iOS preview creation/attach;
  - full-screen blur gate.
- `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java`
  - повний iOS Input layout/state/geometry.
- `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterViewAnimatedIconView.java`
  - VOICE/VIDEO/MENU state і stale MENU guard.
- `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatScrimPopupContainerLayout.java`
  - reactions/preview/action-card ownership;
  - viewport allocation;
  - fixed preview;
  - footer.
- `TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBarPopupWindow.java`
  - action ScrollView, background segmentation, popup scrolling/padding.
- `TMessagesProj/src/main/java/org/telegram/ui/Components/IOSMessageMenuPreview.java`
  - message preview implementation.
- `TMessagesProj/src/main/java/tw/nekomimi/nekogram/NekoConfig.java`
  - `iOSMessageInputField`, `iOSMessageMenu`.
- `TMessagesProj/src/main/java/org/telegram/messenger/authorgram/AuthorGramPlayPolicy.java`
  - Main-only gate.
- `TMessagesProj/src/main/java/toss/authorgram/settings/AGChatSettingsActivity.java`
  - settings rows.
- `TMessagesProj/src/main/res/values/strings_neko.xml`
- `TMessagesProj/src/main/res/values-uk/authorgram_ios.xml`

XML-layout файлів для цих двох функцій немає.

### Активний release patch-chain

- `.github/workflows/release.yml`
- `scripts/final_main_stable_release_12_9_2.sh`
- `scripts/finalize_authorgram_source.py`
- `scripts/patch_authorgram_popup_bounds.py` — лише legacy read-only scan.
- `scripts/patch_authorgram_full_screen_ios_blur.py`
- `scripts/patch_authorgram_main_stability.py`
- `scripts/patch_authorgram_native_menu_stability.py`
- `scripts/patch_authorgram_runtime_regressions.py`
- `scripts/patch_authorgram_ios_input_geometry.py`
- `scripts/patch_authorgram_ios_input_runtime_safety.py`
- `scripts/patch_authorgram_chat_scope_safety.py` — read-only validator.
- `scripts/audit_authorgram_runtime_stability.py`
- `scripts/patch_authorgram_final_ios_sender_header.py` — останнім повністю переписує preview.

### Наявні історичні/неактивні iOS patchers

Вони залишаються в репозиторії, але не є прямим канонічним ланцюгом поточного stable Main workflow:

- `scripts/patch_authorgram_ui_12_9_2.py`
- `scripts/patch_authorgram_ios_menu_v2.py`
- `scripts/patch_authorgram_adaptive_ios_preview.py`
- `scripts/patch_authorgram_final_chat_ui.py`
- `scripts/patch_authorgram_final_menu_voice.py`
- `scripts/patch_authorgram_unified_message_menu.py`
- `scripts/patch_authorgram_final_ui_compat.py`
- `scripts/repair_authorgram_patch_chain.py`
- `scripts/patch_authorgram_play_policy.py`

Під час відкату їх не можна залишити як неявні генератори старої архітектури без окремої перевірки.

## 6. Історія ключових iOS-комітів

### База 12.9.2 та чиста зона перед кастомним message menu

- `1071a304d12164e196e10ada3fdd5bbdca07c6f0` — Integrate NagramXF 12.9.2 into AuthorGram.
- `cee26b322c8e5e5d48a6b64b002a63677a15cc55` — Integrate NagramXF 12.9.2 (6991) into AuthorGram.
- `d3c34906d9d6dbbfe82bdb391d7d268110d66737` — останній commit без custom iOS Message Menu перед `d4e54bfe`; змінює лише release trigger.
- Між `cee26b32` і `d4e54bfe` є 9 комітів, переважно badges/integrity/release-trigger; їх треба класифікувати окремо на Кроці 1.
- Commit message зі словом `Anthropic` у цьому часовому відрізку пошуком не знайдений. Тому точку за згадкою “Anthropic” не можна вигадувати; її треба підтвердити через diff/log навколо кандидатів.

### Початок custom iOS UI

- `d4e54bfe66480d03679766fffcf46fbfa07cb87d` — перший custom iOS Message Menu preview.
- `abd7e8d0f8503d1f7f1a9ab951e8ff6f2d14cf5c` — idempotent UI repair patch.
- `6cdb6c573bc2e65af386bd949a77c4fc7641ed11` — перший custom iOS Input fix: ghost MENU glyph.
- `fecc61cc957169794fe7665ddd05f48dd87c881c` — adaptive message menu bounds.
- `985dc6745c75044052d00329978195ccc98c30c3` — UI patch applied to every message-menu path.
- `8e273a64f36c98ebf0842156a6abfc1d32bd05bc` — sender/blur refinement.
- `277f6e90bbadb56245c9278b53789fbb06d3af4f` — Main-only iOS policy.
- `614a2afcfce48f483d28858ba13b2e954030d5e1` — Main gate and compact actions.
- `a3cbb97481ec9283ae68c5be4ce8f07f03c36203` — synthetic preview replaced with native scrim.
- `a52d9519dc243258709a38193e4d997d5554ffb4` — preview moved inside menu; media button restore.
- `63aee6f0b45062489481cabe7d78dda3860535f1` — combined iOS menu/input/release repairs.
- `b16d8e60ed796254ece8454784221e5d6efe10ba` — native Telegram cell preview.
- `b9b12dd2ce98220504502d1ce1a773addd7b2e8b` — separate native preview and typing overlay fix.

### Подальша patch-chain

- `a893a416e486accb37b86474a3e05169ced90dfe` — sender identity / blur ownership.
- `7115bb197a76c27ebe9814bf436369260c4dd146` — full-screen blur + persistent input menu guard.
- `04f813b9171ccf0a8dacb3811875f33495df8bea` — stale composer menu glyph state.
- `6f8a0df0aa9f981e228259eda912e5f591c16a94` — chat header / iOS menu repair.
- `98bbf953aa19a618edb3354099a6c7105e5addbb` — adaptive preview / popup repair.
- `ce53fe3d6f92ce6fe911610794082033569548ce` — scope-safe preview repair.
- `969ff909eb3048445fd094a3fb934db71f0a57e1` — paused voice draft attach restore.
- `90ba966271cfcd3e2db7eb4a4e95820b7c9aa6e9` — iOS input geometry stabilization.
- `f4c7fe83cb97145646bbb08aad3bf303f56c2eb9` — geometry invariant in release.
- `58130a0030440119ce3563dd5065928ba66d5a43` — geometry preflight.
- `da0d456826810543702e5c369dcdcc9785cfc542` — lifecycle-safe geometry.
- `66572f958ff0072e661666193635ddff61d99d20` — Main chat UI/native preview hardening.
- `8fd4cd687dc0812d6653e3c07be327913f1553c8` — preview ownership safety.
- `1374b1ecc603c618b36cb1e2cd5e543d80684d7b` — bounded native preview.
- `15c23932045984a44a0e2da961e06120ed542269` — separate canonical preview.
- `e16e78e1fa8492fc5fd7f90755b7caa19a9c9f2b` — reference geometry.
- `8e9eba1ecda8934f2db29c06a44f7d633b11dff9` — full-screen blur.
- `14510a187486fc338a2e84e6bf68182277792a18` — Play-stable incoming reply model.
- `0b714079ae536e1767cee0c9d883005e08ce67f6` — reply/menu runtime repair.
- `39f2166f2c24475cb584b318b0d16930da00f71f` — змішаний iOS + unrelated hot-path stability commit.
- `7dda3c378d2847c2b7172d3365aee6da780738aa` — sender context/full-width preview.
- `6119d09773f55193a5b2ab3d723ba9118c11cdb0` — native geometry clone.
- `1e6219874d91da202e2e2c87cbe6502d672d0e41` — native geometry audit.
- `5bf3093a9e6c15f461e6baf1a708252aaeb49803` — final unclipped sender header preview.
- `b01577919ed4e1d0c8e5e2b2473aa6eeca3e7a73` — guard accepting final explicit sender header.

## 7. Межі правильної повторної реалізації

### iOS Input

Дозволена зона:

- `ChatActivityEnterView`;
- `ChatActivityEnterViewAnimatedIconView`;
- `ChatInputViewsContainer`, лише якщо потрібні точні side-bubble bounds;
- setting/policy/strings.

Не змінювати:

- `ChatActivity` message loading;
- replies/crypto;
- deep-link routing;
- dialog adapter/filter/waiters;
- загальний chat header, крім точкового усунення MENU glyph/typing overlay за активного iOS Input.

### iOS Message Menu

Код має виконуватися лише після long press і тільки коли `iOSMessageMenu` увімкнено. Усі нові View повинні жити лише в popup lifecycle.

Не змінювати:

- завантаження/відкриття чату;
- `MessageObject` або вкладений `replyMessage`;
- settings links;
- `DialogsAdapter`;
- filters;
- CryptoInterceptor;
- Ayu waiters;
- Play paths.

Візуальна ціль:

- Telegram-native message rendering;
- ім’я завжди видно;
- avatar у правильній нижній груповій позиції;
- rich/grouped/link-preview message не деградує до raw text;
- коротке повідомлення не має зайвого scroll;
- довге повідомлення повністю доступне;
- жодного overlay поверх message body;
- reactions, message та actions не обрізають одне одного;
- остання дія меню завжди досяжна;
- popup не виходить за safe work area.

## 8. Висновок Кроку 0

Поточна проблема не зводиться до одного невдалого margin. Є три конкуруючі реалізації:

1. committed `dev` preview;
2. release-generated `main` preview;
3. історичні patchers/compatibility branches.

Перед відкатом потрібно зберегти backup поточного HEAD, обрати підтверджений clean commit і прибрати або нейтралізувати саме той patch-chain, який повторно матеріалізує старе меню. На Кроці 0 жоден runtime/source файл не змінювався; створено лише цей документ.
