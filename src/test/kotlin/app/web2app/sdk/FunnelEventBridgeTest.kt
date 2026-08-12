package app.web2app.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Атом Б-1 — события прохождения квиза из WebView-моста.
 *
 * Веб (frontend/src/utils/web2appBridge.ts) шлёт мосту JSON-строку вида
 * `{"source":"web2app","event":"<имя>", ...поля}`. До Б-1 SDK понимал ровно два
 * события и на ЛЮБОМ распознанном закрывал показ; события квиза так закрыли бы
 * WebView на первом же экране.
 *
 * Проверяем три вещи:
 *  1. разбор произвольного события с безопасными полями (PII в мост не уходит:
 *     только screenId/screenIndex/screenTotal + metadata{blockId, blockType});
 *  2. ТЕРМИНАЛЬНОСТЬ — закрывают показ РОВНО два события (`paywall_result:success`
 *     и `close`), выражена чистой функцией [BridgeEventParser.terminalEvent] и
 *     проверяется без Android-рантайма (в Activity её не спрятать — иначе юнит
 *     не увидел бы регрессию);
 *  3. доставку события интегратору через единую точку [MainThread].
 *
 * Чистая JVM — без Robolectric и эмулятора (org.json в JVM-юнитах заглушка,
 * поэтому парс regex-ом, как весь разбор в этой репе).
 */
class FunnelEventBridgeTest {

    private val realDelivery = MainThread.delivery

    /** Сколько раз событие прошло через точку доставки на главный поток. */
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

    // ── 1. Разбор пяти событий квиза ────────────────────────────────────────

    @Test
    fun quizStartParsesAsEventWithoutFields() {
        val message = BridgeEventParser.parseMessage("""{"source":"web2app","event":"quiz_start"}""")
        assertEquals("quiz_start", message?.name)
        assertEquals(FunnelEventData(), message?.data)
    }

    @Test
    fun quizScreenViewParsesScreenFields() {
        val message = BridgeEventParser.parseMessage(
            """{"source":"web2app","event":"quiz_screen_view","screenId":"s1","screenIndex":0,"screenTotal":7}""",
        )
        assertEquals("quiz_screen_view", message?.name)
        assertEquals(FunnelEventData(screenId = "s1", screenIndex = 0, screenTotal = 7), message?.data)
    }

    @Test
    fun quizAnswerParsesNestedMetadata() {
        val message = BridgeEventParser.parseMessage(
            """{"source":"web2app","event":"quiz_answer","screenId":"s2","screenIndex":1,""" +
                """"screenTotal":7,"metadata":{"blockId":"b3","blockType":"single_choice"}}""",
        )
        assertEquals("quiz_answer", message?.name)
        assertEquals(
            FunnelEventData(
                screenId = "s2",
                screenIndex = 1,
                screenTotal = 7,
                blockId = "b3",
                blockType = "single_choice",
            ),
            message?.data,
        )
    }

    @Test
    fun quizEmailSubmitParsesAsEventWithoutFields() {
        val message = BridgeEventParser.parseMessage("""{"source":"web2app","event":"quiz_email_submit"}""")
        assertEquals("quiz_email_submit", message?.name)
        assertEquals(FunnelEventData(), message?.data)
    }

    @Test
    fun quizCompleteParsesAsEventWithoutFields() {
        val message = BridgeEventParser.parseMessage("""{"source":"web2app","event":"quiz_complete"}""")
        assertEquals("quiz_complete", message?.name)
        assertEquals(FunnelEventData(), message?.data)
    }

    // ── Частичные / кривые поля: пропуск поля, а не исключение ──────────────

    @Test
    fun missingFieldsAreSkippedNotFailed() {
        val message = BridgeEventParser.parseMessage(
            """{"source":"web2app","event":"quiz_screen_view","screenId":"s1"}""",
        )
        assertEquals("quiz_screen_view", message?.name)
        assertEquals(FunnelEventData(screenId = "s1"), message?.data)
    }

    @Test
    fun wrongTypedFieldsAreSkippedNotFailed() {
        // Строка вместо числа, число вместо строки, metadata не объект — поле пропускаем.
        val message = BridgeEventParser.parseMessage(
            """{"event":"quiz_answer","screenId":7,"screenIndex":"1","screenTotal":null,"metadata":"nope"}""",
        )
        assertEquals("quiz_answer", message?.name)
        assertEquals(FunnelEventData(), message?.data)
    }

    @Test
    fun emptyMetadataObjectYieldsNoBlockFields() {
        val message = BridgeEventParser.parseMessage("""{"event":"quiz_answer","metadata":{}}""")
        assertEquals("quiz_answer", message?.name)
        assertNull(message?.data?.blockId)
        assertNull(message?.data?.blockType)
    }

    @Test
    fun negativeAndMultiDigitNumbersParse() {
        val message = BridgeEventParser.parseMessage(
            """{"event":"quiz_screen_view","screenIndex":-1,"screenTotal":12}""",
        )
        assertEquals(-1, message?.data?.screenIndex)
        assertEquals(12, message?.data?.screenTotal)
    }

    // ── 2. Терминальность: закрывают показ РОВНО два события ────────────────

    @Test
    fun quizEventsAreNotTerminal() {
        val quizEvents = listOf(
            """{"source":"web2app","event":"quiz_start"}""",
            """{"source":"web2app","event":"quiz_screen_view","screenId":"s1","screenIndex":0,"screenTotal":7}""",
            """{"source":"web2app","event":"quiz_answer","metadata":{"blockId":"b3"}}""",
            """{"source":"web2app","event":"quiz_email_submit"}""",
            """{"source":"web2app","event":"quiz_complete"}""",
        )
        quizEvents.forEach { json ->
            val message = BridgeEventParser.parseMessage(json)!!
            assertNull("событие квиза не должно закрывать WebView: $json", BridgeEventParser.terminalEvent(message))
        }
    }

    @Test
    fun paywallSuccessAndCloseAreTheOnlyTerminalEvents() {
        val success = BridgeEventParser.parseMessage(
            """{"source":"web2app","event":"paywall_result","status":"success"}""",
        )!!
        val close = BridgeEventParser.parseMessage("""{"source":"web2app","event":"close"}""")!!
        assertEquals(BridgeEvent.PAYMENT_SUCCESS, BridgeEventParser.terminalEvent(success))
        assertEquals(BridgeEvent.CLOSE, BridgeEventParser.terminalEvent(close))
    }

    @Test
    fun paywallResultDismissedParsesButIsNotTerminal() {
        // Кнопка закрытия шлёт dismissed, а следом close — закроет именно close.
        val message = BridgeEventParser.parseMessage(
            """{"source":"web2app","event":"paywall_result","status":"dismissed"}""",
        )
        assertEquals("paywall_result", message?.name)
        assertEquals("dismissed", message?.status)
        assertNull(BridgeEventParser.terminalEvent(message!!))
    }

    @Test
    fun unknownEventParsesButIsNotTerminal() {
        val message = BridgeEventParser.parseMessage("""{"source":"web2app","event":"whatever_new"}""")
        assertEquals("whatever_new", message?.name)
        assertNull(BridgeEventParser.terminalEvent(message!!))
    }

    @Test
    fun garbageInputYieldsNullWithoutThrowing() {
        assertNull(BridgeEventParser.parseMessage(null))
        assertNull(BridgeEventParser.parseMessage(""))
        assertNull(BridgeEventParser.parseMessage("   "))
        assertNull(BridgeEventParser.parseMessage("not json"))
        assertNull(BridgeEventParser.parseMessage("{}"))
        assertNull(BridgeEventParser.parseMessage("[]"))
        assertNull(BridgeEventParser.parseMessage("""{"event":}"""))
        // Незакрытая metadata — имя события читается, поля пропускаем.
        assertEquals(
            FunnelEventData(),
            BridgeEventParser.parseMessage("""{"event":"quiz_answer","metadata":{"blockId":"b3" """)?.data,
        )
    }

    /** Обратная совместимость: старая [BridgeEventParser.parse] по-прежнему видит только терминальные. */
    @Test
    fun legacyParseStillReturnsOnlyTerminalEvents() {
        assertEquals(
            BridgeEvent.PAYMENT_SUCCESS,
            BridgeEventParser.parse("""{"event":"paywall_result","status":"success"}"""),
        )
        assertEquals(BridgeEvent.CLOSE, BridgeEventParser.parse("""{"event":"close"}"""))
        assertNull(BridgeEventParser.parse("""{"event":"paywall_result","status":"dismissed"}"""))
        assertNull(BridgeEventParser.parse("""{"event":"quiz_complete"}"""))
    }

    // ── Маршрутизация моста: кто закрывает окно ─────────────────────────────

    @Test
    fun routerNeverClosesWindowOnQuizEvents() {
        var closes = 0
        val seen = mutableListOf<String>()
        Web2AppSdk.setFunnelEventListener { name, _ -> seen += name }

        listOf("quiz_start", "quiz_screen_view", "quiz_answer", "quiz_email_submit", "quiz_complete")
            .forEach { name ->
                BridgeMessageRouter.route("""{"source":"web2app","event":"$name"}""") { closes++ }
            }

        assertEquals(0, closes)
        assertEquals(
            listOf("quiz_start", "quiz_screen_view", "quiz_answer", "quiz_email_submit", "quiz_complete"),
            seen,
        )
    }

    @Test
    fun routerClosesWindowOnTerminalEventsOnly() {
        val closedWith = mutableListOf<BridgeEvent>()
        BridgeMessageRouter.route("""{"source":"web2app","event":"paywall_result","status":"success"}""") {
            closedWith += it
        }
        BridgeMessageRouter.route("""{"source":"web2app","event":"close"}""") { closedWith += it }
        assertEquals(listOf(BridgeEvent.PAYMENT_SUCCESS, BridgeEvent.CLOSE), closedWith)
    }

    @Test
    fun routerDeliversDismissedButKeepsWindowOpen() {
        var closes = 0
        val seen = mutableListOf<Pair<String, String?>>()
        Web2AppSdk.setFunnelEventListener { name, _ -> seen += name to null }
        BridgeMessageRouter.route("""{"source":"web2app","event":"paywall_result","status":"dismissed"}""") {
            closes++
        }
        assertEquals(0, closes)
        assertEquals(listOf("paywall_result" to null), seen)
    }

    @Test
    fun routerIgnoresGarbageSilently() {
        var closes = 0
        var notified = 0
        Web2AppSdk.setFunnelEventListener { _, _ -> notified++ }
        listOf(null, "", "not json", "{}").forEach { json ->
            BridgeMessageRouter.route(json) { closes++ }
        }
        assertEquals(0, closes)
        assertEquals(0, notified)
    }

    // ── 3. Доставка слушателю на главный поток ──────────────────────────────

    @Test
    fun listenerReceivesEventThroughMainThreadExactlyOnce() {
        var calls = 0
        var name: String? = null
        var data: FunnelEventData? = null
        Web2AppSdk.setFunnelEventListener { n, d -> calls++; name = n; data = d }

        BridgeMessageRouter.route(
            """{"source":"web2app","event":"quiz_answer","screenId":"s2","screenIndex":1,""" +
                """"screenTotal":7,"metadata":{"blockId":"b3","blockType":"single_choice"}}""",
        ) { }

        assertEquals(1, calls)
        assertEquals(1, posts)
        assertEquals("quiz_answer", name)
        assertEquals(
            FunnelEventData(
                screenId = "s2",
                screenIndex = 1,
                screenTotal = 7,
                blockId = "b3",
                blockType = "single_choice",
            ),
            data,
        )
    }

    @Test
    fun noListenerMeansNoDeliveryAtAll() {
        // Слушатель не подписан — событие не должно даже прыгать на главный поток.
        BridgeMessageRouter.route("""{"source":"web2app","event":"quiz_start"}""") { }
        assertEquals(0, posts)
    }

    @Test
    fun listenerCanBeCleared() {
        var calls = 0
        Web2AppSdk.setFunnelEventListener { _, _ -> calls++ }
        BridgeMessageRouter.route("""{"source":"web2app","event":"quiz_start"}""") { }
        Web2AppSdk.setFunnelEventListener(null)
        BridgeMessageRouter.route("""{"source":"web2app","event":"quiz_complete"}""") { }
        assertEquals(1, calls)
    }
}
