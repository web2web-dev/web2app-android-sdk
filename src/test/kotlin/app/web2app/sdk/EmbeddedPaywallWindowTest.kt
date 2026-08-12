package app.web2app.sdk

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Паритет iOS 0.4.1 по встроенному пейволлу:
 *  А-1 — окно поллинга после закрытия ОДИНАКОВОЕ для всех исходов (`let attempts = 10`
 *        в Web2AppSDK.swift): закрывший пейволл юзер с медленным вебхуком Stripe
 *        больше не получает мгновенный notPaid.
 *  А-2 — `PaywallResult.Unavailable` («пейволл не показали») вместо вранья `NotPaid`.
 * Чистая JVM — без Robolectric и эмулятора.
 */
class EmbeddedPaywallWindowTest {

    // ── А-1: окно поллинга ──────────────────────────────────────────────────

    @Test
    fun pollWindowIsTenAttemptsAfterPaymentSuccess() {
        assertEquals(10, WebPaywallLauncher.embeddedPollAttempts(BridgeEvent.PAYMENT_SUCCESS))
    }

    @Test
    fun pollWindowIsTenAttemptsAfterBridgeClose() {
        assertEquals(10, WebPaywallLauncher.embeddedPollAttempts(BridgeEvent.CLOSE))
    }

    @Test
    fun pollWindowIsTenAttemptsAfterNativeClose() {
        // event == null — нативный крестик или системный «назад».
        assertEquals(10, WebPaywallLauncher.embeddedPollAttempts(null))
    }

    // ── А-2: Unavailable ────────────────────────────────────────────────────

    @Test
    fun embeddedWithoutConfigureReturnsUnavailable() {
        var result: PaywallResult? = null
        Web2AppSdk.openWebPaywallEmbedded(
            context = unusedContext,
            paywallUrl = "https://client.example.com/paywall/pw1",
        ) { result = it }
        assertEquals(PaywallResult.Unavailable, result)
    }

    @Test
    fun embeddedByIdReturnsUnavailableWhenUrlNotResolved() {
        var result: PaywallResult? = null
        Web2AppSdk.openWebPaywallEmbeddedById(
            context = unusedContext,
            paywallId = "pw-unknown",
        ) { result = it }
        assertEquals(PaywallResult.Unavailable, result)
    }

    /**
     * Context в проверяемых ветках не используется: гард по config/URL срабатывает
     * до любого обращения к Android-рантайму. Пустая обёртка из заглушек android.jar
     * (unitTests.isReturnDefaultValues) — только чтобы пройти проверку non-null.
     */
    private val unusedContext = ContextWrapper(null)
}
