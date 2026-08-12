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

    /**
     * Б-3 — profile-id подписочных платформ (Adapty/RevenueCat) в URL веб-страницы.
     * Имена параметров читает фронт (`providerProfileLink.ts`) — они часть контракта,
     * поэтому проверяются дословно.
     */
    @Test
    fun appOriginUrlIncludesBothProviderProfileIds() {
        val url = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = null,
            guid = "g-1",
            adaptyProfileId = "adapty-1",
            revenuecatProfileId = "rc-1",
        )
        assertTrue(url.contains("adapty_profile_id=adapty-1"))
        assertTrue(url.contains("revenuecat_profile_id=rc-1"))
        // старое не сломали
        assertTrue(url.contains("origin=app"))
        assertTrue(url.contains("guid=g-1"))
    }

    @Test
    fun appOriginUrlOmitsAbsentProviderProfileId() {
        val onlyAdapty = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = null,
            guid = "g-1",
            adaptyProfileId = "adapty-1",
        )
        assertTrue(onlyAdapty.contains("adapty_profile_id=adapty-1"))
        assertFalse(onlyAdapty.contains("revenuecat_profile_id"))

        val onlyRevenueCat = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = null,
            guid = "g-1",
            revenuecatProfileId = "rc-1",
        )
        assertTrue(onlyRevenueCat.contains("revenuecat_profile_id=rc-1"))
        assertFalse(onlyRevenueCat.contains("adapty_profile_id"))
    }

    /** Пустая строка = «не передали»: `adapty_profile_id=` слать нельзя. */
    @Test
    fun appOriginUrlTreatsBlankProviderProfileIdAsAbsent() {
        val url = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = null,
            guid = "g-1",
            adaptyProfileId = "",
            revenuecatProfileId = "",
        )
        assertFalse(url.contains("adapty_profile_id"))
        assertFalse(url.contains("revenuecat_profile_id"))
    }

    /** Существующий query исходного URL сохранён, разделители корректны. */
    @Test
    fun appOriginUrlPreservesExistingQueryWithProviderProfileIds() {
        val withQuery = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1?utm=x",
            email = null,
            guid = "g-1",
            adaptyProfileId = "adapty-1",
            revenuecatProfileId = "rc-1",
        )
        assertTrue(withQuery.contains("utm=x"))
        assertTrue(withQuery.contains("pw1?utm=x&"))
        assertEquals(1, withQuery.count { it == '?' })
        assertTrue(withQuery.contains("&adapty_profile_id=adapty-1"))
        assertTrue(withQuery.contains("&revenuecat_profile_id=rc-1"))

        val withoutQuery = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = null,
            guid = "g-1",
            adaptyProfileId = "adapty-1",
        )
        assertTrue(withoutQuery.startsWith("https://client.example.com/paywall/pw1?"))
        assertEquals(1, withoutQuery.count { it == '?' })
    }

    /** Значения кодируются так же, как остальные (иначе `+`/`&` в id порвут query). */
    @Test
    fun appOriginUrlEncodesProviderProfileIds() {
        val url = WebPaywallLauncher.appOriginUrl(
            paywallUrl = "https://client.example.com/paywall/pw1",
            email = null,
            guid = "g-1",
            adaptyProfileId = "a b&c",
            revenuecatProfileId = "\$RCAnonymousID:7f/3",
        )
        assertTrue(url.contains("adapty_profile_id=a+b%26c"))
        assertTrue(url.contains("revenuecat_profile_id=%24RCAnonymousID%3A7f%2F3"))
        // разделителей ровно столько, сколько параметров: чужой & в значение не утёк
        assertEquals(3, url.count { it == '&' })
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
