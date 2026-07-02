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
            val body = Http.get("${config.baseUrl}/public/entitlement?guid=$q")
            val grant = body?.let {
                val grants = JSONObject(it).optJSONArray("grants")
                if (grants != null && grants.length() > 0) {
                    val g = grants.getJSONObject(0)
                    EntitlementGrant(
                        level = g.optString("level"),
                        status = g.optString("status"),
                        expiresAt = if (g.isNull("expires_at")) null else g.optString("expires_at"),
                        priceId = if (g.isNull("price_id")) null else g.optString("price_id"),
                    )
                } else null
            }
            onResult(grant)
        }
    }
}

/** Тонкий HTTP-хелпер на HttpURLConnection (без OkHttp-зависимости в скелете). */
internal object Http {
    fun io(block: () -> Unit) = Thread(block).apply { isDaemon = true }.start()

    fun get(url: String): String? = request(url, "GET", null)

    fun postJson(url: String, json: String): String? = request(url, "POST", json)

    /** POST для эндпоинтов без тела ответа (напр. 204). true = 2xx. */
    fun postOk(url: String, json: String): Boolean = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(json.toByteArray()) }
            val ok = responseCode in 200..299
            disconnect()
            ok
        }
    } catch (_: Exception) {
        false
    }

    private fun request(url: String, method: String, json: String?): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(json.toByteArray()) }
            }
            val ok = responseCode in 200..299
            val stream = if (ok) inputStream else errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            disconnect()
            if (ok) text else null
        }
    } catch (_: Exception) {
        null
    }
}
