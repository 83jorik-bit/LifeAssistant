package com.lifenavigator.app.data.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lifenavigator.app.data.local.AppSettings

/**
 * Читает уведомления банковских приложений и превращает их в траты.
 *
 * Как это работает с точки зрения приватности — важное:
 * система отдаёт сервису ВЕСЬ поток уведомлений, отфильтровать его на своей стороне
 * она не умеет. Поэтому фильтр стоит первой же строкой обработчика: уведомления
 * не из списка банков отбрасываются сразу, их текст никуда не записывается
 * и никак не используется. Ни одно уведомление не покидает устройство.
 *
 * Разрешение на чтение уведомлений выдаётся вручную в системных настройках —
 * обычным запросом его получить нельзя, и это правильно: доступ серьёзный.
 */
class BankNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Фильтр по пакету — раньше любого чтения текста.
        if (sbn.packageName !in BankNotificationParser.BANK_PACKAGES) return

        val settings = AppSettings(applicationContext)
        if (!settings.bankNotificationsEnabled) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        val payment = BankNotificationParser.parse(sbn.packageName, title, text) ?: return
        PaymentIntake.accept(applicationContext, payment, source = "PUSH")
    }

    companion object {
        /**
         * Выдано ли разрешение на чтение уведомлений. Проверяется по системному
         * списку включённых слушателей — отдельного API «спросить разрешение» тут нет.
         */
        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return enabled.contains(context.packageName)
        }

        /** Открывает системный экран, где пользователь включает доступ вручную. */
        fun openAccessSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
