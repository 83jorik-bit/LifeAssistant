package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище бюджета: лимиты по категориям и список трат.
 *
 * Лежит в SharedPreferences в виде JSON. Для сотен записей этого достаточно;
 * если трат станет очень много, имеет смысл перейти на Room.
 */
class BudgetStorage(context: Context) {

    private val prefs = context.getSharedPreferences("budget", Context.MODE_PRIVATE)

    // --- Лимиты ---

    fun loadLimits(): Map<NeedCategory, Int> {
        val raw = prefs.getString(KEY_LIMITS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            NeedCategory.entries
                .mapNotNull { category ->
                    val limit = json.optInt(category.name, -1)
                    if (limit >= 0) category to limit else null
                }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveLimit(category: NeedCategory, limitRub: Int) {
        val limits = loadLimits().toMutableMap()
        limits[category] = limitRub
        val json = JSONObject()
        limits.forEach { (cat, value) -> json.put(cat.name, value) }
        prefs.edit().putString(KEY_LIMITS, json.toString()).apply()
    }

    // --- Траты ---

    fun loadExpenses(): List<Expense> {
        val raw = prefs.getString(KEY_EXPENSES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val category = runCatching { NeedCategory.valueOf(item.getString("category")) }
                    .getOrNull() ?: return@mapNotNull null
                Expense(
                    id = item.getString("id"),
                    category = category,
                    amountRub = item.getInt("amountRub"),
                    note = if (item.isNull("note")) null else item.optString("note").takeIf { it.isNotBlank() },
                    dateEpochDay = item.getLong("dateEpochDay")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveExpenses(expenses: List<Expense>) {
        val array = JSONArray()
        expenses.forEach { expense ->
            array.put(
                JSONObject().apply {
                    put("id", expense.id)
                    put("category", expense.category.name)
                    put("amountRub", expense.amountRub)
                    put("note", expense.note ?: JSONObject.NULL)
                    put("dateEpochDay", expense.dateEpochDay)
                }
            )
        }
        prefs.edit().putString(KEY_EXPENSES, array.toString()).apply()
    }

    private companion object {
        const val KEY_LIMITS = "limits_json"
        const val KEY_EXPENSES = "expenses_json"
    }
}
