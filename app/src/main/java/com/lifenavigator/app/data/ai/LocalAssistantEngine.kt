package com.lifenavigator.app.data.ai

import android.content.Context
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.local.PlacesCache
import com.lifenavigator.app.data.local.TaskStorage
import com.lifenavigator.app.data.local.TaskReminderScheduler
import com.lifenavigator.app.data.model.TaskPriority
import com.lifenavigator.app.data.model.NeedCategory
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Автономный помощник для базовых бытовых вопросов.
 *
 * Работает непосредственно с локальными данными приложения и не требует регистрации,
 * сети или API-ключа. Это честный резервный режим, а не имитация ответа языковой модели:
 * для свободного диалога используется серверный YandexGPT, когда он настроен.
 */
object LocalAssistantEngine {

    fun reply(context: Context, userText: String): String {
        val question = userText.lowercase(Locale.ROOT)
        val taskStorage = TaskStorage(context)
        val taskItems = taskStorage.load()

        if (listOf("мои задачи", "список задач", "что по задачам", "какие задачи", "что мне нужно сделать", "что осталось сделать", "план на день", "что сегодня", "есть ли просроченные").any(question::contains)) {
            val active = taskItems.filterNot { it.isCompleted }
            if (active.isEmpty()) return "Активных задач пока нет. Если появится поручение, просто скажи: «Добавь задачу…»."
            val overdue = active.filter { it.dueAtEpochMillis != null && it.dueAtEpochMillis < System.currentTimeMillis() }
            val lines = active.take(10).joinToString("\\n") { task ->
                val mark = if (task.priority == TaskPriority.HIGH) "❗ " else ""
                mark + "• " + task.title
            }
            return buildString {
                append("Активных задач: ${active.size}.")
                if (overdue.isNotEmpty()) append(" Просрочено: ${overdue.size}.")
                append("\\n")
                append(lines)
                if (active.size > 10) append("\\nИ ещё ${active.size - 10} задач.")
            }
        }

        val taskCreationCue = listOf(
            "добавь задачу", "создай задачу", "запиши задачу", "напомни", "не забудь",
            "надо сделать", "нужно сделать", "мне нужно", "мне надо"
        ).any(question::contains)
        if (taskCreationCue) {
            var title = userText.trim()
                .replace(Regex("(?i)^\\s*(добавь задачу|создай задачу|запиши задачу|напомни мне|напомни|не забудь|надо сделать|нужно сделать|мне нужно|мне надо)\\s*"), "")
                .trim()
                .replace("послезавтра", "").replace("завтра", "").replace("сегодня", "")
                .trimStart(':', ',', '-', ' ')
            if (title.isBlank()) return "Что именно нужно добавить в задачи?"
            val dueAt = when {
                "послезавтра" in question -> java.time.LocalDate.now().plusDays(2).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                "завтра" in question -> java.time.LocalDate.now().plusDays(1).atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                "сегодня" in question -> java.time.LocalDate.now().atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                else -> null
            }
            val priority = if (listOf("срочно", "важно", "обязательно").any(question::contains)) TaskPriority.HIGH else TaskPriority.NORMAL
            val task = taskStorage.add(title, dueAt = dueAt, reminderAt = dueAt, priority = priority)
            TaskReminderScheduler.schedule(context, task)
            val dueText = dueAt?.let {
                " Срок поставил: " + java.text.SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(java.util.Date(it)) + "."
            } ?: ""
            return "Добавил задачу: «${task.title}».$dueText Открой вкладку «Задачи», чтобы посмотреть и отметить выполнение."
        }
        val storage = BudgetStorage(context)
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val expenses = storage.loadExpenses().filter {
            runCatching { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == month }
                .getOrDefault(false)
        }
        val limits = storage.loadLimits()
        val income = storage.loadIncome()[month.toString()] ?: 0
        val customTitles = storage.loadCustomCategories().associate { it.key to it.title }
        fun titleOf(key: String): String =
            NeedCategory.entries.firstOrNull { it.name == key }?.displayName
                ?: customTitles[key]
                ?: key

        val spentByCategory = expenses.groupBy { it.categoryKey }
            .mapValues { (_, list) -> list.sumOf { it.amountRub.toLong() } }
        val totalSpent = expenses.sumOf { it.amountRub.toLong() }
        val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1

        fun money(value: Long): String =
            value.toString().reversed().chunked(3).joinToString(" ").reversed() + " ₽"

        val asksSubscriptions = listOf("подписк", "автоплатеж", "автоплатёж").any(question::contains)
        val asksOverspend = listOf("превыш", "перерасход", "выход за лимит").any(question::contains)
        val asksLimits = asksOverspend || listOf("лимит", "категор").any(question::contains)
        val asksExpenses = listOf("потрат", "расход", "ушло денег", "сколько ушло").any(question::contains)
        // Expense/limit questions take precedence: "сколько потратил в аптеке"
        // must not be mistaken for a request to find a pharmacy.
        val asksPlaces = !asksExpenses && !asksLimits && listOf(
            "рядом", "мест", "аптек", "магазин", "продукт", "кафе", "ресторан",
            "купить", "ближайш", "где", "адрес"
        ).any(question::contains)
        val asksDaily = listOf(
            "в день", "ежеднев", "сколько можно тратить", "сколько тратить",
            "до конца месяца", "остаток", "хватит ли", "бюджет", "деньги"
        ).any(question::contains)

        if (question.trim() in setOf("привет", "здравствуй", "здравствуйте", "добрый день", "доброе утро", "добрый вечер", "hello", "hi")) {
            return "Привет! Я LifeAssistant. Пока подключается облачный ИИ, могу уже помочь с расчётами по бюджету и с сохранёнными местами в приложении."
        }

        if (listOf("кто ты", "что ты умеешь", "помоги", "что умеешь").any(question::contains)) {
            return "Я LifeAssistant. В автономном режиме могу посчитать расходы за текущий месяц, остаток бюджета, траты по категориям, превышение лимитов и показать сохранённые места рядом. Для свободного диалога нужен подключённый серверный ИИ."
        }

        if (asksSubscriptions) {
            return "Раздел подписок сейчас содержит демонстрационные данные, поэтому я не буду выдавать их за реальные списания. Можно посмотреть их на вкладке «Подписки и автоплатежи»."
        }

        if (asksPlaces) {
            val allPlaces = PlacesCache(context).load().sortedBy { it.distanceKm }
            if (allPlaces.isEmpty()) {
                return "В сохранённом списке пока нет мест. Открой вкладку карты и выполни поиск рядом; после этого я смогу показать найденные места."
            }

            val category = when {
                listOf("аптек", "лекарств", "медицин").any(question::contains) -> NeedCategory.PHARMACY
                listOf("продукт", "магазин", "супермаркет").any(question::contains) -> NeedCategory.GROCERY
                listOf("кафе", "ресторан", "поесть", "кофе").any(question::contains) -> NeedCategory.CAFE
                listOf("одежд", "обув").any(question::contains) -> NeedCategory.CLOTHING
                listOf("клиник", "врач", "больниц").any(question::contains) -> NeedCategory.CLINIC
                else -> null
            }
            val places = allPlaces.filter { category == null || it.category == category }.take(5)
            if (places.isEmpty()) {
                val categoryName = category?.displayName ?: "этой категории"
                return "В сохранённом поиске нет мест категории «$categoryName». Попробуй обновить поиск на карте."
            }
            val intro = if (category == null) "Вот ближайшие сохранённые места:" else "Вот ближайшие сохранённые места категории «${category.displayName}»:"
            val details = places.joinToString("\n") { place ->
                val rating = if (place.rating > 0) " · рейтинг ${"%.1f".format(Locale("ru", "RU"), place.rating)}" else ""
                "- ${place.name} — ${"%.1f".format(Locale("ru", "RU"), place.distanceKm)} км$rating"
            }
            return "$intro\n$details"
        }

        if (asksLimits) {
            if (limits.isEmpty()) {
                return "Лимиты расходов пока не заданы. Добавь их во вкладке бюджета, и я смогу показать превышения и остатки по категориям."
            }
            val exceeded = limits.entries.filter { (key, limit) ->
                (spentByCategory[key] ?: 0L) > limit.toLong()
            }
            if (asksOverspend) {
                if (exceeded.isEmpty()) return "По сохранённым тратам за текущий месяц превышений заданных лимитов нет."
                return "Превышены лимиты за текущий месяц:\n" + exceeded.joinToString("\n") { (key, limit) ->
                    val spent = spentByCategory[key] ?: 0L
                    "- ${titleOf(key)}: потрачено ${money(spent)}, лимит ${money(limit.toLong())}, превышение ${money(spent - limit)}"
                }
            }
            return "Лимиты и траты за текущий месяц:\n" + limits.entries.joinToString("\n") { (key, limit) ->
                val spent = spentByCategory[key] ?: 0L
                val remaining = limit.toLong() - spent
                "- ${titleOf(key)}: ${money(spent)} из ${money(limit.toLong())}; " +
                    if (remaining >= 0) "осталось ${money(remaining)}" else "превышение ${money(-remaining)}"
            }
        }

        if (asksExpenses) {
            if (expenses.isEmpty()) {
                return "За текущий месяц в бюджете пока нет записанных трат."
            }
            val byCategory = spentByCategory.entries
                .sortedByDescending { it.value }
                .take(6)
                .joinToString("\n") { (key, amount) -> "- ${titleOf(key)}: ${money(amount)}" }
            return "За ${month.monthValue} месяц текущего года записано трат на ${money(totalSpent)}.\nПо категориям:\n$byCategory"
        }

        if (asksDaily) {
            if (income > 0) {
                val remaining = income.toLong() - totalSpent
                val daily = remaining / daysLeft
                return buildString {
                    appendLine("Доход на месяц: ${money(income.toLong())}.")
                    appendLine("Записанные траты: ${money(totalSpent)}.")
                    appendLine(if (remaining >= 0) "Остаток от дохода: ${money(remaining)}." else "Траты превысили доход на ${money(-remaining)}.")
                    append("До конца месяца, включая сегодня, $daysLeft дн. " +
                        if (remaining >= 0) "Ориентир — ${money(daily)} в день." else "Сначала стоит пересмотреть расходы и план бюджета.")
                }
            }
            if (limits.isNotEmpty()) {
                val remainingByLimits = limits.entries.sumOf { (key, limit) ->
                    (limit.toLong() - (spentByCategory[key] ?: 0L)).coerceAtLeast(0L)
                }
                return "Остаток в пределах заданных лимитов — ${money(remainingByLimits)} суммарно. " +
                    "До конца месяца, включая сегодня, $daysLeft дн.; ориентир — ${money(remainingByLimits / daysLeft)} в день. " +
                    "Расчёт учитывает только категории, для которых заданы лимиты."
            }
            return "Чтобы рассчитать ориентир расходов на день, добавь доход или лимиты во вкладке бюджета. Если внесены траты, я также смогу посчитать сумму расходов."
        }

        return "Сейчас я работаю в автономном режиме и отвечаю по данным приложения. Спроси, например: «Сколько я потратил в этом месяце?», «Есть ли превышения лимитов?» или «Какие аптеки рядом?» Для свободного диалога нужен серверный ИИ."
    }
}
