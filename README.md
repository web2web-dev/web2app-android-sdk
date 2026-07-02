# web2app Android SDK (скелет) — WEB-434

Тонкий Android-SDK для P10-моста web→app. **IP наш, MIT.** Модель B+C (тонкая обвязка над
нашим backend, не полный native руками).

> ⚠ **Статус: СКЕЛЕТ.** Реализовано по контрактам backend: R1-passthrough, APP_INSTALLED,
> guid-persist, token/email-resolve, Install Referrer read. Полная раздача клиентам — после
> достройки + POC (см. WEB-434).

## Установка (Gradle)
После публикации в Maven Central (`mavenCentral()` уже подключён по умолчанию):
```kotlin
dependencies {
    implementation("io.github.web2web-dev:web2app-sdk:0.1.0")
}
```
(До публикации — собирается из исходников этого репо.)

## API (4 точки, Web2Wave-стиль)
```kotlin
Web2AppSdk.configure(context, projectId = "proj_…", baseUrl = "https://api.…")

Web2AppSdk.identify(                       // Android сам читает Install Referrer
    onResult = { r -> /* guid */ },
    onNeedEmail = { /* показать экран email */ },
)
Web2AppSdk.requestEmailRecovery(email) { _ -> }   // сервер шлёт magic-link (204)

Web2AppSdk.entitlement { grant -> if (grant?.isActive == true) unlock() }   // R1 passthrough
```

## Принципы (WEB-428)
- `guid` = client-held ключ (EncryptedSharedPreferences); `email` = recovery.
- **Свой fingerprint НЕ строим** — Install Referrer (детерминир.) + email-ядро.
- `entitlement()` дословно проксирует наш R1 (не тронут).

## Backend-контракты (сверены с кодом)
| Точка | Метод |
|---|---|
| Entitlement (R1) | `GET /public/entitlement?guid=` → `{grants:[…]}` |
| App-installed | `POST /public/handoff/app-callback` → 204 |
| Token→guid | `GET /public/handoff/resolve?code=` → `{guid}` |
| Email-recovery | `POST /public/handoff/email-recovery/request` → 204 (magic-link) |

## Сборка
```
./gradlew assembleRelease      # AAR
./gradlew publishToMavenLocal   # локальная проверка публикации (после настройки Maven)
```
minSdk 24, JDK 17, AGP 8.5, Gradle 8.9.

## iOS
Отдельный репо: https://github.com/web2web-dev/web2app-ios-sdk
