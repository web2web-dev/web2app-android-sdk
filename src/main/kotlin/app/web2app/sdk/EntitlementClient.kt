package app.web2app.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Право доступа — форма ответа R1 `GET /public/entitlement?guid=`.
 * status: "active" | "expired" | "revoked". [Web2AppSdk.entitlement] отдаёт первую
 * действующую запись ответа (WEB-2480), см. [EntitlementClient.preferredGrant].
 */
data class EntitlementGrant(
    val level: String,
    val status: String,
    val expiresAt: String?,
    val priceId: String?,
    /**
     * true ТОЛЬКО у синтетического гранта тестового режима проекта (WEB-1166):
     * бэкенд помечает такие гранты `testMode: true`. Это НЕ настоящая оплата —
     * не выдавайте боевой контент, если `testMode == true`, даже при
     * `isActive == true`. Старые ответы без поля читаются как false.
     */
    val testMode: Boolean = false,
) {
    val isActive: Boolean get() = status == "active"
}

/**
 * WEB-2480 — ответ [Web2AppSdk.entitlements]: все записи о доступе покупателя.
 * [grants] — в порядке сервера (новые вперёд), включая неактивные и записи соседних
 * проектов той же организации: что открывать, приложение решает по своей карте
 * `level` / `priceId`. [testMode] — признак тестового режима проекта (WEB-1166).
 */
data class Entitlements(
    val guid: String,
    val grants: List<EntitlementGrant>,
    val testMode: Boolean,
) {
    /** Первая действующая запись (как у [Web2AppSdk.entitlement]), null — действующих нет. */
    val activeGrant: EntitlementGrant? get() = grants.firstOrNull { it.isActive }
}

/**
 * WEB-2480 — различимый итог [Web2AppSdk.entitlements]. «Записей нет» — НЕ ошибка:
 * это [Success] с пустым `grants`. При [Network] / [Http] доступ не отнимайте —
 * спросите позже.
 */
sealed class EntitlementsResult {
    data class Success(val entitlements: Entitlements) : EntitlementsResult()

    /** `configure` не вызывался. */
    object NotConfigured : EntitlementsResult()

    /** guid ещё не сохранён (не было identify / показа страницы). */
    object NoGuid : EntitlementsResult()

    /** Нет ответа сервера (нет сети, обрыв, таймаут). */
    object Network : EntitlementsResult()

    /** Сервер ответил кодом не 2xx. */
    data class Http(val code: Int) : EntitlementsResult()

    /** Ответ 2xx, но тело не разобрать. */
    object BadResponse : EntitlementsResult()
}

/** R1 passthrough — HTTP + parse + выбор записи для `entitlement` (WEB-2480). */
internal class EntitlementClient(private val config: Web2AppConfig) {
    companion object {
        /**
         * Разбор тела `/public/entitlement` → ВСЕ записи в порядке сервера (новые
         * вперёд). Бросает на мусоре вместо JSON — вызывающий переводит это в
         * `entitlement.decode_failed`.
         */
        fun parseGrants(body: String): List<EntitlementGrant> {
            val grants = JSONObject(body).optJSONArray("grants") ?: return emptyList()
            return (0 until grants.length()).map { i ->
                val g = grants.getJSONObject(i)
                EntitlementGrant(
                    level = g.optString("level"),
                    status = g.optString("status"),
                    expiresAt = if (g.isNull("expires_at")) null else g.optString("expires_at"),
                    priceId = if (g.isNull("price_id")) null else g.optString("price_id"),
                    // Поле необязательное: боевые гранты приходят без него → false.
                    testMode = g.optBoolean("testMode", false),
                )
            }
        }

        /**
         * WEB-2480 п.1 — запись для [Web2AppSdk.entitlement]: первая ДЕЙСТВУЮЩАЯ
         * (`isActive`) в порядке сервера, а если действующих нет — самая новая
         * (первая в ответе), как раньше. Раньше бралась только первая: свежая
         * неактивная запись (возврат докупки, истёкшая) прятала действующую подписку.
         * `isActive` у записи не меняется.
         */
        fun preferredGrant(grants: List<EntitlementGrant>): EntitlementGrant? =
            grants.firstOrNull { it.isActive } ?: grants.firstOrNull()

        /** WEB-2480 п.2 — различимый итог запроса права (чистая функция, без сети). */
        fun classify(resp: HttpResponse): EntitlementsResult {
            if (resp.code == 0) return EntitlementsResult.Network
            if (resp.code !in 200..299) return EntitlementsResult.Http(resp.code)
            val body = resp.body ?: return EntitlementsResult.BadResponse
            return runCatching {
                val json = JSONObject(body)
                if (!json.has("guid") || json.optJSONArray("grants") == null) {
                    return@runCatching EntitlementsResult.BadResponse
                }
                EntitlementsResult.Success(
                    Entitlements(
                        guid = json.getString("guid"),
                        grants = parseGrants(body),
                        testMode = json.optBoolean("testMode", false),
                    ),
                )
            }.getOrElse { EntitlementsResult.BadResponse }
        }

        /** Разбор тела → запись для [Web2AppSdk.entitlement] по [preferredGrant]. */
        fun parseGrant(body: String): EntitlementGrant? = preferredGrant(parseGrants(body))
    }

    /** WEB-2480 — все записи с различимой причиной отказа. */
    fun fetchAll(guid: String, onResult: (EntitlementsResult) -> Unit) {
        Http.io {
            val q = URLEncoder.encode(guid, "UTF-8")
            onResult(classify(Http.getWithStatus("${config.baseUrl}/public/entitlement?guid=$q")))
        }
    }

    fun fetch(guid: String, onResult: (EntitlementGrant?) -> Unit) {
        Http.io {
            val q = URLEncoder.encode(guid, "UTF-8")
            val resp = Http.getWithStatus("${config.baseUrl}/public/entitlement?guid=$q")
            if (resp.body == null) {
                // 0 = сетевое исключение, иначе HTTP-код неуспеха (паритет iOS
                // entitlement.network_error).
                SdkLogger.error(
                    "entitlement.network_error",
                    context = mapOf("http" to resp.code.toString()),
                )
                onResult(null)
                return@io
            }
            val grant = runCatching {
                parseGrant(resp.body)
            }.getOrElse {
                // Мусор вместо JSON — раньше исключение молча убивало daemon-поток
                // и onResult не приходил вовсе; теперь это видимый decode_failed.
                SdkLogger.error(
                    "entitlement.decode_failed",
                    context = mapOf("http" to resp.code.toString()),
                )
                null
            }
            onResult(grant)
        }
    }
}

/** Ответ HTTP-хелпера: [code] (0 = сеть/исключение), [body] — тело 2xx-ответа. */
internal data class HttpResponse(val code: Int, val body: String?)

/** Тонкий HTTP-хелпер на HttpURLConnection (без OkHttp-зависимости в скелете). */
internal object Http {
    fun io(block: () -> Unit) = Thread(block).apply { isDaemon = true }.start()

    fun get(url: String): String? = requestWithStatus(url, "GET", null).body

    fun postJson(url: String, json: String): String? = requestWithStatus(url, "POST", json).body

    /** POST для эндпоинтов без тела ответа (напр. 204). true = 2xx. */
    fun postOk(url: String, json: String): Boolean =
        requestWithStatus(url, "POST", json).code in 200..299

    /** GET с HTTP-кодом — для журнала ошибок (SdkLogger). */
    fun getWithStatus(url: String): HttpResponse = requestWithStatus(url, "GET", null)

    /** POST с HTTP-кодом — для журнала ошибок (SdkLogger). */
    fun postWithStatus(url: String, json: String): HttpResponse =
        requestWithStatus(url, "POST", json)

    private fun requestWithStatus(url: String, method: String, json: String?): HttpResponse = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(json.toByteArray()) }
            }
            val code = responseCode
            val ok = code in 200..299
            val stream = if (ok) inputStream else errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            disconnect()
            HttpResponse(code, if (ok) text else null)
        }
    } catch (_: Exception) {
        HttpResponse(0, null)
    }
}
