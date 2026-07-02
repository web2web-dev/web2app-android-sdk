package app.web2app.sdk

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * Android identify-ветка: читает Google Play Install Referrer ОДИН раз на 1-м запуске
 * (детерминир., даром — не строим fingerprint) → извлекает carrier-token → resolve → guid.
 *
 * Play кладёт значение `&referrer=<token>` из install-ссылки (WEB-433) в installReferrer-строку.
 * Токен = opaque carrier (не сырой guid). SERVICE_UNAVAILABLE → короткий reconnect;
 * FEATURE_NOT_SUPPORTED (Huawei/sideload) → провал → email-fallback (caller.onNeedEmail).
 *
 * ⚠ Точный парс referrer-строки (сырой токен vs `key=token`) сверить с форматом ссылки WEB-433.
 */
internal class InstallReferrerResolver(private val config: Web2AppConfig) {

    fun readAndResolve(context: Context, onResult: (Result<String>) -> Unit) {
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val raw = client.installReferrer.installReferrer
                            val token = extractToken(raw)
                            if (token.isNullOrEmpty()) {
                                onResult(Result.failure(IllegalStateException("no token in referrer")))
                            } else {
                                AttributionResolver(config).resolveToken(token, onResult)
                            }
                        }
                        // Huawei/sideload — API недоступен → email-fallback.
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                            onResult(Result.failure(IllegalStateException("referrer FEATURE_NOT_SUPPORTED")))
                        // Транзиент — caller может повторить identify() позже.
                        InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE ->
                            onResult(Result.failure(IllegalStateException("referrer SERVICE_UNAVAILABLE")))
                        else ->
                            onResult(Result.failure(IllegalStateException("referrer code=$responseCode")))
                    }
                } catch (e: Exception) {
                    onResult(Result.failure(e))
                } finally {
                    runCatching { client.endConnection() }
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                // Одноразовое чтение; не держим соединение.
            }
        })
    }

    /**
     * Извлекает carrier-token из install-referrer строки. WEB-433 кладёт `referrer=<token>`,
     * так что Play вернёт либо голый токен, либо `key=value&...`. Пробуем как query, иначе raw.
     */
    private fun extractToken(referrer: String?): String? {
        if (referrer.isNullOrEmpty()) return null
        // Формат `a=b&referrer=<token>` → достаём referrer; иначе — вся строка = токен.
        val parts = referrer.split("&")
        for (p in parts) {
            val kv = p.split("=", limit = 2)
            if (kv.size == 2 && (kv[0] == "referrer" || kv[0] == "deep_link_value")) {
                return kv[1]
            }
        }
        return if (referrer.contains("=")) null else referrer
    }
}
