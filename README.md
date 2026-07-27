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
    implementation("com.github.web2web-dev:web2app-android-sdk:0.1.0")
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
        // атрибуции нет (sideload / органика) — покажите экран «введите email»
        // и вызовите Web2AppSdk.requestEmailRecovery(...)
    },
)

// 3. Проверка доступа — в любой момент, чтобы открыть/закрыть платный контент.
Web2AppSdk.entitlement { grant ->
    if (grant?.isActive == true) {
        // разблокировать доступ
    }
}
```

### Где взять Project ID

В веб-кабинете: **проект → Настройки → «Подключение приложения» → «Полный мост»** —
там показан ваш Project ID (можно скопировать) и готовые сниппеты.

---

## API

| Метод | Назначение |
|---|---|
| `configure(context, projectId, baseUrl)` | Инициализация SDK. Вызвать один раз при старте. |
| `identify(onResult, onNeedEmail)` | Опознать пользователя. Android сам читает Install Referrer; при промахе — `onNeedEmail`. |
| `requestEmailRecovery(email, onResult)` | Запросить восстановление по email — мы отправим пользователю ссылку-магнит. |
| `entitlement { grant -> }` | Получить текущий доступ (`grant.isActive`, `level`, `status`, `expiresAt`). |
| `currentGuid()` | Текущий идентификатор пользователя (если уже опознан). |

Восстановление по email — два шага: `requestEmailRecovery(email)` отправляет пользователю
письмо со ссылкой; когда он по ней перейдёт, приложение получит код из диплинка и передаёт
его в `identifyWithDeepLinkValue(code)`.

---

## Как это работает

1. Пользователь проходит вашу веб-воронку — мы знаем, кто он и что оплатил.
2. Он переходит в Google Play и ставит приложение. Идентификатор доезжает через
   **Install Referrer** — SDK читает его автоматически при первом запуске.
3. SDK опознаёт пользователя через наш сервер и связывает установку с вашим проектом.
4. `entitlement()` возвращает актуальный доступ — вы открываете платный контент.

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

Код из пути (`/handoff/<КОД>`) обрабатывается так же, как в iOS-версии:
резолв через `GET /public/handoff/resolve?code=` → `identify(guid)`.

## Приватность

- Идентификатор пользователя (`guid`) хранится в EncryptedSharedPreferences, никакой
  рекламный трекинг SDK сам не ведёт.
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
