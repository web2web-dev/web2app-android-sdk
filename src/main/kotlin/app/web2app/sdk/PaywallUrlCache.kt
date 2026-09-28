package app.web2app.sdk

import android.os.SystemClock

/**
 * 0.7.2 — кэш «id пейволла → публичный адрес» для [Web2AppSdk.openWebPaywallEmbeddedById]:
 * повторное открытие того же пейволла в течение часа не ждёт `GET /public/paywall-url/:id`.
 * Промах или протухшая запись — прежний путь через сеть. Кэшируется только успешный
 * адрес (404 не запоминаем: пейволл могут опубликовать через минуту).
 *
 * В адресе из ручки нет ни guid, ни email — их дописывает [WebPaywallLauncher.appOriginUrl]
 * уже после кэша, так что запись не привязана к пользователю. Сбрасывается при
 * [Web2AppSdk.configure] (мог смениться проект/сервер) и при смене guid.
 *
 * [now] — монотонные часы (не сбиваются переводом времени на телефоне); подменяемы в тестах.
 */
internal class PaywallUrlCache(
    private val ttlMs: Long = TTL_MS,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private class Entry(val url: String, val savedAtMs: Long)

    private val entries = HashMap<String, Entry>()

    /** Свежий адрес либо null (нет записи / протухла — протухшая удаляется). */
    @Synchronized
    fun get(paywallId: String): String? {
        val entry = entries[paywallId] ?: return null
        if (!isFresh(entry.savedAtMs, now(), ttlMs)) {
            entries.remove(paywallId)
            return null
        }
        return entry.url
    }

    @Synchronized
    fun put(paywallId: String, url: String) {
        entries[paywallId] = Entry(url, now())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        /** Час — решение владельца (0.7.2). */
        const val TTL_MS = 60 * 60 * 1000L

        /**
         * Свежий ли адрес, сохранённый в [savedAtMs], на момент [nowMs]. Часы ушли
         * назад (перезагрузка телефона сбрасывает монотонные часы) — не свежий.
         */
        fun isFresh(savedAtMs: Long, nowMs: Long, ttlMs: Long = TTL_MS): Boolean =
            nowMs >= savedAtMs && nowMs - savedAtMs < ttlMs
    }
}
