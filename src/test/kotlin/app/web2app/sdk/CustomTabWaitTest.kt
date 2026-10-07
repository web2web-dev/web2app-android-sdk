package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-2480 п.3 — Android: окно ожидания оплаты в браузерной вкладке считается от
 * ВОЗВРАЩЕНИЯ приложения, а не от открытия вкладки (хвост WEB-813). До починки
 * опрос 30 × 2 с стартовал при открытии вкладки и через минуту отдавал «не оплачено»
 * тому, кто дольше минуты вводил карту и проходил 3-D Secure.
 *
 * Опрос подменён: тест сам решает, когда и чем заканчивается каждое окно.
 */
class CustomTabWaitTest {
    private val active = EntitlementGrant("sub", "active", null, "sub")

    private class Harness(lifecycleAvailable: Boolean = true) {
        /** Запущенные окна опроса: колбэк окна и его «продолжать ли». */
        val polls = mutableListOf<Pair<(EntitlementGrant?) -> Unit, () -> Boolean>>()
        val delivered = mutableListOf<EntitlementGrant?>()
        val wait = CustomTabWait(
            startPoll = { shouldContinue, done -> polls += done to shouldContinue },
            deliver = { delivered += it },
            lifecycleAvailable = lifecycleAvailable,
        )
    }

    /** Вкладка открыта, минута без записи, затем вернулись — окно перезапущено и отдало запись. */
    @Test
    fun lateReturnRestartsWindowAndDeliversActiveGrant() {
        val h = Harness()
        h.wait.start()
        assertEquals(1, h.polls.size)
        h.polls[0].first(null) // первое окно истекло, человек ещё во вкладке
        assertTrue("null не отдаётся, пока приложение не вернулось", h.delivered.isEmpty())
        h.wait.onAppResumed()
        assertEquals(2, h.polls.size)
        h.polls[1].first(active)
        assertEquals(listOf<EntitlementGrant?>(active), h.delivered)
    }

    /** Закрыли вкладку, не платя: после окна от возвращения — null, ровно один раз. */
    @Test
    fun returnWithoutPaymentDeliversNullOnceAfterWindow() {
        val h = Harness()
        h.wait.start()
        h.wait.onAppResumed()
        h.polls[0].first(null) // старое окно — игнор
        assertTrue(h.delivered.isEmpty())
        h.polls[1].first(null)
        assertEquals(listOf<EntitlementGrant?>(null), h.delivered)
        h.wait.onAppResumed() // повторное возвращение после ответа — ничего
        assertEquals(2, h.polls.size)
        assertEquals(1, h.delivered.size)
    }

    /** Быстрая оплата — первое окно находит запись, как до правки; двойного колбэка нет. */
    @Test
    fun fastPaymentDeliversFromFirstWindowOnce() {
        val h = Harness()
        h.wait.start()
        h.polls[0].first(active)
        h.wait.onAppResumed()
        assertEquals(1, h.polls.size)
        assertEquals(listOf<EntitlementGrant?>(active), h.delivered)
    }

    /** Старое окно прекращает опрос, когда началось новое; всё прекращается после ответа. */
    @Test
    fun supersededWindowStopsPolling() {
        val h = Harness()
        h.wait.start()
        assertTrue(h.polls[0].second())
        h.wait.onAppResumed()
        assertFalse(h.polls[0].second())
        assertTrue(h.polls[1].second())
        h.polls[1].first(active)
        assertFalse(h.polls[1].second())
    }

    /** Результат возвратного метода уходит и в исходный openWebPaywall — один раз. */
    @Test
    fun returnUrlResultIsOfferedOnce() {
        val h = Harness()
        h.wait.start()
        h.wait.offer(null) // пустой ответ возвратного метода окно не закрывает
        assertTrue(h.delivered.isEmpty())
        h.wait.offer(active)
        h.wait.offer(active)
        h.polls[0].first(active)
        assertEquals(listOf<EntitlementGrant?>(active), h.delivered)
        assertTrue(h.wait.isFinished)
    }

    /**
     * Доработка 4: вернулся, снова ушёл во вкладку (приложение не на экране) — окно
     * на паузе: «не оплачено» не отдаётся, пока покупатель платит. Вернулся снова —
     * новое окно, и только его истечение даёт null.
     */
    @Test
    fun windowExpiringWhileAppAwayDoesNotDeliverNull() {
        val h = Harness()
        h.wait.start()
        h.wait.onAppResumed()
        h.wait.onAppPaused() // снова ушёл во вкладку проходить 3-D Secure
        h.polls[1].first(null) // окно истекло, пока он во вкладке
        assertTrue("null не отдаётся, пока приложение не на экране", h.delivered.isEmpty())
        h.wait.onAppResumed()
        assertEquals(3, h.polls.size)
        h.polls[2].first(null)
        assertEquals(listOf<EntitlementGrant?>(null), h.delivered)
    }

    /** На паузе действующая запись всё равно отдаётся сразу. */
    @Test
    fun activeGrantFoundWhileAppAwayIsDelivered() {
        val h = Harness()
        h.wait.start()
        h.wait.onAppResumed()
        h.wait.onAppPaused()
        h.polls[1].first(active)
        assertEquals(listOf<EntitlementGrant?>(active), h.delivered)
    }

    /** Нет доступа к жизненному циклу приложения — прежнее поведение: окно от открытия. */
    @Test
    fun withoutLifecycleFallsBackToWindowFromLaunch() {
        val h = Harness(lifecycleAvailable = false)
        h.wait.start()
        h.polls[0].first(null)
        assertEquals(listOf<EntitlementGrant?>(null), h.delivered)
    }

    /** Опрос с подменённым «продолжать ли» останавливается без колбэка. */
    @Test
    fun pollStopsWhenToldToWithoutCompletion() {
        var fetches = 0
        var completions = 0
        WebPaywallLauncher.pollForActiveGrant(
            intervalMs = 0,
            maxAttempts = 5,
            fetch = { cb -> fetches++; cb(null) },
            completion = { completions++ },
            shouldContinue = { fetches < 2 },
        )
        Thread.sleep(200)
        assertEquals(2, fetches)
        assertEquals(0, completions)
    }
}
