package app.web2app.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresApi

/**
 * WEB-814 — встроенный показ веб-пейволла (паритет iOS openWebPaywallEmbedded
 * 0.4.0-0.4.3): full-screen WebView + JS-мост `web2appBridge`.
 *
 * На успех оплаты страница шлёт событие мосту → пейволл закрывается
 * АВТОМАТИЧЕСКИ, юзеру не нужно жать «Закрыть». Кнопка «Закрыть» страницы тоже
 * идёт мостом; нативный крестик (frosted-стиль, как iOS 0.4.3) — фолбэк.
 *
 * Коллбэк отдаётся РОВНО один раз: событие моста либо null (юзер закрыл
 * нативно — крестиком или системным back).
 *
 * А-4: показ регистрируется в [EmbeddedPaywallPresentations] — новый показ
 * вытесняет предыдущий (стопки пейволлов не будет), вытесненный доигрывает
 * свой обычный путь и отдаёт колбэк из [onDestroy].
 */
internal class EmbeddedPaywallActivity : Activity(), EmbeddedPaywallPresentation {
    private var finishedWithEvent = false

    /** Адрес показа — нужен, чтобы загрузить страницу заново в новом WebView. */
    private lateinit var pageUrl: String

    /** Контейнер экрана: WebView — нижний слой, над ним индикатор, сверху крестик. */
    private lateinit var root: FrameLayout

    /**
     * 0.7.2 — системный индикатор загрузки по центру вместо белого экрана. Виден
     * до `onPageFinished` (или до ошибки главного кадра). Текстов нет — только
     * системный элемент, цвет системный.
     */
    private lateinit var loadingIndicator: ProgressBar

    /** Текущий WebView (после гибели процесса страницы — уже новый). */
    private var webView: WebView? = null

    /**
     * 0.7.2: сколько НАСТОЯЩИХ падений процесса страницы было подряд (выгрузка
     * системой не считается). Сбрасывается, когда главный кадр догрузился без
     * ошибки.
     */
    private var renderProcessCrashesInARow = 0

    /**
     * 0.7.2: сколько настоящих падений процесса страницы было за весь показ (за
     * жизнь Activity). Не сбрасывается никогда — потолок
     * [EmbeddedWebViewPolicy.MAX_RENDER_CRASHES_PER_SHOW].
     */
    private var renderProcessCrashesTotal = 0

    /**
     * Была ли ошибка главного кадра у текущей загрузки (`onReceivedError`); читается
     * в `onPageFinished` — сбрасывать ли счётчик «подряд». Где флаг снимается и
     * почему не в `onPageStarted` — см. [MainFrameLoadTracker].
     */
    private val mainFrameLoad = MainFrameLoadTracker()

    /** Экран на переднем плане (между `onResume` и `onPause`). */
    private var isInForeground = false

    /**
     * Процесс страницы погиб, пока экран был не на переднем плане: новый WebView
     * уже стоит, адрес загрузится в [onResume].
     */
    private var reloadOnResume = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            deliver(null)
            finish()
            return
        }

        // Показ состоится → он и есть активный; предыдущий (если был) вытесняется.
        EmbeddedPaywallPresentations.setActive(this)

        pageUrl = url

        // Нативный крестик-фолбэк: полупрозрачная подложка (frosted, iOS 0.4.3).
        val density = resources.displayMetrics.density
        val closeButton = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F1F1F"))
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#CCFFFFFF"))
            }
            elevation = 6 * density
            setOnClickListener {
                deliver(null)
                finish()
            }
        }

        loadingIndicator = ProgressBar(this).apply { isIndeterminate = true }
        root = FrameLayout(this).apply {
            addView(
                loadingIndicator,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            val size = (36 * density).toInt()
            val margin = (16 * density).toInt()
            addView(
                closeButton,
                FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                    topMargin = margin
                    rightMargin = margin
                },
            )
        }
        setContentView(root)
        attachNewWebView()
    }

    /**
     * Создаёт WebView, кладёт его НИЖНИМ слоем (крестик остаётся поверх) и, если
     * [loadNow], грузит [pageUrl]. Зовётся при старте показа и после гибели процесса
     * страницы (тогда при экране в фоне загрузка откладывается до [onResume]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun attachNewWebView(loadNow: Boolean = true) {
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = PageClient()
            addJavascriptInterface(Bridge(), "web2appBridge")
        }
        webView = view
        mainFrameLoad.onLoadStartedBySdk()
        root.addView(
            view,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        loadingIndicator.visibility = View.VISIBLE
        if (loadNow) view.loadUrl(pageUrl)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        if (reloadOnResume) {
            reloadOnResume = false
            webView?.let {
                mainFrameLoad.onLoadStartedBySdk()
                it.loadUrl(pageUrl)
            }
        }
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    /**
     * 0.7.2 — гибель процесса страницы (рендерера Chromium). Без обработки система
     * роняет ВСЁ приложение интегратора. Погибший WebView больше не годен: убираем
     * его из иерархии и уничтожаем, сразу ставим новый. Настоящее падение
     * (`didCrash`) идёт в лимиты: второе подряд или шестое за показ — закрываем
     * показ с результатом «недоступно». Выгрузка системой ради памяти (`didCrash=false`, обычно в фоне,
     * пока человек платит в банке) — не срыв, в лимит не идёт. Экран в фоне —
     * страница грузится при возврате на экран, а не сразу.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) {
        val didCrash = detail.didCrash()
        SdkLogger.log(
            if (didCrash) "paywall.webview_process_terminated" else "paywall.webview_process_reclaimed",
            level = if (didCrash) "error" else "info",
            context = mapOf(
                "didCrash" to didCrash.toString(),
                "rendererPriorityAtExit" to
                    EmbeddedWebViewPolicy.rendererPriorityName(detail.rendererPriorityAtExit()),
                "inForeground" to isInForeground.toString(),
            ),
        )
        root.removeView(view)
        view.destroy()
        // Погиб не текущий WebView (старый, уже заменённый) — текущий жив, новый не
        // создаём и в лимиты не считаем.
        if (view !== webView) return
        webView = null
        // Экран уже закрывается — пересоздавать нечего, колбэк отдаст onDestroy.
        if (isFinishing || isDestroyed) return

        val decision = EmbeddedWebViewPolicy.renderProcessGoneDecision(
            didCrash = didCrash,
            isResumed = isInForeground,
            crashesInARow = renderProcessCrashesInARow,
            totalCrashes = renderProcessCrashesTotal,
        )
        renderProcessCrashesInARow = decision.crashesInARow
        renderProcessCrashesTotal = decision.totalCrashes
        when (decision.action) {
            RenderProcessGoneAction.RELOAD_NOW -> {
                reloadOnResume = false
                attachNewWebView(loadNow = true)
            }
            RenderProcessGoneAction.RELOAD_ON_RESUME -> {
                reloadOnResume = true
                attachNewWebView(loadNow = false)
            }
            RenderProcessGoneAction.GIVE_UP -> {
                if (decision.giveUpReason == GiveUpReason.CRASHES_PER_SHOW) {
                    SdkLogger.error(
                        "paywall.webview_process_crash_limit",
                        context = mapOf("total" to decision.totalCrashes.toString()),
                    )
                } else {
                    SdkLogger.error("paywall.webview_process_terminated_twice")
                }
                deliverUnavailable()
                finish()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        deliver(null)
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /**
     * Вытеснение новым показом ([EmbeddedPaywallPresentations]): просто закрываем
     * окно. Результат отдаст [onDestroy] обычным путём — ровно один раз, гард
     * [finishedWithEvent] + one-shot [EmbeddedPaywallCallbacks].
     */
    override fun dismiss() {
        finish()
    }

    override fun onDestroy() {
        // Слабую ссылку чистим сами — по идентичности, чтобы не снести новый показ.
        EmbeddedPaywallPresentations.clear(this)
        if (EmbeddedWebViewPolicy.shouldDeliverCloseOnDestroy(isChangingConfigurations, isFinishing)) {
            // Активити умерла без события (система убила/back) → отдать null один раз.
            deliver(null)
        } else {
            // 0.7.2: пересоздание из-за смены конфигурации — не закрытие. Новая
            // Activity поднимется с тем же Intent (тот же callbackId) и доиграет показ.
            SdkLogger.log("paywall.recreated_on_config_change")
        }
        super.onDestroy()
    }

    private fun deliver(event: BridgeEvent?) {
        if (finishedWithEvent) return
        finishedWithEvent = true
        val id = intent.getStringExtra(EXTRA_CALLBACK_ID) ?: return
        EmbeddedPaywallCallbacks.deliver(id, event)
    }

    /** Показ сорвался — «недоступно». Тот же one-shot гард, что у [deliver]. */
    private fun deliverUnavailable() {
        if (finishedWithEvent) return
        finishedWithEvent = true
        val id = intent.getStringExtra(EXTRA_CALLBACK_ID) ?: return
        EmbeddedPaywallCallbacks.deliverUnavailable(id)
    }

    private inner class PageClient : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView) return
            loadingIndicator.visibility = View.GONE
            // Страница догрузилась без ошибки главного кадра — прежние падения
            // больше не «подряд». После ошибки onPageFinished тоже приходит, но
            // страницы нет — счётчик не трогаем. Общий счётчик за показ — никогда.
            // Флаг ошибки снимается здесь же, после решения (MainFrameLoadTracker).
            if (mainFrameLoad.onPageFinished()) {
                renderProcessCrashesInARow = 0
            }
        }

        /**
         * Ошибка главного кадра (нет сети, DNS, таймаут): прячем индикатор и пишем
         * в журнал код ошибки. Сообщения человеку нет (в SDK надписей не заводим) —
         * остаётся крестик. Адрес и описание ошибки не пишем: в адресе email.
         */
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView) return
            if (!EmbeddedWebViewPolicy.reactsToLoadError(request.isForMainFrame)) return
            mainFrameLoad.onMainFrameError()
            loadingIndicator.visibility = View.GONE
            SdkLogger.error(
                "paywall.webview_load_failed",
                context = mapOf("errorCode" to error.errorCode.toString()),
            )
        }

        /**
         * true = «гибель обработана, приложение не ронять». Вызывается системой
         * только с API 26 (minSdk 24): на 24-25 метода у WebViewClient нет, там
         * поведение прежнее.
         */
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            this@EmbeddedPaywallActivity.onRenderProcessGone(view, detail)
            return true
        }
    }

    private inner class Bridge {
        /**
         * Страница: `window.web2appBridge.postMessage(JSON.stringify({...}))`.
         *
         * Б-1: маршрут один — [BridgeMessageRouter]. Событие уходит слушателю
         * интегратора, а окно закрывается ТОЛЬКО на терминальном (их ровно два).
         * До Б-1 закрывалось любое распознанное событие — с событиями квиза это
         * схлопнуло бы воронку на первом же экране.
         */
        @JavascriptInterface
        fun postMessage(json: String?) {
            BridgeMessageRouter.route(json) { event ->
                runOnUiThread {
                    deliver(event)
                    finish()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_URL = "app.web2app.sdk.EXTRA_URL"
        private const val EXTRA_CALLBACK_ID = "app.web2app.sdk.EXTRA_CALLBACK_ID"

        fun start(context: Context, url: String, callbackId: String) {
            context.startActivity(
                Intent(context, EmbeddedPaywallActivity::class.java).apply {
                    putExtra(EXTRA_URL, url)
                    putExtra(EXTRA_CALLBACK_ID, callbackId)
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}

/**
 * Реестр one-shot коллбэков embedded-показа: активити живёт своим жизненным
 * циклом, лямбду в Intent не положить — передаём id, лямбда ждёт здесь.
 */
internal object EmbeddedPaywallCallbacks {
    private class Pending(
        val onClose: (BridgeEvent?) -> Unit,
        val onUnavailable: (() -> Unit)?,
    )

    private val pending = mutableMapOf<String, Pending>()

    /**
     * [onUnavailable] — 0.7.2: показ сорвался (процесс страницы упал два раза подряд
     * или шесть раз за показ).
     * Не задан → такой срыв отдаётся как обычное нативное закрытие (`null`).
     */
    @Synchronized
    fun register(
        id: String,
        onUnavailable: (() -> Unit)? = null,
        callback: (BridgeEvent?) -> Unit,
    ) {
        pending[id] = Pending(callback, onUnavailable)
    }

    fun deliver(id: String, event: BridgeEvent?) {
        take(id)?.onClose?.invoke(event)
    }

    /** Показ сорвался. One-shot общий с [deliver]: что пришло первым, то и отдано. */
    fun deliverUnavailable(id: String) {
        val entry = take(id) ?: return
        val onUnavailable = entry.onUnavailable
        if (onUnavailable != null) onUnavailable() else entry.onClose(null)
    }

    // Колбэк зовётся ВНЕ замка — как и раньше, достаётся под замком ровно один раз.
    @Synchronized
    private fun take(id: String): Pending? = pending.remove(id)
}
