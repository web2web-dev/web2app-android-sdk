# web2app Android SDK

Тонкий SDK, который связывает вашу веб-воронку с мобильным приложением: пользователь
проходит воронку в вебе, устанавливает приложение — и приложение узнаёт, **кто это** и
**что он оплатил**, чтобы сразу открыть платный контент. Матчинг «воронка → установка»
делается на нашей стороне, вам не нужно писать его логику.

- Язык: Kotlin · Платформа: Android 7.0+ (minSdk 24) · Лицензия: MIT
- Установка: Gradle через JitPack

---

## Установка (Gradle через JitPack)

**1. Добавьте репозиторий JitPack** (в `settings.gradle.kts`):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**2. Добавьте зависимость** (в `build.gradle.kts` модуля приложения):

```kotlin
dependencies {
    implementation("com.github.web2web-dev:web2app-android-sdk:0.7.2")
}
```

---

## Быстрый старт

Три шага. Больше для базовой интеграции ничего не нужно.

```kotlin
import app.web2app.sdk.Web2AppSdk

// 1. Инициализация — один раз при старте приложения.
Web2AppSdk.configure(
    context,
    projectId = "ВАШ_PROJECT_ID",                 // берётся в кабинете проекта
    baseUrl = "https://api.testfunnelsdev.click"
)

// 2. Идентификация при первом запуске.
//    SDK сам читает Google Play Install Referrer — вам ничего передавать не нужно.
Web2AppSdk.identify(
    onResult = { result ->
        result.onSuccess { guid -> /* пользователь опознан */ }
    },
    onNeedEmail = {
        // ни атрибуции, ни совпадения отпечатка (sideload / органика) —
        // покажите экран «введите email» и вызовите Web2AppSdk.requestEmailRecovery(...)
    },
)

// 3. Проверка доступа — в любой момент, чтобы открыть/закрыть платный контент.
Web2AppSdk.entitlement { grant ->
    if (grant?.isActive == true) {
        // разблокировать доступ
    }
}
```

### Тестовый режим проекта

Если проект в кабинете переведён в тестовый режим, бэкенд отдаёт **синтетический**
грант: он выглядит активным (`isActive == true`), но помечен `testMode == true`.
Это не настоящая оплата — **не выдавайте по такому гранту боевой контент**:

```kotlin
Web2AppSdk.entitlement { grant ->
    when {
        grant == null || !grant.isActive -> lock()
        grant.testMode -> {
            // тестовый доступ: открывайте контент только в dev/QA-сборках
        }
        else -> {
            // боевой доступ
        }
    }
}
```

У боевых грантов поле отсутствует или `false` — SDK читает его как `false`,
старые ответы без поля не ломаются.

### Где взять Project ID

В веб-кабинете: **проект → Настройки → «Подключение приложения» → «Полный мост»** —
там показан ваш Project ID (можно скопировать) и готовые сниппеты.

---

## API

| Метод | Назначение |
|---|---|
| `configure(context, projectId, baseUrl)` | Инициализация SDK. Вызвать один раз при старте. |
| `identify(onResult, onNeedEmail)` | Опознать пользователя. Порядок: сохранённый guid → Install Referrer → отпечаток устройства; только если промахнулось всё — `onNeedEmail`. |
| `requestEmailRecovery(email, onResult)` | Запросить восстановление по email — мы отправим пользователю ссылку-магнит. |
| `entitlement { grant -> }` | Получить текущий доступ (`grant.isActive`, `level`, `status`, `expiresAt`, `testMode` — см. «Тестовый режим проекта»). |
| `currentGuid()` | Текущий идентификатор пользователя (если уже опознан). |
| `openWebPaywall(context, paywallUrl, email) { grant -> }` | Показать веб-пейволл в Chrome Custom Tab; доступ придёт по guid-поллингу. |
| `openWebPaywallById(context, paywallId, email) { grant -> }` | То же по ID пейволла из кабинета — URL резолвится сам. |
| `openWebPaywallEmbedded(context, paywallUrl, email) { result -> }` | Встроенный показ (WebView + JS-мост): авто-закрытие на успехе, типизированный `PaywallResult`. |
| `openWebPaywallEmbeddedById(context, paywallId, email) { result -> }` | Встроенный показ по ID пейволла. |
| `openQuizEmbedded(context, quizUrl, email) { result -> }` | Встроенный показ КВИЗА (тот же WebView + мост). События прохождения идут в слушатель, колбэк — про закрытие экрана (`QuizResult`). |
| `handleReturnUrl(uri) { grant -> }` | Обработать возвратную ссылку `<схема>://handoff` (кнопка «Закрыть» на success-экране). |
| `identifyWithDeepLinkValue(code) { result -> }` | Опознать по одноразовому коду — из ссылки в письме после оплаты или из MMP-коллбека (AppsFlyer/Adjust). Возвращает `guid`. |
| `setFunnelEventListener { name, data -> }` | Слушать события прохождения квиза из встроенного показа. Пейволл они не закрывают — см. раздел ниже. |

Все пять методов показа принимают ещё два необязательных именованных параметра —
`adaptyProfileId` и `revenuecatProfileId` (см. раздел «Adapty / RevenueCat»).

Восстановление по email — два шага: `requestEmailRecovery(email)` отправляет пользователю
письмо со ссылкой; когда он по ней перейдёт, приложение получит код из диплинка и передаёт
его в `identifyWithDeepLinkValue(code)`.

**Колбэки всегда приходят на главный поток.** Это касается всех публичных методов,
включая `onNeedEmail` и ранние возвраты (например, вызов без `configure`): если вы уже
на главном потоке — колбэк выполнится синхронно, иначе будет доставлен через
`Handler.post`. Обновлять UI прямо в колбэке безопасно, руками на `runOnUiThread`
переключаться не нужно.

---

## Как это работает

1. Пользователь проходит вашу веб-воронку — мы знаем, кто он и что оплатил.
2. Он переходит в Google Play и ставит приложение. Идентификатор доезжает через
   **Install Referrer** — SDK читает его автоматически при первом запуске.
3. SDK опознаёт пользователя через наш сервер и связывает установку с вашим проектом.
4. `entitlement()` возвращает актуальный доступ — вы открываете платный контент.

Полный порядок опознания в `identify()` (каждый следующий шаг — только при
промахе предыдущего):

1. **Сохранённый guid** (кэш) — пользователь уже опознан ранее.
2. **Install Referrer** — идентификатор из ссылки установки Google Play.
3. **Токен из диплинка** — если у вас MMP (AppsFlyer/Adjust) или пользователь
   пришёл по ссылке из письма, токен передаётся отдельным методом
   `identifyWithDeepLinkValue(code)`.
4. **Отпечаток устройства** — сигналы устройства (версия Android, модель, экран
   в логических dp, таймзона, язык) сверяются с сигналами, которые веб-страница
   оставила в момент ухода покупателя в стор; guid приходит только при
   единственном уверенном совпадении. Рекламные идентификаторы (GAID,
   ANDROID_ID) не используются.
5. **Email** — только если промахнулось всё: `onNeedEmail`, экран «введите
   email» и `requestEmailRecovery(...)`.

---

## Возврат из веб-пейволла по своей схеме (handleReturnUrl)

Если вы открываете веб-пейволл через `openWebPaywall` (внешний Custom Tab),
кнопка «Закрыть» на экране после оплаты может вернуть пользователя прямо в
приложение по вашей URL-схеме. Настройка:

1. Объявите intent-filter своей схемы в манифесте (activity, которая примет возврат):

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="mycoolapp" />
</intent-filter>
```

2. Укажите ту же схему в кабинете проекта (настройки подключения приложения —
   поле «Схема возврата»). Ссылка кнопки станет `mycoolapp://handoff?code=...`.

3. Передавайте ВСЕ входящие deep-link в SDK — чужие он вернёт `false`:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    intent?.data?.let { uri ->
        Web2AppSdk.handleReturnUrl(uri) { grant ->
            if (grant?.isActive == true) unlockPremium()
        }
    }
}
```

При распознавании SDK сразу коротко поллит доступ (не ждёт планового окна) —
пользователь возвращается «уже платным». `code` из ссылки SDK намеренно не
использует: доступ приходит по guid, а токен остаётся валидным для письма.

## Встроенный веб-пейволл (WebView + JS-мост)

`openWebPaywallEmbedded` показывает пейволл во встроенном WebView. Страница
сама сообщает SDK об успехе оплаты — пейволл закрывается автоматически, схема
возврата не нужна. Результат типизирован:

```kotlin
Web2AppSdk.openWebPaywallEmbedded(context, paywallUrl = url) { result ->
    when (result) {
        is PaywallResult.Paid -> unlockPremium(result.grant)
        PaywallResult.Pending -> showProcessing() // перепроверьте entitlement позже
        PaywallResult.NotPaid -> keepFreeTier()
        PaywallResult.Unavailable -> showError() // пейволл не показали
    }
}
```

`Unavailable` ≠ `NotPaid`: первый означает, что экран вообще не открылся (SDK не
сконфигурирован или URL пейволла не зарезолвился) либо показ сорвался — процесс
страницы WebView погиб дважды подряд (с 0.7.2; первый раз SDK сам пересоздаёт
WebView и грузит страницу заново, приложение при этом не падает), второй — что
пейволл показали, но оплату не подтвердили.

Открытие по ID (`openWebPaywallById` / `openWebPaywallEmbeddedById`): пейволл
должен быть опубликован и привязан к домену — иначе колбэк получит
null (не-embedded) / `Unavailable` (embedded).

Два встроенных пейволла одновременно не висят: **новый показ вытесняет
предыдущий** — если вызвать `openWebPaywallEmbedded` повторно, пока первый экран
ещё открыт, старый показ завершается, и дальше работает только новый.

С 0.7.2 пока страница грузится, по центру виден системный индикатор загрузки
(вместо белого экрана), а поворот экрана показ не закрывает и страницу не
перезагружает — это касается и встроенного квиза.

---

## Встроенный квиз (`openQuizEmbedded`)

Квиз открывается тем же встроенным WebView, что и пейволл, — отдельной настройки
не требуется:

```kotlin
Web2AppSdk.setFunnelEventListener { name, data -> analytics.log(name) }

Web2AppSdk.openQuizEmbedded(context, quizUrl = "https://client.example.com/q/quiz-1") { result ->
    when (result) {
        is QuizResult.Closed -> when (result.reason) {
            QuizCloseReason.PAID -> Web2AppSdk.entitlement { grant ->
                if (grant?.isActive == true) unlockPremium()   // оплата прошла в том же окне
            }
            QuizCloseReason.PAGE -> goBackToApp()               // страница попросила закрыть
            QuizCloseReason.USER -> goBackToApp()               // крестик или системный «назад»
        }
        QuizResult.Unavailable -> showError()                   // квиз не показали: нет configure
    }
}
```

Что важно знать:

- **Результат другой, чем у пейволла.** У квиза нет оплаты, поэтому метод не
  возвращает `PaywallResult` и ничего не поллит. Наблюдаемое — поток событий
  прохождения (`quiz_start`, `quiz_screen_view`, `quiz_answer`,
  `quiz_email_submit`, `quiz_complete`), он идёт в `setFunnelEventListener`
  (см. раздел «События воронки»), — плюс факт закрытия экрана в колбэке.
- **`quiz_complete` экран НЕ закрывает.** После квиза страница часто сама ведёт
  на пейволл внутри того же WebView — закрытие на «квиз пройден» оборвало бы
  воронку перед оплатой. Показ завершают те же два события, что и у пейволла
  (успех оплаты и «Закрыть»), плюс закрытие юзером; какое именно — видно в
  `QuizResult.Closed.reason`.
- **URL берётся готовый.** Резолва «URL квиза по ID» на бэкенде нет (ручка
  резолва существует только для пейволлов), поэтому `openQuizById` в SDK
  отсутствует — передавайте опубликованный URL квиза.
- **guid уезжает в URL так же, как для пейволла** (`origin=app&guid=...`) — веб
  связывает прохождение квиза с тем же пользователем, что и последующую оплату.
  Без `configure` guid некуда сохранить, поэтому экран не показывается:
  `QuizResult.Unavailable`.
- Показ один на SDK: открытый квиз вытесняется новым показом (квиза или
  пейволла) — так же, как встроенные пейволлы вытесняют друг друга.

---

## Adapty / RevenueCat: передать profile-id

Если подписки у вас на Adapty или RevenueCat, передайте profile-id их SDK при
открытии веб-страницы — все пять методов показа принимают необязательные
`adaptyProfileId` и `revenuecatProfileId`:

```kotlin
Web2AppSdk.openWebPaywallEmbedded(
    context,
    paywallUrl = url,
    adaptyProfileId = Adapty.getProfileId(),
    revenuecatProfileId = Purchases.sharedInstance.appUserID,
) { result -> /* ... */ }
```

Порядок такой: сначала получаете profile-id из SDK подписочной платформы,
потом открываете веб-страницу. SDK дописывает его в URL
(`adapty_profile_id` / `revenuecat_profile_id`); не переданные (`null` или пустая
строка) в URL не попадают вовсе.

**Как сервер пишет связку `guid` ↔ profile-id (важно).** Страница записывает
значение только в **пустой** слот. Если слот уже занят:

- заменить сохранённое значение через страницу **нельзя** — только серверной
  ручкой `POST /s2s/v1/identity/link-profile` (Bearer-ключ `sk_…`, право
  `identity:write`);
- дописать **вторую** платформу можно, только если в этом же открытии предъявлены
  верные значения всех уже занятых слотов.

Отсюда практическое правило: **передавайте оба идентификатора при каждом
открытии страницы**, а не «один из двух» — тогда доказательство всегда при
запросе, и вторая платформа допишется. Полагаться на «сервер сам разберётся»
нельзя.

⚠ **При неполном наборе отказ молчаливый.** Сервер отвечает «успех» в любом
случае — так сделано, чтобы по ответу нельзя было перебирать плательщиков.
Значит, по ответу вы не увидите, что связка не записалась; отказ виден только в
мониторинге на нашей стороне.

⚠ **Не используйте наш `guid` как profile-id подписочной платформы.** Если
приложение делало `Purchases.logIn(<наш guid>)` или `Adapty.identify(<наш guid>)`,
то предъявленное значение совпадёт с guid — такое доказательство сервер не
принимает, и дозапись второй платформы через страницу не сработает никогда.
Отдавайте платформам их собственный идентификатор пользователя.

---

## События воронки (`setFunnelEventListener`)

Страница во встроенном WebView сообщает SDK о прохождении квиза. Подписка —
одна точка, слушатель получает **имя события** и **данные**; колбэк приходит на
главный поток, из него можно сразу трогать UI.

```kotlin
Web2AppSdk.setFunnelEventListener { name, data ->
    analytics.log(
        name,
        mapOf(
            "screen_id" to data.screenId,
            "screen_index" to data.screenIndex,
            "screen_total" to data.screenTotal,
            "block_id" to data.blockId,
            "block_type" to data.blockType,
        ),
    )
}

Web2AppSdk.setFunnelEventListener(null) // отписаться
```

Какие события приходят сегодня:

| Событие | Когда | Заполненные поля |
|---|---|---|
| `quiz_start` | воронка открылась | — |
| `quiz_screen_view` | показан экран | `screenId`, `screenIndex`, `screenTotal` |
| `quiz_answer` | дан ответ на блоке | `screenId`, `screenIndex`, `screenTotal`, `blockId`, `blockType` |
| `quiz_email_submit` | отправлен email | — |
| `quiz_complete` | квиз пройден | — |
| `paywall_result` | исход пейволла | — |
| `close` | тап по «Закрыть» на странице | — |

**Эти события не закрывают пейволл.** Показ завершают ровно два случая:
`paywall_result` со статусом успеха и `close` — их SDK обрабатывает сам и отдаёт
результат в `onResult` метода `openWebPaywallEmbedded`. Всё остальное, включая
все `quiz_*`, только уведомляет слушателя, WebView остаётся открытым.

То же правило действует и для встроенного квиза (`openQuizEmbedded`): `quiz_complete`
экран не закрывает — см. раздел «Встроенный квиз».

Про поля: PII через мост не ходит — email и сами тексты ответов страница
вырезает, в `FunnelEventData` приезжают только идентификаторы. Любое поле может
отсутствовать (тогда `null`) — это норма, а не ошибка. Незнакомое событие SDK
молча передаёт слушателю и ничего не ломает, так что новые события со стороны
веба не требуют обновления SDK.

---

## Отличия от iOS SDK (осознанные)

Оба SDK дают одну и ту же интеграцию, но часть вещей на Android устроена иначе —
это не баги и не отставание, а следствие платформы:

- **Поллинг права в не-embedded `openWebPaywall` стартует сразу**, а не после
  закрытия пейволла: Chrome Custom Tab не отдаёт приложению колбэк закрытия, так
  что «момент возврата» отследить нечем.
- **`handleReturnUrl` имеет собственный `onResult` с грантом** — возврат по
  своей схеме приходит в отдельную точку входа (в iOS результат приезжает иначе).
- **Имена `openWebPaywallById` / `openWebPaywallEmbeddedById`** вместо перегрузок
  одного метода: в Kotlin перегрузки с одинаковой сигнатурой (`String` URL против
  `String` ID) неразличимы, поэтому у открытия по ID отдельное имя.
- **`identify()` без аргумента читает Google Play Install Referrer сам** —
  на iOS источника атрибуции такого рода нет, там значение передаёт интегратор.
- **`debugSetGuid` / `debugClear` видны и в release-сборке.** На iOS они закрыты
  `#if DEBUG` и в релиз не попадают; Android-библиотека не имеет доступа к
  `BuildConfig.DEBUG` приложения-хоста, поэтому вырезать их аналогично нельзя.
  Вместо обрезки методы помечены `@Deprecated` (WARNING) — компилятор
  предупредит на каждом вызове. Оборачивайте их в `if (BuildConfig.DEBUG)` на
  своей стороне или убирайте перед релизом.

---

## Возврат в приложение (App Links): что передать владельцу воронки (WEB-802)

Чтобы возвратные ссылки после оплаты (в т.ч. из письма) открывали ваше
приложение, владелец воронки вписывает в кабинете (Настройки проекта →
Подключение приложения → App ID) два значения, которые даёте вы:

- **Package name** — `applicationId` из `build.gradle` модуля приложения,
  например `com.example.android`.
- **SHA-256 отпечаток ключа подписи** — строго из **Play Console → Настройки →
  Целостность приложения → Подпись приложений** (ключ, которым подписывает
  Google Play при публикации). ⚠ НЕ локальный upload-key (`keytool` по своему
  keystore даёт другой отпечаток) — это самая частая ошибка, при ней App Links
  молча не работают.

После сохранения файл `/.well-known/assetlinks.json` на домене возврата отдаёт
конфигурацию автоматически. На вашей стороне — intent-filter на домен возврата
с автопроверкой:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="<projectId>.go.<домен>" />
</intent-filter>
```

Как обработать код из пути — раздел «Ссылка из письма после оплаты» ниже.

## Ссылка из письма после оплаты (App Link)

После успешной оплаты покупателю приходит письмо со ссылкой вида
`https://<projectId>.go.<домен>/handoff/<КОД>` — одноразовый 8-символьный код в
пути. Если App Links настроены (раздел выше), Android откроет ваше приложение —
обработчик пишете вы (ссылка приходит приложению, SDK перехватить её не может):

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleIncomingLink(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleIncomingLink(intent)
}

private fun handleIncomingLink(intent: Intent?) {
    val uri = intent?.data ?: return
    // 1) Кнопка «Закрыть» с веб-пейволла (<схема>://handoff?...) — отдаём SDK,
    //    чужие ссылки он вернёт false и можно обрабатывать свои deep-link'и.
    if (Web2AppSdk.handleReturnUrl(uri) { grant ->
            if (grant?.isActive == true) unlockPremium()
        }
    ) return
    // 2) Ссылка из письма: https-ссылка с кодом в пути /handoff/<КОД>
    val seg = uri.pathSegments
    if (seg.size >= 2 && seg[0] == "handoff") {
        Web2AppSdk.identifyWithDeepLinkValue(seg[1]) { result ->
            result.onSuccess {
                Web2AppSdk.entitlement { grant ->
                    if (grant?.isActive == true) unlockPremium()
                }
            }.onFailure {
                // Код одноразовый (повторный тап по письму = ошибка). guid уже
                // мог быть сохранён ранее — сперва проверьте entitlement(), и
                // только при пустом ответе показывайте экран «введите email» с
                // requestEmailRecovery(email).
            }
        }
    }
}
```

Не путать с кнопкой «Закрыть»: её ссылка — кастомная схема
`<схема>://handoff?code=...`, она обрабатывается `handleReturnUrl(uri)` и код
намеренно не тратит (доступ приходит по guid-поллингу). Ссылка из письма —
https App Link с кодом в пути, её обрабатывает `identifyWithDeepLinkValue(code)`
и код расходует.

## Журнал SDK (диагностика интеграции)

SDK ведёт удалённый журнал своих внутренних шагов — он нужен, чтобы мы могли
диагностировать интеграцию, не прося вас снимать логи Logcat. Как устроено:

- **Что отправляется:** технические шаги SDK (инициализация, ветки опознания,
  запрос права, открытия пейволла/квиза, события моста, ошибки сети с
  HTTP-кодами) плюс контекст устройства — модель, версия Android, версия и
  package name вашего приложения, наш `guid`.
- **Что НЕ отправляется:** email и любые другие персональные данные — в журнал
  попадает только факт «email передан»; тексты ответов квиза и содержимое
  страниц не логируются. `guid` — наш собственный идентификатор, к рекламным
  идентификаторам (GAID, ANDROID_ID) SDK не обращается.
- Отправка — fire-and-forget пачками на наш бэкенд: сбой сети или бэкенда
  никак не влияет на работу SDK и не задерживает колбэки.
- Те же записи дублируются в Logcat с тегом `Web2App` — удобно смотреть шаги
  SDK при отладке у себя.

## Приватность

- Идентификатор пользователя (`guid`) хранится в EncryptedSharedPreferences, никакой
  рекламный трекинг SDK сам не ведёт.
- Опознание по отпечатку использует только неперсональные сигналы устройства
  (модель, версия Android, экран, таймзона, язык) — без GAID и ANDROID_ID.
- Зависимости: Google Play Install Referrer, AndroidX Security-Crypto.

---

## iOS

Отдельный пакет: https://github.com/web2web-dev/web2app-ios-sdk

## Безопасность: SDK ≠ серверные ключи

SDK работает ТОЛЬКО с публичным `projectId` — этого достаточно для распознавания
пользователя и проверки доступа с устройства. **Никогда не встраивайте серверный
API-ключ (`sk_live_…`) в приложение** — он даёт доступ к данным проекта и
предназначен только для server-to-server вызовов с вашего бэкенда. Про
S2S-аутентификацию (Bearer `sk_`, ручки `/s2s/v1/*`, входящие/исходящие вебхуки с
подписью) — см. раздел «Аутентификация» в документации для разработчиков
(dev-docs.html в кабинете проекта).

## Лицензия

MIT.
