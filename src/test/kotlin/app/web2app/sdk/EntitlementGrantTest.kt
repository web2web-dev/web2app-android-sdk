package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Скелет-тест (WEB-434): POC-независимая чистая логика isActive.
 * WEB-1166: разбор testMode у гранта — через настоящий org.json
 * (testImplementation в build.gradle.kts), НЕ через заглушку android.jar
 * (ловушка L-4, см. AttributionParseTest).
 */
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

    /** Конструктор без testMode (старый код/вызовы) → false по умолчанию. */
    @Test
    fun testModeDefaultsFalseInConstructor() {
        val g = EntitlementGrant(level = "l", status = "active", expiresAt = null, priceId = null)
        assertFalse(g.testMode)
    }

    // MARK — WEB-1166: разбор ответа /public/entitlement.

    @Test
    fun parsesTestModeTrueOnSyntheticGrant() {
        val body = """
            {"guid":"g1","testMode":true,"grants":[
              {"level":"test","status":"active","expires_at":null,"price_id":"test","testMode":true}
            ]}
        """.trimIndent()
        val g = EntitlementClient.parseFirstGrant(body)
        assertNotNull(g)
        assertTrue(g!!.testMode)
        assertTrue(g.isActive) // isActive не меняли: активен, но помечен тестовым
        assertEquals("test", g.level)
    }

    @Test
    fun parsesTestModeFalseExplicit() {
        val body = """
            {"guid":"g1","testMode":false,"grants":[
              {"level":"price_abc","status":"active","expires_at":null,"price_id":"price_abc","testMode":false}
            ]}
        """.trimIndent()
        val g = EntitlementClient.parseFirstGrant(body)
        assertNotNull(g)
        assertFalse(g!!.testMode)
        assertTrue(g.isActive)
    }

    /** Старый ответ без поля testMode — разбор не ломается, testMode = false. */
    @Test
    fun missingTestModeFieldDefaultsFalse() {
        val body = """
            {"guid":"g1","grants":[
              {"level":"price_abc","status":"active","expires_at":null,"price_id":"price_abc"}
            ]}
        """.trimIndent()
        val g = EntitlementClient.parseFirstGrant(body)
        assertNotNull(g)
        assertFalse(g!!.testMode)
        assertEquals("price_abc", g.priceId)
    }
}
