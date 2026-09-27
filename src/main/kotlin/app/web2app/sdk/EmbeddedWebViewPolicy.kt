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
}
