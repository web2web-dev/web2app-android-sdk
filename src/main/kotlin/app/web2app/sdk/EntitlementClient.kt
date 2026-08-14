package app.web2app.sdk

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Право доступа — форма ответа R1 `GET /public/entitlement?guid=`.
 * status: "active" | "expired" | "revoked". Доступ = первый грант active.
 */
data class EntitlementGrant(
    val level: String,
    val status: String,
    val expiresAt: String?,
    val priceId: String?,
) {
    val isActive: Boolean get() = status == "active"
}

/** R1 passthrough — только HTTP + parse, без логики права. */
internal class EntitlementClient(private val config: Web2AppConfig) {
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
                val grants = JSONObject(resp.body).optJSONArray("grants")
                if (grants != null && grants.length() > 0) {
                    val g = grants.getJSONObject(0)
                    EntitlementGrant(
                        level = g.optString("level"),
                        status = g.optString("status"),
                        expiresAt = if (g.isNull("expires_at")) null else g.optString("expires_at"),
                        priceId = if (g.isNull("price_id")) null else g.optString("price_id"),
                    )
                } else null
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
