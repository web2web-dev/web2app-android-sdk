package app.web2app.sdk

/**
 * WEB-814 — типизированный результат показа веб-пейволла (паритет iOS
 * PaywallResult 0.4.0). Заменяет неоднозначный `EntitlementGrant?` (null не
 * отличал «не оплатил» от «оплатил, но подтверждение не успело доехать»).
 */
sealed class PaywallResult {
    /** Оплата подтверждена — активный грант в руках, открывайте платный флоу. */
    data class Paid(val grant: EntitlementGrant) : PaywallResult()

    /** Пользователь закрыл пейволл, активного гранта нет — бесплатный тариф. */
    object NotPaid : PaywallResult()

    /**
     * Окно ожидания истекло без подтверждения (медленный вебхук/сеть).
     * Доступ может появиться позже — перепроверьте `Web2AppSdk.entitlement`.
     */
    object Pending : PaywallResult()
}
