package app.web2app.sdk

/**
 * Атом Б-2 — чем закончился встроенный показ КВИЗА ([Web2AppSdk.openQuizEmbedded]).
 *
 * У квиза нет результата-оплаты, поэтому это НЕ [PaywallResult]: наблюдаемое —
 * поток событий прохождения (он идёт в [Web2AppSdk.setFunnelEventListener]) плюс
 * факт закрытия экрана. Право SDK в квиз-режиме не поллит — за грантом идите в
 * [Web2AppSdk.entitlement] (или показывайте пейволл своим методом).
 */
sealed class QuizResult {
    /** Экран квиза закрылся. Почему — см. [reason]. */
    data class Closed(val reason: QuizCloseReason) : QuizResult()

    /**
     * Квиз НЕ был показан: SDK не сконфигурирован ([Web2AppSdk.configure]) либо
     * (0.7.2) показ сорвался — процесс страницы WebView упал два раза подряд или шесть раз за показ (выгрузка системой в фоне не считается).
     * Отличается от [Closed] — там экран показали. Паритет [PaywallResult.Unavailable].
     */
    object Unavailable : QuizResult()
}

/** Почему закрылся экран квиза. */
enum class QuizCloseReason {
    /** Страница попросила закрыть экран — событие `close` (её кнопка «Закрыть»). */
    PAGE,

    /** Юзер закрыл экран сам: нативный крестик или системный «назад». */
    USER,

    /**
     * Внутри ТОГО ЖЕ WebView прошла оплата (`paywall_result:success`) — квиз довёл
     * до пейволла, и показ закрылся на успехе. Грант подтверждайте
     * [Web2AppSdk.entitlement]: подтверждение оплаты доезжает вебхуком и может
     * отставать на несколько секунд.
     */
    PAID,
}
