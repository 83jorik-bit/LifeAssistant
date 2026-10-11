package com.lifenavigator.app.data.money

import android.content.Context
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import java.time.LocalDate
import java.util.UUID

/** Распознанная трата из банковского уведомления. */
data class ParsedPayment(
    val amountRub: Int,
    val merchant: String?
)

/**
 * Разбор пушей от банковских приложений.
 *
 * Единого формата у банков нет, поэтому ориентируемся на общее: сумма с валютой
 * и слово-признак операции. Намеренно отбрасываем зачисления и переводы себе —
 * иначе поступление зарплаты попало бы в траты и испортило весь бюджет.
 */
object BankNotificationParser {

    /**
     * Пакеты банковских приложений, чьи уведомления читаем.
     *
     * Список закрытый и по одной причине: служба чтения уведомлений технически
     * видит ВСЕ уведомления на телефоне. Ограничение по списку — то, что не даёт
     * приложению трогать переписку, почту и всё остальное.
     */
    val BANK_PACKAGES = setOf(
        "ru.sberbankmobile",
        "ru.sberbank.android",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.raiffeisennews",
        "ru.raiffeisen.mobile.new",
        "ru.gazprombank.android.mobilebank.app",
        "ru.psbank.mobile",
        "ru.mkb.mobile",
        "ru.rosbank.android",
        "ru.akbars.mobile",
        "ru.sovcombank.halva",
        "com.openbank",
        "ru.pochta.bank"
    )

    private val AMOUNT_REGEX = Regex(
        """(\d[\d\s\u00A0]*(?:[.,]\d{1,2})?)\s*(?:₽|руб|р\.|RUB)""",
        RegexOption.IGNORE_CASE
    )

    /** Слова, по которым понимаем, что это именно расход. */
    private val SPENDING_WORDS = listOf("покупка", "оплата", "списание", "оплачено", "потрачено")

    /** Слова, по которым уведомление точно НЕ расход. */
    private val INCOME_WORDS = listOf("зачисление", "пополнение", "поступление", "возврат", "отмена", "кэшбэк", "кешбэк")

    fun parse(text: String): ParsedPayment? {
        val lower = text.lowercase()

        if (INCOME_WORDS.any { it in lower }) return null
        if (SPENDING_WORDS.none { it in lower }) return null

        val match = AMOUNT_REGEX.find(text) ?: return null
        val amount = match.groupValues[1]
            .replace('\u00A0', ' ')
            .replace(" ", "")
            .replace(',', '.')
            .toDoubleOrNull() ?: return null
        if (amount <= 0) return null

        return ParsedPayment(
            amountRub = Math.round(amount).toInt(),
            merchant = extractMerchant(text, match.range.last)
        )
    }

    /** Название магазина обычно идёт после суммы — берём остаток строки. */
    private fun extractMerchant(text: String, amountEnd: Int): String? {
        if (amountEnd + 1 >= text.length) return null
        return text.substring(amountEnd + 1)
            .trim(' ', '.', ',', ':', ';', '\n')
            .takeIf { it.isNotBlank() }
            ?.take(40)
    }
}

/**
 * Единственное место, куда пишутся траты из автоматических источников
 * (уведомления, чеки, импорт выписки). Ручной ввод идёт через BudgetViewModel,
 * но хранилище одно и то же, поэтому записи не расходятся.
 */
object ExpenseRecorder {

    /** Ключ для трат, которым не удалось определить категорию. */
    const val UNCATEGORIZED_KEY = "uncategorized"

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
