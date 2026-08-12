# Changelog

## 0.4.3 — 2026-08-12

Паритет с iOS SDK 0.4.3. Номер выровнен с эталоном: координата публикации
(`build.gradle.kts`, README) отставала на `0.1.0`, пока changelog уже шёл по 0.4.x.

- **Починен парс ответа `GET /public/handoff/resolve?code=`**: guid лежит внутри
  обёртки `{"success":true,"data":{...}}`, а читался с верхнего уровня. Из-за
  этого `identify()` и `identifyWithDeepLinkValue()` не работали вообще —
  теперь работают (проверено на живом проде).
- ⚠ **Потенциально ломающее изменение:** у `PaywallResult` появился четвёртый
  вариант — `Unavailable` («пейволл не показали»: SDK не сконфигурирован или URL
  по `paywallId` не зарезолвился) — отдельно от `NotPaid` («показали, но оплату
  не подтвердили»). Исчерпывающий `when` по `PaywallResult` без `else`
  перестанет компилироваться, пока не добавите ветку.
- Окно поллинга права после закрытия встроенного пейволла — 10 попыток по 1 с
  для всех исходов, включая нативное закрытие крестиком (было 2 попытки).
- Все колбэки публичных методов (включая `onNeedEmail` и ранние возвраты)
  доставляются на главный поток: на главном — синхронно, иначе `Handler.post`.
- Новый встроенный показ вытесняет предыдущий — два пейволла одновременно не
  висят, колбэк вытесненного закрывается.
- `debugSetGuid` / `debugClear` помечены `@Deprecated` (WARNING). В Android
  библиотека не видит `BuildConfig.DEBUG` приложения, поэтому аналога
  iOS-обрезки `#if DEBUG` нет — предупреждение компилятора заменяет её.
  Методы остаются рабочими, поведение не изменилось.
- CI: `.github/workflows/ci.yml` — `./gradlew test` на JDK 17 (push + PR).

## 0.4.0 — 2026-07-27

Паритет iOS SDK 0.4.x (WEB-814):

- `openWebPaywallById` / `openWebPaywallEmbeddedById` — открытие пейволла по ID
  из кабинета (резолв публичного URL через `GET /public/paywall-url/:paywallId`).
- `openWebPaywallEmbedded` — встроенный показ (full-screen WebView + JS-мост
  `web2appBridge`): авто-закрытие на успехе оплаты, кнопка «Закрыть» страницы
  идёт мостом, нативный крестик-фолбэк (frosted-стиль, как iOS 0.4.3).
- `PaywallResult` — типизированный результат: `Paid(grant)` / `NotPaid` /
  `Pending` (окно истекло, доступ может доехать позже).

## 0.3.0 — 2026-07-27

Паритет iOS SDK 0.3.0 (WEB-813):

- `handleReturnUrl(url|uri)` — обработка возвратного deep-link
  `<схема-прилки>://handoff?code=...` (кнопка «Закрыть» на success-экране,
  WEB-800): распознавание по host `handoff` + немедленный короткий поллинг
  права. `code` намеренно не консьюмится (паритет iOS). README: настройка
  intent-filter и схемы в кабинете.

## 0.2.0 — 2026-07-16

- `openWebPaywall` — обратный флоу app→web-paywall (Chrome Custom Tab +
  guid-поллинг права, WEB-525 под-атом C).

## 0.1.0 — 2026-07-08

- Скелет SDK (WEB-434): configure / identify (Install Referrer + email-fallback
  + deep_link_value) / entitlement / currentGuid; JitPack-публикация.
