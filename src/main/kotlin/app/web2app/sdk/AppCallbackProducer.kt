package app.web2app.sdk

import org.json.JSONObject

/**
 * APP_INSTALLED-продюсер (закрывает Lucas-дыру «висячий хук»). На 1-м запуске любая
 * ветка резолва guid → `POST /public/handoff/app-callback` (метрика conversionToApp, 204,
 * идемпотентно на бэке). Grant НЕ зависит от этого callback — чисто метрика, fire-and-forget.
 */
internal class AppCallbackProducer(private val config: Web2AppConfig) {
    fun reportAppInstalled(guid: String) {
        Http.io {
            val body = JSONObject()
                .put("guid", guid)
                .put("projectId", config.projectId)
                .put("device", "android")
                .put("event", "app_installed")
                .toString()
            // Сбой метрики НЕ ломает пользовательский поток.
            Http.postJson("${config.baseUrl}/public/handoff/app-callback", body)
        }
    }
}
