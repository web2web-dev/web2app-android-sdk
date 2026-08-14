package app.web2app.sdk

import org.json.JSONObject

/**
 * APP_INSTALLED-продюсер (закрывает Lucas-дыру «висячий хук»). На 1-м запуске любая
 * ветка резолва guid → `POST /public/handoff/app-callback` (метрика conversionToApp, 204,
 * идемпотентно на бэке). Grant НЕ зависит от этого callback — чисто метрика, fire-and-forget.
 */
internal class AppCallbackProducer(private val config: Web2AppConfig) {
    fun reportAppInstalled(guid: String) {
        SdkLogger.log("app_callback.sending")
        Http.io {
            val body = JSONObject()
                .put("guid", guid)
                .put("projectId", config.projectId)
                .put("device", "android")
                .put("event", "app_installed")
                .toString()
            // Сбой метрики НЕ ломает пользовательский поток — только журнал.
            val resp = Http.postWithStatus("${config.baseUrl}/public/handoff/app-callback", body)
            if (resp.code in 200..299) {
                SdkLogger.log("app_callback.sent", context = mapOf("http" to resp.code.toString()))
            } else {
                SdkLogger.error("app_callback.failed", context = mapOf("http" to resp.code.toString()))
            }
        }
    }
}
