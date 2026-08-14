package app.web2app.sdk

import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Удалённый журнал SDK (паритет iOS SdkLogger 0.6.0): каждый внутренний шаг
 * (configure / identify / резолв токена / чтение права / пейволл / события
 * моста / ошибки сети) пачками отправляется на наш бэкенд
 * `POST /public/sdk-logs`. Без него единственный способ понять, что происходит
 * внутри приложения интегратора, — просить его снять логи Logcat.
 *
 * Правила:
 *  - **Никогда не ломает SDK.** Отправка fire-and-forget; сбой сети — молча.
 *  - **PII не уходит.** Email не логируется (только факт «email передан»);
 *    guid — наш собственный client-held идентификатор.
 *  - До [attach] (вызывается из `configure`) записи копятся в буфере и уезжают
 *    после конфигурации.
 *  - Буфер ограничен (старые записи вытесняются), пачка ≤ 50 записей —
 *    зеркало серверного капа (`ArrayMaxSize(50)` в SdkLogBatchDto).
 *  - Каждая запись дублируется в Logcat (тег `Web2App`) — локальная
 *    видимость у интегратора тем же ходом.
 */
internal object SdkLogger {

    /** Версия SDK — уезжает в каждую пачку журнала. Синхронна с build.gradle.kts. */
    const val SDK_VERSION = "0.7.0"

    private const val TAG = "Web2App"

    /** Кап буфера: телеметрия не имеет права копить память без предела. */
    private const val MAX_BUFFERED_ENTRIES = 200

    /** Зеркало серверного `ArrayMaxSize(50)` — больше сервер отверг бы целиком. */
    private const val BATCH_LIMIT = 50

    /** Дебаунс отправки: соседние шаги склеиваются в одну пачку. */
    private const val FLUSH_DELAY_MS = 3_000L

    /**
     * Серийный исполнитель — единственный владелец мутируемого состояния ниже
     * (аналог серийной DispatchQueue в iOS-эталоне). Daemon: журнал не должен
     * удерживать процесс.
     */
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(
            ThreadFactory { r -> Thread(r, "web2app-sdk-logger").apply { isDaemon = true } },
        )

    // Всё ниже читается/пишется ТОЛЬКО с потока [executor].
    private var config: Web2AppConfig? = null
    private var guid: String? = null
    private var device: Map<String, String> = emptyMap()
    private val buffer = ArrayDeque<String>()
    private var flushScheduled = false

    /** Вызывается из [Web2AppSdk.configure] — с этого момента журнал может уезжать. */
    fun attach(config: Web2AppConfig, appContext: Context) {
        val device = collectDeviceInfo(appContext)
        executor.execute {
            this.config = config
            this.device = device
            scheduleFlushLocked()
        }
    }

    /** guid резолвлен/зачеканен — включается во все последующие пачки. */
    fun setGuid(guid: String) {
        executor.execute { this.guid = guid }
    }

    fun log(
        event: String,
        message: String = "",
        context: Map<String, String> = emptyMap(),
        level: String = "info",
    ) {
        append(event = event, message = message, context = context, level = level)
    }

    fun error(
        event: String,
        message: String = "",
        context: Map<String, String> = emptyMap(),
    ) = log(event, message, context, level = "error")

    // ---- буфер и отправка ----

    private fun append(event: String, message: String, context: Map<String, String>, level: String) {
        // Дубль в Logcat — тем же ходом, чтобы интегратор видел шаги локально.
        val line = event +
            (if (message.isEmpty()) "" else " — $message") +
            (if (context.isEmpty()) "" else " $context")
        when (level) {
            "error" -> Log.e(TAG, line)
            "warn" -> Log.w(TAG, line)
            else -> Log.d(TAG, line)
        }

        // Запись сериализуется сразу (ts = момент события, не отправки).
        val entry = SdkLogPayload.entryJson(
            ts = System.currentTimeMillis(),
            level = level,
            event = event,
            message = message,
            context = context,
        )
        executor.execute {
            buffer.addLast(entry)
            while (buffer.size > MAX_BUFFERED_ENTRIES) buffer.removeFirst()
            scheduleFlushLocked()
        }
    }

    /** Только с потока [executor]. Планирует одну отправку с дебаунсом. */
    private fun scheduleFlushLocked() {
        if (config == null || buffer.isEmpty() || flushScheduled) return
        flushScheduled = true
        executor.schedule({ flushLocked() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** Только с потока [executor]. Снимает пачку с буфера и шлёт fire-and-forget. */
    private fun flushLocked() {
        flushScheduled = false
        val cfg = config ?: return
        if (buffer.isEmpty()) return
        val batch = ArrayList<String>(minOf(buffer.size, BATCH_LIMIT))
        repeat(minOf(buffer.size, BATCH_LIMIT)) { batch.add(buffer.removeFirst()) }

        val body = SdkLogPayload.batchJson(
            projectId = cfg.projectId,
            sdkVersion = SDK_VERSION,
            guid = guid,
            device = device,
            entries = batch,
        )
        // Сбой отправки — молча: журнал вспомогательный, записи не возвращаем
        // в буфер (иначе мёртвая сеть раздувала бы его бесконечными ретраями).
        // Сеть — на отдельном daemon-потоке, чтобы не блокировать сериализатор.
        Http.io { Http.postJson("${cfg.baseUrl}/public/sdk-logs", body) }

        scheduleFlushLocked() // остались записи → следующая пачка
    }

    /**
     * Модель/ОС/версия приложения — контекст каждой пачки. Каждое поле —
     * best-effort: недоступное просто не попадает в пачку (журнал не имеет
     * права уронить configure).
     */
    private fun collectDeviceInfo(appContext: Context): Map<String, String> {
        val info = LinkedHashMap<String, String>()
        runCatching { Build.MODEL?.let { info["model"] = it } }
        runCatching { Build.VERSION.RELEASE?.let { info["os"] = "Android $it" } }
        runCatching {
            appContext.packageManager
                ?.getPackageInfo(appContext.packageName, 0)
                ?.versionName
                ?.let { info["appVersion"] = it }
        }
        runCatching { appContext.packageName?.let { info["bundleId"] = it } }
        return info
    }
}

/**
 * Чистая JVM-сборка тел журнала (без org.json — тот в JVM-юнитах Android
 * заглушка, ловушка L-4). Контракт тела — прод-бэк `SdkLogBatchDto`
 * (`POST /public/sdk-logs`), менять поля НЕЛЬЗЯ:
 * `{"projectId","platform":"android","sdkVersion","guid"?,
 *   "device":{"model","os","appVersion","bundleId"},
 *   "entries":[{"ts","level","event","message"?,"context"?}]}`.
 */
internal object SdkLogPayload {

    /** Кап имени события — зеркало iOS (`event.prefix(128)`). */
    private const val MAX_EVENT_LENGTH = 128

    /** Кап сообщения — зеркало iOS (`message.prefix(2000)`). */
    private const val MAX_MESSAGE_LENGTH = 2000

    /**
     * Одна запись журнала. `message`/`context` включаются только непустыми
     * (зеркало iOS-эталона); event и message обрезаются до капов.
     */
    fun entryJson(
        ts: Long,
        level: String,
        event: String,
        message: String,
        context: Map<String, String>,
    ): String = buildString {
        append("{\"ts\":").append(ts)
        append(",\"level\":\"").append(escape(level)).append('"')
        append(",\"event\":\"").append(escape(event.take(MAX_EVENT_LENGTH))).append('"')
        if (message.isNotEmpty()) {
            append(",\"message\":\"").append(escape(message.take(MAX_MESSAGE_LENGTH))).append('"')
        }
        if (context.isNotEmpty()) {
            append(",\"context\":").append(objectJson(context))
        }
        append('}')
    }

    /** Пачка журнала. [entries] — УЖЕ сериализованные [entryJson]-строки. */
    fun batchJson(
        projectId: String,
        sdkVersion: String,
        guid: String?,
        device: Map<String, String>,
        entries: List<String>,
    ): String = buildString {
        append("{\"projectId\":\"").append(escape(projectId)).append('"')
        append(",\"platform\":\"android\"")
        append(",\"sdkVersion\":\"").append(escape(sdkVersion)).append('"')
        if (guid != null) append(",\"guid\":\"").append(escape(guid)).append('"')
        append(",\"device\":").append(objectJson(device))
        append(",\"entries\":[").append(entries.joinToString(",")).append("]}")
    }

    /** Плоский JSON-объект из строковой map (ключи и значения экранируются). */
    fun objectJson(fields: Map<String, String>): String =
        fields.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"${escape(k)}\":\"${escape(v)}\""
        }

    /** JSON-экранирование строки: кавычки, бэкслеши, управляющие символы. */
    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
    }
}
