package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-2480 п.2 — [Web2AppSdk.entitlements] различает «записей нет» и «не удалось
 * спросить». Старый [Web2AppSdk.entitlement] отвечал null на всё сразу: приложение
 * по примеру README отнимало доступ у оплатившего без сети.
 */
class EntitlementsResultTest {
    private val okBody = """
        {"guid":"g1","testMode":true,"grants":[
          {"level":"old","status":"expired","expires_at":null,"price_id":"old"},
          {"level":"sub","status":"active","expires_at":null,"price_id":"sub"}
        ]}
    """.trimIndent()

    @Test
    fun successCarriesAllGrantsTestModeAndActiveGrant() {
        val r = EntitlementClient.classify(HttpResponse(200, okBody))
        assertTrue(r is EntitlementsResult.Success)
        val e = (r as EntitlementsResult.Success).entitlements
        assertEquals("g1", e.guid)
        assertEquals(listOf("old", "sub"), e.grants.map { it.level })
        assertTrue(e.testMode)
        assertEquals("sub", e.activeGrant?.level)
    }

    /** Четыре случая — четыре разных результата. */
    @Test
    fun networkHttpGarbageAndEmptyAreDistinct() {
        assertEquals(EntitlementsResult.Network, EntitlementClient.classify(HttpResponse(0, null)))
        assertEquals(EntitlementsResult.Http(500), EntitlementClient.classify(HttpResponse(500, null)))
        assertEquals(EntitlementsResult.BadResponse, EntitlementClient.classify(HttpResponse(200, "<html>")))
        val empty = EntitlementClient.classify(HttpResponse(200, """{"guid":"g1","grants":[]}"""))
        assertTrue(empty is EntitlementsResult.Success)
        val e = (empty as EntitlementsResult.Success).entitlements
        assertTrue(e.grants.isEmpty())
        assertFalse(e.testMode)
        assertNull(e.activeGrant)
    }

    @Test
    fun noActiveGrantMeansNullActiveGrant() {
        val r = EntitlementClient.classify(
            HttpResponse(200, """{"guid":"g","grants":[{"level":"x","status":"revoked"}]}"""),
        ) as EntitlementsResult.Success
        assertEquals(1, r.entitlements.grants.size)
        assertNull(r.entitlements.activeGrant)
    }

    @Test
    fun withoutConfigureIsNotConfiguredDeliveredOnce() {
        val real = MainThread.delivery
        var posts = 0
        MainThread.delivery = { block -> posts++; block() }
        try {
            val seen = mutableListOf<EntitlementsResult>()
            Web2AppSdk.entitlements { seen += it }
            assertEquals(listOf<EntitlementsResult>(EntitlementsResult.NotConfigured), seen)
            assertEquals(1, posts)
        } finally {
            MainThread.delivery = real
        }
    }
}
