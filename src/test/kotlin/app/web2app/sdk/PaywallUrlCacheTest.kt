package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.7.2 — кэш адреса пейволла (час) и прогрев движка WebView только в основном
 * процессе. Чистая JVM, часы подменяются.
 */
class PaywallUrlCacheTest {

    private val hour = PaywallUrlCache.TTL_MS

    // ── свежесть ────────────────────────────────────────────────────────────

    @Test
    fun ttlIsOneHour() {
        assertEquals(3_600_000L, PaywallUrlCache.TTL_MS)
    }

    @Test
    fun freshWithinHour() {
        assertTrue(PaywallUrlCache.isFresh(savedAtMs = 1_000, nowMs = 1_000 + hour - 1))
    }

    @Test
    fun staleExactlyAtHour() {
        assertFalse(PaywallUrlCache.isFresh(savedAtMs = 1_000, nowMs = 1_000 + hour))
    }

    @Test
    fun staleWhenClockWentBack() {
        // Перезагрузка телефона сбрасывает монотонные часы — запись не верим.
        assertFalse(PaywallUrlCache.isFresh(savedAtMs = 5_000, nowMs = 4_000))
    }

    // ── кэш ─────────────────────────────────────────────────────────────────

    @Test
    fun returnsSavedUrlWhileFresh() {
        var now = 10_000L
        val cache = PaywallUrlCache(now = { now })
        cache.put("pw1", "https://client.example.com/paywall/pw1")
        now += hour - 1
        assertEquals("https://client.example.com/paywall/pw1", cache.get("pw1"))
    }

    @Test
    fun missAfterHour() {
        var now = 10_000L
        val cache = PaywallUrlCache(now = { now })
        cache.put("pw1", "https://client.example.com/paywall/pw1")
        now += hour
        assertNull(cache.get("pw1"))
    }

    @Test
    fun missForOtherId() {
        val cache = PaywallUrlCache(now = { 0L })
        cache.put("pw1", "https://client.example.com/paywall/pw1")
        assertNull(cache.get("pw2"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = PaywallUrlCache(now = { 0L })
        cache.put("pw1", "https://client.example.com/paywall/pw1")
        cache.clear()
        assertNull(cache.get("pw1"))
    }

    // ── прогрев движка WebView ──────────────────────────────────────────────

    @Test
    fun warmUpInMainProcess() {
        assertTrue(WebViewWarmup.shouldWarmUp("com.example.app", "com.example.app"))
    }

    @Test
    fun noWarmUpInSecondaryProcess() {
        assertFalse(WebViewWarmup.shouldWarmUp("com.example.app:push", "com.example.app"))
    }

    @Test
    fun noWarmUpWhenProcessUnknown() {
        assertFalse(WebViewWarmup.shouldWarmUp(null, "com.example.app"))
        assertFalse(WebViewWarmup.shouldWarmUp("com.example.app", null))
    }
}
