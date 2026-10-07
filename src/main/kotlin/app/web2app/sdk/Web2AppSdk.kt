package app.web2app.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

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

    /** WEB-1384: метка первой неудачной попытки отпечатка (окно 2 часа). */
    private var fingerprintGate: FingerprintAttemptGate? = null

    /**
     * Незавершённые `openWebPaywall` (окно браузера), которые ждут оплату.
     * WEB-2479: не пусто — окно библиотеки «открыто», возвратный метод код не тратит.
     * WEB-2480: результат возвратного метода уходит и в них.
     */
    internal val customTabWaits = CopyOnWriteArraySet<CustomTabWait>()

    /**
     * WEB-2479 п.2: окно оплаты библиотеки открыто — идёт незавершённый
     * `openWebPaywall` (окно браузера) или встроенный показ. Открыто → возвратный
     * метод код из ссылки не тратит (решение владельца 06.10).
     */
    internal fun isLibraryWindowOpen(): Boolean =
        customTabWaits.isNotEmpty() || EmbeddedPaywallPresentations.active() != null

    /** 0.7.2: «id пейволла → адрес» на час для встроенного показа по id. */
    private val paywallUrlCache = PaywallUrlCache()

    /**
     * Слушатель событий воронки ([setFunnelEventListener]). `@Volatile` —
     * пишется с потока интегратора, читается с потока JavascriptInterface.
     */
    @Volatile
    private var funnelEventListener: ((String, FunnelEventData) -> Unit)? = null

    /**
     * Инициализация. [projectId] = ключ проекта арендатора; [baseUrl] = наш API.
     *
     * [warmUpWebView] (0.7.2, по умолчанию `true`) — прогреть движок WebView на
     * главном потоке, чтобы первый встроенный показ не ждал его загрузки (стоит
     * сотни миллисекунд главного потока при запуске). `false` — не греть,
     * в журнал `paywall.webview_warmup_skipped` с причиной `disabled_by_config`.
     * Если приложение зовёт `WebView.setDataDirectorySuffix`, это нужно сделать
     * ДО `configure`: после прогрева движок уже поднят и вызов бросит исключение.
     */
    @JvmOverloads
    fun configure(context: Context, projectId: String, baseUrl: String, warmUpWebView: Boolean = true) {
        val cfg = Web2AppConfig(projectId, baseUrl.trimEnd('/'))
        config = cfg
        guidStore = GuidStore(context.applicationContext)
        // WEB-1384: окно попыток отпечатка (2ч с первой неудачи).
        fingerprintGate = FingerprintAttemptGate(context.applicationContext)
        SdkLogger.attach(cfg, context.applicationContext)
        // Уже опознанный guid — сразу в контекст журнала (best-effort: сбой
        // хранилища не имеет права уронить configure, guid догонит на identify).
        runCatching { guidStore.load() }.getOrNull()?.let { SdkLogger.setGuid(it) }
        SdkLogger.log("configure", context = mapOf("baseUrl" to cfg.baseUrl))
        // 0.7.2: мог смениться проект/сервер — адреса прежнего не годятся.
        paywallUrlCache.clear()
        // 0.7.2: прогрев движка WebView — первый встроенный показ не платит за его загрузку.
        WebViewWarmup.warmUp(context, enabled = warmUpWebView)
    }

    /**
     * Резолвит и персистит guid. Порядок (первый запуск):
     *  1. Сохранённый guid → возвращаем (steady-state).
     *  2. Install Referrer (`&referrer=<token>`) → resolve → guid.
     *  3. Промах (Huawei/sideload/органика, FEATURE_NOT_SUPPORTED) → опознание
     *     по отпечатку устройства (WEB-1213, паритет iOS 0.7.0): сигналы
     *     устройства → `POST /public/handoff/resolve-by-fingerprint`; guid
     *     приходит только при единственном уверенном совпадении с сигналами,
     *     которые веб-страница оставила в момент ухода покупателя в стор.
     *  4. Промах отпечатка → [onNeedEmail] (email-fallback) — поведение не
     *     хуже старого.
     * На успехе — APP_INSTALLED-продюсер (best-effort).
     */
    fun identify(
        onResult: (Result<String>) -> Unit = {},
        onNeedEmail: () -> Unit = {},
    ) {
        // Обёртка на главный поток РОВНО одна и на входе: ниже по коду (включая
        // ранние возвраты и колбэки резолверов) зовём только обёрнутые.
        val deliver = MainThread.wrap(onResult)
        val deliverNeedEmail = MainThread.wrapNoArgs(onNeedEmail)

        val cfg = config
        if (cfg == null) {
            SdkLogger.error("identify.not_configured")
            return deliver(Result.failure(IllegalStateException("not configured")))
        }

        guidStore.load()?.let {
            SdkLogger.setGuid(it)
            SdkLogger.log("identify.cached_guid")
            return deliver(Result.success(it))
        }

        InstallReferrerResolver(cfg).readAndResolve(guidStore.context) { result ->
            result.onSuccess { guid ->
                guidStore.save(guid)
                SdkLogger.setGuid(guid)
                SdkLogger.log("identify.resolved")
                AppCallbackProducer(cfg).reportAppInstalled(guid)
                deliver(Result.success(guid))
            }.onFailure {
                // WEB-1213: промах referrer → СНАЧАЛА опознание по отпечатку
                // устройства; любой промах отпечатка → прежний email-fallback
                // (НЕ падаем молча, поведение не хуже старого).
                // WEB-1384: попытки ограничены окном 2 часа с ПЕРВОЙ неудачи —
                // ровно столько живёт слепок на сервере; дальше в сеть не
                // ходим никогда (ответ известен, лишней работы не делаем).
                val firstFailedAt =
                    runCatching { fingerprintGate?.firstFailedAtMillis() }.getOrNull()
                if (!FingerprintResolver.isWithinAttemptWindow(
                        firstFailedAt,
                        System.currentTimeMillis(),
                    )
                ) {
                    SdkLogger.log(
                        "identify.fingerprint_window_expired",
                        "окно попыток отпечатка истекло — сразу email-экран",
                    )
                    deliverNeedEmail()
                    return@onFailure
                }
                SdkLogger.log("identify.fingerprint_attempt")
                FingerprintResolver(cfg).resolve { match ->
                    if (match != null) {
                        guidStore.save(match.guid)
                        SdkLogger.setGuid(match.guid)
                        SdkLogger.log(
                            "identify.fingerprint_matched",
                            context = mapOf("matchMethod" to (match.matchMethod ?: "unknown")),
                        )
                        AppCallbackProducer(cfg).reportAppInstalled(match.guid)
                        deliver(Result.success(match.guid))
                    } else {
                        runCatching { fingerprintGate?.markFailure() }
                        SdkLogger.log(
                            "identify.needs_email_fallback",
                            "ни referrer-токена, ни совпадения отпечатка — нужен email-экран",
                            level = "warn",
                        )
                        deliverNeedEmail()
                    }
                }
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
        val cfg = config
        if (cfg == null) {
            SdkLogger.error("email_recovery.not_configured")
            return deliver(Result.failure(IllegalStateException("not configured")))
        }
        // PII: сам email в журнал сознательно не пишется — только факт запроса.
        SdkLogger.log("email_recovery.requested")
        AttributionResolver(cfg).requestEmailRecovery(email) { result ->
            result.onSuccess { SdkLogger.log("email_recovery.sent") }
                .onFailure { SdkLogger.error("email_recovery.failed", it.message ?: "") }
            deliver(result)
        }
    }

    /**
     * Обмен кода на guid: токен из MMP-callback (AppsFlyer/Adjust) или код из
     * ссылки возврата / письма восстановления.
     * ⚠ Токен MMP лежит в `deep_link_sub1` (продублирован в `af_sub1`), а НЕ в
     * `deep_link_value` — там наш бэкенд ставит константу `handoff`, и опознание с
     * ней молча не сработает. **[POC-1]** — валидируется на реальном девайсе.
     *
     * Код обменивается всегда, даже если guid уже сохранён: успех → новый guid
     * перезаписывает сохранённый. WEB-2479: сервер отверг код (погашен или
     * просрочен, 404), а guid сохранён → успех с сохранённым guid (повторный
     * переход по той же ссылке — не ошибка, в журнал
     * `identify.code_rejected_cached_guid`; раньше — ошибка). Сбой сети или
     * сервера — ошибка, как раньше.
     */
    fun identifyWithDeepLinkValue(token: String, onResult: (Result<String>) -> Unit) {
        val deliver = MainThread.wrap(onResult)
        val cfg = config
        if (cfg == null) {
            SdkLogger.error("identify.not_configured")
            return deliver(Result.failure(IllegalStateException("not configured")))
        }
        exchangeCode(cfg, token) { outcome ->
            deliver(
                when (outcome) {
                    is CodeExchangeOutcome.Exchanged -> Result.success(outcome.guid)
                    is CodeExchangeOutcome.KeptCached -> Result.success(outcome.guid)
                    is CodeExchangeOutcome.Failed -> Result.failure(outcome.error)
                },
            )
        }
    }

    /**
     * WEB-2479 — обмен кода (общий для [identifyWithDeepLinkValue] и
     * [handleReturnUrl]). Итог — по [ReturnCodeRules.outcome]; побочные эффекты
     * (перезапись guid, APP_INSTALLED, журнал) — здесь.
     */
    private fun exchangeCode(
        cfg: Web2AppConfig,
        code: String,
        onOutcome: (CodeExchangeOutcome) -> Unit,
    ) {
        SdkLogger.log("identify.resolving_token")
        AttributionResolver(cfg).resolveToken(code) { result ->
            val cached = if (::guidStore.isInitialized) {
                runCatching { guidStore.load() }.getOrNull()
            } else {
                null
            }
            val outcome = ReturnCodeRules.outcome(result, cached)
            when (outcome) {
                is CodeExchangeOutcome.Exchanged -> {
                    guidStore.save(outcome.guid)
                    // 0.7.2: guid сменился — кэш адресов пейволла сбрасываем.
                    paywallUrlCache.clear()
                    SdkLogger.setGuid(outcome.guid)
                    SdkLogger.log("identify.resolved")
                    AppCallbackProducer(cfg).reportAppInstalled(outcome.guid)
                }
                is CodeExchangeOutcome.KeptCached -> {
                    SdkLogger.setGuid(outcome.guid)
                    SdkLogger.log(
                        "identify.code_rejected_cached_guid",
                        "сервер отверг код (погашен или просрочен) — отдан сохранённый guid",
                        level = "warn",
                    )
                }
                is CodeExchangeOutcome.Failed ->
                    SdkLogger.error("identify.resolve_failed", outcome.error.message ?: "")
            }
            onOutcome(outcome)
        }
    }

    /**
     * Читает право по сохранённому guid — `GET /public/entitlement?guid=`: первая
     * действующая запись ответа, а если действующих нет — самая новая (WEB-2480).
     * null означает И «записей нет», И «не удалось спросить» (сеть, сервер) —
     * чтобы различать, зовите [entitlements].
     */
    fun entitlement(onResult: (EntitlementGrant?) -> Unit) {
        val deliver = MainThread.wrap(onResult)
        val cfg = config
        val guid = if (::guidStore.isInitialized) guidStore.load() else null
        if (cfg == null || guid == null) {
            SdkLogger.log(
                "entitlement.skipped",
                "нет configure или сохранённого guid",
                level = "warn",
            )
            return deliver(null)
        }
        SdkLogger.log("entitlement.fetch")
        EntitlementClient(cfg).fetch(guid) { grant ->
            SdkLogger.log(
                "entitlement.result",
                context = mapOf("status" to (grant?.status ?: "none")),
            )
            deliver(grant)
        }
    }

    /**
     * WEB-2480 — все записи о доступе по сохранённому guid с различимой причиной
     * отказа. В отличие от [entitlement], «нет записей» ([EntitlementsResult.Success]
     * с пустым `grants`) здесь отличается от «не удалось спросить»
     * ([EntitlementsResult.Network], [EntitlementsResult.Http]) — при сбое сети не
     * отнимайте доступ, спросите позже. Результат — на главном потоке.
     *
     * ```
     * Web2AppSdk.entitlements { r ->
     *     when (r) {
     *         is EntitlementsResult.Success -> if (r.entitlements.activeGrant != null) unlock() else lock()
     *         EntitlementsResult.Network, is EntitlementsResult.Http -> Unit // оставить как есть
     *         else -> lock()
     *     }
     * }
     * ```
     */
    fun entitlements(onResult: (EntitlementsResult) -> Unit) {
        val deliver = MainThread.wrap(onResult)
        val cfg = config
        if (cfg == null) {
            SdkLogger.log("entitlements.skipped", "нет configure", level = "warn")
            return deliver(EntitlementsResult.NotConfigured)
        }
        val guid = if (::guidStore.isInitialized) runCatching { guidStore.load() }.getOrNull() else null
        if (guid == null) {
            SdkLogger.log("entitlements.skipped", "нет сохранённого guid", level = "warn")
            return deliver(EntitlementsResult.NoGuid)
        }
        SdkLogger.log("entitlements.fetch")
        EntitlementClient(cfg).fetchAll(guid) { result ->
            if (result is EntitlementsResult.Success) {
                SdkLogger.log(
                    "entitlements.result",
                    context = mapOf(
                        "count" to result.entitlements.grants.size.toString(),
                        "active" to (result.entitlements.activeGrant != null).toString(),
                    ),
                )
            } else {
                SdkLogger.error(
                    "entitlements.failed",
                    (result as? EntitlementsResult.Http)?.let { "http ${it.code}" }
                        ?: (result::class.simpleName ?: "unknown"),
                )
            }
            deliver(result)
        }
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
     * поллинг стартует сразу после запуска, а с WEB-2480 окно ожидания (≈60 с)
     * начинается заново при каждом возвращении приложения на передний план: долгая оплата во
     * вкладке не даёт «не оплачено», null приходит только через ≈60 с после возвращения.
     * Результат [handleReturnUrl] тоже приходит сюда — [onResult] зовётся ровно один раз.
     *
     * [paywallUrl] — URL опубликованного веб-пейвола (кастом-домен клиента; наши apex/поддомены
     * пейволы не отдают — WEB-395). [email] — опц. prefill (юзер может поправить на вебе).
     * [onResult] — активный [EntitlementGrant] или null, если за окно право не появилось.
     *
     * Б-3: [adaptyProfileId] / [revenuecatProfileId] — profile-id подписочной платформы,
     * если она у вас есть. SDK лишь дописывает их в URL страницы; связку guid ↔ profile-id
     * пишет страница, но ТОЛЬКО в пустой слот: уже сохранённое значение через страницу не
     * перезаписывается, а дописать вторую платформу удастся, лишь предъявив в ЭТОМ ЖЕ
     * открытии верные значения всех уже занятых слотов.
     *
     * **Рецепт:** передавайте ОБА идентификатора при КАЖДОМ открытии — тогда доказательство
     * всегда при запросе. Отказ молчаливый: сервер отвечает «успех» в любом случае (защита от
     * перебора плательщиков), по ответу проблему не увидеть. Не используйте наш guid как
     * profile-id провайдера (`Purchases.logIn(guid)` и т.п.) — с таким значением дозапись
     * второй платформы через страницу не сработает никогда. Заменить сохранённое значение
     * можно только серверной ручкой `POST /s2s/v1/identity/link-profile` (Bearer `sk_`,
     * право `identity:write`). См. [openWebPaywallEmbedded].
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
        val cfg = config
        if (cfg == null) {
            SdkLogger.error("paywall.not_configured")
            return deliver(null)
        }

        // guid-поллинг: берём client-held guid или чеканим новый — grant на вебе ляжет на него.
        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)
        SdkLogger.setGuid(guid)

        val url = WebPaywallLauncher
            .appOriginUrl(paywallUrl, email, guid, adaptyProfileId, revenuecatProfileId)
        SdkLogger.log(
            "paywall.open_custom_tab",
            context = mapOf(
                "host" to hostOf(paywallUrl),
                "hasEmail" to (!email.isNullOrEmpty()).toString(),
                "hasAdaptyId" to (!adaptyProfileId.isNullOrEmpty()).toString(),
                "hasRevenuecatId" to (!revenuecatProfileId.isNullOrEmpty()).toString(),
            ),
        )
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))

        // Поллим право по нашему guid: окно 30 попыток × 2с ≈ 60с (покрывает Stripe
        // webhook→grant). WEB-2480: окно перезапускается при каждом возвращении
        // приложения на передний план — долгая оплата во вкладке не даёт «не оплачено».
        val client = EntitlementClient(cfg)
        val app = context.applicationContext as? Application
        var lifecycle: Application.ActivityLifecycleCallbacks? = null
        lateinit var wait: CustomTabWait
        wait = CustomTabWait(
            startPoll = { shouldContinue, onDone ->
                WebPaywallLauncher.pollForActiveGrant(
                    intervalMs = 2_000,
                    maxAttempts = 30,
                    fetch = { cb -> client.fetch(guid, cb) },
                    completion = onDone,
                    shouldContinue = shouldContinue,
                )
            },
            deliver = { grant ->
                customTabWaits.remove(wait)
                lifecycle?.let { cb -> MainThread.post { app?.unregisterActivityLifecycleCallbacks(cb) } }
                SdkLogger.log(
                    "paywall.poll_result",
                    context = mapOf("active" to (grant != null).toString()),
                )
                deliver(grant)
            },
            lifecycleAvailable = app != null,
        )
        if (app != null) {
            lifecycle = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    if (wait.isFinished) return
                    SdkLogger.log("paywall.app_resumed_restart_wait")
                    wait.onAppResumed()
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) {
                    // Доработка 4: ушёл с экрана (снова во вкладку) — окно на паузе.
                    // Переход между своими экранами: pause → resume следующего, пауза снимается.
                    wait.onAppPaused()
                }
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }.also { app.registerActivityLifecycleCallbacks(it) }
        }
        customTabWaits.add(wait)
        wait.start()
    }

    /**
     * WEB-813 — обработчик возвратного deep-link'а из веб-пейволла (паритет iOS
     * handleReturnURL): кнопки «Закрыть» и «Открыть приложение (альтернативный
     * способ)» на success-экране ведут на `<схема-прилки>://handoff?code=...`
     * (WEB-800). Интегратор: (1) объявляет intent-filter своей схемы и указывает
     * её в кабинете проекта (bridgeConfig.returnScheme); (2) зовёт этот метод из
     * onCreate/onNewIntent для ВСЕХ входящих deep-link — чужие вернут false.
     *
     * При распознавании (WEB-2479):
     *  - **окно библиотеки открыто** (идёт незавершённый [openWebPaywall] или
     *    встроенный показ) — короткий опрос права по guid библиотеки (10 × 1 с),
     *    результат в [onResult]. Действующая запись нашлась — `code` НЕ тратится:
     *    он остаётся живым для письма «оплата прошла» (открыть приложение на втором
     *    устройстве). Не нашлась, а `code` есть (решение владельца 07.10: бросил
     *    окно и оплатил в обычном браузере) — `code` обменивается, как ниже;
     *  - **окна нет** (покупатель платил в обычном браузере или пришёл из письма) —
     *    `code` обменивается тем же путём, что [identifyWithDeepLinkValue] (guid
     *    может смениться), затем тот же короткий опрос права по новому guid.
     *    Обмен не удался — [onResult] получает null.
     */
    @JvmOverloads
    fun handleReturnUrl(
        url: String,
        onResult: (EntitlementGrant?) -> Unit = {},
    ): Boolean {
        // Чужой deep-link: колбэк не зовём вовсе — контракт «false и тишина».
        if (!WebPaywallLauncher.isHandoffReturnUrl(url)) return false
        SdkLogger.log("return_url.recognized")
        val deliver = MainThread.wrap(onResult)
        val cfg = config
        if (cfg == null || !::guidStore.isInitialized) {
            deliver(null)
            return true
        }
        val client = EntitlementClient(cfg)
        runReturnFlow(
            url,
            exchange = { code, cb -> exchangeCode(cfg, code, cb) },
            pollStoredGuid = { cb ->
                val guid = runCatching { guidStore.load() }.getOrNull()
                if (guid == null) {
                    cb(null)
                } else {
                    WebPaywallLauncher.pollForActiveGrant(
                        intervalMs = 1_000,
                        maxAttempts = 10,
                        fetch = { done -> client.fetch(guid, done) },
                        completion = cb,
                    )
                }
            },
            completion = deliver,
        )
        return true
    }

    /**
     * Ход возвратного метода с подставляемыми сетевыми шагами (доработка 4: тесты
     * подключения). Решение «окно открыто» — из [isLibraryWindowOpen]; результат
     * получают и незавершённые ожидания `openWebPaywall` (WEB-2480, критерий
     * WEB-811 → WEB-813), и [completion] — каждый ровно один раз.
     */
    internal fun runReturnFlow(
        url: String,
        exchange: (code: String, onOutcome: (CodeExchangeOutcome) -> Unit) -> Unit,
        pollStoredGuid: (onResult: (EntitlementGrant?) -> Unit) -> Unit,
        completion: (EntitlementGrant?) -> Unit,
    ) {
        val waits = customTabWaits.toList()
        val windowOpen = isLibraryWindowOpen()
        SdkLogger.log("return_url.action", context = mapOf("windowOpen" to windowOpen.toString()))
        ReturnUrlFlow(
            windowOpen = windowOpen,
            exchange = exchange,
            pollStoredGuid = pollStoredGuid,
        ).run(ReturnCodeRules.codeFromReturnUrl(url)) { grant ->
            waits.forEach { it.offer(grant) }
            completion(grant)
        }
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
     * (берётся из её SDK ДО показа страницы). SDK дописывает их в URL страницы; связку
     * guid ↔ profile-id пишет страница, но ТОЛЬКО в пустой слот: занятое значение через
     * страницу не перезаписывается, а вторую платформу можно дописать, лишь предъявив в этом
     * же открытии верные значения всех уже занятых слотов. Отсюда рецепт: передавайте ОБА
     * идентификатора при КАЖДОМ открытии. Отказ молчаливый — сервер всегда отвечает «успех».
     * Наш guid как profile-id провайдера не годится (тогда дозапись второй платформы через
     * страницу не сработает никогда); замена сохранённого значения — только серверной ручкой
     * `POST /s2s/v1/identity/link-profile`.
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
        val cfg = config
        if (cfg == null) {
            SdkLogger.error("paywall.not_configured")
            return deliver(PaywallResult.Unavailable)
        }
        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)
        SdkLogger.setGuid(guid)

        val url = WebPaywallLauncher
            .appOriginUrl(paywallUrl, email, guid, adaptyProfileId, revenuecatProfileId)
        SdkLogger.log(
            "paywall.open_embedded",
            context = mapOf(
                "host" to hostOf(paywallUrl),
                "hasEmail" to (!email.isNullOrEmpty()).toString(),
                "hasAdaptyId" to (!adaptyProfileId.isNullOrEmpty()).toString(),
                "hasRevenuecatId" to (!revenuecatProfileId.isNullOrEmpty()).toString(),
            ),
        )
        val client = EntitlementClient(cfg)
        val callbackId = UUID.randomUUID().toString()
        // 0.7.2: показ сорвался (процесс страницы упал два раза подряд или шесть раз за показ). Оплата могла
        // пройти до сбоя — поллим тем же окном, что и нативное закрытие; нет права →
        // Unavailable (пользователь ничего не отклонял, это не NotPaid).
        val onBrokenShow: () -> Unit = {
            SdkLogger.log("paywall.webview_closed", context = mapOf("bridgeEvent" to "unavailable"))
            WebPaywallLauncher.pollForActiveGrant(
                intervalMs = 1_000,
                maxAttempts = WebPaywallLauncher.embeddedPollAttempts(null),
                fetch = { cb -> client.fetch(guid, cb) },
            ) { grant ->
                val result = EmbeddedWebViewPolicy.paywallResultAfterBrokenShow(grant)
                SdkLogger.log(
                    "paywall.result",
                    context = mapOf("result" to if (result is PaywallResult.Paid) "paid" else "unavailable"),
                )
                deliver(result)
            }
        }
        EmbeddedPaywallCallbacks.register(callbackId, onUnavailable = onBrokenShow) { event ->
            SdkLogger.log(
                "paywall.webview_closed",
                context = mapOf("bridgeEvent" to (event?.name ?: "none")),
            )
            // Окно поллинга одинаковое для всех исходов, включая нативное закрытие
            // (event == null): вебхук Stripe доезжает секундами позже закрытия окна.
            val attempts = WebPaywallLauncher.embeddedPollAttempts(event)
            WebPaywallLauncher.pollForActiveGrant(
                intervalMs = 1_000,
                maxAttempts = attempts,
                fetch = { cb -> client.fetch(guid, cb) },
            ) { grant ->
                when {
                    grant != null -> {
                        SdkLogger.log("paywall.result", context = mapOf("result" to "paid"))
                        deliver(PaywallResult.Paid(grant))
                    }
                    event == BridgeEvent.PAYMENT_SUCCESS -> {
                        SdkLogger.log("paywall.result", context = mapOf("result" to "pending"))
                        deliver(PaywallResult.Pending)
                    }
                    else -> {
                        SdkLogger.log("paywall.result", context = mapOf("result" to "notPaid"))
                        deliver(PaywallResult.NotPaid)
                    }
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
        resolvePaywallUrl(paywallId, useCache = true) { url ->
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
     * подписочной платформы: SDK дописывает их в URL, связку guid ↔ profile-id пишет
     * страница, но только в пустой слот — дописать вторую платформу удастся, лишь предъявив
     * в этом же открытии верные значения уже занятых слотов. Поэтому передавайте ОБА
     * идентификатора при КАЖДОМ открытии; отказ молчаливый (сервер всегда отвечает «успех»),
     * наш guid в роли profile-id провайдера не годится (см. [openWebPaywallEmbedded]).
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
        if (config == null) {
            SdkLogger.error("quiz.not_configured")
            return deliver(QuizResult.Unavailable)
        }

        val guid = (if (::guidStore.isInitialized) guidStore.load() else null)
            ?: UUID.randomUUID().toString()
        if (::guidStore.isInitialized) guidStore.save(guid)
        SdkLogger.setGuid(guid)

        val url = QuizPresentation
            .quizUrl(quizUrl, email, guid, adaptyProfileId, revenuecatProfileId)
        SdkLogger.log(
            "quiz.open",
            context = mapOf(
                "host" to hostOf(quizUrl),
                "hasEmail" to (!email.isNullOrEmpty()).toString(),
            ),
        )
        val callbackId = UUID.randomUUID().toString()
        QuizPresentation.registerCloseCallback(callbackId) { result ->
            SdkLogger.log("quiz.closed", context = mapOf("result" to quizResultLabel(result)))
            deliver(result)
        }
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
     * Точка входа моста. Журнал получает `funnel.<имя>` в любом случае (паритет
     * iOS); слушателя нет → прыжка на главный поток не делаем. Обёртка
     * [MainThread] здесь ровно одна — событие рождается на потоке
     * JavascriptInterface.
     */
    internal fun emitFunnelEvent(name: String, data: FunnelEventData) {
        val ctx = buildMap {
            data.screenId?.let { put("screenId", it) }
            data.screenIndex?.let { put("screenIndex", it.toString()) }
            data.blockType?.let { put("blockType", it) }
        }
        SdkLogger.log("funnel.$name", context = ctx)
        val listener = funnelEventListener ?: return
        MainThread.post { listener(name, data) }
    }

    /**
     * [useCache] — 0.7.2, только встроенный показ по id: свежий (моложе часа) адрес
     * из [paywallUrlCache] отдаётся сразу, без сети; промах — прежний путь, успешный
     * ответ кладётся в кэш.
     */
    private fun resolvePaywallUrl(
        paywallId: String,
        useCache: Boolean = false,
        onResult: (String?) -> Unit,
    ) {
        val cfg = config ?: return onResult(null)
        if (useCache) {
            paywallUrlCache.get(paywallId)?.let { cached ->
                SdkLogger.log("paywall.resolve_url_cached", context = mapOf("paywallId" to paywallId))
                return onResult(cached)
            }
        }
        SdkLogger.log("paywall.resolve_url", context = mapOf("paywallId" to paywallId))
        Http.io {
            val encoded = java.net.URLEncoder.encode(paywallId, "UTF-8")
            val resp = Http.getWithStatus("${cfg.baseUrl}/public/paywall-url/$encoded")
            val url = WebPaywallLauncher.parsePaywallUrlResponse(resp.body)
            if (url == null) {
                SdkLogger.error(
                    "paywall.resolve_url_failed",
                    context = mapOf("paywallId" to paywallId, "http" to resp.code.toString()),
                )
            } else if (useCache) {
                paywallUrlCache.put(paywallId, url)
            }
            onResult(url)
        }
    }

    /** Хост URL — для контекста журнала (без query/path: PII туда не попадает). */
    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull() ?: ""

    /** Человекочитаемый исход квиза для журнала (без PII). */
    private fun quizResultLabel(result: QuizResult): String = when (result) {
        is QuizResult.Closed -> "closed(${result.reason.name})"
        QuizResult.Unavailable -> "unavailable"
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
        paywallUrlCache.clear()
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
        paywallUrlCache.clear()
    }
}

/** Конфиг SDK. */
internal data class Web2AppConfig(val projectId: String, val baseUrl: String)
