package com.lifenavigator.app.data.ai

import android.content.Context
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.local.PlacesCache
import com.lifenavigator.app.data.local.CalendarStorage
import com.lifenavigator.app.data.local.TaskStorage
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.lifenavigator.app.data.model.NeedCategory
import java.time.LocalDate
import java.time.YearMonth

/**
 * Собирает выжимку данных пользователя для помощника.
 *
 * Зачем это нужно: без контекста модель отвечает общими словами («ведите учёт,
 * планируйте бюджет»), что бесполезно. Получив реальные цифры, она отвечает
 * по делу — какая категория перерасходована и сколько осталось на день.
 *
 * Что сюда НЕ попадает: имя, почта, точные координаты и история перемещений.
 * Модели отправляется ровно то, без чего она не ответит на вопрос о бюджете
 * и местах рядом, — суммы, названия категорий и названия заведений.
 */
object AssistantContext {

    fun build(context: Context): String {
        val budget = budgetSummary(context)
        val places = placesSummary(context)
        val agenda = agendaSummary(context)

        return buildString {
            appendLine(
                "Ты — LifeAssistant, личный помощник. Помогаешь с повседневными задачами, " +
                    "личным бюджетом и местами рядом — чтобы пользователь мог уделять время главному. " +
                    "Отвечай кратко и по существу, " +
                    "на русском языке, без вступлений вроде «конечно» и без списков там, " +
                    "где хватает двух предложений."
            )
            appendLine(
                "Опирайся на данные ниже. Если данных для ответа не хватает — скажи об этом " +
                    "прямо, не придумывай цифры и не выдумывай заведения, которых нет в списке."
            )
            appendLine()
            appendLine("ДАННЫЕ БЮДЖЕТА:")
            appendLine(budget)
            appendLine()
            appendLine("РАСПИСАНИЕ И ЗАДАЧИ (локальные данные приложения):")
            appendLine(agenda)
            appendLine()
            appendLine("МЕСТА РЯДОМ (из последнего поиска):")
            appendLine(places)
        }.trim()
    }

    private fun budgetSummary(context: Context): String {
        val storage = BudgetStorage(context)
        val month = YearMonth.now()
        val expenses = storage.loadExpenses()
            .filter { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == month }
        val limits = storage.loadLimits()
        val income = storage.loadIncome()[month.toString()] ?: 0
        val customTitles = storage.loadCustomCategories().associate { it.key to it.title }

        if (expenses.isEmpty() && limits.isEmpty() && income == 0) {
            return "Пользователь ещё не вводил доход, лимиты и траты."
        }

        fun titleOf(key: String) = NeedCategory.entries.firstOrNull { it.name == key }?.displayName
            ?: customTitles[key] ?: key

        val spentByCategory = expenses.groupBy { it.categoryKey }
            .mapValues { (_, list) -> list.sumOf { it.amountRub } }
        val totalSpent = expenses.sumOf { it.amountRub }
        val daysLeft = month.lengthOfMonth() - LocalDate.now().dayOfMonth + 1

        return buildString {
            appendLine("Месяц: $month. Осталось дней: $daysLeft.")
            if (income > 0) appendLine("Доход за месяц: $income ₽.")
            appendLine("Потрачено всего: $totalSpent ₽.")
            if (income > 0) appendLine("Остаток от дохода: ${income - totalSpent} ₽.")

            if (limits.isEmpty()) {
                appendLine("Лимиты по категориям не заданы.")
            } else {
                appendLine("Лимиты и траты по категориям:")
                limits.forEach { (key, limit) ->
                    val spent = spentByCategory[key] ?: 0
                    val mark = if (spent > limit) " — ПРЕВЫШЕН" else ""
                    appendLine("- ${titleOf(key)}: потрачено $spent ₽ из $limit ₽$mark")
                }
            }

            val withoutLimit = spentByCategory.keys - limits.keys
            if (withoutLimit.isNotEmpty()) {
                appendLine("Траты в категориях без лимита: " +
                    withoutLimit.joinToString(", ") { "${titleOf(it)} ${spentByCategory[it]} ₽" })
            }
        }.trim()
    }

    private fun placesSummary(context: Context): String {
        val places = PlacesCache(context).load()
        if (places.isEmpty()) return "Список мест пуст — пользователь ещё не искал места рядом."

        // Берём ближайшие: весь список слать незачем, он раздувает запрос
        // и стоит денег по токенам, а дальние места для совета бесполезны.
        return places
            .sortedBy { it.distanceKm }
            .take(15)
            .joinToString("\n") { place ->
                val rating = if (place.rating > 0) ", рейтинг ${"%.1f".format(place.rating)}" else ""
                "- ${place.name} (${place.category.displayName}), ${place.distanceKm} км$rating"
            }
    }
}
