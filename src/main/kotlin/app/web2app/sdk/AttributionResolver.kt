package app.web2app.sdk

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Резолв guid из carrier-token или email. Стабильные backend-контракты (POC-независимо):
 *  - token → `GET /public/handoff/resolve?code=<token>` → `{"success":true,"data":{"guid":…}}` (WEB-433).
 *  - email → verified-resolve (WEB-431, In Review — контракт финализируется при мёрдже).
 */
internal class AttributionResolver(private val config: Web2AppConfig) {

    fun resolveToken(token: String, onResult: (Result<String>) -> Unit) {
        Http.io {
            val q = URLEncoder.encode(token, "UTF-8")
            val resp = Http.getWithStatus("${config.baseUrl}/public/handoff/resolve?code=$q")
            val guid = parseGuidResponse(resp.body)
            if (guid != null) {
                onResult(Result.success(guid))
            } else {
                // В журнал — с HTTP-кодом (0 = сетевое исключение, 404 =
                // унифицированный негатив бэка). Паритет iOS resolve.failed.
                SdkLogger.error("resolve.failed", context = mapOf("http" to resp.code.toString()))
                onResult(Result.failure(IllegalStateException("resolve failed")))
            }
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
            val resp = Http.postWithStatus(
                "${config.baseUrl}/public/handoff/email-recovery/request",
                body,
            )
            val ok = resp.code in 200..299
            if (!ok) {
                // PII: email в журнал не пишется — только HTTP-код неуспеха.
                SdkLogger.error(
                    "email_recovery.http_error",
                    context = mapOf("http" to resp.code.toString()),
                )
            }
            onResult(
                if (ok) Result.success(Unit)
                else Result.failure(IllegalStateException("email-recovery request failed")),
            )
        }
    }

    companion object {
        /** `"guid":"..."` с учётом экранирования — как в [WebPaywallLauncher.parsePaywallUrlResponse]. */
        private val GUID_FIELD = Regex("\"guid\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /**
         * Парсинг ответа `GET /public/handoff/resolve?code=<token>` → guid.
         *
         * Живой прод отдаёт обёртку `{"success":true,"data":{"guid":"X","projectId":"Y"}}`;
         * плоское тело `{"guid":"X"}` поддержано для других/старых окружений. Тело ошибки
         * (унифицированный 404 `{"message":...,"statusCode":404}`), мусор, пустая строка и null → null,
         * т.е. наверх уходит `Result.failure`. Узкий regex-парс вместо org.json — тот в
         * JVM-юнитах Android заглушка и юнит-тестируемым парсер на нём не сделать.
         */
        fun parseGuidResponse(body: String?): String? {
            if (body == null) return null
            val raw = GUID_FIELD.find(body)?.groupValues?.get(1) ?: return null
            val guid = raw
                .replace("\\/", "/")
                .replace("\\\"", "\"")
            return guid.ifEmpty { null }
        }
    }
}
