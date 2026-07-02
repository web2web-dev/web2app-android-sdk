package app.web2app.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Скелет-тест (WEB-434): POC-независимая чистая логика isActive. */
class EntitlementGrantTest {
    @Test
    fun activeStatusIsActive() {
        val g = EntitlementGrant(level = "price_abc", status = "active", expiresAt = null, priceId = "price_abc")
        assertTrue(g.isActive)
    }

    @Test
    fun expiredStatusNotActive() {
        val g = EntitlementGrant(level = "l", status = "expired", expiresAt = "2020-01-01T00:00:00Z", priceId = null)
        assertFalse(g.isActive)
    }
}
