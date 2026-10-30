package com.lifenavigator.app.data.ai

import android.content.Context
import com.lifenavigator.app.data.local.CalendarReminderScheduler
import com.lifenavigator.app.data.local.CalendarStorage
import com.lifenavigator.app.data.local.TaskReminderScheduler
import com.lifenavigator.app.data.local.TaskStorage
import com.lifenavigator.app.data.model.TaskPriority
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Единственная точка маршрутизации голосовых/текстовых команд к функциям приложения.
 * Возвращает null только тогда, когда команда не относится к поддерживаемым действиям.
 * Ответы об изменениях формируются здесь только после успешной записи в хранилище.
 */
object VoiceCommandDispatcher {
    fun dispatch(context: Context, rawText: String): String? {
        val text = rawText.trim()
        val q = text.lowercase(Locale.ROOT)
        val taskStorage = TaskStorage(context)
        val calendarStorage = CalendarStorage(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val listTasks = listOf("мои задачи", "список задач", "покажи задачи", "какие задачи", "что по задачам", "что мне нужно сделать", "что осталось сделать", "план на день", "план на сегодня", "есть ли просроченные").any(q::contains)
        if (listTasks) {
            val tasks = taskStorage.load().filterNot { it.isCompleted }
            if (tasks.isEmpty()) return "Активных задач нет."
            val now = System.currentTimeMillis()
            val overdue = tasks.filter { it.dueAtEpochMillis != null && it.dueAtEpochMillis < now }
            val lines = tasks.sortedWith(compareBy({ it.dueAtEpochMillis ?: Long.MAX_VALUE }, { it.createdAtEpochMillis }))
                .take(20).joinToString("\n") { task ->
                    val due = task.dueAtEpochMillis?.let { " — срок " + Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM HH:mm")) }.orEmpty()
                    val priority = if (task.priority == TaskPriority.HIGH) "Важно: " else ""
                    "• $priority${task.title}$due"
                }
            return "Активных задач: ${tasks.size}. Просрочено: ${overdue.size}.\n$lines"
        }

        val createTaskCue = listOf("добавь задачу", "создай задачу", "запиши задачу", "напомни мне", "напомни", "не забудь", "поставь задачу", "добавь в задачи").firstOrNull { q.startsWith(it) }
        if (createTaskCue != null) {
            var title = text.substring(createTaskCue.length).trim().trimStart(':', ',', '-', ' ')
            val date = when {
                "послезавтра" in q -> today.plusDays(2)
                "завтра" in q -> today.plusDays(1)
                "сегодня" in q -> today
                else -> null
            }
            title = title.replace(Regex("(?i)\\b(сегодня|завтра|послезавтра)\\b"), "")
                .replace(Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b"), "").trim().trim(',', '-', ' ')
            if (title.isBlank()) return "Что именно добавить в задачи?"
            val time = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(q)
            val dueAt = date?.atTime(time?.groupValues?.get(1)?.toIntOrNull() ?: 9, time?.groupValues?.get(2)?.toIntOrNull() ?: 0)
                ?.atZone(zone)?.toInstant()?.toEpochMilli()
            val priority = if (listOf("срочно", "важно", "обязательно").any(q::contains)) TaskPriority.HIGH else TaskPriority.NORMAL
            val task = taskStorage.add(title, dueAt = dueAt, reminderAt = dueAt, priority = priority)
            if (dueAt != null) TaskReminderScheduler.schedule(context, task)
            val due = dueAt?.let { " Срок: ${Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"))}." }.orEmpty()
            return "Задача «${task.title}» сохранена.$due"
        }

        val completeCue = listOf("отметь выполненной задачу", "отметь выполненной", "заверши задачу", "выполни задачу", "закрой задачу").firstOrNull { q.startsWith(it) }
        if (completeCue != null) {
            val titleQuery = text.substring(completeCue.length).trim().trimStart(':', ',', '-', ' ', '«', '»', '"')
            if (titleQuery.isBlank()) return "Назови задачу, которую нужно отметить выполненной."
            val matches = taskStorage.load().filter { !it.isCompleted && it.title.contains(titleQuery, ignoreCase = true) }
            if (matches.isEmpty()) return "Не нашёл активную задачу «$titleQuery». Ничего не изменено."
            if (matches.size > 1) return "Нашёл несколько похожих задач: " + matches.joinToString("; ") { it.title } + ". Уточни название."
            val task = matches.single()
            taskStorage.update(task.copy(isCompleted = true))
            TaskReminderScheduler.cancel(context, task)
            return "Готово. Задача «${task.title}» отмечена выполненной."
        }

        val listCalendarCue = listOf("календарь", "расписание", "мои события", "что запланировано", "какие события").any(q::contains)
        val createEventCue = listOf("добавь событие", "создай событие", "запланируй", "внеси в календарь").any(q::contains)
        if (listCalendarCue && !createEventCue) {
            val date = when {
                "послезавтра" in q -> today.plusDays(2)
                "завтра" in q -> today.plusDays(1)
                "недел" in q || "7 дней" in q -> null
                else -> today
            }
            val events = calendarStorage.load().filter { event ->
                val eventDate = Instant.ofEpochMilli(event.startsAtEpochMillis).atZone(zone).toLocalDate()
                if (date == null) !eventDate.isBefore(today) && !eventDate.isAfter(today.plusDays(7)) else eventDate == date
            }
            val label = date?.toString() ?: "ближайшие 7 дней"
            if (events.isEmpty()) return "В календаре на $label событий нет."
            val lines = events.joinToString("\n") { event ->
                "• ${Instant.ofEpochMilli(event.startsAtEpochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))} — ${event.title}"
            }
            return "События на $label:\n$lines"
        }

        if (createEventCue) {
            val date = when {
                "послезавтра" in q -> today.plusDays(2)
                "завтра" in q -> today.plusDays(1)
                else -> today
            }
            val time = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b").find(q)
                ?: return "На какое время поставить событие? Назови время, например 14:30. Пока ничего не добавлено."
            val hour = time.groupValues[1].toInt()
            val minute = time.groupValues[2].toInt()
            val title = text.replace(Regex("(?i)добавь событие|создай событие|запланируй|внеси в календарь"), "")
                .replace(Regex("(?i)\\b(сегодня|завтра|послезавтра)\\b"), "")
                .replace(time.value, "").trim().trim(',', ':', '-', ' ')
            if (title.isBlank()) return "Как назвать событие?"
            val start = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
            val event = calendarStorage.add(title, start)
            CalendarReminderScheduler.schedule(context, event)
            return "Событие «${event.title}» сохранено в календаре на ${date} в %02d:%02d. Напоминание — за 15 минут.".format(hour, minute)
        }

        return null
    }
}
