package app.web2app.sdk

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * guid = client-held ключ (WEB-428). Персист в EncryptedSharedPreferences.
 * context экспонирован для InstallReferrerClient (нужен Context для connection).
 */
internal class GuidStore(val context: Context) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "web2app_sdk_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): String? = prefs.getString(KEY_GUID, null)

    fun save(guid: String) = prefs.edit().putString(KEY_GUID, guid).apply()

    /** Удаляет сохранённый guid (DEBUG-сброс). */
    fun clear() = prefs.edit().remove(KEY_GUID).apply()

    private companion object {
        const val KEY_GUID = "guid"
    }
}
