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

    suspend fun dispatch(context: Context, rawText: String): String? {
        val text = rawText.trim()
        val q = text.lowercase(Locale.ROOT)
        val taskStorage = TaskStorage(context)
        val calendarStorage = CalendarStorage(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        // Отложенные голосовые действия календаря и задач выполняются только после подтверждения.
        val actionPrefs = context.getSharedPreferences("voice_action_pending", Context.MODE_PRIVATE)
        val actionRaw = actionPrefs.getString("pending", null)
        val actionYes = q in setOf("да", "да, выполнить", "выполни", "подтверждаю", "подтверждаю действие", "сохрани", "добавь")
        val actionNo = q in setOf("нет", "отмена", "отмени", "не надо", "не выполняй", "отменить")
        if (actionRaw != null && actionYes) {
            val pending = runCatching { JSONObject(actionRaw) }.getOrNull()
                ?: run { actionPrefs.edit().remove("pending").apply(); return "Не удалось прочитать подготовленное действие. Ничего не изменено." }
            when (pending.optString("type")) {
                "calendar_event" -> {
                    val title = pending.optString("title")
                    val startsAt = pending.optLong("startsAt", 0L)
                    if (title.isBlank() || startsAt <= 0L) return "Не хватает данных события. Ничего не добавлено."
                    val event = calendarStorage.add(title, startsAt)
                    CalendarReminderScheduler.schedule(context, event)
                    val saved = calendarStorage.load().any { it.id == event.id }
                    actionPrefs.edit().remove("pending").apply()
                    return if (saved) "Подтверждено. Событие «${event.title}» действительно сохранено в календаре." else "Не удалось подтвердить сохранение события. Проверь календарь."
                }
                "task" -> {
                    val title = pending.optString("title")
                    if (title.isBlank()) return "Не указано название задачи. Ничего не добавлено."
                    val dueAt = pending.optLong("dueAt", 0L).takeIf { it > 0L }
                    val priority = if (pending.optString("priority") == "HIGH") TaskPriority.HIGH else TaskPriority.NORMAL
                    val task = taskStorage.add(title, dueAt = dueAt, reminderAt = dueAt, priority = priority)
                    if (dueAt != null) TaskReminderScheduler.schedule(context, task)
                    val saved = taskStorage.load().any { it.id == task.id }
                    actionPrefs.edit().remove("pending").apply()
                    return if (saved) "Подтверждено. Задача «${task.title}» действительно добавлена." else "Не удалось подтвердить сохранение задачи. Проверь список задач."
                }
            }
            actionPrefs.edit().remove("pending").apply()
            return "Подготовленное действие больше не поддерживается. Ничего не изменено."
        }
        if (actionRaw != null && actionNo) {
            actionPrefs.edit().remove("pending").apply()
            return "Отменено. Никакие изменения не внесены."
        }
        if (actionRaw != null) return "Я пока ничего не менял. Подготовлено действие. Скажи «подтверждаю» или «отмена»."

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
            val due = dueAt?.let { " Срок: ${Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"))}." }.orEmpty()
            actionPrefs.edit().putString("pending", JSONObject()
                .put("type", "task").put("title", title).put("dueAt", dueAt ?: 0L)
                .put("priority", priority.name).toString()).apply()
            return "Я понял задачу: «$title».$due Пока не добавляю её. Скажи «подтверждаю», чтобы сохранить, или «отмена»."
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
            actionPrefs.edit().putString("pending", JSONObject()
                .put("type", "calendar_event").put("title", title).put("startsAt", start).toString()).apply()
            return "Подготовил событие «$title» на ${date} в %02d:%02d. Пока не добавляю его. Скажи «подтверждаю», чтобы внести в календарь, или «отмена».".format(hour, minute)
        }

        // Голосовое управление картой использует последнюю явно определённую точку.
        // Фоновая геолокация не запрашивается: если координат нет, просим открыть карту.
        val mapSettings = com.lifenavigator.app.data.local.AppSettings(context)
        val mapCache = com.lifenavigator.app.data.local.PlacesCache(context)
        val routeCue = listOf("построй маршрут", "проложи маршрут", "как добраться", "проведи меня")
            .any(q::contains)
        val openPlaceCue = listOf("открой место", "покажи место", "открой организацию", "открой на карте")
            .any(q::contains)
        val searchPlaceCue = listOf("найди ", "найти ", "поищи ", "покажи рядом", "что рядом", "какие рядом")
            .any(q::contains)
        if (routeCue || openPlaceCue) {
            val places = mapCache.load()
            val query = text.lowercase(Locale.ROOT)
                .replace(Regex("(?i)построй маршрут|проложи маршрут|как добраться|проведи меня|открой место|покажи место|открой организацию|открой на карте|до"), "")
                .trim().trim(' ', ',', '«', '»', '"')
            val matches = places.filter { query.isNotBlank() &&
                (it.name.contains(query, true) || it.address.contains(query, true)) }
            if (matches.isEmpty()) return "Не нашёл это место в последнем поиске на карте. Сначала скажи «найди аптеку рядом» или обнови поиск на карте."
            if (matches.size > 1) return "Нашёл несколько мест: " + matches.take(5).joinToString("; ") { it.name } + ". Уточни название."
            val place = matches.single()
            if (openPlaceCue && !routeCue) {
                com.lifenavigator.app.ui.screens.map.YandexMapsLauncher.openPlaceCard(context, place)
                return "Открываю «${place.name}» в Яндекс Картах."
            }
            val lat = mapSettings.lastUserLatitude
            val lon = mapSettings.lastUserLongitude
            val from = if (lat.isNaN() || lon.isNaN()) null else com.yandex.mapkit.geometry.Point(lat, lon)
            if (from == null) return "Не знаю твоё текущее местоположение. Открой карту и нажми кнопку геолокации, затем повтори команду."
            com.lifenavigator.app.ui.screens.map.YandexMapsLauncher.buildRouteInNavigator(context, from, place)
            return "Строю маршрут до «${place.name}»."
        }
        if (searchPlaceCue) {
            val lat = mapSettings.lastUserLatitude
            val lon = mapSettings.lastUserLongitude
            if (lat.isNaN() || lon.isNaN()) return "Чтобы искать реальные места рядом, сначала открой карту и нажми кнопку геолокации."
            val normalized = q.replace("найди", "").replace("найти", "").replace("поищи", "")
            val category = when {
                listOf("аптек", "лекарств").any(normalized::contains) -> NeedCategory.PHARMACY
                listOf("продукт", "супермаркет", "магазин").any(normalized::contains) -> NeedCategory.GROCERY
                listOf("кафе", "ресторан", "кофейн").any(normalized::contains) -> NeedCategory.CAFE
                listOf("одежд", "обув").any(normalized::contains) -> NeedCategory.CLOTHING
                listOf("салон", "красот", "парикмах").any(normalized::contains) -> NeedCategory.BEAUTY
                listOf("пункт выдачи", "пвз", "постамат").any(normalized::contains) -> NeedCategory.PICKUP_POINT
                listOf("банкомат").any(normalized::contains) -> NeedCategory.ATM
                listOf("останов", "метро", "транспорт", "вокзал").any(normalized::contains) -> NeedCategory.TRANSPORT
                listOf("школ", "садик", "образован", "курс").any(normalized::contains) -> NeedCategory.EDUCATION
                listOf("клиник", "врач", "больниц", "поликлиник").any(normalized::contains) -> NeedCategory.CLINIC
                else -> null
            } ?: return "Уточни, что искать: аптеку, продукты, кафе, одежду, салон красоты, пункт выдачи, банкомат, транспорт, образование или клинику."
            val point = com.yandex.mapkit.geometry.Point(lat, lon)
            val radius = mapSettings.searchRadiusMeters
            val found = com.lifenavigator.app.data.repository.YandexSearchRepository.searchCategory(point, category, radius)
            if (found.isEmpty()) return "В радиусе ${(radius / 1000f)} км ничего не найдено в категории «${category.displayName}». Можешь увеличить радиус в настройках карты."
            val merged = (mapCache.load().filterNot { it.category == category } + found).distinctBy { it.id }
            mapCache.save(merged)
            return "Нашёл мест рядом: ${found.size}.\n" + found.take(8).joinToString("\n") {
                "• ${it.name} — ${"%.1f".format(Locale("ru", "RU"), it.distanceKm)} км" +
                    (if (it.rating > 0) ", рейтинг ${"%.1f".format(Locale("ru", "RU"), it.rating)}" else "") +
                    (if (it.workingHours != null) ", ${it.workingHours}" else "")
            } + "\nСкажи «построй маршрут до [название]» или «открой [название] на карте»."
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
            if (pending.optString("type") == "expense_delete") {
                val expenseId = pending.optString("expenseId")
                val expenses = storage.loadExpenses()
                if (expenses.none { it.id == expenseId }) {
                    budgetPrefs.edit().remove("pending").apply()
                    return "Эта трата уже отсутствует. Ничего не изменено."
                }
                storage.saveExpenses(expenses.filterNot { it.id == expenseId })
                budgetPrefs.edit().remove("pending").apply()
                return "Трата удалена из бюджета."
            }
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
        val deleteExpenseCue = listOf("удали расход", "удали трату", "убери расход", "убери трату").firstOrNull { q.startsWith(it) }
        if (deleteExpenseCue != null) {
            val amount = parseRubles(q) ?: return "Назови точную сумму удаляемой траты."
            val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
            val categoryKey = when {
                "продукт" in q || "магазин" in q || "еда" in q -> NeedCategory.GROCERY.name
                "кафе" in q || "ресторан" in q || "кофе" in q -> NeedCategory.CAFE.name
                "аптек" in q || "лекарств" in q -> NeedCategory.PHARMACY.name
                "одежд" in q || "обув" in q -> NeedCategory.CLOTHING.name
                "транспорт" in q || "такси" in q || "бензин" in q -> NeedCategory.TRANSPORT.name
                else -> storage.loadCustomCategories().firstOrNull { q.contains(it.title.lowercase(Locale.ROOT)) }?.key
            } ?: return "Уточни категорию удаляемой траты."
            val candidates = storage.loadExpenses().filter {
                it.amountRub == amount && it.categoryKey == categoryKey &&
                    YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == YearMonth.now()
            }
            if (candidates.isEmpty()) return "Не нашёл такую трату за текущий месяц. Ничего не удалено."
            if (candidates.size > 1) return "Нашёл несколько трат по ${amount} ₽ в категории «${categoryTitle(categoryKey)}». Уточни дату в формате ДД.ММ."
            val expense = candidates.single()
            budgetPrefs.edit().putString("pending", JSONObject()
                .put("type", "expense_delete").put("expenseId", expense.id).toString()).apply()
            return "Удалить трату ${amount} ₽ из категории «${categoryTitle(categoryKey)}»? Скажи «да» или «отмена»."
        }
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
