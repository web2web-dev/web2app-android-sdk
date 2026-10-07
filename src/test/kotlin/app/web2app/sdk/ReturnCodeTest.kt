package app.web2app.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEB-2479 — паритет с iOS: обмен кода из ссылки и возвратный метод.
 *
 * Решения владельца 06.10: (1) код погашен/просрочен (сервер 404), а сохранённый
 * guid есть → успех с сохранённым guid, не ошибка (раньше Android отдавал
 * ошибку); сбой сети / 5xx — ошибка; (2) код тратится только когда окна оплаты
 * библиотеки нет. Сам [Web2AppSdk] на чистой JVM не поднять (EncryptedSharedPreferences),
 * поэтому проверяются правила и ход возврата с подменёнными шагами.
 */
class ReturnCodeTest {

    // ── правила обмена ──────────────────────────────────────────────────────

    @Test
    fun exchangedOnSuccess() {
        assertEquals(
            CodeExchangeOutcome.Exchanged("B"),
            ReturnCodeRules.outcome(Result.success("B"), cachedGuid = "A"),
        )
    }

    @Test
    fun rejectedCodeKeepsCachedGuid() {
        assertEquals(
            CodeExchangeOutcome.KeptCached("A"),
            ReturnCodeRules.outcome(Result.failure(ResolveFailedException(404)), cachedGuid = "A"),
        )
    }

    @Test
    fun rejectedCodeWithoutCachedGuidFails() {
        val out = ReturnCodeRules.outcome(Result.failure(ResolveFailedException(404)), cachedGuid = null)
        assertTrue(out is CodeExchangeOutcome.Failed)
    }

    @Test
    fun networkAndServerErrorsAreNotMaskedByCachedGuid() {
        // 0 = сетевое исключение; 5xx/429 — сбой сервера, а не «код погашен».
        for (code in listOf(0, 500, 502, 429)) {
            val out = ReturnCodeRules.outcome(
                Result.failure(ResolveFailedException(code)),
                cachedGuid = "A",
            )
            assertTrue("http $code", out is CodeExchangeOutcome.Failed)
        }
        // Чужое исключение без кода — тоже ошибка.
        val other = ReturnCodeRules.outcome(Result.failure(IllegalStateException("x")), "A")
        assertTrue(other is CodeExchangeOutcome.Failed)
    }

    @Test
    fun codeFromReturnUrl() {
        assertEquals("AB12", ReturnCodeRules.codeFromReturnUrl("myapp://handoff?code=AB12"))
        assertEquals("c+1", ReturnCodeRules.codeFromReturnUrl("myapp://handoff?x=1&code=c%2B1#f"))
        assertNull(ReturnCodeRules.codeFromReturnUrl("myapp://handoff"))
        assertNull(ReturnCodeRules.codeFromReturnUrl("myapp://handoff?code="))
        assertNull(ReturnCodeRules.codeFromReturnUrl("myapp://handoff?xcode=1"))
    }

    // ── ход возвратного метода ──────────────────────────────────────────────

    private class Run(
        windowOpen: Boolean,
        code: String?,
        outcome: CodeExchangeOutcome,
        /** Что отдаёт каждый следующий опрос по сохранённому guid (последний повторяется). */
        pollResults: List<EntitlementGrant?> = listOf(ACTIVE),
    ) {
        val exchanged = mutableListOf<String>()
        var polls = 0
        val results = mutableListOf<EntitlementGrant?>()

        init {
            ReturnUrlFlow(
                windowOpen = windowOpen,
                exchange = { c, cb -> exchanged += c; cb(outcome) },
                pollStoredGuid = { cb ->
                    val r = pollResults[minOf(polls, pollResults.size - 1)]
                    polls++
                    cb(r)
                },
            ).run(code) { results += it }
        }
    }

    private companion object {
        val ACTIVE = EntitlementGrant("l", "active", null, "p")
        val EXPIRED = EntitlementGrant("l", "expired", null, "p")
    }

    /** Окна нет, код есть → обмен, затем опрос права; результат один раз. До починки обмена не было. */
    @Test
    fun withoutWindowExchangesCodeThenPolls() {
        val r = Run(windowOpen = false, code = "X", outcome = CodeExchangeOutcome.Exchanged("B"))
        assertEquals(listOf("X"), r.exchanged)
        assertEquals(1, r.polls)
        assertEquals(1, r.results.size)
        assertEquals(true, r.results[0]?.isActive)
    }

    /** Идёт незавершённый openWebPaywall → прежнее поведение: опрос, код не тратится. */
    @Test
    fun withOpenWindowDoesNotSpendCode() {
        val r = Run(windowOpen = true, code = "X", outcome = CodeExchangeOutcome.Exchanged("B"))
        assertEquals(emptyList<String>(), r.exchanged)
        assertEquals(1, r.polls)
        assertEquals(1, r.results.size)
    }

    /**
     * Решение владельца 07.10: окно открыто, но по сохранённому guid действующей
     * записи нет (покупатель бросил окно и оплатил в обычном браузере), а код в
     * ссылке есть → код обменивается, затем опрос по новому guid.
     */
    @Test
    fun withOpenWindowAndNoActiveGrantExchangesCode() {
        val r = Run(
            windowOpen = true, code = "X", outcome = CodeExchangeOutcome.Exchanged("B"),
            pollResults = listOf(null, ACTIVE),
        )
        assertEquals(listOf("X"), r.exchanged)
        assertEquals(2, r.polls)
        assertEquals(listOf<EntitlementGrant?>(ACTIVE), r.results)
    }

    /** Неактивная запись по сохранённому guid — тоже «доступа нет»: код обменивается. */
    @Test
    fun withOpenWindowAndOnlyInactiveGrantExchangesCode() {
        val r = Run(
            windowOpen = true, code = "X", outcome = CodeExchangeOutcome.Exchanged("B"),
            pollResults = listOf(EXPIRED, ACTIVE),
        )
        assertEquals(listOf("X"), r.exchanged)
        assertEquals(listOf<EntitlementGrant?>(ACTIVE), r.results)
    }

    /** Окно открыто, доступа нет, обмен не удался → ответ первого опроса, один раз. */
    @Test
    fun withOpenWindowNoGrantAndFailedExchangeDeliversFirstPollOnce() {
        val r = Run(
            windowOpen = true, code = "X",
            outcome = CodeExchangeOutcome.Failed(ResolveFailedException(404)),
            pollResults = listOf(null),
        )
        assertEquals(listOf("X"), r.exchanged)
        assertEquals(1, r.polls)
        assertEquals(listOf<EntitlementGrant?>(null), r.results)
    }

    /** Окно открыто, доступа нет, кода в ссылке нет → null после одного опроса. */
    @Test
    fun withOpenWindowNoGrantNoCodeDeliversNull() {
        val r = Run(windowOpen = true, code = null, outcome = CodeExchangeOutcome.Exchanged("B"), pollResults = listOf(null))
        assertEquals(emptyList<String>(), r.exchanged)
        assertEquals(1, r.polls)
        assertEquals(listOf<EntitlementGrant?>(null), r.results)
    }

    @Test
    fun exchangeFailureDeliversNullOnceWithoutPolling() {
        val r = Run(false, "X", CodeExchangeOutcome.Failed(ResolveFailedException(0)))
        assertEquals(0, r.polls)
        assertEquals(listOf<EntitlementGrant?>(null), r.results)
    }

    @Test
    fun rejectedCodeWithCachedGuidStillPolls() {
        val r = Run(false, "X", CodeExchangeOutcome.KeptCached("A"))
        assertEquals(1, r.polls)
        assertEquals(1, r.results.size)
    }

    @Test
    fun withoutCodePollsStoredGuid() {
        val r = Run(false, null, CodeExchangeOutcome.Exchanged("B"))
        assertEquals(emptyList<String>(), r.exchanged)
        assertEquals(1, r.polls)
    }
}
