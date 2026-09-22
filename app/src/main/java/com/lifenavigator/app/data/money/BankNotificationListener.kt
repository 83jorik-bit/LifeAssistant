package com.lifenavigator.app.data.money

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lifenavigator.app.data.local.AppSettings
import java.time.LocalDate

/**
 * Читает уведомления банковских приложений и записывает траты автоматически.
 *
 * Ограничения, заложенные намеренно:
 *
 * 1. Служба видит ВСЕ уведомления на устройстве — так устроен Android, выборочного
 *    доступа не существует. Поэтому сразу же отсекаем всё, что пришло не от банка
 *    из списка BankNotificationParser.BANK_PACKAGES, и дальше даже не читаем текст.
 * 2. Ничего, кроме суммы и названия магазина, не сохраняется. Сам текст уведомления
 *    не пишется ни в память, ни в логи.
 * 3. Работает только если пользователь включил это в настройках И выдал системное
 *    разрешение. Выключение настройки останавливает запись, даже если системное
 *    разрешение осталось выданным.
 */
class BankNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in BankNotificationParser.BANK_PACKAGES) return
        if (!AppSettings(applicationContext).autoExpensesEnabled) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val full = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ")
        if (full.isBlank()) return

        val payment = BankNotificationParser.parse(full) ?: return

        ExpenseRecorder.record(
            context = applicationContext,
            amountRub = payment.amountRub,
            note = payment.merchant ?: "Списание по карте",
            date = LocalDate.now(),
            categoryKey = ExpenseRecorder.categoryKeyFor(payment.merchant)
        )
    }
}
