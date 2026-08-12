package app.web2app.sdk

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID

/**
 * web2app SDK — публичная поверхность (4 точки, Web2Wave-стиль). WEB-434.
 *
 * R1 (чтение права) НЕ тронут — [entitlement] дословно проксирует
 * `GET /public/entitlement?guid=`. R2 = обвязка доставки guid перед R1.
 *
 * **Поток колбэков (паритет iOS 0.4.1).** Результат ЛЮБОГО метода ниже —
 * включая `onNeedEmail` и ранние возвраты вроде «нет configure» — приходит на
 * ГЛАВНЫЙ поток: из колбэка можно сразу трогать UI. Если метод вызван уже на
 * главном потоке, колбэк исполняется синхронно, без прыжка через очередь
 * Looper'а; правило одинаковое для всех методов и всех веток одного метода
 * (см. [MainThread]).
 *
 * Использование:
 * ```
 * Web2AppSdk.configure(context, projectId = "proj_...", baseUrl = "https://api.example.com")
 * Web2AppSdk.identify()                       // Android: авто-чтение Install Referrer
 * Web2AppSdk.entitlement { grant -> if (grant?.isActive == true) unlock() }
 * ```
 */
object Web2AppSdk {
    private var config: Web2AppConfig? = null
    private lateinit var guidStore: GuidStore

    /** Инициализация. [projectId] = ключ проекта арендатора; [baseUrl] = наш API. */
    fun configure(context: Context, projectId: String, baseUrl: String) {
        config = Web2AppConfig(projectId, baseUrl.trimEnd('/'))
        guidStore = GuidStore(context.applicationContext)
    }

    /**
     * Резолвит и персистит guid. Порядок (первый запуск):
     *  1. Сохранённый guid → возвращаем (steady-state).
     *  2. Install Referrer (`&referrer=<token>`) → resolve → guid.
     *  3. Промах (Huawei/sideload/органика, FEATURE_NOT_SUPPORTED) → [onNeedEmail] (email-fallback).
     * На успехе — APP_INSTALLED-продюсер (best-effort).
     */
    fun identify(
        onResult: (Result<String>) -> Unit = {},
        onNeedEmail: () -> Unit = {},
    ) {
        // Обёртка на главный поток РОВНО одна и на входе: ниже по коду (включая
        // ранние возвраты и колбэк резолвера) зовём только обёрнутые.
        val deliver = MainThread.wrap(onResult)
        val deliverNeedEmail = MainThread.wrapNoArgs(onNeedEmail)

        val cfg = config ?: return deliver(Result.failure(IllegalStateException("not configured")))

        guidStore.load()?.let { return deliver(Result.success(it)) }

        InstallReferrerResolver(cfg).readAndResolve(guidStore.context) { result ->
            result.onSuccess { guid ->
                guidStore.save(guid)
                AppCallbackProducer(cfg).reportAppInstalled(guid)
                deliver(Result.success(guid))
            }.onFailure {
                // Промах referrer → email-fallback (НЕ падаем молча).
                deliverNeedEmail()
            }
        }
    }

    /**
     * email-fallback (WEB-431, ДВА шага — асинхронно):
     *  (1) requestEmailRecovery → сервер шлёт magic-link на email (204). guid тут НЕ приходит.
     *  (2) юзер открывает ссылку из письма → приложение получает code из deeplink →
     *      identifyWithDeepLinkValue(code) резолвит guid (тот же resolve-путь).
     */
    fun requestEmailRecovery(email: String, onResult: (Result<Unit>) -> Unit) {
        val deliver = MainThread.wrap(onResult)
        val cfg = config ?: return deliver(Result.failure(IllegalStateException("not configured")))
        AttributionResolver(cfg).requestEmailRecovery(email, deliver)
    }

    /**
     * Для проектов с MMP (AppsFlyer/Adjust): интегратор передаёт deep_link_value из своего
     * MMP-callback. **[POC-1]** — валидируется на реальном девайсе (доезжает ли deep_link_value).
     */
    fun identifyWithDeepLinkValue(token: String, onResult: (Result<String>) -> Unit) {
        val deliver = MainThread.wrap(onResult)
        val cfg = config ?: return deliver(Result.failure(IllegalStateException("not configured")))
        AttributionResolver(cfg).resolveToken(token) { result ->
            result.onSuccess { guid ->
                guidStore.save(guid)
                AppCallbackProducer(cfg).reportAppInstalled(guid)
            }
            deliver(result)
        }
    }

    /** Читает право по сохранённому guid — passthrough `GET /public/entitlement?guid=`. */
    fun entitlement(onResult: (EntitlementGrant?) -> Unit) {
        val deliver = MainThread.wrap(onResult)
        val cfg = config
        val guid = if (::guidStore.isInitialized) guidStore.load() else null
        if (cfg == null || guid == null) return deliver(null)
        EntitlementClient(cfg).fetch(guid, deliver)
    }

    /** Текущий guid (client-held ключ). */
    fun currentGuid(): String? =
        if (::guidStore.isInitialized) guidStore.load() else null

    /**
     * WEB-525 R2 — обратный флоу app→web-paywall. Показывает веб-пейвол органик-юзеру
     * (пришёл в прилку НЕ через воронку) в Chrome Custom Tab и возвращает право после оплаты.
     *
     * Возврат = **guid-поллинг** (ратиф. PM 2026-07-07): SDK кейит checkout на СВОЙ guid
     * (`?origin=app&email=&guid=`), затем поллит [entitlement] по нему — БЕЗ resolve-by-email
     * (тот S2S-HMAC, мобильному недоступен). Custom Tab не даёт надёжного close-колбэка, поэтому
     * поллинг стартует сразу после запуска (юзер платит и возвращается — грант ловится в окне).
     *
     * [paywallUrl] — URL опубликованного веб-пейвола (кастом-домен клиента; наши apex/поддомены
     * пейволы не отдают — WEB-395). [email] — опц. prefill (юзер может поправить на вебе).
     * [onResult] — активный [EntitlementGrant] или null, если за окно право не появилось.
     *
     * ⚠ MVP-1: принимает готовый [paywallUrl]. Серверный резолв projectId→дефолт-пейвол-URL —
     * отдельный follow-up.
     */
    fun openWebPaywall(
        context: Context,
        paywallUrl: String,
        email: String? = null,
        onResult: (EntitlementGrant?) -> Unit = {},
    ) = openWebPaywallInternal(context, paywallUrl, email, MainThread.wrap(onResult))

    /**
     * Общее тело [openWebPaywall] и [openWebPaywallById]. [deliver] уже обёрнут
     * вызывающим — здесь НЕ оборачиваем повторно (иначе делегирование `*ById`
     * дало бы двойную обёртку на одну доставку).
     */
    private fun openWebPaywallInternal(
        context: Context,
        paywallUrl: String,
        email: String?,
        deliver: (EntitlementGrant?) -> Unit,
    ) {
        val cfg = config ?: return deliver(null)

        // guid-поллинг: берём client-held guid или чеканим новый — grant на вебе ляжет на него.
        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)

        val url = WebPaywallLauncher.appOriginUrl(paywallUrl, email, guid)
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))

        // Поллим право по нашему guid: 30 попыток × 2с ≈ 60с (покрывает Stripe webhook→grant).
        val client = EntitlementClient(cfg)
        WebPaywallLauncher.pollForActiveGrant(
            intervalMs = 2_000,
            maxAttempts = 30,
            fetch = { cb -> client.fetch(guid, cb) },
            completion = deliver,
        )
    }

    /**
     * WEB-813 — обработчик возвратного deep-link'а из веб-пейволла (паритет iOS
     * handleReturnURL 0.3.0): кнопка «Закрыть» на success-экране ведёт на
     * `<схема-прилки>://handoff?code=...` (WEB-800). Интегратор: (1) объявляет
     * intent-filter своей схемы и указывает её в кабинете проекта
     * (bridgeConfig.returnScheme); (2) зовёт этот метод из onCreate/onNewIntent
     * для ВСЕХ входящих deep-link — чужие вернут false.
     *
     * При распознавании: приложение уже на переднем плане (deep-link вывел его
     * поверх Custom Tab) — SDK запускает НЕМЕДЛЕННЫЙ короткий поллинг права
     * (не ждёт планового окна исходного openWebPaywall) и отдаёт грант в
     * [onResult]. `code` из ссылки намеренно НЕ консьюмится: доступ приходит по
     * guid, а токен остаётся валидным для магик-линк письма.
     */
    @JvmOverloads
    fun handleReturnUrl(
        url: String,
        onResult: (EntitlementGrant?) -> Unit = {},
    ): Boolean {
        // Чужой deep-link: колбэк не зовём вовсе — контракт «false и тишина».
        if (!WebPaywallLauncher.isHandoffReturnUrl(url)) return false
        val deliver = MainThread.wrap(onResult)
        val cfg = config
        val guid = if (::guidStore.isInitialized) guidStore.load() else null
        if (cfg == null || guid == null) {
            deliver(null)
            return true
        }
        val client = EntitlementClient(cfg)
        WebPaywallLauncher.pollForActiveGrant(
            intervalMs = 1_000,
            maxAttempts = 10,
            fetch = { cb -> client.fetch(guid, cb) },
            completion = deliver,
        )
        return true
    }

    /** Перегрузка для intent.data. */
    @JvmOverloads
    fun handleReturnUrl(
        uri: Uri,
        onResult: (EntitlementGrant?) -> Unit = {},
    ): Boolean = handleReturnUrl(uri.toString(), onResult)

    /**
     * WEB-814 — открытие веб-пейволла ПО ID (паритет iOS openWebPaywall(paywallId:)
     * 0.4.0): интегратор знает только `paywallId` из кабинета — SDK резолвит
     * публичный URL через `GET /public/paywall-url/:paywallId` (пейволл должен
     * быть опубликован и привязан к домену; иначе 404 → onResult(null)) и
     * открывает его существующим [openWebPaywall]-флоу.
     */
    fun openWebPaywallById(
        context: Context,
        paywallId: String,
        email: String? = null,
        onResult: (EntitlementGrant?) -> Unit = {},
    ) {
        // Обёртка одна — делегируем УЖЕ обёрнутый колбэк во внутреннее тело.
        val deliver = MainThread.wrap(onResult)
        resolvePaywallUrl(paywallId) { url ->
            if (url == null) deliver(null)
            else openWebPaywallInternal(context, url, email, deliver)
        }
    }

    /**
     * WEB-814 — встроенный показ веб-пейволла (паритет iOS openWebPaywallEmbedded
     * 0.4.0): full-screen WebView + JS-мост `web2appBridge`. На успех оплаты
     * пейволл закрывается АВТОМАТИЧЕСКИ (страница шлёт событие мосту), кнопка
     * «Закрыть» тоже идёт мостом — URL-схема не нужна. Результат типизирован:
     * [PaywallResult.Paid] / [PaywallResult.NotPaid] / [PaywallResult.Pending];
     * [PaywallResult.Unavailable] — если пейволл вообще не показали (нет configure).
     */
    fun openWebPaywallEmbedded(
        context: Context,
        paywallUrl: String,
        email: String? = null,
        onResult: (PaywallResult) -> Unit,
    ) = openWebPaywallEmbeddedInternal(context, paywallUrl, email, MainThread.wrap(onResult))

    /**
     * Общее тело [openWebPaywallEmbedded] и [openWebPaywallEmbeddedById].
     * [deliver] уже обёрнут вызывающим — повторно НЕ оборачиваем.
     */
    private fun openWebPaywallEmbeddedInternal(
        context: Context,
        paywallUrl: String,
        email: String?,
        deliver: (PaywallResult) -> Unit,
    ) {
        // Без configure пейволл не показать — это НЕ «не оплатил» (паритет iOS .unavailable).
        val cfg = config ?: return deliver(PaywallResult.Unavailable)
        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)

        val url = WebPaywallLauncher.appOriginUrl(paywallUrl, email, guid)
        val client = EntitlementClient(cfg)
        val callbackId = UUID.randomUUID().toString()
        EmbeddedPaywallCallbacks.register(callbackId) { event ->
            // Окно поллинга одинаковое для всех исходов, включая нативное закрытие
            // (event == null): вебхук Stripe доезжает секундами позже закрытия окна.
            val attempts = WebPaywallLauncher.embeddedPollAttempts(event)
            WebPaywallLauncher.pollForActiveGrant(
                intervalMs = 1_000,
                maxAttempts = attempts,
                fetch = { cb -> client.fetch(guid, cb) },
            ) { grant ->
                when {
                    grant != null -> deliver(PaywallResult.Paid(grant))
                    event == BridgeEvent.PAYMENT_SUCCESS -> deliver(PaywallResult.Pending)
                    else -> deliver(PaywallResult.NotPaid)
                }
            }
        }
        EmbeddedPaywallActivity.start(context, url, callbackId)
    }

    /**
     * Встроенный показ по paywallId — резолв той же публичной ручкой.
     * Не зарезолвился URL (не опубликован / нет домена / 404) → пейволл не показали:
     * [PaywallResult.Unavailable], а не [PaywallResult.NotPaid].
     */
    fun openWebPaywallEmbeddedById(
        context: Context,
        paywallId: String,
        email: String? = null,
        onResult: (PaywallResult) -> Unit,
    ) {
        // Обёртка одна — делегируем УЖЕ обёрнутый колбэк во внутреннее тело.
        val deliver = MainThread.wrap(onResult)
        resolvePaywallUrl(paywallId) { url ->
            if (url == null) deliver(PaywallResult.Unavailable)
            else openWebPaywallEmbeddedInternal(context, url, email, deliver)
        }
    }

    private fun resolvePaywallUrl(paywallId: String, onResult: (String?) -> Unit) {
        val cfg = config ?: return onResult(null)
        Http.io {
            val encoded = java.net.URLEncoder.encode(paywallId, "UTF-8")
            val body = Http.get("${cfg.baseUrl}/public/paywall-url/$encoded")
            onResult(WebPaywallLauncher.parsePaywallUrlResponse(body))
        }
    }

    /**
     * DEBUG-only (для симулятор/эмулятор/девайс-теста без реальной атрибуции): инъекция guid.
     * ⚠ Вызывать ТОЛЬКО под `if (BuildConfig.DEBUG)` — в проде не использовать.
     */
    fun debugSetGuid(guid: String) {
        if (::guidStore.isInitialized) guidStore.save(guid)
    }

    /** DEBUG-only: сброс сохранённого guid. */
    fun debugClear() {
        if (::guidStore.isInitialized) guidStore.clear()
    }
}

/** Конфиг SDK. */
internal data class Web2AppConfig(val projectId: String, val baseUrl: String)
