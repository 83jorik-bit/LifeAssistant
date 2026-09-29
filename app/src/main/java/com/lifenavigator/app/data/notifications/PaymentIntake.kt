package com.lifenavigator.app.data.notifications

import android.content.Context
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.PendingExpenseStore
import com.lifenavigator.app.data.money.ExpenseRecorder
import java.time.LocalDate

/**
 * Единая точка приёма распознанных операций — и из пушей, и из SMS.
 *
 * Зачем отдельно: раньше в проекте жили две параллельные реализации, и одна из них
 * писала траты в обход очереди подтверждения. Теперь любой источник проходит
 * одни и те же правила, и расхождений между ними быть не может.
 */
object PaymentIntake {

    fun accept(context: Context, payment: ParsedPayment, source: String) {
        val settings = AppSettings(context)

        // Перевод ВСЕГДА идёт на вопрос, даже при включённом автодобавлении:
        // по тексту нельзя понять, оплата ли это услуги или деньги маме. Записать
        // перевод родственнику как расход на «продукты» — хуже, чем спросить.
        val needsConfirmation = payment.kind == PaymentKind.TRANSFER || !settings.bankNotificationsAutoAdd

        if (needsConfirmation) {
            PendingExpenseStore(context).add(
                amountRub = payment.amountRub,
                merchant = payment.merchant,
                category = payment.suggestedCategory,
                kind = payment.kind.name,
                cardTail = payment.cardTail,
                subscriptionService = payment.subscriptionService,
                source = source
            )
            return
        }

        ExpenseRecorder.record(
            context = context,
            amountRub = payment.amountRub,
            note = payment.subscriptionService ?: payment.merchant,
            date = LocalDate.now(),
            categoryKey = when {
                payment.kind == PaymentKind.SUBSCRIPTION -> SUBSCRIPTIONS_KEY
                payment.suggestedCategory != null -> payment.suggestedCategory.name
                else -> ExpenseRecorder.categoryKeyFor(payment.merchant)
            }
        )
    }

    /** Ключ категории для списаний за подписки — отдельная строка бюджета. */
    const val SUBSCRIPTIONS_KEY = "custom:subscriptions"

    /** Ключ для подтверждённых пользователем переводов «не за товары». */
    const val TRANSFERS_KEY = "custom:transfers"
}
