package app.web2app.sdk

/**
 * 0.7.2 — решения встроенного показа (пейволл и квиз — одна Activity), вынесенные
 * из [EmbeddedPaywallActivity] в чистые функции: `Activity`/`WebView` в JVM-юнитах
 * живьём не проверить (Robolectric нет), а сами решения — проверить можно.
 */
internal object EmbeddedWebViewPolicy {

    /**
     * Считать ли уничтожение Activity закрытием показа (отдавать ли колбэк из
     * `onDestroy`).
     *
     * Пересоздание из-за смены конфигурации (поворот, тема, клавиатура) — НЕ
     * закрытие: новая Activity поднимется с тем же `Intent` и доиграет показ сама.
     * Манифест такое пересоздание для нашего экрана запрещает (`configChanges`),
     * это страховка на случай изменения, которого в списке нет.
     *
     * Если Activity одновременно и закрывается ([isFinishing]) — это закрытие:
     * новой Activity не будет, и без колбэка вызывающий ждал бы вечно.
     */
    fun shouldDeliverCloseOnDestroy(isChangingConfigurations: Boolean, isFinishing: Boolean): Boolean =
        isFinishing || !isChangingConfigurations

    /**
     * Сколько настоящих падений процесса страницы ПОДРЯД переживает один показ.
     * Ровно одно: второе подряд — страница не жилец, закрываем показ (без предела
     * страница, роняющая рендерер, крутилась бы бесконечно). «Подряд» — счётчик
     * сбрасывается, когда главный кадр страницы догрузился (`onPageFinished`).
     */
    const val MAX_WEBVIEW_RECREATIONS = 1

    /**
     * Что делать, когда процесс страницы погиб.
     *
     * [didCrash] — `RenderProcessGoneDetail.didCrash()`: `true` — настоящее падение
     * (идёт в лимит), `false` — система выгрузила процесс ради памяти (обычно
     * приложение в фоне, человек ушёл платить в банк) — это не срыв показа и в
     * лимит не идёт никогда.
     *
     * [isResumed] — экран на переднем плане: загрузка сразу; иначе — отложить до
     * возврата на экран (грузить страницу в фоне незачем, система её снова выгрузит).
     *
     * [crashesInARow] — сколько настоящих падений подряд было до этой гибели;
     * в ответе — сколько стало.
     */
    fun renderProcessGoneDecision(didCrash: Boolean, isResumed: Boolean, crashesInARow: Int): RenderProcessGoneDecision {
        if (didCrash && crashesInARow >= MAX_WEBVIEW_RECREATIONS) {
            return RenderProcessGoneDecision(RenderProcessGoneAction.GIVE_UP, crashesInARow + 1)
        }
        val crashesAfter = if (didCrash) crashesInARow + 1 else crashesInARow
        val action = if (isResumed) RenderProcessGoneAction.RELOAD_NOW else RenderProcessGoneAction.RELOAD_ON_RESUME
        return RenderProcessGoneDecision(action, crashesAfter)
    }

    /** Приоритет рендерера на момент гибели — именем для журнала (константы WebView API 26). */
    fun rendererPriorityName(priority: Int): String = when (priority) {
        0 -> "waived"
        1 -> "bound"
        2 -> "important"
        else -> "unknown($priority)"
    }

    /**
     * Итог показа пейволла, сорванного гибелью страницы: оплата могла пройти до
     * сбоя — если право уже активно, это [PaywallResult.Paid], иначе показ
     * считается несостоявшимся — [PaywallResult.Unavailable] (не [PaywallResult.NotPaid]:
     * пользователь ничего не отклонял).
     */
    fun paywallResultAfterBrokenShow(grant: EntitlementGrant?): PaywallResult =
        if (grant != null) PaywallResult.Paid(grant) else PaywallResult.Unavailable

    /**
     * Реагировать ли на ошибку загрузки (прятать индикатор, писать
     * `paywall.webview_load_failed`). Только главный кадр: сбой картинки, шрифта
     * или счётчика не значит, что страница не открылась, — индикатор тогда
     * спрячет `onPageFinished`.
     */
    fun reactsToLoadError(isForMainFrame: Boolean): Boolean = isForMainFrame
}

/** Решение по гибели процесса страницы: действие и счётчик падений подряд после неё. */
internal data class RenderProcessGoneDecision(val action: RenderProcessGoneAction, val crashesInARow: Int)

internal enum class RenderProcessGoneAction {
    /** Новый WebView уже поставлен — загрузить адрес сразу (экран на переднем плане). */
    RELOAD_NOW,

    /** Новый WebView уже поставлен — загрузить адрес при возврате на экран (`onResume`). */
    RELOAD_ON_RESUME,

    /** Закрыть показ с результатом «недоступно». */
    GIVE_UP,
}
