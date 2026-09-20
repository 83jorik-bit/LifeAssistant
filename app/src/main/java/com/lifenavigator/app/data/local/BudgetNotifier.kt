package com.lifenavigator.app.data.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Уведомление о превышении лимита категории бюджета.
 *
 * Включается пользователем в настройках (по умолчанию выключено — уведомления
 * без явного согласия раздражают и рискуют получить отказ при модерации магазина).
 * На Android 13+ дополнительно нужно системное разрешение POST_NOTIFICATIONS,
 * его отдельно запрашивает экран настроек при включении переключателя.
 */
object BudgetNotifier {

    private const val CHANNEL_ID = "budget_alerts"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Превышение бюджета",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Уведомление, когда траты по категории превышают заданный лимит"
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Показывает уведомление о превышении лимита. Молча ничего не делает, если
     * разрешения нет — вызывающий код (BudgetViewModel) сам решает, стоит ли
     * вообще пытаться, опираясь на настройку и текущее состояние разрешения.
     */
    fun notifyOverLimit(context: Context, categoryTitle: String, spentRub: Int, limitRub: Int) {
        if (!hasPermission(context)) return
        ensureChannel(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Превышен лимит: $categoryTitle")
            .setContentText("Потрачено $spentRub ₽ при лимите $limitRub ₽")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // catch: даже при проверке разрешения выше системный вызов теоретически может
        // бросить SecurityException (например, если разрешение отозвали на лету) —
        // уведомление в таком случае просто не важнее, чем сама трата.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
