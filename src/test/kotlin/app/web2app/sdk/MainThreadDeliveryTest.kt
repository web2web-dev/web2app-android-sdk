package app.web2app.sdk

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Атом А-3 — паритет iOS 0.4.1 (каждый completion зовётся внутри
 * `DispatchQueue.main.async`, Web2AppSDK.swift:117-120/:190-193).
 *
 * Проверяем контракт доставки, а не сеть: результат КАЖДОГО публичного метода
 * уходит интегратору через единую точку [MainThread] — РОВНО один раз и РОВНО
 * с одной обёрткой (двойная обёртка при делегировании `*ById` → базовый метод
 * дала бы два прохода через точку доставки на одну доставку результата).
 *
 * Ветки без configure выбраны намеренно: это ранние синхронные возвраты, они по
 * требованию атома подчиняются тому же правилу, что и асинхронные, и при этом
 * достижимы на чистой JVM без Android-рантайма (Robolectric не заводим).
 */
class MainThreadDeliveryTest {

    private val realDelivery = MainThread.delivery

    /** Сколько раз результат прошёл через точку доставки. */
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
    fun restoreDelivery() {
        MainThread.delivery = realDelivery
    }

    // ── identify / атрибуция ────────────────────────────────────────────────

    @Test
    fun identifyDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        var result: Result<String>? = null
        Web2AppSdk.identify(onResult = { calls++; result = it })
        assertEquals(1, posts)
        assertEquals(1, calls)
        assertTrue(result!!.isFailure)
    }

    @Test
    fun identifyWithDeepLinkValueDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        var result: Result<String>? = null
        Web2AppSdk.identifyWithDeepLinkValue("tok") { calls++; result = it }
        assertEquals(1, posts)
        assertEquals(1, calls)
        assertTrue(result!!.isFailure)
    }

    @Test
    fun requestEmailRecoveryDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        var result: Result<Unit>? = null
        Web2AppSdk.requestEmailRecovery("a@b.c") { calls++; result = it }
        assertEquals(1, posts)
        assertEquals(1, calls)
        assertTrue(result!!.isFailure)
    }

    // ── entitlement ─────────────────────────────────────────────────────────

    @Test
    fun entitlementDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        var grant: EntitlementGrant? = EntitlementGrant("premium", "active", null, null)
        Web2AppSdk.entitlement { calls++; grant = it }
        assertEquals(1, posts)
        assertEquals(1, calls)
        assertNull(grant)
    }

    // ── Custom Tab-пейволл ──────────────────────────────────────────────────

    @Test
    fun openWebPaywallDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        Web2AppSdk.openWebPaywall(unusedContext, PAYWALL_URL) { calls++ }
        assertEquals(1, posts)
        assertEquals(1, calls)
    }

    @Test
    fun openWebPaywallByIdDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        Web2AppSdk.openWebPaywallById(unusedContext, "pw-unknown") { calls++ }
        assertEquals(1, posts)
        assertEquals(1, calls)
    }

    // ── Встроенный пейволл ──────────────────────────────────────────────────

    @Test
    fun openWebPaywallEmbeddedDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        var result: PaywallResult? = null
        Web2AppSdk.openWebPaywallEmbedded(unusedContext, PAYWALL_URL) { calls++; result = it }
        assertEquals(1, posts)
        assertEquals(1, calls)
        assertEquals(PaywallResult.Unavailable, result)
    }

    /**
     * `*ById` делегирует в базовый метод — на одну доставку должен приходиться
     * ОДИН проход через точку доставки (не два от двойной обёртки).
     */
    @Test
    fun openWebPaywallEmbeddedByIdDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        var result: PaywallResult? = null
        Web2AppSdk.openWebPaywallEmbeddedById(unusedContext, "pw-unknown") { calls++; result = it }
        assertEquals(1, posts)
        assertEquals(1, calls)
        assertEquals(PaywallResult.Unavailable, result)
    }

    // ── handleReturnUrl ─────────────────────────────────────────────────────

    @Test
    fun handleReturnUrlDeliversThroughMainThreadExactlyOnce() {
        var calls = 0
        val handled = Web2AppSdk.handleReturnUrl("myapp://handoff?code=abc") { calls++ }
        assertTrue(handled)
        assertEquals(1, posts)
        assertEquals(1, calls)
    }

    @Test
    fun handleReturnUrlOfForeignLinkDeliversNothing() {
        // Чужой deep-link: контракт = вернуть false молча, колбэк не звать вовсе.
        var calls = 0
        val handled = Web2AppSdk.handleReturnUrl("myapp://settings") { calls++ }
        assertFalse(handled)
        assertEquals(0, posts)
        assertEquals(0, calls)
    }

    // ── сама точка доставки ─────────────────────────────────────────────────

    @Test
    fun defaultDeliveryRunsInlineWhenAlreadyOnMainThread() {
        // Дефолтная реализация: `Looper.myLooper() == Looper.getMainLooper()` →
        // выполняем синхронно, без лишнего прыжка через очередь Looper'а.
        // В JVM-юнитах оба вызова заглушены в null, т.е. это ветка «уже на main».
        MainThread.delivery = realDelivery
        var ran = 0
        MainThread.post { ran++ }
        assertEquals(1, ran)
    }

    @Test
    fun wrapPassesValueThroughAndPostsOncePerInvocation() {
        val seen = mutableListOf<String>()
        val wrapped = MainThread.wrap<String> { seen += it }
        wrapped("a")
        wrapped("b")
        assertEquals(listOf("a", "b"), seen)
        assertEquals(2, posts)
    }

    private companion object {
        const val PAYWALL_URL = "https://client.example.com/paywall/pw1"
    }

    /**
     * Context в проверяемых ветках не используется: гард по config срабатывает
     * до любого обращения к Android-рантайму (см. EmbeddedPaywallWindowTest).
     */
    private val unusedContext = ContextWrapper(null)
}
