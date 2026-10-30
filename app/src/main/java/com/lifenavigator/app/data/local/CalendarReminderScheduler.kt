package com.lifenavigator.app.data.local

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lifenavigator.app.MainActivity
import com.lifenavigator.app.data.model.CalendarEvent

object CalendarReminderScheduler {
    private const val CHANNEL_ID = "calendar_reminders"
    private const val EXTRA_ID = "calendar_event_id"
    private const val EXTRA_TITLE = "calendar_event_title"

    fun schedule(context: Context, event: CalendarEvent) {
        val at = event.reminderAtEpochMillis ?: return
        if (at <= System.currentTimeMillis()) return
        val intent = Intent(context, CalendarReminderReceiver::class.java)
            .putExtra(EXTRA_ID, event.id).putExtra(EXTRA_TITLE, event.title)
        val pending = PendingIntent.getBroadcast(context, event.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    fun cancel(context: Context, event: CalendarEvent) {
        val pending = PendingIntent.getBroadcast(context, event.id.hashCode(),
            Intent(context, CalendarReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
    }

    internal fun notify(context: Context, id: String, title: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Календарь", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Событие скоро начнётся")
            .setContentText(title).setContentIntent(open).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), notification) }
    }

    internal fun eventId(intent: Intent) = intent.getStringExtra(EXTRA_ID)
    internal fun eventTitle(intent: Intent) = intent.getStringExtra(EXTRA_TITLE)
}

class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = CalendarReminderScheduler.eventId(intent) ?: return
        val title = CalendarReminderScheduler.eventTitle(intent) ?: return
        val event = CalendarStorage(context).load().firstOrNull { it.id == id } ?: return
        CalendarReminderScheduler.notify(context, id, title)
    }
}
