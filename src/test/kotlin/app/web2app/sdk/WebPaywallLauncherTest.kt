package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WEB-525 под-атом C — openWebPaywall (app-origin URL + guid-поллинг возврат).
 * POC-независимое JVM-ядро (сборка URL + поллинг-оркестрация). Custom Tabs / сеть —
 * на девайсе (built≠works, L6).
 */
class WebPaywallLauncherTest {
    @Test
    fun appOriginUrlIncludesOriginEmailGuid() {
        val url = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = "user@app.example",
            guid = "g-123",
        )
        assertTrue(url.startsWith("https://client.example.com/paywall/pw1?"))
        assertTrue(url.contains("origin=app"))
        assertTrue(url.contains("guid=g-123"))
        // email URL-кодируется (@ → %40)
        assertTrue(url.contains("email=user%40app.example"))
    }

    @Test
    fun appOriginUrlOmitsEmailWhenNullAndPreservesQuery() {
        val url = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1?utm=x",
            email = null,
            guid = "g-9",
        )
        assertFalse(url.contains("email="))
        assertTrue(url.contains("origin=app"))
        assertTrue(url.contains("guid=g-9"))
        assertTrue(url.contains("utm=x")) // существующий query сохранён
        // append через & (query уже был)
        assertTrue(url.contains("pw1?utm=x&"))
    }

    @Test
    fun pollStopsOnActiveGrant() {
        val latch = CountDownLatch(1)
        var attempts = 0
        var result: EntitlementGrant? = null
        val active = EntitlementGrant("price_x", "active", null, "price_x")
        WebPaywallLauncher.pollForActiveGrant(
            intervalMs = 10,
            maxAttempts = 5,
            fetch = { cb ->
                attempts++
                cb(if (attempts >= 2) active else null) // null, потом active
            },
            completion = { g ->
                result = g
                latch.countDown()
            },
        )
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result?.isActive == true)
        assertEquals(2, attempts)
    }

    @Test
    fun pollGivesUpAfterMaxAttempts() {
        val latch = CountDownLatch(1)
        var attempts = 0
        var completed = false
        // Заведомо не-null старт — проверяем, что поллинг перезапишет его в null (сдался).
        var result: EntitlementGrant? = EntitlementGrant("l", "active", null, null)
        WebPaywallLauncher.pollForActiveGrant(
            intervalMs = 10,
            maxAttempts = 3,
            fetch = { cb ->
                attempts++
                cb(null)
            },
            completion = { g ->
                result = g
                completed = true
                latch.countDown()
            },
        )
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(completed)
        assertNull(result)
        assertEquals(3, attempts)
    }
}
