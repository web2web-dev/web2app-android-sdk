package app.web2app.sdk

import java.net.URLEncoder

/**
 * WEB-525 под-атом C — обратный флоу `openWebPaywall` (юзер в прилке → веб-пейвол → доступ).
 *
 * Возврат-механика РАТИФИЦИРОВАНА PM (2026-07-07) = **guid-поллинг**, НЕ resolve-by-email
 * (тот S2S с HMAC — мобильному SDK секрет держать нельзя). Схема:
 *   1. SDK берёт/генерит свой guid (client-held, EncryptedSharedPreferences).
 *   2. Открывает веб-пейвол с `?origin=app&email=<e>&guid=<g>` в Chrome Custom Tab.
 *      Веб-пейвол (под-атом A) кейит Stripe-checkout на этот guid → grant ложится на него.
 *   3. Прилка поллит `GET /public/entitlement?guid=g` (R1) до active.
 *
 * Ниже — POC-независимое JVM-ядро (сборка URL + поллинг-оркестрация), покрытое юнит-тестами.
 * Реальный запуск Custom Tab — тонкая Android-обвязка в [Web2AppSdk.openWebPaywall].
 */
internal object WebPaywallLauncher {
    /**
     * Б-3 — имена query-параметров profile-id подписочных платформ. Это КОНТРАКТ с вебом:
     * ровно эти два имени читает `frontend/src/utils/providerProfileLink.ts`, который сам
     * связывает профиль с guid на сервере. Переименование = тихий разрыв связывания.
     */
    private const val PARAM_ADAPTY_PROFILE_ID = "adapty_profile_id"
    private const val PARAM_REVENUECAT_PROFILE_ID = "revenuecat_profile_id"

    /**
     * Чистая сборка app-origin URL: добавляет `origin=app` + опц. `email` + `guid`
     * + опц. profile-id Adapty/RevenueCat ([adaptyProfileId], [revenuecatProfileId]),
     * СОХРАНЯЯ существующий query исходного URL. Значения URL-кодируются. Не использует
     * android.net.Uri — чтобы быть юнит-тестируемой на чистой JVM.
     *
     * Единственная точка сборки URL: profile-id добавляются ЗДЕСЬ, а не конкатенацией в
     * вызывающем коде — иначе теряется и сохранение исходного query, и кодирование значений.
     * Пустая строка = «не передали»: `adapty_profile_id=` слать нельзя.
     */
    fun appOriginUrl(
        paywallUrl: String,
        email: String?,
        guid: String,
        adaptyProfileId: String? = null,
        revenuecatProfileId: String? = null,
    ): String {
        val params = buildList {
            add("origin" to "app")
            if (!email.isNullOrEmpty()) add("email" to email)
            add("guid" to guid)
            if (!adaptyProfileId.isNullOrEmpty()) {
                add(PARAM_ADAPTY_PROFILE_ID to adaptyProfileId)
            }
            if (!revenuecatProfileId.isNullOrEmpty()) {
                add(PARAM_REVENUECAT_PROFILE_ID to revenuecatProfileId)
            }
        }
        val query = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        val sep = if (paywallUrl.contains('?')) "&" else "?"
        return "$paywallUrl$sep$query"
    }

    /**
     * WEB-813 — возвратный deep-link кнопки «Закрыть» на success-экране
     * (контракт WEB-800): `<схема-прилки>://handoff?code=...`. Распознаём по
     * host == "handoff" (case-insensitive); СХЕМУ не проверяем — она
     * клиентская и SDK неизвестна. Паритет iOS isHandoffReturnURL.
     * Чистая JVM (без android.net.Uri) — юнит-тестируемо.
     */
    fun isHandoffReturnUrl(url: String): Boolean {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return false
        val host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return host.lowercase() == "handoff"
    }

    /**
     * WEB-814 — парсинг ответа `GET /public/paywall-url/:paywallId` →
     * URL опубликованного пейволла. Форма: `{"success":true,"data":{"url":"..."}}`.
     * Мусор/404-тело → null (совместимо с anti-enum бэка). Узкий regex-парс
     * вместо org.json — тот в JVM-юнитах Android-заглушка.
     */
    fun parsePaywallUrlResponse(body: String?): String? {
        if (body == null) return null
        val match =
            Regex("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
                ?: return null
        val raw = match.groupValues[1]
            .replace("\\/", "/")
            .replace("\\\"", "\"")
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else null
    }

    /** Окно поллинга после закрытия встроенного пейволла: 10 попыток × 1с ≈ 10с. */
    private const val EMBEDDED_POLL_ATTEMPTS = 10

    /**
     * Сколько попыток поллинга давать после закрытия ВСТРОЕННОГО пейволла.
     * Вынесено из [Web2AppSdk.openWebPaywallEmbedded] отдельной чистой функцией —
     * решение проверяемо на чистой JVM (сам показ требует Android-рантайма).
     *
     * Паритет iOS 0.4.1: окно ОДИНАКОВОЕ для всех исходов, включая нативное
     * закрытие ([event] == null — крестик/системный «назад»). Раньше не-успешные
     * ветки получали 2 попытки, и юзер с медленным вебхуком Stripe (грант доезжает
     * через несколько секунд после закрытия окна) получал мгновенный «не оплатил».
     */
    fun embeddedPollAttempts(event: BridgeEvent?): Int = EMBEDDED_POLL_ATTEMPTS

    /**
     * Поллит [fetch] каждые [intervalMs] мс до [maxAttempts] попыток. Останавливается и отдаёт
     * грант, как только он active; отдаёт null, если active-грант не появился в бюджете попыток.
     * [fetch] инъектируется (сеть/тест) — оркестрация POC-независима. Задержка между попытками
     * на daemon-потоке (не блокирует вызывающий).
     */
    fun pollForActiveGrant(
        intervalMs: Long,
        maxAttempts: Int,
        fetch: (onResult: (EntitlementGrant?) -> Unit) -> Unit,
        completion: (EntitlementGrant?) -> Unit,
    ) {
        if (maxAttempts <= 0) {
            completion(null)
            return
        }
        fun attempt(n: Int) {
            fetch { grant ->
                when {
                    grant != null && grant.isActive -> completion(grant)
                    n + 1 >= maxAttempts -> completion(null)
                    else -> Thread {
                        Thread.sleep(intervalMs)
                        attempt(n + 1)
                    }.apply { isDaemon = true }.start()
                }
            }
        }
        attempt(0)
    }
}
