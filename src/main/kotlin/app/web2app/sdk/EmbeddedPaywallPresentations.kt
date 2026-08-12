package app.web2app.sdk

import java.lang.ref.WeakReference

/**
 * Атом А-4 — что реестру нужно от показа встроенного пейволла: уметь завершиться.
 * У [EmbeddedPaywallActivity] это её `finish()`; тонкая прослойка нужна, чтобы
 * решение «кого вытеснить» оставалось чистым и проверяемым на JVM без Android.
 */
internal interface EmbeddedPaywallPresentation {
    /** Завершить показ. Колбэк уходит ОБЫЧНЫМ путём (реестр колбэков из onDestroy). */
    fun dismiss()
}

/**
 * Атом А-4 — guard одновременных показов встроенного пейволла (паритет iOS 0.4.1:
 * WebPaywallLauncher.swift:108-114 + WebViewPaywallPresenter.swift:74-78 держат
 * статическую ссылку на активный презентер).
 *
 * Раньше два подряд `openWebPaywallEmbedded` наслаивали Activity друг на друга:
 * юзер видел стопку пейволлов, а колбэк первого висел до закрытия своей Activity.
 * Теперь новый показ вытесняет предыдущий — тот завершается и отдаёт свой результат
 * обычным путём (`onDestroy` → [EmbeddedPaywallCallbacks], один раз).
 *
 * Ссылка на активный показ СЛАБАЯ: реестр — синглтон уровня процесса, сильная
 * ссылка на Activity пережила бы её и утекла.
 */
internal object EmbeddedPaywallPresentations {
    private var activeRef: WeakReference<EmbeddedPaywallPresentation>? = null

    /** Текущий активный показ (null — показа нет либо его уже собрал GC). */
    fun active(): EmbeddedPaywallPresentation? = synchronized(this) { activeRef?.get() }

    /**
     * Регистрирует [presentation] активным показом и вытесняет предыдущий.
     * Ссылку меняем ДО `dismiss()` предыдущего: его чистка ([clear]) прилетит
     * по идентичности и не снесёт уже зарегистрированного нового.
     */
    fun setActive(presentation: EmbeddedPaywallPresentation) {
        val previous = synchronized(this) {
            val previous = activeRef?.get()
            activeRef = WeakReference(presentation)
            previous
        }
        // dismiss() зовём ВНЕ монитора — он уходит в Activity.finish() (чужой код).
        if (previous !== null && previous !== presentation) previous.dismiss()
    }

    /**
     * Снимает [presentation] с активного показа при её уничтожении. Чистка по
     * идентичности: `onDestroy` вытесненной Activity прилетает ПОСЛЕ `onCreate`
     * новой, и не должна обнулять уже активный новый показ.
     */
    fun clear(presentation: EmbeddedPaywallPresentation) = synchronized(this) {
        if (activeRef?.get() === presentation) activeRef = null
    }
}
