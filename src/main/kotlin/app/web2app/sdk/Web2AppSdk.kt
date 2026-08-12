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

    /**
     * Слушатель событий воронки ([setFunnelEventListener]). `@Volatile` —
     * пишется с потока интегратора, читается с потока JavascriptInterface.
     */
    @Volatile
    private var funnelEventListener: ((String, FunnelEventData) -> Unit)? = null

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
     * Б-3: [adaptyProfileId] / [revenuecatProfileId] — profile-id подписочной платформы,
     * если она у вас есть. SDK лишь дописывает их в URL страницы; связывание профиля с guid
     * делает веб-страница сама (отдельную ручку звать не надо). См. [openWebPaywallEmbedded].
     *
     * ⚠ MVP-1: принимает готовый [paywallUrl]. Серверный резолв projectId→дефолт-пейвол-URL —
     * отдельный follow-up.
     */
    fun openWebPaywall(
        context: Context,
        paywallUrl: String,
        email: String? = null,
        adaptyProfileId: String? = null,
        revenuecatProfileId: String? = null,
        onResult: (EntitlementGrant?) -> Unit = {},
    ) = openWebPaywallInternal(
        context,
        paywallUrl,
        email,
        adaptyProfileId,
        revenuecatProfileId,
        MainThread.wrap(onResult),
    )

    /**
     * Общее тело [openWebPaywall] и [openWebPaywallById]. [deliver] уже обёрнут
     * вызывающим — здесь НЕ оборачиваем повторно (иначе делегирование `*ById`
     * дало бы двойную обёртку на одну доставку).
     */
    private fun openWebPaywallInternal(
        context: Context,
        paywallUrl: String,
        email: String?,
        adaptyProfileId: String?,
        revenuecatProfileId: String?,
        deliver: (EntitlementGrant?) -> Unit,
    ) {
        val cfg = config ?: return deliver(null)

        // guid-поллинг: берём client-held guid или чеканим новый — grant на вебе ляжет на него.
        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)

        val url = WebPaywallLauncher
            .appOriginUrl(paywallUrl, email, guid, adaptyProfileId, revenuecatProfileId)
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
        adaptyProfileId: String? = null,
        revenuecatProfileId: String? = null,
        onResult: (EntitlementGrant?) -> Unit = {},
    ) {
        // Обёртка одна — делегируем УЖЕ обёрнутый колбэк во внутреннее тело.
        val deliver = MainThread.wrap(onResult)
        resolvePaywallUrl(paywallId) { url ->
            if (url == null) deliver(null)
            else openWebPaywallInternal(
                context,
                url,
                email,
                adaptyProfileId,
                revenuecatProfileId,
                deliver,
            )
        }
    }

    /**
     * WEB-814 — встроенный показ веб-пейволла (паритет iOS openWebPaywallEmbedded
     * 0.4.0): full-screen WebView + JS-мост `web2appBridge`. На успех оплаты
     * пейволл закрывается АВТОМАТИЧЕСКИ (страница шлёт событие мосту), кнопка
     * «Закрыть» тоже идёт мостом — URL-схема не нужна. Результат типизирован:
     * [PaywallResult.Paid] / [PaywallResult.NotPaid] / [PaywallResult.Pending];
     * [PaywallResult.Unavailable] — если пейволл вообще не показали (нет configure).
     *
     * Б-3: [adaptyProfileId] / [revenuecatProfileId] — profile-id вашей подписочной платформы
     * (берётся из её SDK ДО показа страницы). SDK дописывает их в URL страницы; связывание
     * профиля с guid делает сама страница на сервере — звать ничего не нужно.
     */
    fun openWebPaywallEmbedded(
        context: Context,
        paywallUrl: String,
        email: String? = null,
        adaptyProfileId: String? = null,
        revenuecatProfileId: String? = null,
        onResult: (PaywallResult) -> Unit,
    ) = openWebPaywallEmbeddedInternal(
        context,
        paywallUrl,
        email,
        adaptyProfileId,
        revenuecatProfileId,
        MainThread.wrap(onResult),
    )

    /**
     * Общее тело [openWebPaywallEmbedded] и [openWebPaywallEmbeddedById].
     * [deliver] уже обёрнут вызывающим — повторно НЕ оборачиваем.
     */
    private fun openWebPaywallEmbeddedInternal(
        context: Context,
        paywallUrl: String,
        email: String?,
        adaptyProfileId: String?,
        revenuecatProfileId: String?,
        deliver: (PaywallResult) -> Unit,
    ) {
        // Без configure пейволл не показать — это НЕ «не оплатил» (паритет iOS .unavailable).
        val cfg = config ?: return deliver(PaywallResult.Unavailable)
        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)

        val url = WebPaywallLauncher
            .appOriginUrl(paywallUrl, email, guid, adaptyProfileId, revenuecatProfileId)
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
        adaptyProfileId: String? = null,
        revenuecatProfileId: String? = null,
        onResult: (PaywallResult) -> Unit,
    ) {
        // Обёртка одна — делегируем УЖЕ обёрнутый колбэк во внутреннее тело.
        val deliver = MainThread.wrap(onResult)
        resolvePaywallUrl(paywallId) { url ->
            if (url == null) deliver(PaywallResult.Unavailable)
            else openWebPaywallEmbeddedInternal(
                context,
                url,
                email,
                adaptyProfileId,
                revenuecatProfileId,
                deliver,
            )
        }
    }

    /**
     * Б-2 — открытие КВИЗА встроенным WebView (тот же показ, что у пейволла:
     * full-screen WebView + JS-мост `web2appBridge`).
     *
     * **Результат другой, чем у пейволла.** Оплаты у квиза нет, поэтому метод не
     * возвращает [PaywallResult] и не поллит право: наблюдаемое — поток событий
     * прохождения (`quiz_start`, `quiz_screen_view`, `quiz_answer`,
     * `quiz_email_submit`, `quiz_complete`), он идёт в
     * [setFunnelEventListener], — плюс факт закрытия экрана в [onClose].
     *
     * ⚠ `quiz_complete` экран НЕ закрывает: после квиза страница часто сама ведёт
     * на пейволл в том же WebView. Закрывают показ те же два терминальных события,
     * что и у пейволла (`paywall_result:success` и `close`), плюс нативное
     * закрытие юзером — какое из трёх сработало, видно в
     * [QuizResult.Closed.reason].
     *
     * [quizUrl] — ГОТОВЫЙ URL опубликованного квиза. Резолва «URL квиза по ID» на
     * бэкенде нет (`/public/paywall-url/:paywallId` существует только для
     * пейволлов), поэтому `openQuizById` в SDK намеренно отсутствует.
     * [email] — опц. prefill. [adaptyProfileId] / [revenuecatProfileId] — profile-id
     * подписочной платформы: SDK дописывает их в URL, связывание с guid делает
     * сама страница (см. [openWebPaywallEmbedded]).
     *
     * guid кладётся в URL так же, как для пейволла — веб связывает прохождение
     * квиза с тем же пользователем. Без [configure] guid некуда персистить, поэтому
     * экран не показывается: [QuizResult.Unavailable].
     *
     * [onClose] приходит на ГЛАВНЫЙ поток РОВНО один раз (общее правило SDK,
     * см. [MainThread]).
     *
     * ```
     * Web2AppSdk.setFunnelEventListener { name, data -> analytics.log(name) }
     * Web2AppSdk.openQuizEmbedded(context, "https://client.example.com/q/quiz-1") { result ->
     *     if (result is QuizResult.Closed && result.reason == QuizCloseReason.PAID) {
     *         Web2AppSdk.entitlement { grant -> if (grant?.isActive == true) unlock() }
     *     }
     * }
     * ```
     */
    fun openQuizEmbedded(
        context: Context,
        quizUrl: String,
        email: String? = null,
        adaptyProfileId: String? = null,
        revenuecatProfileId: String? = null,
        onClose: (QuizResult) -> Unit = {},
    ) {
        // Обёртка на главный поток ровно одна — на входе публичного метода.
        val deliver = MainThread.wrap(onClose)
        // Без configure guid не сохранить: веб связал бы прохождение с ключом,
        // который прилка тут же потеряет. Экран не показываем — это НЕ «закрыли».
        if (config == null) return deliver(QuizResult.Unavailable)

        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)

        val url = QuizPresentation
            .quizUrl(quizUrl, email, guid, adaptyProfileId, revenuecatProfileId)
        val callbackId = UUID.randomUUID().toString()
        QuizPresentation.registerCloseCallback(callbackId, deliver)
        EmbeddedPaywallActivity.start(context, url, callbackId)
    }

    /**
     * Б-1 — слушатель событий воронки из встроенного показа (WebView-мост).
     *
     * Страница шлёт события прохождения квиза (`quiz_start`, `quiz_screen_view`,
     * `quiz_answer`, `quiz_email_submit`, `quiz_complete`) и пейволла
     * (`paywall_result`, `close`). Слушатель получает имя события и безопасные
     * поля ([FunnelEventData]) — PII в мост не уходит.
     *
     * ⚠ События воронки НИЧЕГО не закрывают: показ завершают ровно
     * `paywall_result:success` и `close` (см. [BridgeEventParser.terminalEvent]).
     *
     * Колбэк приходит на ГЛАВНЫЙ поток — из него можно сразу трогать UI
     * (общее правило SDK, см. [MainThread]).
     *
     * ```
     * Web2AppSdk.setFunnelEventListener { name, data ->
     *     analytics.log(name, mapOf("screen" to data.screenIndex))
     * }
     * ```
     *
     * `null` — отписаться.
     */
    fun setFunnelEventListener(listener: ((name: String, data: FunnelEventData) -> Unit)?) {
        funnelEventListener = listener
    }

    /**
     * Точка входа моста. Слушателя нет → не делаем даже прыжка на главный поток.
     * Обёртка [MainThread] здесь ровно одна — событие рождается на потоке
     * JavascriptInterface.
     */
    internal fun emitFunnelEvent(name: String, data: FunnelEventData) {
        val listener = funnelEventListener ?: return
        MainThread.post { listener(name, data) }
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
     *
     * В iOS-эталоне такие методы обрезаются `#if DEBUG` и в release-сборку не попадают.
     * В Android библиотека не видит `BuildConfig.DEBUG` приложения-хоста, поэтому аналог
     * невозможен — вместо обрезки предупреждение компилятора.
     */
    @Deprecated(
        message = "Отладочный метод, не предназначен для production-кода: подменяет guid в " +
            "обход атрибуции. В release-сборку он попадает (Android-библиотека не видит " +
            "BuildConfig.DEBUG приложения) — оборачивайте вызов в if (BuildConfig.DEBUG) " +
            "или убирайте перед релизом.",
        level = DeprecationLevel.WARNING,
    )
    fun debugSetGuid(guid: String) {
        if (::guidStore.isInitialized) guidStore.save(guid)
    }

    /** DEBUG-only: сброс сохранённого guid. См. оговорку у [debugSetGuid]. */
    @Deprecated(
        message = "Отладочный метод, не предназначен для production-кода: стирает сохранённый " +
            "guid. В release-сборку он попадает (Android-библиотека не видит BuildConfig.DEBUG " +
            "приложения) — оборачивайте вызов в if (BuildConfig.DEBUG) или убирайте перед " +
            "релизом.",
        level = DeprecationLevel.WARNING,
    )
    fun debugClear() {
        if (::guidStore.isInitialized) guidStore.clear()
    }
}

/** Конфиг SDK. */
internal data class Web2AppConfig(val projectId: String, val baseUrl: String)
