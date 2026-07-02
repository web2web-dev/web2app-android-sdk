package app.web2app.sdk

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Резолв guid из carrier-token или email. Стабильные backend-контракты (POC-независимо):
 *  - token → `GET /public/handoff/resolve?code=<token>` → { guid } (WEB-433).
 *  - email → verified-resolve (WEB-431, In Review — контракт финализируется при мёрдже).
 */
internal class AttributionResolver(private val config: Web2AppConfig) {

    fun resolveToken(token: String, onResult: (Result<String>) -> Unit) {
        Http.io {
            val q = URLEncoder.encode(token, "UTF-8")
            onResult(parseGuid(Http.get("${config.baseUrl}/public/handoff/resolve?code=$q")))
        }
    }

    /**
     * email-recovery запрос (шаг 1): POST /public/handoff/email-recovery/request {projectId,email} → 204.
     * Сервер шлёт magic-link; guid придёт на шаге 2, когда юзер откроет ссылку (code) → resolveToken.
     * Контракт подтверждён: public-handoff.controller. Возвращает успех отправки, НЕ guid.
     */
    fun requestEmailRecovery(email: String, onResult: (Result<Unit>) -> Unit) {
        Http.io {
            val body = JSONObject()
                .put("projectId", config.projectId)
                .put("email", email)
                .toString()
            val ok = Http.postOk("${config.baseUrl}/public/handoff/email-recovery/request", body)
            onResult(if (ok) Result.success(Unit) else Result.failure(IllegalStateException("email-recovery request failed")))
        }
    }

    private fun parseGuid(body: String?): Result<String> {
        val guid = body?.let { runCatching { JSONObject(it).optString("guid") }.getOrNull() }
        return if (!guid.isNullOrEmpty()) Result.success(guid)
        else Result.failure(IllegalStateException("resolve failed"))
    }
}
