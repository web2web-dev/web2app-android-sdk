package app.web2app.sdk

/**
 * WEB-814 / Б-1 — JS-мост `web2appBridge` со страницы веб-воронки (embedded-режим).
 *
 * Контракт общий с iOS/фронтом (frontend/src/utils/web2appBridge.ts):
 * Android-ветка страницы шлёт `window.web2appBridge.postMessage(json)`, где
 * json — СТРОКА вида `{"source":"web2app","event":"<имя>", ...поля}`.
 *
 * Сегодня приходят: `quiz_start`, `quiz_screen_view`, `quiz_answer`,
 * `quiz_email_submit`, `quiz_complete` (события прохождения квиза),
 * `paywall_result` со `status` (`success` / `dismissed`) и `close`.
 *
 * ⚠ Закрывают показ РОВНО два события — см. [BridgeEventParser.terminalEvent].
 * Всё остальное доезжает до слушателя интегратора и оставляет WebView открытым.
 */
enum class BridgeEvent {
    /** Оплата подтверждена страницей — пейволл закрывается автоматически. */
    PAYMENT_SUCCESS,

    /** Тап по кнопке «Закрыть» на странице. */
    CLOSE,
}

/**
 * Безопасные поля события воронки. PII в мост НЕ уходит: email и сырые значения
 * ответов веб вырезает на своей стороне — сюда доезжают только идентификаторы.
 *
 * Любое поле может отсутствовать (или прийти не того типа) — тогда оно `null`.
 */
data class FunnelEventData(
    /** Идентификатор экрана воронки. */
    val screenId: String? = null,
    /** Порядковый номер экрана (с нуля). */
    val screenIndex: Int? = null,
    /** Сколько экранов в воронке всего. */
    val screenTotal: Int? = null,
    /** `metadata.blockId` — идентификатор блока, породившего событие. */
    val blockId: String? = null,
    /** `metadata.blockType` — тип блока (например `single_choice`). */
    val blockType: String? = null,
)

/**
 * Разобранное сообщение моста: имя события + безопасные поля + `status`
 * (нужен только для `paywall_result`, наружу не публикуется).
 */
internal data class BridgeMessage(
    val name: String,
    val status: String? = null,
    val data: FunnelEventData = FunnelEventData(),
)

internal object BridgeEventParser {
    /** Событие успешной оплаты. */
    private const val EVENT_PAYWALL_RESULT = "paywall_result"

    /** Кнопка «Закрыть» на странице. */
    private const val EVENT_CLOSE = "close"

    /** Статус `paywall_result`, означающий подтверждённую оплату. */
    private const val STATUS_SUCCESS = "success"

    /**
     * Чистый JVM-парсер тела postMessage (regex по фиксированному контракту —
     * org.json в JVM-юнитах Android-заглушка, тест на нём бессмысленен).
     *
     * Разбирает ЛЮБОЕ событие: незнакомое имя — не ошибка, оно просто доедет до
     * слушателя и никого не закроет. Битый json / нет ключа `event` → null.
     * Поле не того типа или отсутствует → поле пропускается, исключений нет.
     */
    fun parseMessage(json: String?): BridgeMessage? {
        if (json == null) return null
        val name = extract(json, "event") ?: return null
        val metadata = extractObject(json, "metadata")
        return BridgeMessage(
            name = name,
            status = extract(json, "status"),
            data = FunnelEventData(
                screenId = extract(json, "screenId"),
                screenIndex = extractInt(json, "screenIndex"),
                screenTotal = extractInt(json, "screenTotal"),
                blockId = metadata?.let { extract(it, "blockId") },
                blockType = metadata?.let { extract(it, "blockType") },
            ),
        )
    }

    /**
     * ТЕРМИНАЛЬНОСТЬ (шрам Б-1): какие события закрывают показ. Их РОВНО два —
     * `paywall_result:success` и `close`. Всё остальное (включая все `quiz_*` и
     * `paywall_result:dismissed`) возвращает null = окно остаётся открытым.
     *
     * Функция чистая и живёт здесь, а не внутри Activity, именно чтобы юнит
     * ловил регрессию «событие квиза закрыло воронку на первом же экране».
     */
    fun terminalEvent(message: BridgeMessage): BridgeEvent? = when {
        message.name == EVENT_PAYWALL_RESULT && message.status == STATUS_SUCCESS -> BridgeEvent.PAYMENT_SUCCESS
        message.name == EVENT_CLOSE -> BridgeEvent.CLOSE
        else -> null
    }

    /**
     * Совместимость: старый контракт «json → терминальное событие или null».
     * Поведение не изменилось — знали два события, знаем те же два.
     */
    fun parse(json: String?): BridgeEvent? = parseMessage(json)?.let { terminalEvent(it) }

    /** Строковое поле верхнего уровня переданного объекта. */
    private fun extract(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(json)
            ?.groupValues
            ?.get(1)

    /** Целочисленное поле. Строка вместо числа (`"1"`) сюда не подходит — поле пропускается. */
    private fun extractInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    /**
     * Вложенный объект (`metadata`) целиком — со счётом скобок и уважением к
     * строкам/экранированию, чтобы `blockId` читался ИМЕННО из metadata, а не
     * откуда угодно. Поле не объект / скобка не закрыта → null (поле пропускаем).
     */
    private fun extractObject(json: String, key: String): String? {
        val head = Regex("\"$key\"\\s*:\\s*\\{").find(json) ?: return null
        val open = json.indexOf('{', head.range.first)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until json.length) {
            val c = json[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
        }
        return null
    }
}

/**
 * Маршрутизация сообщения моста — одна на весь SDK, чтобы правило «что закрывает
 * показ» проверялось юнитом, а не только жило внутри Activity.
 *
 * Порядок: сперва событие уходит слушателю интегратора, потом (и только если оно
 * терминальное) вызывается [onTerminal] — закрытие показа.
 */
internal object BridgeMessageRouter {
    fun route(json: String?, onTerminal: (BridgeEvent) -> Unit) {
        val message = BridgeEventParser.parseMessage(json) ?: return
        Web2AppSdk.emitFunnelEvent(message.name, message.data)
        BridgeEventParser.terminalEvent(message)?.let(onTerminal)
    }
}
