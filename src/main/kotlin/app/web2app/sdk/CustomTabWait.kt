package app.web2app.sdk

/**
 * WEB-2480 п.3 — ожидание оплаты в браузерной вкладке ([Web2AppSdk.openWebPaywall]).
 *
 * Раньше окно опроса (30 × 2 с) отсчитывалось от ОТКРЫТИЯ вкладки, и покупатель,
 * который дольше минуты вводил карту и проходил 3-D Secure, получал «не оплачено».
 * Теперь:
 *  - первое окно стартует при открытии вкладки (быстрая оплата ловится, как раньше);
 *  - каждое возвращение приложения на передний план ([onAppResumed]) начинает новое
 *    окно, прежнее прекращает опрос ([startPoll] получает «продолжать ли»);
 *  - действующая запись из любого окна или от возвратного метода ([offer]) отдаётся
 *    сразу; «не оплачено» (null) — только когда истекло окно, начатое ПОСЛЕ
 *    возвращения приложения (пока покупатель во вкладке, «нет» не отдаётся);
 *  - пока приложение снова не на экране ([onAppPaused]), «не оплачено» не отдаётся;
 *  - [deliver] зовётся ровно один раз.
 *
 * [lifecycleAvailable] = false (нет доступа к жизненному циклу приложения) — прежнее
 * поведение: окно от открытия вкладки.
 *
 * Чистая JVM — проверяемо юнит-тестами; подписка на жизненный цикл — в [Web2AppSdk].
 */
internal class CustomTabWait(
    private val startPoll: (
        shouldContinue: () -> Boolean,
        onDone: (EntitlementGrant?) -> Unit,
    ) -> Unit,
    private val deliver: (EntitlementGrant?) -> Unit,
    lifecycleAvailable: Boolean,
) {
    private var generation = 0
    private var resumedSinceLaunch = !lifecycleAvailable
    private var finished = false
    /** Приложение ушло с экрана (снова во вкладке) — «не оплачено» не отдаём. */
    private var appAway = false

    /** Ответ уже отдан — дальнейшие события игнорируются. */
    val isFinished: Boolean get() = synchronized(this) { finished }

    /** Вкладка открыта — первое окно. */
    fun start() = startWindow()

    /** Приложение вернулось на передний план — новое окно от этого момента. */
    fun onAppResumed() {
        synchronized(this) {
            if (finished) return
            resumedSinceLaunch = true
            appAway = false
        }
        startWindow()
    }

    /**
     * Доработка 4: приложение ушло с экрана (покупатель снова во вкладке) — окно на
     * паузе: его истечение не даёт «не оплачено», пока приложение не вернётся
     * (возвращение начинает новое окно). Действующая запись отдаётся и на паузе.
     */
    fun onAppPaused() {
        synchronized(this) {
            if (finished) return
            appAway = true
        }
    }

    /** Результат возвратного метода: действующая запись закрывает ожидание. */
    fun offer(grant: EntitlementGrant?) {
        if (grant != null && grant.isActive) finish(grant)
    }

    private fun startWindow() {
        val gen = synchronized(this) {
            if (finished) return
            ++generation
        }
        startPoll(
            { synchronized(this) { !finished && generation == gen } },
            { grant -> onWindowDone(gen, grant) },
        )
    }

    private fun onWindowDone(gen: Int, grant: EntitlementGrant?) {
        if (grant != null && grant.isActive) {
            finish(grant)
            return
        }
        val isCurrentAfterReturn = synchronized(this) {
            generation == gen && resumedSinceLaunch && !appAway
        }
        if (isCurrentAfterReturn) finish(null)
    }

    private fun finish(grant: EntitlementGrant?) {
        synchronized(this) {
            if (finished) return
            finished = true
        }
        deliver(grant)
    }
}
