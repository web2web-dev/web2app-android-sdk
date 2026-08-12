package app.web2app.sdk

import android.os.Handler
import android.os.Looper

/**
 * Единая точка доставки результатов SDK интегратору (атом А-3, паритет iOS 0.4.1:
 * там каждый completion зовётся внутри `DispatchQueue.main.async` —
 * Web2AppSDK.swift:117-120, :190-193).
 *
 * Зачем: колбэки SDK рождаются на фоновых потоках ([Http.io] и поллинг гранта —
 * daemon-Thread), а интегратор из колбэка обновляет UI — это естественное
 * ожидание от SDK. Без прыжка на главный поток он ловит
 * `CalledFromWrongThreadException`.
 *
 * **Правило (одинаковое для ВСЕХ публичных методов [Web2AppSdk], включая ранние
 * синхронные возвраты):** если вызов уже на главном потоке — блок исполняется
 * СИНХРОННО, иначе — `Handler(Looper.getMainLooper()).post`. Синхронное
 * исполнение выбрано осознанно: лишний прыжок через очередь Looper'а сдвинул бы
 * колбэк на следующий кадр без всякой пользы. Предсказуемость сохраняется —
 * гарантия «колбэк всегда на главном потоке» держится в обеих ветках, а
 * порядок «сначала вернулся метод, потом пришёл колбэк» SDK не обещал никогда
 * (ранние возвраты и раньше звали колбэк синхронно).
 */
internal object MainThread {

    /**
     * Механика доставки. Подменяема (internal) — в JVM-юнитах `Looper`/`Handler`
     * из android.jar это заглушки, и без подмены нельзя ни посчитать проходы,
     * ни проверить «ровно один раз».
     *
     * NB: в JVM-юнитах (`unitTests.isReturnDefaultValues`) оба `Looper`-вызова
     * отдают null, сравнение истинно → блок исполняется синхронно, тесты видят
     * результат сразу. На живом Android `getMainLooper()` null не бывает, так
     * что ветка «оба null» там недостижима.
     */
    internal var delivery: (block: () -> Unit) -> Unit = { block ->
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else Handler(Looper.getMainLooper()).post(block)
    }

    /** Исполняет [block] на главном потоке. */
    fun post(block: () -> Unit) {
        delivery(block)
    }

    /**
     * Обёртка одноаргументного колбэка. Оборачивать РОВНО один раз — на входе
     * публичного метода; внутренние делегирования получают уже обёрнутый колбэк.
     */
    fun <T> wrap(callback: (T) -> Unit): (T) -> Unit = { value -> post { callback(value) } }

    /** Обёртка колбэка без аргументов (`onNeedEmail`). Правила те же. */
    fun wrapNoArgs(callback: () -> Unit): () -> Unit = { post { callback() } }
}
