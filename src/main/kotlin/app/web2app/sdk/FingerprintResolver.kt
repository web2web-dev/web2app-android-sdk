package app.web2app.sdk

import android.content.res.Resources
import android.os.Build
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WEB-1213 — опознание первого запуска по отпечатку устройства (паритет iOS
 * FingerprintResolver 0.7.0, аналог Web2Wave `identify`). На Android закрывает
 * сценарий, где Install Referrer пуст или недоступен (Huawei/sideload/органика),
 * а MMP-токена и кэша guid нет — раньше это сразу вело в email-фолбэк.
 *
 * Механика: веб-страница после оплаты в момент ухода в стор отправила на
 * бэкенд сигналы (IP/UA — серверные; экран/таймзона/язык — клиентские).
 * Приложение на первом запуске шлёт СВОИ сигналы на
 * `POST /public/handoff/resolve-by-fingerprint`; бэкенд отвечает guid только
 * при единственном уверенном совпадении в окне (два кандидата на одном IP →
 * отказ обоим), иначе — унифицированный 404 и мы уходим в прежний
 * email-фолбэк. Отпечаток одноразовый.
 *
 * PII не собирается: модель/версия ОС/экран/таймзона/язык — не рекламные
 * идентификаторы (ни GAID, ни ANDROID_ID).
 */
internal class FingerprintResolver(private val config: Web2AppConfig) {

    /**
     * Сигналы устройства. Экран — ЛОГИЧЕСКИЕ dp, нормализованные к
     * «min x max» (ориентация не должна рвать матч; сервер нормализует так же).
     */
    data class Signals(
        val platform: String,
        val osVersion: String,
        val deviceModel: String?,
        val screen: String,
        val timezone: String,
        val language: String?,
    )

    /** Совпадение: guid + метод матча (для журнала; наружу SDK его не отдаёт). */
    data class Match(val guid: String, val matchMethod: String?)

    /**
     * Резолв guid по отпечатку. Любой промах (нет совпадения, неоднозначно,
     * протухло, выключено у проекта) приходит одинаковым 404 — наружу отдаём
     * просто null, и вызывающий уходит в email-фолбэк. [onResult] приходит на
     * фоновом потоке — вызывающий сам оборачивает доставку в [MainThread].
     */
    fun resolve(onResult: (Match?) -> Unit) {
        Http.io {
            val signals = runCatching { collectSignals() }.getOrNull()
            if (signals == null) {
                // Сигналы не собрались (экзотическая среда) — честный промах.
                onResult(null)
                return@io
            }
            val resp = Http.postWithStatus(
                "${config.baseUrl}/public/handoff/resolve-by-fingerprint",
                requestBody(config.projectId, signals),
            )
            val match = parseResolveResponse(resp.body)
            if (match == null) {
                if (resp.code == 0) {
                    SdkLogger.error("identify.fingerprint_network_error")
                } else {
                    // 404 = штатный промах (нет уверенного единственного совпадения).
                    SdkLogger.log(
                        "identify.fingerprint_no_match",
                        context = mapOf("http" to resp.code.toString()),
                    )
                }
            }
            onResult(match)
        }
    }

    companion object {
        /** `"guid":"..."` с учётом экранирования — как в [AttributionResolver]. */
        private val GUID_FIELD = Regex("\"guid\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val MATCH_METHOD_FIELD =
            Regex("\"matchMethod\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** Сигналы текущего устройства (Android-рантайм; юнитами не покрывается). */
        fun collectSignals(): Signals {
            val dm = Resources.getSystem().displayMetrics
            return Signals(
                platform = "android",
                osVersion = Build.VERSION.RELEASE ?: "",
                deviceModel = Build.MODEL,
                screen = normalizedScreen(dm.widthPixels, dm.heightPixels, dm.density),
                timezone = TimeZone.getDefault().id,
                language = runCatching { Locale.getDefault().toLanguageTag() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() && it != "und" }
                    ?: Locale.getDefault().language.takeIf { it.isNotEmpty() },
            )
        }

        /**
         * Нормализация экрана: px → логические dp (px/density, округление до
         * целого), затем «min x max» — портрет и ландшафт дают одну строку.
         * Чистая JVM-функция (юнит-тесты без Android). density ≤ 0 (заглушки,
         * битые метрики) → px как есть, лишь бы не делить на ноль.
         */
        fun normalizedScreen(widthPixels: Int, heightPixels: Int, density: Float): String {
            val d = if (density > 0f) density else 1f
            val w = (widthPixels / d).roundToInt()
            val h = (heightPixels / d).roundToInt()
            return "${min(w, h)}x${max(w, h)}"
        }

        /**
         * Тело `POST /public/handoff/resolve-by-fingerprint` (контракт бэка):
         * `{projectId,platform,osVersion,deviceModel?,screen,timezone,language?}`.
         * null-поля не отправляются. Чистая JVM-сборка (без org.json — ловушка L-4).
         */
        fun requestBody(projectId: String, signals: Signals): String {
            val fields = LinkedHashMap<String, String>()
            fields["projectId"] = projectId
            fields["platform"] = signals.platform
            fields["osVersion"] = signals.osVersion
            signals.deviceModel?.let { fields["deviceModel"] = it }
            fields["screen"] = signals.screen
            fields["timezone"] = signals.timezone
            signals.language?.let { fields["language"] = it }
            return SdkLogPayload.objectJson(fields)
        }

        /**
         * Успех приходит обёрткой `{"success":true,"data":{"guid":…,"matchMethod":…}}`
         * (та же форма, что у `public/handoff/resolve`). Тело ошибки
         * (унифицированный 404), мусор, пустая строка, null и пустой guid → null.
         * Узкий regex-парс вместо org.json — тот в JVM-юнитах Android заглушка
         * (паттерн [AttributionResolver.parseGuidResponse]).
         */
        fun parseResolveResponse(body: String?): Match? {
            if (body == null) return null
            val guid = GUID_FIELD.find(body)?.groupValues?.get(1)
                ?.let(::unescape)
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val matchMethod = MATCH_METHOD_FIELD.find(body)?.groupValues?.get(1)
                ?.let(::unescape)
                ?.takeIf { it.isNotEmpty() }
            return Match(guid, matchMethod)
        }

        private fun unescape(raw: String): String = raw
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }
}
