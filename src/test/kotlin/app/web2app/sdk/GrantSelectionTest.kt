package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-2480 п.1 — [Web2AppSdk.entitlement] отдаёт первую ДЕЙСТВУЮЩУЮ запись, а если
 * действующих нет — самую новую (первую в ответе), как раньше. Сервер отдаёт записи
 * новыми вперёд; до починки библиотека брала только первую и говорила оплатившему
 * «не оплачено», если самая новая запись неактивна (возврат докупки, истёкшая).
 */
class GrantSelectionTest {
    private fun body(vararg grants: String) =
        """{"guid":"g1","testMode":false,"grants":[${grants.joinToString(",")}]}"""

    private fun grant(level: String, status: String) =
        """{"level":"$level","status":"$status","expires_at":null,"price_id":"$level"}"""

    @Test
    fun activeOlderGrantWinsOverNewerExpired() {
        val g = EntitlementClient.parseGrant(
            body(grant("one_time", "expired"), grant("subscription", "active")),
        )
        assertEquals("subscription", g?.level)
        assertTrue(g!!.isActive)
    }

    @Test
    fun activeOlderGrantWinsOverNewerRevoked() {
        val g = EntitlementClient.parseGrant(
            body(grant("refunded", "revoked"), grant("expired_old", "expired"), grant("sub", "active")),
        )
        assertEquals("sub", g?.level)
    }

    @Test
    fun onlyInactiveGrantIsReturnedAsIs() {
        val g = EntitlementClient.parseGrant(body(grant("refunded", "revoked")))
        assertEquals("refunded", g?.level)
        assertFalse(g!!.isActive)
    }

    @Test
    fun noActiveGrantsReturnsNewest() {
        val g = EntitlementClient.parseGrant(
            body(grant("newest", "expired"), grant("older", "revoked")),
        )
        assertEquals("newest", g?.level)
    }

    @Test
    fun firstOfSeveralActiveGrantsKeepsServerOrder() {
        val g = EntitlementClient.parseGrant(
            body(grant("a", "active"), grant("b", "active")),
        )
        assertEquals("a", g?.level)
    }

    @Test
    fun emptyListIsNull() {
        assertNull(EntitlementClient.parseGrant(body()))
    }

    @Test
    fun parseGrantsKeepsAllInServerOrder() {
        val all = EntitlementClient.parseGrants(
            body(grant("x", "expired"), grant("y", "active")),
        )
        assertEquals(listOf("x", "y"), all.map { it.level })
    }
}
