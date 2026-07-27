# Changelog

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
