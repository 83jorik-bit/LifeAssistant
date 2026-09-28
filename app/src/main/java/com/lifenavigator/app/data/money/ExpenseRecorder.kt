package com.lifenavigator.app.data.money

import android.content.Context
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import java.time.LocalDate
import java.util.UUID

/**
 * Единственное место, куда пишутся траты из автоматических источников
 * (уведомления, чеки, импорт выписки). Ручной ввод идёт через BudgetViewModel,
 * но хранилище одно и то же, поэтому записи не расходятся.
 */
object ExpenseRecorder {

    /** Ключ для трат, которым не удалось определить категорию. */
    const val UNCATEGORIZED_KEY = "custom:uncategorized"

    /** Грубое определение категории по названию магазина. */
    private val MERCHANT_HINTS: List<Pair<List<String>, NeedCategory>> = listOf(
        listOf("магнит", "пятероч", "перекрест", "лента", "дикси", "ашан", "вкусвилл", "окей", "продукт")
            to NeedCategory.GROCERY,
        listOf("аптек", "здравсити", "ригла", "36,6", "горздрав") to NeedCategory.PHARMACY,
        listOf("кафе", "ресторан", "кофе", "coffee", "додо", "вкусно", "бургер", "пицц", "столов")
            to NeedCategory.CAFE,
        listOf("аптека") to NeedCategory.PHARMACY,
        listOf("озон", "wildberries", "вайлдберриз", "пункт выдачи") to NeedCategory.PICKUP_POINT,
        listOf("метро", "транспорт", "троллейбус", "автобус", "ржд") to NeedCategory.TRANSPORT
    )

    fun categoryKeyFor(merchant: String?): String {
        val lower = merchant?.lowercase() ?: return UNCATEGORIZED_KEY
        MERCHANT_HINTS.forEach { (words, category) ->
            if (words.any { it in lower }) return category.name
        }
        return UNCATEGORIZED_KEY
    }

    /**
     * Добавляет трату. Возвращает false, если такая уже записана — защита от дублей,
     * когда банк присылает несколько уведомлений об одной операции
     * (например «оплата» и следом «списание»).
     */
    fun record(
        context: Context,
        amountRub: Int,
        note: String?,
        date: LocalDate,
        categoryKey: String
    ): Boolean {
        val storage = BudgetStorage(context)
        val expenses = storage.loadExpenses()

        val isDuplicate = expenses.any {
            it.amountRub == amountRub &&
                it.dateEpochDay == date.toEpochDay() &&
                it.note == note
        }
        if (isDuplicate) return false

        storage.saveExpenses(
            expenses + Expense(
                id = UUID.randomUUID().toString(),
                categoryKey = categoryKey,
                amountRub = amountRub,
                note = note,
                dateEpochDay = date.toEpochDay()
            )
        )
        return true
    }
}
