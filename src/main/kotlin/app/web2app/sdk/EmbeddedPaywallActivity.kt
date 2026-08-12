package app.web2app.sdk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

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

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "web2appBridge")
            loadUrl(url)
        }

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

        val root = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
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
        // Активити умерла без события (система убила/back) → отдать null один раз.
        deliver(null)
        super.onDestroy()
    }

    private fun deliver(event: BridgeEvent?) {
        if (finishedWithEvent) return
        finishedWithEvent = true
        val id = intent.getStringExtra(EXTRA_CALLBACK_ID) ?: return
        EmbeddedPaywallCallbacks.deliver(id, event)
    }

    private inner class Bridge {
        /** Страница: `window.web2appBridge.postMessage(JSON.stringify({...}))`. */
        @JavascriptInterface
        fun postMessage(json: String?) {
            val event = BridgeEventParser.parse(json) ?: return
            runOnUiThread {
                deliver(event)
                finish()
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
    private val pending = mutableMapOf<String, (BridgeEvent?) -> Unit>()

    @Synchronized
    fun register(id: String, callback: (BridgeEvent?) -> Unit) {
        pending[id] = callback
    }

    @Synchronized
    fun deliver(id: String, event: BridgeEvent?) {
        pending.remove(id)?.invoke(event)
    }
}
