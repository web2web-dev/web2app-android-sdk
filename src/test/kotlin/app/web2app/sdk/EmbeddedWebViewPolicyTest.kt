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

    // Таблица: (didCrash, isResumed, падений подряд до этой гибели) → (действие, падений подряд после).
    private fun decide(didCrash: Boolean, isResumed: Boolean, crashesInARow: Int) =
        EmbeddedWebViewPolicy.renderProcessGoneDecision(didCrash, isResumed, crashesInARow)

    @Test
    fun firstCrashOnScreenReloadsNowAndCounts() {
        assertEquals(
            RenderProcessGoneDecision(RenderProcessGoneAction.RELOAD_NOW, crashesInARow = 1),
            decide(didCrash = true, isResumed = true, crashesInARow = 0),
        )
    }

    @Test
    fun firstCrashInBackgroundReloadsOnResumeAndCounts() {
        assertEquals(
            RenderProcessGoneDecision(RenderProcessGoneAction.RELOAD_ON_RESUME, crashesInARow = 1),
            decide(didCrash = true, isResumed = false, crashesInARow = 0),
        )
    }

    @Test
    fun secondCrashInARowGivesUp() {
        assertEquals(
            RenderProcessGoneDecision(RenderProcessGoneAction.GIVE_UP, crashesInARow = 2),
            decide(didCrash = true, isResumed = true, crashesInARow = 1),
        )
    }

    @Test
    fun secondCrashInARowGivesUpEvenInBackground() {
        assertEquals(
            RenderProcessGoneAction.GIVE_UP,
            decide(didCrash = true, isResumed = false, crashesInARow = 1).action,
        )
    }

    @Test
    fun furtherCrashesKeepGivingUp() {
        // Не бесконечно: сколько бы ни было падений подряд после первого — только закрытие.
        assertEquals(RenderProcessGoneAction.GIVE_UP, decide(didCrash = true, isResumed = true, crashesInARow = 5).action)
    }

    @Test
    fun systemReclaimInBackgroundIsNotCountedAndWaitsForResume() {
        // Человек ушёл платить в банк, система выгрузила страницу — это не срыв показа.
        assertEquals(
            RenderProcessGoneDecision(RenderProcessGoneAction.RELOAD_ON_RESUME, crashesInARow = 1),
            decide(didCrash = false, isResumed = false, crashesInARow = 1),
        )
    }

    @Test
    fun systemReclaimOnScreenIsNotCountedAndReloadsNow() {
        assertEquals(
            RenderProcessGoneDecision(RenderProcessGoneAction.RELOAD_NOW, crashesInARow = 0),
            decide(didCrash = false, isResumed = true, crashesInARow = 0),
        )
    }

    @Test
    fun repeatedSystemReclaimsNeverGiveUp() {
        var crashes = 0
        repeat(5) {
            val d = decide(didCrash = false, isResumed = false, crashesInARow = crashes)
            assertEquals(RenderProcessGoneAction.RELOAD_ON_RESUME, d.action)
            crashes = d.crashesInARow
        }
        assertEquals(0, crashes)
    }

    @Test
    fun reclaimBetweenTwoCrashesDoesNotResetButDoesNotCount() {
        // Падение → выгрузка системой → падение: выгрузка счётчик не трогает, второе падение — закрытие.
        val afterCrash = decide(didCrash = true, isResumed = true, crashesInARow = 0)
        val afterReclaim = decide(didCrash = false, isResumed = false, crashesInARow = afterCrash.crashesInARow)
        assertEquals(1, afterReclaim.crashesInARow)
        assertEquals(
            RenderProcessGoneAction.GIVE_UP,
            decide(didCrash = true, isResumed = true, crashesInARow = afterReclaim.crashesInARow).action,
        )
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

    // ── Индикатор загрузки ──────────────────────────────────────────────────

    @Test
    fun mainFrameLoadErrorHidesIndicator() {
        assertTrue(EmbeddedWebViewPolicy.reactsToLoadError(isForMainFrame = true))
    }

    @Test
    fun subresourceLoadErrorIsIgnored() {
        // Упала картинка/счётчик — страница жива, индикатор спрячет onPageFinished.
        assertFalse(EmbeddedWebViewPolicy.reactsToLoadError(isForMainFrame = false))
    }
}
