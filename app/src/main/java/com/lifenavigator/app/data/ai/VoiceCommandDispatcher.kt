package com.lifenavigator.app.data.ai

import android.content.Context
import com.lifenavigator.app.data.local.CalendarReminderScheduler
import com.lifenavigator.app.data.local.CalendarStorage
import com.lifenavigator.app.data.local.TaskReminderScheduler
import com.lifenavigator.app.data.local.TaskStorage
import com.lifenavigator.app.data.model.TaskPriority
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import java.time.YearMonth
import java.util.UUID
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.lifenavigator.app.data.execution.ActionId
import com.lifenavigator.app.data.execution.ActionParameterValue
import com.lifenavigator.app.data.execution.ConfirmationManager
import com.lifenavigator.app.data.execution.OperationManagerResult
import com.lifenavigator.app.data.execution.OperationStatus
import com.lifenavigator.app.data.execution.PendingOperation
import com.lifenavigator.app.data.execution.SessionId
import com.lifenavigator.app.data.execution.SharedPreferencesOperationStateStore

/**
 * Единственная точка маршрутизации голосовых/текстовых команд к функциям приложения.
 * Возвращает null только тогда, когда команда не относится к поддерживаемым действиям.
 * Ответы об изменениях формируются здесь только после успешной записи в хранилище.
 */
object VoiceCommandDispatcher {

    private const val SESSION_ID = SharedPreferencesOperationStateStore.DEFAULT_SESSION_ID

    private data class DomainExecutionOutcome(
        val success: Boolean,
        val message: String,
        val entityId: String? = null
    )

    private fun confirmationManager(context: Context) =
        ConfirmationManager(SharedPreferencesOperationStateStore(context))

    suspend fun dispatchResult(context: Context, rawText: String): AssistantActionResult {
        return try {
            val manager = confirmationManager(context)
            val message = dispatchInternal(context, rawText, manager)
                ?: return AssistantActionResult.NotHandled
            val active = manager.activeOperation()
            when {
                active?.status == OperationStatus.RESULT_UNKNOWN ->
                    AssistantActionResult.Failed(message)
                active != null && isConfirmationPrompt(message) ->
                    AssistantActionResult.NeedsConfirmation(message, active.request.operationId.value)
                listOf(
                    "что именно", "какое событие", "назови задачу", "назови категорию",
                    "уточни", "неоднознач", "не нашёл задачу", "не нашел задачу",
                    "не удалось определить"
                ).any { message.contains(it, ignoreCase = true) } ->
                    AssistantActionResult.NeedsClarification(message)
                listOf(
                    "не удалось", "ошибка", "ничего не добавлено", "ничего не изменено",
                    "не сохранено", "не выполнено", "не повторяю"
                ).any { message.contains(it, ignoreCase = true) } ->
                    AssistantActionResult.Failed(message)
                else -> AssistantActionResult.Completed(message)
            }
        } catch (error: Exception) {
            AssistantActionResult.Failed(
                "Не удалось выполнить действие: ${error.message ?: "неизвестная ошибка"}. Изменение данных не подтверждено."
            )
        }
    }

    suspend fun dispatch(context: Context, rawText: String): String? =
        dispatchInternal(context, rawText, confirmationManager(context))

    private fun dispatchInternal(
        context: Context,
        rawText: String,
        manager: ConfirmationManager
    ): String? {
        val text = rawText.trim()
        val q = text.lowercase(Locale.ROOT)
        val taskStorage = TaskStorage(context)
        val calendarStorage = CalendarStorage(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val activeOperation = try {
            manager.activeOperation()
        } catch (error: Exception) {
            return "Не удалось безопасно восстановить ожидающее действие. Ничего не изменено. Обрати внимание: ${error.message ?: "ошибка состояния"}."
        }

        if (activeOperation != null) {
            val yes = q in setOf(
                "да", "да, выполнить", "да сохранить", "выполни", "подтверждаю",
                "подтверждаю действие", "сохрани", "сохранить"
            )
            val no = q in setOf(
                "нет", "отмена", "отмени", "не надо", "не выполняй",
                "отменить", "не сохраняй"
            )

            if (activeOperation.status == OperationStatus.RESULT_UNKNOWN) {
                return "Предыдущее действие было прервано во время выполнения. Я не буду повторять его автоматически: сначала необходимо установить, сохранились ли данные."
            }
            if (activeOperation.status == OperationStatus.EXECUTING) {
                return "Действие уже передано на выполнение. Повторный запуск заблокирован."
            }
            if (no) {
                return when (manager.cancel(
                    activeOperation.request.sessionId,
                    activeOperation.request.operationId
                )) {
                    is OperationManagerResult.Success -> "Операция отменена. Изменения не вносились."
                    else -> "Не удалось зафиксировать отмену операции. Ничего нового не выполнялось."
                }
            }
            if (yes) return executeConfirmedOperation(context, manager, activeOperation)
            return "Есть одно подготовленное действие: ${activeOperation.confirmationPrompt} Скажи «подтверждаю» или «отмена»."
        }

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

        val createTaskCue = listOf("добавь задачу", "создай задачу", "запиши задачу", "напомни мне", "напомни", "не забудь", "поставь задачу", "добавь в задачи", "надо сделать", "нужно сделать", "мне нужно", "мне надо").firstOrNull { q.startsWith(it) }
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
            val parameters = linkedMapOf<String, ActionParameterValue>(
                "title" to ActionParameterValue.Text(title),
                "priority" to ActionParameterValue.Text(priority.name)
            )
            dueAt?.let {
                parameters["dueAtEpochMillis"] = ActionParameterValue.Integer(it)
                parameters["reminderAtEpochMillis"] = ActionParameterValue.Integer(it)
            }
            return prepareOperation(
                manager,
                ActionId("task.create"),
                parameters,
                "Я понял задачу: «$title».$due Пока не добавляю её. Скажи «подтверждаю», чтобы сохранить, или «отмена»."
            )
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
            return prepareOperation(
                manager,
                ActionId("calendar.event.create"),
                mapOf(
                    "title" to ActionParameterValue.Text(title),
                    "startsAtEpochMillis" to ActionParameterValue.Integer(start)
                ),
                "Подготовил событие «$title» на ${date} в %02d:%02d. Пока не добавляю его. Скажи «подтверждаю», чтобы внести в календарь, или «отмена».".format(hour, minute)
            )
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

        val expenseCue = listOf("добавь расход", "запиши расход", "учти расход").firstOrNull { q.startsWith(it) }
        val deleteExpenseCue = listOf("удали расход", "удали трату", "убери расход", "убери трату").firstOrNull { q.startsWith(it) }
        if (deleteExpenseCue != null) {
            val amount = RublesParser.parse(q) ?: return "Назови точную сумму удаляемой траты."
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
            return prepareOperation(
                manager,
                ActionId("budget.expense.delete"),
                mapOf("expenseId" to ActionParameterValue.Text(expense.id)),
                "Удалить трату ${amount} ₽ из категории «${categoryTitle(context, categoryKey)}»? Скажи «да» или «отмена»."
            )
        }
        val incomeCue = listOf("добавь доход", "запиши доход", "учти доход", "установи доход", "измени доход").firstOrNull { q.startsWith(it) }
        val limitCue = listOf("установи лимит", "задай лимит", "измени лимит", "поставь лимит").firstOrNull { q.startsWith(it) }
        if (expenseCue != null || incomeCue != null || limitCue != null) {
            val amount = RublesParser.parse(q)
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
            val actionId = when {
                isExpense -> ActionId("budget.expense.create")
                isLimit -> ActionId("budget.limit.set")
                else -> ActionId("budget.income.set")
            }
            val parameters = linkedMapOf<String, ActionParameterValue>(
                "amountRub" to ActionParameterValue.Integer(amount.toLong())
            )
            if (category != null && (isExpense || isLimit)) {
                parameters["categoryKey"] = ActionParameterValue.Text(category)
            }
            val prompt = when {
                isExpense ->
                    "Распознал расход: $amount ₽, категория «${categoryTitle(context, category!!)}». Сохранить? Скажи «да» или «отмена»."
                isLimit -> {
                    val previous = storage.loadLimits()[category] ?: 0
                    "Установить лимит «${categoryTitle(context, category!!)}» $amount ₽${if (previous > 0) " вместо текущего $previous ₽" else ""}? Скажи «да» или «отмена»."
                }
                else -> {
                    val previous = storage.loadIncome()[YearMonth.now().toString()] ?: 0
                    "Установить общий доход за текущий месяц $amount ₽${if (previous > 0) " вместо текущего $previous ₽" else ""}? Скажи «да» или «отмена»."
                }
            }
            return prepareOperation(manager, actionId, parameters, prompt)
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

    private fun prepareOperation(
        manager: ConfirmationManager,
        actionId: ActionId,
        parameters: Map<String, ActionParameterValue>,
        prompt: String
    ): String {
        return when (val result = manager.prepare(
            sessionId = SessionId(SESSION_ID),
            actionId = actionId,
            parameters = parameters,
            confirmationPrompt = prompt
        )) {
            is OperationManagerResult.Success -> prompt
            is OperationManagerResult.Busy ->
                "Уже ожидает подтверждения другое действие. ${result.activeOperation.confirmationPrompt}"
            is OperationManagerResult.Invalid ->
                "Не удалось подготовить действие: ${result.errors.joinToString("; ")}."
            is OperationManagerResult.StorageFailure ->
                "Не удалось безопасно сохранить подготовленное действие. Ничего не изменено."
            OperationManagerResult.NoActiveOperation,
            is OperationManagerResult.WrongOperation,
            is OperationManagerResult.WrongSession,
            is OperationManagerResult.InvalidState ->
                "Не удалось подготовить действие из-за конфликта состояния. Ничего не изменено."
        }
    }

    private fun executeConfirmedOperation(
        context: Context,
        manager: ConfirmationManager,
        pending: PendingOperation
    ): String {
        val request = pending.request
        val sessionId = request.sessionId
        val operationId = request.operationId

        val confirmed = when (pending.status) {
            OperationStatus.AWAITING_CONFIRMATION -> manager.confirm(sessionId, operationId)
            OperationStatus.CONFIRMED -> OperationManagerResult.Success(pending)
            else -> OperationManagerResult.InvalidState(pending, "AWAITING_CONFIRMATION or CONFIRMED")
        }
        if (confirmed !is OperationManagerResult.Success) {
            return "Не удалось подтвердить именно это действие. Ничего не выполнялось."
        }

        val started = manager.beginExecution(sessionId, operationId)
        if (started !is OperationManagerResult.Success) {
            return "Подтверждение принято, но не удалось зафиксировать начало выполнения. Действие не запускалось."
        }

        return try {
            val outcome = executeDomainOperation(context, pending)
            val terminal = if (outcome.success) {
                manager.complete(sessionId, operationId)
            } else {
                manager.fail(sessionId, operationId)
            }
            if (terminal is OperationManagerResult.Success) {
                outcome.message
            } else {
                "Изменение могло сохраниться, но итог операции не удалось надёжно зафиксировать. Повторный запуск заблокирован."
            }
        } catch (error: Exception) {
            manager.markResultUnknown(sessionId, operationId)
            "Выполнение было прервано: ${error.message ?: "неизвестная ошибка"}. Результат сохранения не установлен; повторно действие не запускаю."
        }
    }

    private fun executeDomainOperation(
        context: Context,
        pending: PendingOperation
    ): DomainExecutionOutcome {
        val parameters = pending.request.parameters
        fun text(name: String): String? =
            (parameters[name] as? ActionParameterValue.Text)?.value
        fun number(name: String): Long? =
            (parameters[name] as? ActionParameterValue.Integer)?.value

        return when (pending.request.actionId.value) {
            "task.create" -> {
                val title = text("title").orEmpty()
                if (title.isBlank()) return DomainExecutionOutcome(false, "Не указано название задачи. Ничего не добавлено.")
                val dueAt = number("dueAtEpochMillis")
                val reminderAt = number("reminderAtEpochMillis")
                val priority = if (text("priority") == TaskPriority.HIGH.name) TaskPriority.HIGH else TaskPriority.NORMAL
                val storage = TaskStorage(context)
                val task = storage.add(title, dueAt = dueAt, reminderAt = reminderAt, priority = priority)
                if (reminderAt != null) TaskReminderScheduler.schedule(context, task)
                val saved = storage.load().any { it.id == task.id }
                if (saved) {
                    DomainExecutionOutcome(true, "Подтверждено. Задача «${task.title}» действительно добавлена.", task.id)
                } else {
                    DomainExecutionOutcome(false, "Не удалось подтвердить сохранение задачи. Проверь список задач.")
                }
            }

            "calendar.event.create" -> {
                val title = text("title").orEmpty()
                val startsAt = number("startsAtEpochMillis") ?: 0L
                if (title.isBlank() || startsAt <= 0L) return DomainExecutionOutcome(false, "Не хватает данных события. Ничего не добавлено.")
                val storage = CalendarStorage(context)
                val event = storage.add(title, startsAt)
                CalendarReminderScheduler.schedule(context, event)
                val saved = storage.load().any { it.id == event.id }
                if (saved) {
                    DomainExecutionOutcome(true, "Подтверждено. Событие «${event.title}» действительно сохранено в календаре.", event.id)
                } else {
                    DomainExecutionOutcome(false, "Не удалось подтвердить сохранение события. Проверь календарь.")
                }
            }

            "budget.expense.create" -> {
                val amount = number("amountRub")?.toInt() ?: 0
                val category = text("categoryKey").orEmpty()
                if (amount <= 0 || category.isBlank()) return DomainExecutionOutcome(false, "Некорректные данные расхода. Ничего не сохранено.")
                val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
                val expense = Expense(
                    UUID.randomUUID().toString(),
                    category,
                    amount,
                    null,
                    LocalDate.now(ZoneId.systemDefault()).toEpochDay()
                )
                val entries = storage.loadExpenses().toMutableList()
                entries.add(expense)
                storage.saveExpenses(entries)
                val saved = storage.loadExpenses().any { it.id == expense.id }
                if (saved) {
                    DomainExecutionOutcome(true, "Расход $amount ₽ сохранён: ${categoryTitle(context, category)}.", expense.id)
                } else {
                    DomainExecutionOutcome(false, "Не удалось подтвердить сохранение расхода. Проверь бюджет.")
                }
            }

            "budget.expense.delete" -> {
                val expenseId = text("expenseId").orEmpty()
                val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
                val expenses = storage.loadExpenses()
                if (expenseId.isBlank() || expenses.none { it.id == expenseId }) return DomainExecutionOutcome(false, "Эта трата уже отсутствует. Ничего не изменено.")
                storage.saveExpenses(expenses.filterNot { it.id == expenseId })
                val deleted = storage.loadExpenses().none { it.id == expenseId }
                if (deleted) {
                    DomainExecutionOutcome(true, "Трата удалена из бюджета.", expenseId)
                } else {
                    DomainExecutionOutcome(false, "Не удалось подтвердить удаление траты. Проверь бюджет.")
                }
            }

            "budget.income.set" -> {
                val amount = number("amountRub")?.toInt() ?: 0
                if (amount <= 0) return DomainExecutionOutcome(false, "Некорректная сумма дохода. Ничего не изменено.")
                val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
                val month = YearMonth.now()
                storage.saveIncome(month, amount)
                val saved = storage.loadIncome()[month.toString()] == amount
                if (saved) DomainExecutionOutcome(true, "Доход $amount ₽ сохранён для текущего месяца.")
                else DomainExecutionOutcome(false, "Не удалось подтвердить сохранение дохода. Проверь бюджет.")
            }

            "budget.limit.set" -> {
                val amount = number("amountRub")?.toInt() ?: 0
                val category = text("categoryKey").orEmpty()
                if (amount <= 0 || category.isBlank()) return DomainExecutionOutcome(false, "Некорректные данные лимита. Ничего не изменено.")
                val storage = com.lifenavigator.app.data.local.BudgetStorage(context)
                storage.saveLimit(category, amount)
                val saved = storage.loadLimits()[category] == amount
                if (saved) DomainExecutionOutcome(true, "Лимит «${categoryTitle(context, category)}» установлен: $amount ₽.")
                else DomainExecutionOutcome(false, "Не удалось подтвердить сохранение лимита. Проверь бюджет.")
            }

            else -> DomainExecutionOutcome(false, "Для этого действия ещё не подключён исполнитель. Ничего не изменено.")
        }
    }

    private fun categoryTitle(context: Context, key: String): String =
        NeedCategory.entries.firstOrNull { it.name == key }?.displayName
            ?: com.lifenavigator.app.data.local.BudgetStorage(context)
                .loadCustomCategories().firstOrNull { it.key == key }?.title
            ?: key

    private fun isConfirmationPrompt(message: String): Boolean =
        listOf(
            "Скажи «да", "Скажи «подтверждаю", "Сохранить?",
            "Есть одно подготовленное действие", "Подтверди его",
            "Уже ожидает подтверждения"
        ).any { message.contains(it, ignoreCase = true) }

}