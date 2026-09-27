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
     * Сколько раз за один показ можно пересоздать WebView после гибели процесса
     * страницы. Ровно один: второй подряд — страница не жилец, закрываем показ
     * (без предела страница, убивающая рендерер, крутилась бы бесконечно).
     */
    const val MAX_WEBVIEW_RECREATIONS = 1

    /** Что делать, когда процесс страницы погиб ([terminationsBefore] — сколько гибелей уже было за показ). */
    fun renderProcessGoneAction(terminationsBefore: Int): RenderProcessGoneAction =
        if (terminationsBefore < MAX_WEBVIEW_RECREATIONS) RenderProcessGoneAction.RECREATE
        else RenderProcessGoneAction.GIVE_UP

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
}

internal enum class RenderProcessGoneAction {
    /** Убрать погибший WebView, создать новый и загрузить адрес заново. */
    RECREATE,

    /** Закрыть показ с результатом «недоступно». */
    GIVE_UP,
}
