package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 0.7.2 — решение «прогревать ли движок WebView»: выключатель в настройке SDK
 * (`configure(..., warmUpWebView = false)`) × основной процесс приложения.
 * Ответ — причина пропуска для события `paywall.webview_warmup_skipped` или
 * `null` («греть»).
 */
class WebViewWarmupTest {

    private val pkg = "com.example.app"

    @Test
    fun enabledInMainProcessWarmsUp() {
        assertNull(WebViewWarmup.skipReason(enabledByConfig = true, processName = pkg, packageName = pkg))
    }

    @Test
    fun enabledInSecondaryProcessSkipsAsNotMainProcess() {
        assertEquals(
            "not_main_process",
            WebViewWarmup.skipReason(enabledByConfig = true, processName = "$pkg:push", packageName = pkg),
        )
    }

    @Test
    fun enabledWithUnknownProcessSkipsAsNotMainProcess() {
        assertEquals(
            "not_main_process",
            WebViewWarmup.skipReason(enabledByConfig = true, processName = null, packageName = pkg),
        )
    }

    @Test
    fun disabledInMainProcessSkipsAsDisabledByConfig() {
        assertEquals(
            "disabled_by_config",
            WebViewWarmup.skipReason(enabledByConfig = false, processName = pkg, packageName = pkg),
        )
    }

    @Test
    fun disabledInSecondaryProcessStillReportsDisabledByConfig() {
        // Выключатель интегратора — первая причина: имя процесса тогда даже не нужно.
        assertEquals(
            "disabled_by_config",
            WebViewWarmup.skipReason(enabledByConfig = false, processName = "$pkg:push", packageName = pkg),
        )
    }
}
