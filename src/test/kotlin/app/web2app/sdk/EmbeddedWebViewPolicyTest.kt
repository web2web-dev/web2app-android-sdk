package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.7.2 — решения встроенного показа, вынесенные из Activity в чистые функции.
 * Чистая JVM — без Robolectric и эмулятора.
 */
class EmbeddedWebViewPolicyTest {

    // ── Поворот экрана не считается закрытием ───────────────────────────────

    @Test
    fun configChangeRecreationIsNotClose() {
        assertFalse(
            EmbeddedWebViewPolicy.shouldDeliverCloseOnDestroy(
                isChangingConfigurations = true,
                isFinishing = false,
            ),
        )
    }

    @Test
    fun finishingIsClose() {
        assertTrue(
            EmbeddedWebViewPolicy.shouldDeliverCloseOnDestroy(
                isChangingConfigurations = false,
                isFinishing = true,
            ),
        )
    }

    @Test
    fun systemDestroyWithoutFinishIsClose() {
        // Система убила Activity (не пересоздание) — показ окончен, колбэк обязан уйти.
        assertTrue(
            EmbeddedWebViewPolicy.shouldDeliverCloseOnDestroy(
                isChangingConfigurations = false,
                isFinishing = false,
            ),
        )
    }

    @Test
    fun finishingDuringConfigChangeIsClose() {
        // Новой Activity не будет — без колбэка вызывающий ждал бы вечно.
        assertTrue(
            EmbeddedWebViewPolicy.shouldDeliverCloseOnDestroy(
                isChangingConfigurations = true,
                isFinishing = true,
            ),
        )
    }

    // ── Гибель процесса страницы ────────────────────────────────────────────

    @Test
    fun firstRendererDeathRecreatesWebView() {
        assertEquals(RenderProcessGoneAction.RECREATE, EmbeddedWebViewPolicy.renderProcessGoneAction(0))
    }

    @Test
    fun secondRendererDeathGivesUp() {
        assertEquals(RenderProcessGoneAction.GIVE_UP, EmbeddedWebViewPolicy.renderProcessGoneAction(1))
    }

    @Test
    fun furtherRendererDeathsKeepGivingUp() {
        // Не бесконечно: сколько бы ни было гибелей после первой — только закрытие.
        assertEquals(RenderProcessGoneAction.GIVE_UP, EmbeddedWebViewPolicy.renderProcessGoneAction(5))
    }

    @Test
    fun rendererPriorityNamesMatchWebViewConstants() {
        // WebView.RENDERER_PRIORITY_WAIVED = 0, BOUND = 1, IMPORTANT = 2 (API 26).
        assertEquals("waived", EmbeddedWebViewPolicy.rendererPriorityName(0))
        assertEquals("bound", EmbeddedWebViewPolicy.rendererPriorityName(1))
        assertEquals("important", EmbeddedWebViewPolicy.rendererPriorityName(2))
        assertEquals("unknown(7)", EmbeddedWebViewPolicy.rendererPriorityName(7))
    }

    @Test
    fun brokenShowWithoutGrantIsUnavailable() {
        assertEquals(PaywallResult.Unavailable, EmbeddedWebViewPolicy.paywallResultAfterBrokenShow(null))
    }

    @Test
    fun brokenShowWithActiveGrantIsPaid() {
        // Оплата прошла до сбоя страницы — «недоступно» было бы враньём.
        val grant = EntitlementGrant("pro", "active", null, null)
        assertEquals(PaywallResult.Paid(grant), EmbeddedWebViewPolicy.paywallResultAfterBrokenShow(grant))
    }

    // ── Реестр колбэков: «недоступно» ───────────────────────────────────────

    @Test
    fun unavailableGoesToItsOwnHandlerExactlyOnce() {
        val events = mutableListOf<BridgeEvent?>()
        var unavailable = 0
        EmbeddedPaywallCallbacks.register("cb-broken", onUnavailable = { unavailable++ }) { events += it }

        EmbeddedPaywallCallbacks.deliverUnavailable("cb-broken")
        // onDestroy после закрытия отдаёт «закрыли» — второй доставки быть не должно.
        EmbeddedPaywallCallbacks.deliver("cb-broken", null)

        assertEquals(1, unavailable)
        assertEquals(emptyList<BridgeEvent?>(), events)
    }

    @Test
    fun unavailableWithoutOwnHandlerFallsBackToClose() {
        val events = mutableListOf<BridgeEvent?>()
        EmbeddedPaywallCallbacks.register("cb-plain") { events += it }

        EmbeddedPaywallCallbacks.deliverUnavailable("cb-plain")

        assertEquals(listOf<BridgeEvent?>(null), events)
    }

    @Test
    fun quizBrokenShowIsUnavailable() {
        val results = mutableListOf<QuizResult>()
        QuizPresentation.registerCloseCallback("cb-quiz-broken") { results += it }

        EmbeddedPaywallCallbacks.deliverUnavailable("cb-quiz-broken")

        assertEquals(listOf<QuizResult>(QuizResult.Unavailable), results)
    }
}
