package app.web2app.sdk

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.webkit.WebSettings

/**
 * 0.7.2 — прогрев движка WebView (Chromium) при [Web2AppSdk.configure]: первый
 * встроенный показ иначе платит за загрузку движка сам, это заметная пауза на
 * белом экране. `WebSettings.getDefaultUserAgent` поднимает движок без создания
 * WebView. Один раз за процесс, на главном потоке, ошибки глотаются с записью
 * `paywall.webview_warmup_failed` (движок может обновляться или отсутствовать).
 *
 * Только в ОСНОВНОМ процессе приложения: `configure` обычно зовут из
 * `Application.onCreate`, а он исполняется в каждом процессе приложения. Движок,
 * поднятый во вспомогательном процессе, может занять каталог данных WebView, и
 * тогда WebView основного процесса упадёт («один каталог данных — один процесс»,
 * Android 9+). Прогрев того не стоит.
 */
internal object WebViewWarmup {
    @Volatile
    private var started = false

    fun warmUp(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext ?: context
        val processName = runCatching { currentProcessName(appContext) }.getOrNull()
        if (!shouldWarmUp(processName, appContext.packageName)) {
            SdkLogger.log("paywall.webview_warmup_skipped", context = mapOf("reason" to "not_main_process"))
            return
        }
        Handler(Looper.getMainLooper()).post {
            try {
                WebSettings.getDefaultUserAgent(appContext)
            } catch (t: Throwable) {
                SdkLogger.error(
                    "paywall.webview_warmup_failed",
                    context = mapOf("error" to t.javaClass.simpleName),
                )
            }
        }
    }

    /** Греть ли движок: только в основном процессе (имя процесса = имя пакета). */
    fun shouldWarmUp(processName: String?, packageName: String?): Boolean =
        processName != null && processName == packageName

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val pid = Process.myPid()
        return am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }
}
