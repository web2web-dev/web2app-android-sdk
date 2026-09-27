package app.web2app.sdk

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
}
