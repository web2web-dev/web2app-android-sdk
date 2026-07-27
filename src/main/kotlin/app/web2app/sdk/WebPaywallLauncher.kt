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
     * Чистая сборка app-origin URL: добавляет `origin=app` + опц. `email` + `guid`,
     * СОХРАНЯЯ существующий query исходного URL. Значения URL-кодируются. Не использует
     * android.net.Uri — чтобы быть юнит-тестируемой на чистой JVM.
     */
    fun appOriginUrl(paywallUrl: String, email: String?, guid: String): String {
        val params = buildList {
            add("origin" to "app")
            if (!email.isNullOrEmpty()) add("email" to email)
            add("guid" to guid)
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
