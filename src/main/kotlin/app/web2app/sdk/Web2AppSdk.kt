package app.web2app.sdk

import android.content.Context

/**
 * web2app SDK — публичная поверхность (4 точки, Web2Wave-стиль). WEB-434.
 *
 * R1 (чтение права) НЕ тронут — [entitlement] дословно проксирует
 * `GET /public/entitlement?guid=`. R2 = обвязка доставки guid перед R1.
 *
 * Использование:
 * ```
 * Web2AppSdk.configure(context, projectId = "proj_...", baseUrl = "https://api.example.com")
 * Web2AppSdk.identify()                       // Android: авто-чтение Install Referrer
 * Web2AppSdk.entitlement { grant -> if (grant?.isActive == true) unlock() }
 * ```
 */
object Web2AppSdk {
    private var config: Web2AppConfig? = null
    private lateinit var guidStore: GuidStore

    /** Инициализация. [projectId] = ключ проекта арендатора; [baseUrl] = наш API. */
    fun configure(context: Context, projectId: String, baseUrl: String) {
        config = Web2AppConfig(projectId, baseUrl.trimEnd('/'))
        guidStore = GuidStore(context.applicationContext)
    }

    /**
     * Резолвит и персистит guid. Порядок (первый запуск):
     *  1. Сохранённый guid → возвращаем (steady-state).
     *  2. Install Referrer (`&referrer=<token>`) → resolve → guid.
     *  3. Промах (Huawei/sideload/органика, FEATURE_NOT_SUPPORTED) → [onNeedEmail] (email-fallback).
     * На успехе — APP_INSTALLED-продюсер (best-effort).
     */
    fun identify(
        onResult: (Result<String>) -> Unit = {},
        onNeedEmail: () -> Unit = {},
    ) {
        val cfg = config ?: return onResult(Result.failure(IllegalStateException("not configured")))

        guidStore.load()?.let { return onResult(Result.success(it)) }

        InstallReferrerResolver(cfg).readAndResolve(guidStore.context) { result ->
            result.onSuccess { guid ->
                guidStore.save(guid)
                AppCallbackProducer(cfg).reportAppInstalled(guid)
                onResult(Result.success(guid))
            }.onFailure {
                // Промах referrer → email-fallback (НЕ падаем молча).
                onNeedEmail()
            }
        }
    }

    /**
     * email-fallback (WEB-431, ДВА шага — асинхронно):
     *  (1) requestEmailRecovery → сервер шлёт magic-link на email (204). guid тут НЕ приходит.
     *  (2) юзер открывает ссылку из письма → приложение получает code из deeplink →
     *      identifyWithDeepLinkValue(code) резолвит guid (тот же resolve-путь).
     */
    fun requestEmailRecovery(email: String, onResult: (Result<Unit>) -> Unit) {
        val cfg = config ?: return onResult(Result.failure(IllegalStateException("not configured")))
        AttributionResolver(cfg).requestEmailRecovery(email, onResult)
    }

    /**
     * Для проектов с MMP (AppsFlyer/Adjust): интегратор передаёт deep_link_value из своего
     * MMP-callback. **[POC-1]** — валидируется на реальном девайсе (доезжает ли deep_link_value).
     */
    fun identifyWithDeepLinkValue(token: String, onResult: (Result<String>) -> Unit) {
        val cfg = config ?: return onResult(Result.failure(IllegalStateException("not configured")))
        AttributionResolver(cfg).resolveToken(token) { result ->
            result.onSuccess { guid ->
                guidStore.save(guid)
                AppCallbackProducer(cfg).reportAppInstalled(guid)
            }
            onResult(result)
        }
    }

    /** Читает право по сохранённому guid — passthrough `GET /public/entitlement?guid=`. */
    fun entitlement(onResult: (EntitlementGrant?) -> Unit) {
        val cfg = config
        val guid = if (::guidStore.isInitialized) guidStore.load() else null
        if (cfg == null || guid == null) return onResult(null)
        EntitlementClient(cfg).fetch(guid, onResult)
    }

    /** Текущий guid (client-held ключ). */
    fun currentGuid(): String? =
        if (::guidStore.isInitialized) guidStore.load() else null

    /**
     * DEBUG-only (для симулятор/эмулятор/девайс-теста без реальной атрибуции): инъекция guid.
     * ⚠ Вызывать ТОЛЬКО под `if (BuildConfig.DEBUG)` — в проде не использовать.
     */
    fun debugSetGuid(guid: String) {
        if (::guidStore.isInitialized) guidStore.save(guid)
    }

    /** DEBUG-only: сброс сохранённого guid. */
    fun debugClear() {
        if (::guidStore.isInitialized) guidStore.clear()
    }
}

/** Конфиг SDK. */
internal data class Web2AppConfig(val projectId: String, val baseUrl: String)
