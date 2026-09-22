package com.lifenavigator.app.data.local

import android.content.Context
import android.net.Uri
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.notifications.BankNotificationParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ImportResult(val imported: Int, val skipped: Int)

/**
 * Импорт банковской выписки из CSV.
 *
 * Зачем он нужен, если есть чтение уведомлений: уведомления ловят траты только
 * с момента включения и только пока приложение установлено. Выписка закрывает
 * прошлое — и работает без каких-либо особых разрешений.
 *
 * Единого формата выписки у банков нет, поэтому разбор намеренно терпимый:
 * ищем в каждой строке дату и сумму, а не требуем конкретных колонок в конкретном
 * порядке. Строки, где не нашлось и того и другого, пропускаются и попадают
 * в счётчик пропущенных — молча терять данные хуже, чем показать число.
 */
object StatementImporter {

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy")
    )

    private val DATE_REGEX = Regex("""\d{2}[./]\d{2}[./]\d{4}|\d{4}-\d{2}-\d{2}""")

    /**
     * Сумма как отдельное поле выписки.
     *
     * Требование границ по краям — не придирка: в строке
     * «12.09.2026;PYATEROCHKA 5432;-1250,50» простое выражение цеплялось за «5432»
     * из названия магазина и давало 125 ₽ вместо 1250. Поэтому число считается
     * суммой, только если оно либо с копейками, либо со знаком, и не примыкает
     * к букве или цифре.
     */
    private val AMOUNT_REGEX = Regex(
        """(?<![\w])([-+]?\d{1,3}(?:[ \u00A0]\d{3})*[.,]\d{1,2}|[-+]?\d+[.,]\d{1,2}|[-+]\d[\d \u00A0]*)(?![\w])"""
    )

    fun import(context: Context, uri: Uri): ImportResult {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readText()
        } ?: return ImportResult(0, 0)

        val storage = BudgetStorage(context)
        val existing = storage.loadExpenses()
        val added = mutableListOf<Expense>()
        var skipped = 0

        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val parsed = parseLine(line)
            if (parsed == null) {
                skipped++
                return@forEach
            }
            added += parsed
        }

        if (added.isNotEmpty()) {
            storage.saveExpenses(existing + added)
        }
        return ImportResult(added.size, skipped)
    }

    private fun parseLine(line: String): Expense? {
        val dateText = DATE_REGEX.find(line)?.value ?: return null
        val date = parseDate(dateText) ?: return null

        // Сумму ищем в части строки ПОСЛЕ даты, иначе можно принять за сумму
        // кусок самой даты. Берём первое отрицательное число — в выписках расход
        // обычно со знаком минус; если минусов нет, берём первое подходящее число.
        val tail = line.substringAfter(dateText)
        val numbers = AMOUNT_REGEX.findAll(tail)
            .map { it.groupValues[1].replace(" ", "").replace("\u00A0", "").replace(',', '.') }
            .mapNotNull { it.toDoubleOrNull() }
            .filter { it != 0.0 }
            .toList()
        if (numbers.isEmpty()) return null

        val negative = numbers.firstOrNull { it < 0 }
        val amount = negative ?: numbers.first()
        // Поступления не импортируем: это бюджет расходов.
        if (negative == null && amount > 0 && numbers.none { it < 0 } && looksLikeIncome(line)) return null

        val amountRub = Math.round(Math.abs(amount)).toInt()
        if (amountRub <= 0) return null

        val description = line
            .substringAfter(dateText)
            .replace(AMOUNT_REGEX, " ")
            .split(';', ',', '\t')
            .map { it.trim() }
            .firstOrNull { it.length in 2..40 }

        return Expense(
            id = UUID.randomUUID().toString(),
            categoryKey = description?.let { guessCategoryKey(it) } ?: UNCATEGORIZED_KEY,
            amountRub = amountRub,
            note = description,
            dateEpochDay = date.toEpochDay()
        )
    }

    private fun looksLikeIncome(line: String): Boolean {
        val lower = line.lowercase()
        return listOf("зачисл", "пополн", "поступл", "возврат", "кэшбэк", "кешбэк").any { it in lower }
    }

    /** Категорию подсказываем тем же справочником, что и для уведомлений — незачем дублировать. */
    private fun guessCategoryKey(description: String): String? =
        BankNotificationParser.parse("ru.sberbankmobile", "Покупка 1 ₽", "Покупка 1 ₽ $description")
            ?.suggestedCategory?.name

    private fun parseDate(text: String): LocalDate? {
        DATE_FORMATS.forEach { format ->
            runCatching { return LocalDate.parse(text, format) }
        }
        return null
    }

    const val UNCATEGORIZED_KEY = "custom:uncategorized"
}
