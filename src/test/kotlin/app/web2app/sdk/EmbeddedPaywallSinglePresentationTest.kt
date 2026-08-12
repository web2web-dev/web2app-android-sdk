package app.web2app.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Атом А-4 — guard одновременных показов встроенного пейволла (паритет iOS 0.4.1:
 * WebPaywallLauncher.swift:108-114 держит статическую ссылку на активный презентер,
 * новый показ завершает предыдущий, и его completion уходит обычным путём).
 *
 * Реальную Activity в JVM-юнитах не поднять, поэтому решение «кто сейчас активен /
 * кого вытеснить» вынесено в чистый реестр [EmbeddedPaywallPresentations], а Activity
 * остаётся тонкой прослойкой ([EmbeddedPaywallPresentation.dismiss] = её `finish()`).
 * Здесь проверяется сам реестр + стык с one-shot реестром колбэков.
 */
class EmbeddedPaywallSinglePresentationTest {

    /** Двойник показа: считает, сколько раз его попросили завершиться. */
    private class FakePresentation(val name: String) : EmbeddedPaywallPresentation {
        var dismissCount = 0
            private set

        override fun dismiss() {
            dismissCount++
        }

        override fun toString() = name
    }

    @After
    fun clearRegistry() {
        EmbeddedPaywallPresentations.active()?.let { EmbeddedPaywallPresentations.clear(it) }
    }

    // ── регистрация активного показа ────────────────────────────────────────

    @Test
    fun firstPresentationBecomesActiveAndIsNotDismissed() {
        val first = FakePresentation("first")
        EmbeddedPaywallPresentations.setActive(first)
        assertSame(first, EmbeddedPaywallPresentations.active())
        assertEquals(0, first.dismissCount)
    }

    // ── вытеснение предыдущего показа ───────────────────────────────────────

    @Test
    fun secondPresentationDismissesPreviousExactlyOnce() {
        val first = FakePresentation("first")
        val second = FakePresentation("second")
        EmbeddedPaywallPresentations.setActive(first)
        EmbeddedPaywallPresentations.setActive(second)
        assertEquals(1, first.dismissCount)
        assertEquals(0, second.dismissCount)
        assertSame(second, EmbeddedPaywallPresentations.active())
    }

    @Test
    fun thirdPresentationDismissesOnlyTheSecond() {
        val first = FakePresentation("first")
        val second = FakePresentation("second")
        val third = FakePresentation("third")
        EmbeddedPaywallPresentations.setActive(first)
        EmbeddedPaywallPresentations.setActive(second)
        EmbeddedPaywallPresentations.setActive(third)
        assertEquals(1, first.dismissCount)
        assertEquals(1, second.dismissCount)
        assertEquals(0, third.dismissCount)
        assertSame(third, EmbeddedPaywallPresentations.active())
    }

    @Test
    fun reRegisteringTheSamePresentationDoesNotDismissIt() {
        val only = FakePresentation("only")
        EmbeddedPaywallPresentations.setActive(only)
        EmbeddedPaywallPresentations.setActive(only)
        assertEquals(0, only.dismissCount)
        assertSame(only, EmbeddedPaywallPresentations.active())
    }

    // ── очистка при уничтожении показа ──────────────────────────────────────

    @Test
    fun clearDropsActivePresentation() {
        val only = FakePresentation("only")
        EmbeddedPaywallPresentations.setActive(only)
        EmbeddedPaywallPresentations.clear(only)
        assertNull(EmbeddedPaywallPresentations.active())
    }

    @Test
    fun staleClearFromEvictedShowDoesNotDropTheNewActiveOne() {
        // Реальный порядок Android: onDestroy вытесненной Activity прилетает ПОСЛЕ
        // onCreate новой. Чистка по идентичности не должна снести нового активного.
        val first = FakePresentation("first")
        val second = FakePresentation("second")
        EmbeddedPaywallPresentations.setActive(first)
        EmbeddedPaywallPresentations.setActive(second)
        EmbeddedPaywallPresentations.clear(first)
        assertSame(second, EmbeddedPaywallPresentations.active())
    }

    @Test
    fun newShowAfterClearDoesNotDismissTheAlreadyDestroyedOne() {
        val first = FakePresentation("first")
        EmbeddedPaywallPresentations.setActive(first)
        EmbeddedPaywallPresentations.clear(first)
        EmbeddedPaywallPresentations.setActive(FakePresentation("second"))
        assertEquals(0, first.dismissCount)
    }

    // ── отсутствие утечки Activity ──────────────────────────────────────────

    @Test
    fun registryDoesNotRetainPresentationStrongly() {
        // Ссылка на показ (в проде — Activity) держится СЛАБО: если интегратор
        // не позвал clear (процесс убил Activity), реестр не должен её удерживать.
        var leaked: FakePresentation? = FakePresentation("leaked")
        EmbeddedPaywallPresentations.setActive(leaked!!)
        leaked = null
        // GC не мгновенна — даём ей несколько попыток, иначе тест был бы флаки.
        for (attempt in 0 until 50) {
            if (EmbeddedPaywallPresentations.active() == null) break
            System.gc()
            Thread.sleep(10)
        }
        assertNull("реестр удержал показ сильной ссылкой", EmbeddedPaywallPresentations.active())
    }

    // ── колбэк вытесненного показа: РОВНО один раз ──────────────────────────

    @Test
    fun evictedShowDeliversItsCallbackExactlyOnce() {
        // Двойник вытесняемой Activity: dismiss() = finish(), а колбэк уходит
        // обычным путём из onDestroy — через one-shot реестр колбэков.
        val results = mutableListOf<BridgeEvent?>()
        val callbackId = "cb-evicted"
        EmbeddedPaywallCallbacks.register(callbackId) { results += it }

        val evicted = object : EmbeddedPaywallPresentation {
            override fun dismiss() {
                // finish() → система зовёт onDestroy → deliver(null).
                EmbeddedPaywallCallbacks.deliver(callbackId, null)
            }
        }
        EmbeddedPaywallPresentations.setActive(evicted)
        EmbeddedPaywallPresentations.setActive(FakePresentation("second"))

        // Повторный onDestroy/вторая доставка не должны задвоить колбэк.
        EmbeddedPaywallCallbacks.deliver(callbackId, null)

        assertEquals(listOf<BridgeEvent?>(null), results)
    }

    @Test
    fun secondShowKeepsItsOwnCallbackAliveAfterFirstOneWasEvicted() {
        val delivered = mutableListOf<String>()
        EmbeddedPaywallCallbacks.register("cb-first") { delivered += "first" }
        EmbeddedPaywallCallbacks.register("cb-second") { delivered += "second" }

        val first = object : EmbeddedPaywallPresentation {
            override fun dismiss() {
                EmbeddedPaywallCallbacks.deliver("cb-first", null)
            }
        }
        EmbeddedPaywallPresentations.setActive(first)
        EmbeddedPaywallPresentations.setActive(FakePresentation("second"))
        assertEquals(listOf("first"), delivered)

        // Второй показ доводится до конца своим событием — колбэк жив.
        EmbeddedPaywallCallbacks.deliver("cb-second", BridgeEvent.PAYMENT_SUCCESS)
        assertEquals(listOf("first", "second"), delivered)
    }
}
