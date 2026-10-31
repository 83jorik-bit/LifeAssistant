package com.lifenavigator.app.data.ai

import android.content.Context
import com.lifenavigator.app.data.local.CalendarReminderScheduler
import com.lifenavigator.app.data.local.CalendarStorage
import com.lifenavigator.app.data.local.TaskReminderScheduler
import com.lifenavigator.app.data.local.TaskStorage
import com.lifenavigator.app.data.model.TaskPriority
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import org.json.JSONObject
import java.time.YearMonth
import java.util.UUID
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
    private fun parseRubles(text: String): Int? {
        val normalized = text.lowercase(Locale.ROOT)
        val thousands = Regex("(\\d{1,3}(?:[ \\u00A0]\\d{3})*|\\d+)\\s*(тыс(?:яч(?:а|и)?)?\\.?|тысяч(?:а|и)?)").find(normalized)
        if (thousands != null) {
            val base = thousands.groupValues[1].replace(Regex("[ \\u00A0]"), "").toLongOrNull() ?: return null
            val rest = normalized.substring(thousands.range.last + 1)
            val extra = Regex("\\b(\\d{1,3})\\b").find(rest)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return (base * 1000L + extra).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
        }
        val words = Regex("\\b(одна|одну|один|две|два|три|четыре|пять|шесть|семь|восемь|девять|десять)\\s+тысяч(?:а|и)?\\b").find(normalized)
        if (words != null) {
            val base = when (words.groupValues[1]) {
                "одна", "одну", "один" -> 1; "две", "два" -> 2; "три" -> 3; "четыре" -> 4
                "пять" -> 5; "шесть" -> 6; "семь" -> 7; "восемь" -> 8; "девять" -> 9; else -> 10
            }
            val extra = Regex("\\b(\\d{1,3})\\b").find(normalized.substring(words.range.last + 1))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return base * 1000 + extra
        }
        val number = Regex("(?<!\\d)(\\d{1,3}(?:[ \\u00A0]\\d{3})+|\\d{1,9})(?!\\d)").find(normalized) ?: return null
        return number.groupValues[1].replace(Regex("[ \\u00A0]"), "").toIntOrNull()
    }

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

        // Pending budget mutations are persisted separately and require an explicit confirmation.
        val budgetPrefs = context.getSharedPreferences("voice_budget_pending", Context.MODE_PRIVATE)
        val pendingRaw = budgetPrefs.getString("pending", null)
        val confirmation = q in setOf("да", "да сохранить", "подтверждаю", "сохрани", "сохранить")
        val cancellation = q in setOf("нет", "отмена", "отмени", "не сохраняй", "отменить")
        fun categoryTitle(key: String): String =
            NeedCategory.entries.firstOrNull { it.name == key }?.displayName
                ?: com.lifenavigator.app.data.local.BudgetStorage(context).loadCustomCategories().firstOrNull { it.key == key }?.title
                ?: key
        if (pendingRaw != null && confirmation) {
            val pending = runCatching { JSONObject(pendingRaw) }.getOrNull()
                ?: run { budgetPrefs.edit().remove("pending").apply(); return "Не удалось прочитать операцию. Ничего не сохранено." }
            val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
            val amount = pending.optInt("amount", 0)
            if (amount <= 0) return "Некорректная сумма. Ничего не сохранено."
            when (pending.optString("type")) {
                "expense" -> {
                    val category = pending.optString("category")
                    if (category.isBlank()) return "Не указана категория. Ничего не сохранено."
                    val entries = storage.loadExpenses().toMutableList()
                    entries.add(Expense(UUID.randomUUID().toString(), category, amount, null, LocalDate.now(zone).toEpochDay()))
                    storage.saveExpenses(entries)
                    budgetPrefs.edit().remove("pending").apply()
                    return "Расход $amount ₽ сохранён: ${categoryTitle(category)}."
                }
                "income" -> {
                    storage.saveIncome(YearMonth.now(), amount)
                    budgetPrefs.edit().remove("pending").apply()
                    return "Доход $amount ₽ сохранён для текущего месяца."
                }
                "limit" -> {
                    val category = pending.optString("category")
                    if (category.isBlank()) return "Не указана категория лимита. Ничего не изменено."
                    storage.saveLimit(category, amount)
                    budgetPrefs.edit().remove("pending").apply()
                    return "Лимит «${categoryTitle(category)}» установлен: $amount ₽."
                }
            }
            return "Неизвестный тип операции. Ничего не сохранено."
        }
        if (pendingRaw != null && cancellation) {
            budgetPrefs.edit().remove("pending").apply()
            return "Операция отменена. Бюджет не изменён."
        }
        if (pendingRaw != null) return "Ожидаю подтверждение: скажи «да» или «отмена»."
        val expenseCue = listOf("добавь расход", "запиши расход", "учти расход").firstOrNull { q.startsWith(it) }
        val incomeCue = listOf("добавь доход", "запиши доход", "учти доход", "установи доход", "измени доход").firstOrNull { q.startsWith(it) }
        val limitCue = listOf("установи лимит", "задай лимит", "измени лимит", "поставь лимит").firstOrNull { q.startsWith(it) }
        if (expenseCue != null || incomeCue != null || limitCue != null) {
            val amount = parseRubles(q)
                ?: return "Не распознал сумму. Например: «Добавь расход 2 тысячи рублей на продукты» или «2 500 рублей»."
            if (amount <= 0) return "Сумма должна быть больше нуля."
            val isExpense = expenseCue != null
            val isLimit = limitCue != null
            val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
            val category = if (!isExpense && !isLimit) null else when {
                listOf("продукт", "магазин", "еда").any(q::contains) -> NeedCategory.GROCERY.name
                listOf("кафе", "ресторан", "кофе").any(q::contains) -> NeedCategory.CAFE.name
                listOf("аптек", "лекарств").any(q::contains) -> NeedCategory.PHARMACY.name
                listOf("одежд", "обув").any(q::contains) -> NeedCategory.CLOTHING.name
                listOf("транспорт", "такси", "проезд", "бензин").any(q::contains) -> NeedCategory.TRANSPORT.name
                listOf("красот", "салон").any(q::contains) -> NeedCategory.BEAUTY.name
                listOf("образован", "курс", "обучен").any(q::contains) -> NeedCategory.EDUCATION.name
                listOf("клиник", "врач", "медицин").any(q::contains) -> NeedCategory.CLINIC.name
                else -> storage.loadCustomCategories().firstOrNull { q.contains(it.title.lowercase(Locale.ROOT)) }?.key
            }
            if ((isExpense || isLimit) && category == null) return "Уточни категорию: продукты, кафе, аптека, одежда, транспорт, красота, образование или медицина. Ничего не изменено."
            val type = when { isExpense -> "expense"; isLimit -> "limit"; else -> "income" }
            val pending = JSONObject().put("type", type).put("amount", amount).put("category", category ?: "")
            if (type == "income") pending.put("previousAmount", storage.loadIncome()[YearMonth.now().toString()] ?: 0)
            if (type == "limit") pending.put("previousAmount", storage.loadLimits()[category] ?: 0)
            budgetPrefs.edit().putString("pending", pending.toString()).apply()
            return when (type) {
                "expense" -> "Распознал расход: $amount ₽, категория «${categoryTitle(category!!)}». Сохранить? Скажи «да» или «отмена»."
                "limit" -> {
                    val previous = pending.optInt("previousAmount", 0)
                    "Установить лимит «${categoryTitle(category!!)}» $amount ₽${if (previous > 0) " вместо текущего $previous ₽" else ""}? Скажи «да» или «отмена»."
                }
                else -> {
                    val previous = pending.optInt("previousAmount", 0)
                    "Установить общий доход за текущий месяц $amount ₽${if (previous > 0) " вместо текущего $previous ₽" else ""}? Скажи «да» или «отмена»."
                }
            }
        }
        // Чтение бюджета и сохранённых мест выполняет существующий локальный движок,
        // который обращается к реальным BudgetStorage и PlacesCache.
        val budgetOrPlacesCue = listOf(
            "сколько потратил", "сколько потратила", "мои расходы", "мои траты",
            "расходы за", "траты за", "бюджет", "лимит", "превышен", "перерасход",
            "сколько можно тратить", "остаток денег", "до конца месяца",
            "аптека", "аптеки", "магазин рядом", "магазины рядом", "кафе рядом",
            "рестораны рядом", "какие места рядом", "ближайшие места", "где купить"
        ).any(q::contains)
        if (budgetOrPlacesCue) return LocalAssistantEngine.reply(context, text)

        return null
    }
}
