package app.web2app.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WEB-2479 доработка 4 — подключение возвратного метода: [Web2AppSdk.handleReturnUrl]
 * спрашивает [Web2AppSdk.isLibraryWindowOpen] и отдаёт результат в незавершённые
 * ожидания `openWebPaywall`. Сеть подменена: обмен кода и опрос права — заглушки.
 */
class ReturnUrlWiringTest {
    private val active = EntitlementGrant("l", "active", null, "p")
    private val delivered = mutableListOf<EntitlementGrant?>()
    private val wait = CustomTabWait(
        startPoll = { _, _ -> },
        deliver = { delivered += it },
        lifecycleAvailable = true,
    )

    @After
    fun tearDown() {
        Web2AppSdk.customTabWaits.remove(wait)
    }

    private fun run(url: String): Pair<List<String>, List<EntitlementGrant?>> {
        val exchanged = mutableListOf<String>()
        val results = mutableListOf<EntitlementGrant?>()
        Web2AppSdk.runReturnFlow(
            url,
            exchange = { code, cb -> exchanged += code; cb(CodeExchangeOutcome.Exchanged("B")) },
            pollStoredGuid = { cb -> cb(active) },
        ) { results += it }
        return exchanged to results
    }

    /** Идёт openWebPaywall, доступ есть → код не тратится; ожидание получает доступ. */
    @Test
    fun openCustomTabWaitKeepsCodeAndReceivesResult() {
        Web2AppSdk.customTabWaits.add(wait)
        val (exchanged, results) = run("myapp://handoff?code=K1")
        assertEquals(emptyList<String>(), exchanged)
        assertEquals(listOf<EntitlementGrant?>(active), results)
        assertEquals(listOf<EntitlementGrant?>(active), delivered)
    }

    /** Окна нет → код обменивается. */
    @Test
    fun noWindowExchangesCode() {
        val (exchanged, results) = run("myapp://handoff?code=K1")
        assertEquals(listOf("K1"), exchanged)
        assertEquals(listOf<EntitlementGrant?>(active), results)
    }
}
