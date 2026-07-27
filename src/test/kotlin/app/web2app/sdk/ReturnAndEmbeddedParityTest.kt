package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-813/814 — паритет iOS 0.3.0/0.4.0: распознавание возвратного deep-link,
 * парсинг ответа paywall-url, парсер событий JS-моста. Чистая JVM.
 */
class ReturnAndEmbeddedParityTest {

    // ── WEB-813: isHandoffReturnUrl ─────────────────────────────────────────

    @Test
    fun recognizesHandoffReturnUrlAnyScheme() {
        assertTrue(WebPaywallLauncher.isHandoffReturnUrl("myapp://handoff?code=abc"))
        assertTrue(WebPaywallLauncher.isHandoffReturnUrl("com.client.app://HANDOFF"))
        assertTrue(WebPaywallLauncher.isHandoffReturnUrl("x://handoff/extra?y=1"))
    }

    @Test
    fun rejectsForeignDeepLinks() {
        assertFalse(WebPaywallLauncher.isHandoffReturnUrl("myapp://settings"))
        assertFalse(WebPaywallLauncher.isHandoffReturnUrl("https://example.com/handoff"))
        assertFalse(WebPaywallLauncher.isHandoffReturnUrl("myapp://handoffx?code=1"))
        assertFalse(WebPaywallLauncher.isHandoffReturnUrl("not-a-url"))
        assertFalse(WebPaywallLauncher.isHandoffReturnUrl(""))
    }

    // ── WEB-814: parsePaywallUrlResponse ────────────────────────────────────

    @Test
    fun parsesPaywallUrlFromEnvelope() {
        val body = """{"success":true,"data":{"url":"https://client.example.com/paywall/pw1"},"error":null}"""
        assertEquals(
            "https://client.example.com/paywall/pw1",
            WebPaywallLauncher.parsePaywallUrlResponse(body),
        )
    }

    @Test
    fun parsesEscapedSlashes() {
        val body = """{"data":{"url":"https:\/\/client.example.com\/paywall\/pw1"}}"""
        assertEquals(
            "https://client.example.com/paywall/pw1",
            WebPaywallLauncher.parsePaywallUrlResponse(body),
        )
    }

    @Test
    fun paywallUrlGarbageAndNonHttpRejected() {
        assertNull(WebPaywallLauncher.parsePaywallUrlResponse(null))
        assertNull(WebPaywallLauncher.parsePaywallUrlResponse("not json"))
        assertNull(WebPaywallLauncher.parsePaywallUrlResponse("""{"success":false,"data":null}"""))
        // javascript:-и прочий не-http мусор не открываем
        assertNull(
            WebPaywallLauncher.parsePaywallUrlResponse("""{"data":{"url":"javascript:alert(1)"}}"""),
        )
    }

    // ── WEB-814: BridgeEventParser ──────────────────────────────────────────

    @Test
    fun parsesPaymentSuccessEvent() {
        val json = """{"source":"web2app","event":"paywall_result","status":"success"}"""
        assertEquals(BridgeEvent.PAYMENT_SUCCESS, BridgeEventParser.parse(json))
    }

    @Test
    fun parsesCloseEvent() {
        assertEquals(
            BridgeEvent.CLOSE,
            BridgeEventParser.parse("""{"source":"web2app","event":"close"}"""),
        )
    }

    @Test
    fun rejectsNonSuccessStatusUnknownEventsAndGarbage() {
        assertNull(BridgeEventParser.parse("""{"event":"paywall_result","status":"failed"}"""))
        assertNull(BridgeEventParser.parse("""{"event":"paywall_result"}"""))
        assertNull(BridgeEventParser.parse("""{"event":"evil"}"""))
        assertNull(BridgeEventParser.parse("{}"))
        assertNull(BridgeEventParser.parse("not json"))
        assertNull(BridgeEventParser.parse(null))
    }
}
