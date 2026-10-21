package com.lifenavigator.app.data.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lifenavigator.app.data.local.AppSettings

/**
 * Читает уведомления банковских приложений и превращает их в траты.
 *
 * Приватность: система отдаёт сервису ВЕСЬ поток уведомлений, отфильтровать его
 * на своей стороне она не умеет. Поэтому фильтр по пакету стоит первой строкой:
 * уведомления не из списка банков отбрасываются до чтения текста. Ни одно
 * уведомление не покидает устройство.
 */
class BankNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = AppSettings(applicationContext)
        val isBank = sbn.packageName in BankNotificationParser.BANK_PACKAGES

        if (!isBank) {
            // Имя чужого приложения пишется в журнал только в режиме диагностики,
            // включённом вручную, — так можно найти свой банк, если его пакета нет
            // в списке. Текст не читается в любом случае.
            if (settings.captureDiagnostics) {
                CaptureLog.add(applicationContext, "PUSH", sbn.packageName, "не в списке банков")
            }
            return
        }

        if (!settings.bankNotificationsEnabled) {
            CaptureLog.add(applicationContext, "PUSH", sbn.packageName, "чтение уведомлений выключено в настройках")
            return
        }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        // Полный текст многие банки кладут в развёрнутую версию уведомления,
        // а EXTRA_TEXT содержит лишь обрезанную строку. Берём самое полное из доступного.
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it.toString() }
        val shortText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val text = listOfNotNull(bigText, lines, shortText).maxByOrNull { it.length }

        val payment = BankNotificationParser.parse(sbn.packageName, title, text)
        CaptureLog.add(applicationContext, "PUSH", sbn.packageName, CaptureLog.verdictOf(payment))
        payment ?: return
        PaymentIntake.accept(applicationContext, payment, source = "PUSH")
    }

    companion object {

        private fun component(context: Context) =
            ComponentName(context, BankNotificationListener::class.java)

        /**
         * Выдан ли доступ ИМЕННО этому сервису.
         *
         * Раньше проверялось лишь имя пакета — и это врало. Система выдаёт доступ
         * конкретному компоненту: когда слушатель переехал из data.money в
         * data.notifications, доступ остался у старого, несуществующего компонента,
         * а проверка по пакету продолжала показывать «включено».
         */
        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val ours = component(context)
            return enabled.split(":").any { entry ->
                ComponentName.unflattenFromString(entry) == ours
            }
        }

        /**
         * Просит систему заново подключить сервис. После обновления приложения
         * Android иногда не переподключает слушатель уведомлений сам — доступ выдан,
         * а уведомления не приходят. Вызывается при каждом запуске приложения.
         */
        fun requestRebindIfAllowed(context: Context) {
            if (!hasAccess(context)) return
            runCatching { requestRebind(component(context)) }
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
