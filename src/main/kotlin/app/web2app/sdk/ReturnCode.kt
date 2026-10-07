package app.web2app.sdk

import java.net.URLDecoder

/**
 * WEB-2479 — отказ обмена кода с HTTP-кодом ответа (0 — сетевое исключение,
 * 404 — сервер отверг код: не найден / просрочен / уже использован).
 */
internal class ResolveFailedException(val httpCode: Int) :
    IllegalStateException("resolve failed (http $httpCode)")

/** WEB-2479 — чем закончился обмен кода из ссылки. */
internal sealed class CodeExchangeOutcome {
    /** Сервер обменял код — новый guid перезаписывает сохранённый. */
    data class Exchanged(val guid: String) : CodeExchangeOutcome()

    /**
     * Сервер отверг код (404), а guid уже сохранён — отдаём сохранённый успехом
     * (решение владельца 06.10, паритет iOS).
     */
    data class KeptCached(val guid: String) : CodeExchangeOutcome()

    /** Обмен не удался и подменить нечем (сеть, не-404, нет сохранённого guid). */
    data class Failed(val error: Throwable) : CodeExchangeOutcome()
}

/** WEB-2479 — правила обмена кода. Чистая JVM — проверяемо юнит-тестами. */
internal object ReturnCodeRules {
    private const val CODE_REJECTED_HTTP = 404

    /**
     * Итог обмена. Сохранённый guid подставляется ТОЛЬКО при отказе сервера
     * (404): сеть или 5xx/429 — ошибка, иначе покупатель, чья оплата лежит под
     * новым кодом, молча получил бы старый guid.
     */
    fun outcome(result: Result<String>, cachedGuid: String?): CodeExchangeOutcome {
        result.getOrNull()?.let { return CodeExchangeOutcome.Exchanged(it) }
        val error = result.exceptionOrNull() ?: IllegalStateException("resolve failed")
        if (error is ResolveFailedException &&
            error.httpCode == CODE_REJECTED_HTTP &&
            !cachedGuid.isNullOrEmpty()
        ) {
            return CodeExchangeOutcome.KeptCached(cachedGuid)
        }
        return CodeExchangeOutcome.Failed(error)
    }

    /**
     * Код из возвратной ссылки `<схема-приложения>://handoff?code=<код>`.
     * Без android.net.Uri — чтобы проверялось на чистой JVM.
     */
    fun codeFromReturnUrl(url: String): String? {
        val query = url.substringAfter('?', missingDelimiterValue = "").substringBefore('#')
        val raw = query.split('&')
            .firstOrNull { it.substringBefore('=') == "code" }
            ?.substringAfter('=', missingDelimiterValue = "")
            ?: return null
        val code = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return code.ifEmpty { null }
    }
}

/**
 * WEB-2479 п.2 — ход возвратного метода: шаги подставляются снаружи
 * ([Web2AppSdk.handleReturnUrl] — настоящие, тесты — подмены). Результат в
 * `completion` — ровно один раз.
 *
 * Окно библиотеки открыто ([windowOpen]: идёт незавершённый `openWebPaywall`
 * или встроенный показ) → сначала опрос права по сохранённому guid. Нашлась
 * действующая запись — код НЕ тратится (он остаётся живым для письма «оплата
 * прошла»). Действующей нет, а код в ссылке есть (решение владельца 07.10:
 * покупатель бросил окно и оплатил в обычном браузере) — код обменивается и
 * право опрашивается по новому guid; обмен не удался — ответ первого опроса.
 * Окна нет → обмен кода, затем опрос права по guid из хранилища.
 */
internal class ReturnUrlFlow(
    private val windowOpen: Boolean,
    private val exchange: (code: String, onOutcome: (CodeExchangeOutcome) -> Unit) -> Unit,
    /** Опрос права по guid, который лежит в хранилище в момент вызова. */
    private val pollStoredGuid: (onResult: (EntitlementGrant?) -> Unit) -> Unit,
) {
    fun run(code: String?, completion: (EntitlementGrant?) -> Unit) {
        if (code == null) {
            pollStoredGuid(completion)
            return
        }
        if (!windowOpen) {
            exchangeThenPoll(code, onFailed = null, completion)
            return
        }
        pollStoredGuid { grant ->
            if (grant != null && grant.isActive) {
                completion(grant)
            } else {
                exchangeThenPoll(code, onFailed = grant, completion)
            }
        }
    }

    private fun exchangeThenPoll(
        code: String,
        onFailed: EntitlementGrant?,
        completion: (EntitlementGrant?) -> Unit,
    ) {
        exchange(code) { outcome ->
            when (outcome) {
                is CodeExchangeOutcome.Exchanged,
                is CodeExchangeOutcome.KeptCached,
                -> pollStoredGuid(completion)
                is CodeExchangeOutcome.Failed -> completion(onFailed)
            }
        }
    }
}
