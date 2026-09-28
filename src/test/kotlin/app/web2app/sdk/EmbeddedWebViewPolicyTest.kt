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

    // Таблица: (didCrash, isResumed, падений подряд до, падений за показ до) →
    // (действие, падений подряд после). Счётчик за показ проверяется отдельными случаями ниже.
    private fun decide(didCrash: Boolean, isResumed: Boolean, crashesInARow: Int, totalCrashes: Int = 0) =
        EmbeddedWebViewPolicy.renderProcessGoneDecision(didCrash, isResumed, crashesInARow, totalCrashes)

    private fun RenderProcessGoneDecision.actionAndInARow() = action to crashesInARow

    @Test
    fun firstCrashOnScreenReloadsNowAndCounts() {
        assertEquals(
            RenderProcessGoneAction.RELOAD_NOW to 1,
            decide(didCrash = true, isResumed = true, crashesInARow = 0).actionAndInARow(),
        )
    }

    @Test
    fun firstCrashInBackgroundReloadsOnResumeAndCounts() {
        assertEquals(
            RenderProcessGoneAction.RELOAD_ON_RESUME to 1,
            decide(didCrash = true, isResumed = false, crashesInARow = 0).actionAndInARow(),
        )
    }

    @Test
    fun secondCrashInARowGivesUp() {
        val d = decide(didCrash = true, isResumed = true, crashesInARow = 1, totalCrashes = 1)
        assertEquals(RenderProcessGoneAction.GIVE_UP to 2, d.actionAndInARow())
        assertEquals(GiveUpReason.CRASHES_IN_A_ROW, d.giveUpReason)
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
        val d = decide(didCrash = false, isResumed = false, crashesInARow = 1, totalCrashes = 3)
        assertEquals(RenderProcessGoneAction.RELOAD_ON_RESUME to 1, d.actionAndInARow())
        assertEquals(3, d.totalCrashes)
    }

    @Test
    fun systemReclaimOnScreenIsNotCountedAndReloadsNow() {
        assertEquals(
            RenderProcessGoneAction.RELOAD_NOW to 0,
            decide(didCrash = false, isResumed = true, crashesInARow = 0).actionAndInARow(),
        )
    }

    @Test
    fun repeatedSystemReclaimsNeverGiveUp() {
        var inARow = 0
        var total = 0
        repeat(20) {
            val d = decide(didCrash = false, isResumed = false, crashesInARow = inARow, totalCrashes = total)
            assertEquals(RenderProcessGoneAction.RELOAD_ON_RESUME, d.action)
            inARow = d.crashesInARow
            total = d.totalCrashes
        }
        assertEquals(0, inARow)
        assertEquals(0, total)
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

    // ── Общий потолок падений за один показ ─────────────────────────────────

    @Test
    fun crashCeilingPerShowIsFive() {
        assertEquals(5, EmbeddedWebViewPolicy.MAX_RENDER_CRASHES_PER_SHOW)
    }

    @Test
    fun everyRealCrashCountsTowardsTotal() {
        assertEquals(1, decide(didCrash = true, isResumed = true, crashesInARow = 0, totalCrashes = 0).totalCrashes)
        assertEquals(4, decide(didCrash = true, isResumed = false, crashesInARow = 0, totalCrashes = 3).totalCrashes)
    }

    @Test
    fun fifthCrashPerShowStillReloads() {
        // Пятое падение за показ (не подряд) — ещё в пределах потолка.
        val d = decide(didCrash = true, isResumed = true, crashesInARow = 0, totalCrashes = 4)
        assertEquals(RenderProcessGoneAction.RELOAD_NOW, d.action)
        assertEquals(5, d.totalCrashes)
        assertEquals(null, d.giveUpReason)
    }

    @Test
    fun sixthCrashPerShowGivesUpEvenIfNotInARow() {
        val d = decide(didCrash = true, isResumed = true, crashesInARow = 0, totalCrashes = 5)
        assertEquals(RenderProcessGoneAction.GIVE_UP, d.action)
        assertEquals(6, d.totalCrashes)
        assertEquals(GiveUpReason.CRASHES_PER_SHOW, d.giveUpReason)
    }

    @Test
    fun sixthCrashPerShowGivesUpInBackgroundToo() {
        assertEquals(
            RenderProcessGoneAction.GIVE_UP,
            decide(didCrash = true, isResumed = false, crashesInARow = 0, totalCrashes = 5).action,
        )
    }

    @Test
    fun systemReclaimAtCeilingDoesNotGiveUp() {
        // Выгрузка системой не считается и в общий потолок.
        val d = decide(didCrash = false, isResumed = true, crashesInARow = 0, totalCrashes = 5)
        assertEquals(RenderProcessGoneAction.RELOAD_NOW, d.action)
        assertEquals(5, d.totalCrashes)
    }

    @Test
    fun loadCrashLoadCrashLoopStopsAtSixthCrash() {
        // Бесконечный круг «загрузилась → упала»: «подряд» каждый раз сбрасывается,
        // но общий потолок закрывает показ на шестом падении.
        var inARow = 0
        var total = 0
        val actions = mutableListOf<RenderProcessGoneAction>()
        repeat(6) {
            val d = decide(didCrash = true, isResumed = true, crashesInARow = inARow, totalCrashes = total)
            actions += d.action
            total = d.totalCrashes
            // Страница снова догрузилась без ошибки главного кадра — «подряд» сброшен.
            inARow = if (EmbeddedWebViewPolicy.shouldResetCrashesInARow(mainFrameLoadFailed = false)) 0 else d.crashesInARow
        }
        assertEquals(List(5) { RenderProcessGoneAction.RELOAD_NOW } + RenderProcessGoneAction.GIVE_UP, actions)
    }

    @Test
    fun twoInARowReachedTogetherWithCeilingReportsCeiling() {
        // Шестое падение и одновременно второе подряд — закрытие, причина — общий потолок.
        val d = decide(didCrash = true, isResumed = true, crashesInARow = 1, totalCrashes = 5)
        assertEquals(RenderProcessGoneAction.GIVE_UP, d.action)
        assertEquals(GiveUpReason.CRASHES_PER_SHOW, d.giveUpReason)
    }

    // ── Сброс «подряд» только после настоящей загрузки ──────────────────────

    @Test
    fun pageFinishedAfterSuccessfulLoadResetsInARow() {
        assertTrue(EmbeddedWebViewPolicy.shouldResetCrashesInARow(mainFrameLoadFailed = false))
    }

    @Test
    fun pageFinishedAfterMainFrameErrorDoesNotResetInARow() {
        // onPageFinished приходит и после ошибки главного кадра — страница не загрузилась.
        assertFalse(EmbeddedWebViewPolicy.shouldResetCrashesInARow(mainFrameLoadFailed = true))
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
