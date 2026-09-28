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
     * сбрасывается, когда главный кадр страницы догрузился без ошибки
     * (`onPageFinished`, см. [shouldResetCrashesInARow]). Поверх — общий потолок
     * за показ [MAX_RENDER_CRASHES_PER_SHOW].
     */
    const val MAX_WEBVIEW_RECREATIONS = 1

    /**
     * Общий потолок настоящих падений процесса страницы за один показ (за жизнь
     * Activity) — не сбрасывается никогда. Шестое падение закрывает показ, даже
     * если между падениями страница каждый раз догружалась: иначе круг
     * «загрузилась → упала → перезагрузка» крутился бы бесконечно.
     */
    const val MAX_RENDER_CRASHES_PER_SHOW = 5

    /**
     * Что делать, когда процесс страницы погиб.
     *
     * [didCrash] — `RenderProcessGoneDetail.didCrash()`: `true` — настоящее падение
     * (идёт в оба лимита), `false` — система выгрузила процесс ради памяти (обычно
     * приложение в фоне, человек ушёл платить в банк) — это не срыв показа и ни в
     * один лимит не идёт никогда.
     *
     * [isResumed] — экран на переднем плане: загрузка сразу; иначе — отложить до
     * возврата на экран (грузить страницу в фоне незачем, система её снова выгрузит).
     *
     * [crashesInARow] — сколько настоящих падений подряд было до этой гибели;
     * [totalCrashes] — сколько настоящих падений было за весь показ до неё.
     * В ответе — сколько стало. Если сработали оба лимита сразу, причина
     * закрытия — общий потолок.
     */
    fun renderProcessGoneDecision(
        didCrash: Boolean,
        isResumed: Boolean,
        crashesInARow: Int,
        totalCrashes: Int,
    ): RenderProcessGoneDecision {
        if (!didCrash) {
            val action = if (isResumed) RenderProcessGoneAction.RELOAD_NOW else RenderProcessGoneAction.RELOAD_ON_RESUME
            return RenderProcessGoneDecision(action, crashesInARow, totalCrashes, giveUpReason = null)
        }
        val inARowAfter = crashesInARow + 1
        val totalAfter = totalCrashes + 1
        val giveUpReason = when {
            totalCrashes >= MAX_RENDER_CRASHES_PER_SHOW -> GiveUpReason.CRASHES_PER_SHOW
            crashesInARow >= MAX_WEBVIEW_RECREATIONS -> GiveUpReason.CRASHES_IN_A_ROW
            else -> null
        }
        val action = when {
            giveUpReason != null -> RenderProcessGoneAction.GIVE_UP
            isResumed -> RenderProcessGoneAction.RELOAD_NOW
            else -> RenderProcessGoneAction.RELOAD_ON_RESUME
        }
        return RenderProcessGoneDecision(action, inARowAfter, totalAfter, giveUpReason)
    }

    /**
     * Сбрасывать ли счётчик падений «подряд» в `onPageFinished`. Этот колбэк
     * приходит и после ошибки загрузки главного кадра (нет сети, таймаут) —
     * тогда страница НЕ загрузилась, и прежние падения остаются «подряд».
     */
    fun shouldResetCrashesInARow(mainFrameLoadFailed: Boolean): Boolean = !mainFrameLoadFailed

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

/**
 * Решение по гибели процесса страницы: действие, счётчики падений (подряд и за
 * показ) после неё и причина закрытия (только у [RenderProcessGoneAction.GIVE_UP]).
 */
internal data class RenderProcessGoneDecision(
    val action: RenderProcessGoneAction,
    val crashesInARow: Int,
    val totalCrashes: Int,
    val giveUpReason: GiveUpReason?,
)

/** Почему показ закрыт из-за падений процесса страницы. */
internal enum class GiveUpReason {
    /** Два падения подряд — журнал `paywall.webview_process_terminated_twice`. */
    CRASHES_IN_A_ROW,

    /** Шестое падение за показ — журнал `paywall.webview_process_crash_limit`. */
    CRASHES_PER_SHOW,
}

internal enum class RenderProcessGoneAction {
    /** Новый WebView уже поставлен — загрузить адрес сразу (экран на переднем плане). */
    RELOAD_NOW,

    /** Новый WebView уже поставлен — загрузить адрес при возврате на экран (`onResume`). */
    RELOAD_ON_RESUME,

    /** Закрыть показ с результатом «недоступно». */
    GIVE_UP,
}

/**
 * 0.7.2 — была ли ошибка главного кадра у ТЕКУЩЕЙ загрузки (решает, сбрасывать ли
 * счётчик падений «подряд» в `onPageFinished`).
 *
 * Флаг НЕ снимается в `onPageStarted`: у современного WebView при ошибке главного
 * кадра порядок бывает «ошибка → onPageStarted (страница ошибки) → onPageFinished»,
 * и сброс в начале загрузки стёр бы ошибку раньше времени. Снимается:
 * - когда загрузку запускает сам SDK ([onLoadStartedBySdk]: новый WebView, отложенная
 *   загрузка при возврате на экран);
 * - в конце загрузки ([onPageFinished]) — ПОСЛЕ решения о сбросе счётчика. Так флаг
 *   живёт ровно до конца той загрузки, в которой случилась ошибка, и переход по
 *   ссылке внутри страницы (новая загрузка без участия SDK) начинается с чистого листа.
 */
internal class MainFrameLoadTracker {
    private var mainFrameLoadFailed = false

    fun onLoadStartedBySdk() {
        mainFrameLoadFailed = false
    }

    fun onMainFrameError() {
        mainFrameLoadFailed = true
    }

    /** `true` — сбросить счётчик падений «подряд». Флаг после вызова снят. */
    fun onPageFinished(): Boolean {
        val reset = EmbeddedWebViewPolicy.shouldResetCrashesInARow(mainFrameLoadFailed)
        mainFrameLoadFailed = false
        return reset
    }
}
