package com.lifenavigator.app.data.money

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * added — записано, skipped — пропущено осознанно (поступление или уже записанный дубль),
 * failed — строка не разобрана (нет даты или суммы). Разделение важно: «не разобрано»
 * сигнализирует о незнакомом формате выписки, а «пропущено» — нормальная работа.
 */
data class ImportResult(val added: Int, val skipped: Int, val failed: Int)

/**
 * Импорт банковской выписки из CSV.
 *
 * Закрывает прошлое: чтение уведомлений ловит траты только с момента включения.
 * Единого формата выписки у банков нет, поэтому разбор терпимый — ищем в строке
 * дату и сумму, а не требуем конкретных колонок.
 *
 * Запись идёт через ExpenseRecorder, поэтому повторный импорт того же файла или
 * наложение на уже распознанные уведомления не создаёт дублей.
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
     * Здесь две ловушки, обе проверены на реальных строках:
     * 1. Дата «12.09.2026» содержит «12.09», похожее на сумму. Поэтому сумму ищем
     *    только в части строки ПОСЛЕ даты.
     * 2. В «PYATEROCHKA 5432;-1250,50» простое выражение цепляется за «5432»
     *    из названия магазина. Поэтому число считается суммой, только если оно
     *    с копейками или со знаком и не примыкает к букве или цифре.
     */
    private val AMOUNT_REGEX = Regex(
        """(?<![\w])([-+]?\d{1,3}(?:[ \u00A0]\d{3})*[.,]\d{1,2}|[-+]?\d+[.,]\d{1,2}|[-+]\d[\d \u00A0]*)(?![\w])"""
    )

    private val INCOME_WORDS = listOf("зачисл", "пополн", "поступл", "возврат", "кэшбэк", "кешбэк")

    fun import(context: Context, uri: Uri): ImportResult {
        var added = 0
        var skipped = 0
        var failed = 0

        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: return ImportResult(0, 0, 0)

        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach

            val dateText = DATE_REGEX.find(line)?.value
            val date = dateText?.let { parseDate(it) }
            if (dateText == null || date == null) {
                failed++
                return@forEach
            }

            val tail = line.substringAfter(dateText)
            val numbers = AMOUNT_REGEX.findAll(tail)
                .map { it.groupValues[1].replace(" ", "").replace("\u00A0", "").replace(',', '.') }
                .mapNotNull { it.toDoubleOrNull() }
                .filter { it != 0.0 }
                .toList()
            if (numbers.isEmpty()) {
                failed++
                return@forEach
            }

            val negative = numbers.firstOrNull { it < 0 }
            // Поступления в бюджет расходов не импортируем.
            if (negative == null && INCOME_WORDS.any { it in line.lowercase() }) {
                skipped++
                return@forEach
            }
            val amountRub = Math.round(Math.abs(negative ?: numbers.first())).toInt()
            if (amountRub <= 0) {
                failed++
                return@forEach
            }

            val note = tail
                .replace(AMOUNT_REGEX, " ")
                .split(';', ',', '\t')
                .map { it.trim() }
                .firstOrNull { it.length in 2..40 }

            val recorded = ExpenseRecorder.record(
                context = context,
                amountRub = amountRub,
                note = note,
                date = date,
                categoryKey = ExpenseRecorder.categoryKeyFor(note)
            )
            if (recorded) added++ else skipped++
        }

        return ImportResult(added, skipped, failed)
    }

    private fun parseDate(text: String): LocalDate? {
        DATE_FORMATS.forEach { format ->
            runCatching { return LocalDate.parse(text, format) }
        }
        return null
    }
}
