package app.web2app.sdk

/**
 * Атом Б-2 — квиз-режим встроенного показа. Механика показа ПЕРЕИСПОЛЬЗУЕТСЯ целиком
 * ([EmbeddedPaywallActivity] + мост + реестр активного показа [EmbeddedPaywallPresentations]
 * + one-shot [EmbeddedPaywallCallbacks]): второго WebView-класса в SDK нет.
 *
 * Отличие квиза от пейволла живёт ровно здесь — в том, ЧТО делает зарегистрированный
 * колбэк показа. У пейволла это поллинг гранта → [PaywallResult]; у квиза оплаты нет,
 * поэтому событие закрытия сразу отображается в [QuizResult], без сетевых запросов.
 * Флага «режим» внутри Activity намеренно нет: её поведение в обоих режимах одинаково
 * (терминальность общая — см. [BridgeEventParser.terminalEvent]), а лишнее состояние
 * в Android-слое было бы непроверяемым юнитом.
 */
internal object QuizPresentation {
    /**
     * URL квиза собирается ТЕМ ЖЕ app-origin билдером, что и URL пейволла
     * ([WebPaywallLauncher.appOriginUrl]): `origin=app` + guid (+ email и profile-id
     * Adapty/RevenueCat, если есть), исходный query сохраняется, значения кодируются.
     *
     * guid тут не косметика: по нему веб связывает прохождение квиза с тем же
     * пользователем, что и последующую оплату. Отдельного билдера у квиза нет
     * намеренно — руками склеенный query потерял бы и то, и другое.
     */
    fun quizUrl(
        quizUrl: String,
        email: String?,
        guid: String,
        adaptyProfileId: String?,
        revenuecatProfileId: String?,
    ): String = WebPaywallLauncher.appOriginUrl(
        paywallUrl = quizUrl,
        email = email,
        guid = guid,
        adaptyProfileId = adaptyProfileId,
        revenuecatProfileId = revenuecatProfileId,
    )

    /**
     * Событие, закрывшее показ, → результат квиза. Чистая функция (юнит без Android).
     *
     * `null` = закрыли нативно (крестик/системный «назад»). `quiz_complete` сюда не
     * приходит вовсе — он не терминален, показ на нём НЕ закрывается (страница после
     * квиза часто сама ведёт на пейволл в том же WebView).
     */
    fun result(event: BridgeEvent?): QuizResult = when (event) {
        BridgeEvent.CLOSE -> QuizResult.Closed(QuizCloseReason.PAGE)
        BridgeEvent.PAYMENT_SUCCESS -> QuizResult.Closed(QuizCloseReason.PAID)
        null -> QuizResult.Closed(QuizCloseReason.USER)
    }

    /**
     * Регистрирует one-shot колбэк показа в квиз-режиме: тот же реестр, что у пейволла,
     * но без поллинга гранта — событие закрытия сразу превращается в [QuizResult].
     * [deliver] сюда приходит УЖЕ обёрнутым в [MainThread] (обёртка ровно одна, на входе
     * публичного метода).
     */
    fun registerCloseCallback(callbackId: String, deliver: (QuizResult) -> Unit) {
        // 0.7.2: показ сорвался (процесс страницы упал два раза подряд) — квиз не показан.
        EmbeddedPaywallCallbacks.register(
            callbackId,
            onUnavailable = { deliver(QuizResult.Unavailable) },
        ) { event -> deliver(result(event)) }
    }
}
