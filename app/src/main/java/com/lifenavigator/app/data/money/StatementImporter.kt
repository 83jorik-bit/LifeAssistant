package com.lifenavigator.app.data.money

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ImportResult(val added: Int, val skipped: Int, val failed: Int)

/**
 * Импорт выписки из банковского приложения (CSV).
 *
 * Единого формата выписок не существует — каждый банк выгружает по-своему.
 * Поэтому разбор намеренно терпимый: ищем в каждой строке дату и сумму, а не
 * требуем строго определённый порядок колонок. Строки, где не нашлось ни того,
 * ни другого, просто пропускаются и попадают в счётчик — пользователь увидит,
 * сколько строк не поддалось, вместо молчаливой потери данных.
 *
 * Файл читается через системный выбор файла, поэтому разрешение на доступ
 * к хранилищу не нужно: Android сам даёт доступ к выбранному файлу.
 */
object StatementImporter {

    private val DATE_PATTERNS = listOf(
        "dd.MM.yyyy" to Regex("""\b(\d{2}\.\d{2}\.\d{4})\b"""),
        "yyyy-MM-dd" to Regex("""\b(\d{4}-\d{2}-\d{2})\b""")
    )

    private val AMOUNT_REGEX = Regex("""(-?\d[\d\s\u00A0]*[.,]\d{2})""")

    fun import(context: Context, uri: Uri): ImportResult {
        var added = 0
        var skipped = 0
        var failed = 0

        context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank()) return@forEach

                val date = extractDate(line)
                val amount = extractAmount(line)
                if (date == null || amount == null) {
                    failed++
                    return@forEach
                }
                // Положительные суммы в выписке — это поступления, а не траты.
                if (amount >= 0) {
                    skipped++
                    return@forEach
                }

                val recorded = ExpenseRecorder.record(
                    context = context,
                    amountRub = Math.round(-amount).toInt(),
                    note = extractNote(line),
                    date = date,
                    categoryKey = ExpenseRecorder.categoryKeyFor(extractNote(line))
                )
                if (recorded) added++ else skipped++
            }
        } ?: return ImportResult(0, 0, 0)

        return ImportResult(added, skipped, failed)
    }

    private fun extractDate(line: String): LocalDate? {
        DATE_PATTERNS.forEach { (pattern, regex) ->
            val value = regex.find(line)?.groupValues?.get(1) ?: return@forEach
            runCatching {
                return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern))
            }
        }
        return null
    }

    private fun extractAmount(line: String): Double? =
        AMOUNT_REGEX.find(line)?.groupValues?.get(1)
            ?.replace('\u00A0', ' ')
            ?.replace(" ", "")
            ?.replace(',', '.')
            ?.toDoubleOrNull()

    /** Назначение платежа: самый длинный текстовый фрагмент строки. */
    private fun extractNote(line: String): String? =
        line.split(';', ',', '\t')
            .map { it.trim().trim('"') }
            .filter { it.length > 3 && !it.any(Char::isDigit) }
            .maxByOrNull { it.length }
            ?.take(40)
}
