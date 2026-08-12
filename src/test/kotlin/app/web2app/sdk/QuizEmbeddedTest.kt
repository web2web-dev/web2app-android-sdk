package app.web2app.sdk

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Атом Б-2 — открытие КВИЗА встроенным WebView ([Web2AppSdk.openQuizEmbedded]).
 *
 * Семантика результата у квиза ДРУГАЯ, чем у пейволла: оплаты тут нет, наблюдаемое —
 * поток событий (он уже уходит в [Web2AppSdk.setFunnelEventListener], атом Б-1) плюс
 * факт закрытия экрана. Поэтому колбэк отдаёт [QuizResult], а не [PaywallResult],
 * и никакого поллинга гранта в квиз-режиме нет.
 *
 * Проверяем четыре вещи:
 *  1. сборку URL квиза — тем же app-origin билдером, что у пейволла (origin/guid/email/
 *     profile-id + сохранение исходного query);
 *  2. `quiz_complete` НЕ закрывает WebView — после квиза страница часто сама ведёт на
 *     пейволл внутри того же окна; закрытие остаётся за двумя терминальными событиями;
 *  3. отображение исхода закрытия в [QuizResult] (страница / юзер / оплата в том же окне);
 *  4. доставку колбэка закрытия РОВНО один раз и через единую точку [MainThread].
 *
 * Чистая JVM — без Robolectric и эмулятора: сам показ (Activity + WebView) требует
 * Android-рантайма, поэтому здесь воспроизводится его проводка (реестр колбэков +
 * [BridgeMessageRouter]), а не рисуется окно.
 */
class QuizEmbeddedTest {

    private val realDelivery = MainThread.delivery

    /** Сколько раз результат прошёл через точку доставки на главный поток. */
    private var posts = 0

    @Before
    fun installCountingDelivery() {
        posts = 0
        MainThread.delivery = { block ->
            posts++
            block()
        }
    }

    @After
    fun restoreDeliveryAndListener() {
        MainThread.delivery = realDelivery
        Web2AppSdk.setFunnelEventListener(null)
    }

    // ── 1. Сборка URL квиза ─────────────────────────────────────────────────

    @Test
    fun quizUrlCarriesOriginGuidEmailAndProfileIds() {
        val url = QuizPresentation.quizUrl(
            quizUrl = "https://client.example.com/q/quiz-1",
            email = "user@app.example",
            guid = "g-123",
            adaptyProfileId = "adapty-1",
            revenuecatProfileId = "rc-1",
        )
        assertTrue(url.startsWith("https://client.example.com/q/quiz-1?"))
        assertTrue(url.contains("origin=app"))
        // guid — тот же ключ, что у пейволла: веб связывает прохождение с тем же юзером.
        assertTrue(url.contains("guid=g-123"))
        assertTrue(url.contains("email=user%40app.example"))
        assertTrue(url.contains("adapty_profile_id=adapty-1"))
        assertTrue(url.contains("revenuecat_profile_id=rc-1"))
    }

    @Test
    fun quizUrlPreservesExistingQuery() {
        val url = QuizPresentation.quizUrl(
            quizUrl = "https://client.example.com/q/quiz-1?utm_source=push&step=2",
            email = null,
            guid = "g-9",
            adaptyProfileId = null,
            revenuecatProfileId = null,
        )
        assertTrue(url.contains("utm_source=push"))
        assertTrue(url.contains("step=2"))
        assertTrue(url.contains("quiz-1?utm_source=push&step=2&"))
        assertTrue(url.contains("origin=app"))
        assertTrue(url.contains("guid=g-9"))
        assertFalse(url.contains("email="))
        assertFalse(url.contains("adapty_profile_id"))
        assertFalse(url.contains("revenuecat_profile_id"))
    }

    // ── 2. quiz_complete не закрывает окно ──────────────────────────────────

    @Test
    fun quizCompleteKeepsWindowOpenAndDeliversNoResult() {
        // После квиза страница обычно сама ведёт на пейволл в том же WebView —
        // закрыть окно на quiz_complete значит оборвать воронку перед оплатой.
        val results = mutableListOf<QuizResult>()
        val callbackId = "cb-quiz-complete"
        QuizPresentation.registerCloseCallback(callbackId, MainThread.wrap { results += it })

        var closes = 0
        BridgeMessageRouter.route("""{"source":"web2app","event":"quiz_complete"}""") { event ->
            closes++
            EmbeddedPaywallCallbacks.deliver(callbackId, event)
        }

        assertEquals(0, closes)
        assertEquals(emptyList<QuizResult>(), results)

        // Колбэк не потрачен: показ жив и доиграет своё закрытие.
        EmbeddedPaywallCallbacks.deliver(callbackId, BridgeEvent.CLOSE)
        assertEquals(listOf<QuizResult>(QuizResult.Closed(QuizCloseReason.PAGE)), results)
    }

    @Test
    fun allQuizEventsKeepWindowOpen() {
        val results = mutableListOf<QuizResult>()
        val callbackId = "cb-quiz-stream"
        QuizPresentation.registerCloseCallback(callbackId, MainThread.wrap { results += it })

        val seen = mutableListOf<String>()
        Web2AppSdk.setFunnelEventListener { name, _ -> seen += name }

        listOf("quiz_start", "quiz_screen_view", "quiz_answer", "quiz_email_submit", "quiz_complete")
            .forEach { name ->
                BridgeMessageRouter.route("""{"source":"web2app","event":"$name"}""") { event ->
                    EmbeddedPaywallCallbacks.deliver(callbackId, event)
                }
            }

        // События доехали до слушателя интегратора, но экран не закрылся.
        assertEquals(
            listOf("quiz_start", "quiz_screen_view", "quiz_answer", "quiz_email_submit", "quiz_complete"),
            seen,
        )
        assertEquals(emptyList<QuizResult>(), results)

        EmbeddedPaywallCallbacks.deliver(callbackId, null)
    }

    // ── 3. Исход закрытия → QuizResult ──────────────────────────────────────

    @Test
    fun pageCloseButtonYieldsClosedByPage() {
        assertEquals(QuizResult.Closed(QuizCloseReason.PAGE), QuizPresentation.result(BridgeEvent.CLOSE))
    }

    @Test
    fun nativeCloseYieldsClosedByUser() {
        // event == null — нативный крестик или системный «назад».
        assertEquals(QuizResult.Closed(QuizCloseReason.USER), QuizPresentation.result(null))
    }

    @Test
    fun paymentInsideSameWebViewYieldsClosedAfterPayment() {
        // Квиз → пейволл в том же окне: оплата закрывает показ. Право SDK тут НЕ
        // поллит — за грантом интегратор идёт в Web2AppSdk.entitlement.
        assertEquals(
            QuizResult.Closed(QuizCloseReason.PAID),
            QuizPresentation.result(BridgeEvent.PAYMENT_SUCCESS),
        )
    }

    // ── 4. Доставка колбэка закрытия ────────────────────────────────────────

    @Test
    fun closeCallbackIsDeliveredExactlyOnceThroughMainThread() {
        val results = mutableListOf<QuizResult>()
        val callbackId = "cb-quiz-once"
        QuizPresentation.registerCloseCallback(callbackId, MainThread.wrap { results += it })

        BridgeMessageRouter.route("""{"source":"web2app","event":"close"}""") { event ->
            EmbeddedPaywallCallbacks.deliver(callbackId, event)
        }
        // Повторная доставка (onDestroy после finish) не должна задвоить колбэк.
        EmbeddedPaywallCallbacks.deliver(callbackId, null)

        assertEquals(listOf<QuizResult>(QuizResult.Closed(QuizCloseReason.PAGE)), results)
        assertEquals(1, posts)
    }

    @Test
    fun openQuizEmbeddedWithoutConfigureIsUnavailableDeliveredOnce() {
        // Без configure guid некуда персистить: веб связал бы прохождение с ключом,
        // который прилка тут же потеряет. Экран не показываем — это НЕ «закрыли».
        var calls = 0
        var result: QuizResult? = null
        Web2AppSdk.openQuizEmbedded(
            context = unusedContext,
            quizUrl = "https://client.example.com/q/quiz-1",
        ) { calls++; result = it }
        assertEquals(1, calls)
        assertEquals(1, posts)
        assertEquals(QuizResult.Unavailable, result)
    }

    // ── 5. Пейвольный путь не поехал ────────────────────────────────────────

    @Test
    fun quizAndPaywallPresentationsDoNotShareCallbacks() {
        // Реестр показа один на оба режима — результаты не должны перепутаться.
        val quizResults = mutableListOf<QuizResult>()
        val paywallEvents = mutableListOf<BridgeEvent?>()
        QuizPresentation.registerCloseCallback("cb-quiz-mixed") { quizResults += it }
        EmbeddedPaywallCallbacks.register("cb-paywall-mixed") { paywallEvents += it }

        EmbeddedPaywallCallbacks.deliver("cb-quiz-mixed", BridgeEvent.CLOSE)
        EmbeddedPaywallCallbacks.deliver("cb-paywall-mixed", BridgeEvent.PAYMENT_SUCCESS)

        assertEquals(listOf<QuizResult>(QuizResult.Closed(QuizCloseReason.PAGE)), quizResults)
        assertEquals(listOf<BridgeEvent?>(BridgeEvent.PAYMENT_SUCCESS), paywallEvents)
    }

    @Test
    fun terminalContractIsUnchangedByQuizMode() {
        // Шрам Б-1: терминальных событий по-прежнему РОВНО два, и квиз их не трогает.
        val complete = BridgeEventParser.parseMessage("""{"event":"quiz_complete"}""")!!
        val close = BridgeEventParser.parseMessage("""{"event":"close"}""")!!
        val success = BridgeEventParser.parseMessage("""{"event":"paywall_result","status":"success"}""")!!
        assertEquals(null, BridgeEventParser.terminalEvent(complete))
        assertEquals(BridgeEvent.CLOSE, BridgeEventParser.terminalEvent(close))
        assertEquals(BridgeEvent.PAYMENT_SUCCESS, BridgeEventParser.terminalEvent(success))
        // Окно поллинга встроенного ПЕЙВОЛЛА не изменилось.
        assertEquals(10, WebPaywallLauncher.embeddedPollAttempts(null))
    }

    /**
     * Context в проверяемой ветке не используется: гард по config срабатывает до
     * любого обращения к Android-рантайму (та же схема, что в EmbeddedPaywallWindowTest).
     */
    private val unusedContext = ContextWrapper(null)
}
