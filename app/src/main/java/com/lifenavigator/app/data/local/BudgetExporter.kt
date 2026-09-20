package com.lifenavigator.app.data.local

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.lifenavigator.app.data.model.NeedCategory
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Экспорт трат в CSV.
 *
 * Читает хранилище бюджета напрямую (`BudgetStorage`), а не через `BudgetViewModel`:
 * экран настроек, откуда вызывается экспорт, живёт на другом экране приложения
 * и своего экземпляра ViewModel бюджета не имеет. Хранилище — общий источник правды
 * для обоих экранов, поэтому это не костыль, а тот же путь, каким пользуется сам экран бюджета.
 */
object BudgetExporter {

    private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * Формирует CSV во временном файле и возвращает intent «Поделиться».
     * Файл кладётся в cacheDir — как временный экспорт, а не постоянное хранилище.
     */
    fun buildShareIntent(context: Context): Intent {
        val storage = BudgetStorage(context)
        val customTitles = storage.loadCustomCategories().associate { it.key to it.title }

        fun titleOf(key: String): String =
            NeedCategory.entries.firstOrNull { it.name == key }?.displayName
                ?: customTitles[key]
                ?: key

        val expenses = storage.loadExpenses().sortedBy { it.dateEpochDay }

        val csv = buildString {
            // Точка с запятой — чтобы Excel с русской локалью открывал файл без лишних
            // настроек: там разделитель списка по умолчанию не запятая, а именно она.
            appendLine("Дата;Категория;Сумма (\u20BD);Комментарий")
            expenses.forEach { expense ->
                val date = LocalDate.ofEpochDay(expense.dateEpochDay).format(DATE_FORMAT)
                val note = expense.note?.replace(";", ",")?.replace("\n", " ") ?: ""
                appendLine("$date;${titleOf(expense.categoryKey)};${expense.amountRub};$note")
            }
        }

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "budget-export.csv")
        // UTF-8 BOM — без него Excel на Windows принимает кириллицу за latin1 и превращает
        // её в нечитаемые символы.
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray())

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
