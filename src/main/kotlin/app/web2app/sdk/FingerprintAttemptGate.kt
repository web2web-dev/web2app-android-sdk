package app.web2app.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * WEB-1384 — хранилище метки «первая неудачная попытка отпечатка».
 *
 * Обычные SharedPreferences, не Encrypted: метка не секрет, а после успешного
 * опознания ветка отпечатка перекрывается сохранённым guid и это хранилище
 * больше не читается. Пишется только ПЕРВАЯ неудача — окно (2 часа, см.
 * [FingerprintResolver.ATTEMPT_WINDOW_MS]) отсчитывается от неё.
 */
internal class FingerprintAttemptGate(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun firstFailedAtMillis(): Long? {
        val ts = prefs.getLong(KEY_FIRST_FAILED_AT, 0L)
        return if (ts > 0L) ts else null
    }

    fun markFailure(nowMillis: Long = System.currentTimeMillis()) {
        if (firstFailedAtMillis() != null) return
        prefs.edit().putLong(KEY_FIRST_FAILED_AT, nowMillis).apply()
    }

    private companion object {
        const val PREFS_NAME = "web2app_fingerprint"
        const val KEY_FIRST_FAILED_AT = "first_failed_at"
    }
}
