package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.NeedCategory
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * Трата, распознанная из банковского уведомления, но ещё не подтверждённая.
 */
data class PendingExpense(
    val id: String,
    val amountRub: Int,
    val merchant: String?,
    val suggestedCategoryKey: String?,
    val dateEpochDay: Long
)

/**
 * Очередь распознанных трат, ожидающих подтверждения.
 *
 * Почему очередь, а не сразу запись в бюджет: разбор чужих уведомлений — это
 * эвристика (см. BankNotificationParser), и ошибка здесь портит именно те данные,
 * ради которых приложение существует. Поэтому по умолчанию человек подтверждает
 * трату одним касанием, а автоматическое добавление включается отдельной настройкой,
 * осознанно.
 */
class PendingExpenseStore(context: Context) {

    private val prefs = context.getSharedPreferences("pending_expenses", Context.MODE_PRIVATE)

    fun load(): List<PendingExpense> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                PendingExpense(
                    id = item.getString("id"),
                    amountRub = item.getInt("amountRub"),
                    merchant = if (item.isNull("merchant")) null else item.optString("merchant").takeIf { it.isNotBlank() },
                    suggestedCategoryKey = if (item.isNull("category")) null else item.optString("category").takeIf { it.isNotBlank() },
                    dateEpochDay = item.getLong("dateEpochDay")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(amountRub: Int, merchant: String?, category: NeedCategory?) {
        val updated = load() + PendingExpense(
            id = UUID.randomUUID().toString(),
            amountRub = amountRub,
            merchant = merchant,
            suggestedCategoryKey = category?.name,
            dateEpochDay = LocalDate.now().toEpochDay()
        )
        // Ограничиваем длину: если пользователь долго не заходил, копить сотни
        // записей бессмысленно — они потеряют актуальность раньше, чем он их разберёт.
        save(updated.takeLast(MAX_ITEMS))
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun save(items: List<PendingExpense>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("amountRub", item.amountRub)
                    put("merchant", item.merchant ?: JSONObject.NULL)
                    put("category", item.suggestedCategoryKey ?: JSONObject.NULL)
                    put("dateEpochDay", item.dateEpochDay)
                }
            )
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private companion object {
        const val KEY_ITEMS = "items_json"
        const val MAX_ITEMS = 50
    }
}
