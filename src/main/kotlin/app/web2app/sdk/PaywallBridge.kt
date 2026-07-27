package app.web2app.sdk

/**
 * WEB-814 — JS-мост `web2appBridge` со страницы веб-пейволла (embedded-режим).
 *
 * Контракт общий с iOS/фронтом (frontend/src/utils/web2appBridge.ts):
 * Android-ветка страницы шлёт `window.web2appBridge.postMessage(json)`, где
 * json — строка вида `{"source":"web2app","event":"paywall_result","status":"success"}`
 * либо `{"source":"web2app","event":"close"}`.
 */
enum class BridgeEvent {
    /** Оплата подтверждена страницей — пейволл закрывается автоматически. */
    PAYMENT_SUCCESS,

    /** Тап по кнопке «Закрыть» на странице. */
    CLOSE,
}

internal object BridgeEventParser {
    /**
     * Чистый JVM-парсер тела postMessage (regex по фиксированному контракту —
     * org.json в JVM-юнитах Android-заглушка). Чужой/битый json → null.
     */
    fun parse(json: String?): BridgeEvent? {
        if (json == null) return null
        val event = extract(json, "event") ?: return null
        return when (event) {
            "paywall_result" ->
                if (extract(json, "status") == "success") BridgeEvent.PAYMENT_SUCCESS else null
            "close" -> BridgeEvent.CLOSE
            else -> null
        }
    }

    private fun extract(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(json)
            ?.groupValues
            ?.get(1)
}
