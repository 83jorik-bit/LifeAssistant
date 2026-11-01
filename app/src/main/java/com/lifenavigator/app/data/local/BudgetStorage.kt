package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.CustomCategory
import com.lifenavigator.app.data.model.Expense
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

/**
 * Хранилище бюджета: доход по месяцам, лимиты, свои категории и список трат.
 *
 * Лежит в SharedPreferences в виде JSON. Для сотен записей этого достаточно;
 * если трат станет очень много, имеет смысл перейти на Room.
 */
class BudgetStorage(context: Context) {

    private val prefs = context.getSharedPreferences("budget", Context.MODE_PRIVATE)

    // --- Доход ---

    /**
     * Доход хранится по месяцам: в разные месяцы он разный, и подменять прошлые
     * значения текущим было бы неверно.
     */
    fun loadIncome(): Map<String, Int> = readJsonObject(KEY_INCOME) { json, key ->
        json.optInt(key, 0).takeIf { it > 0 }
    }

    fun saveIncome(month: YearMonth, amountRub: Int) {
        val income = loadIncome().toMutableMap()
        if (amountRub > 0) income[month.toString()] = amountRub else income.remove(month.toString())
        writeJsonObject(KEY_INCOME, income)
    }

    /**
     * Полная замена дохода по всем месяцам. Нужна при приёме снимка с сервера:
     * там синхронизация идёт целиком, и дописывать поверх старого нельзя —
     * иначе удалённые на другом устройстве записи воскресали бы при каждой загрузке.
     */
    fun replaceIncome(income: Map<String, Int>) {
        writeJsonObject(KEY_INCOME, income.filterValues { it > 0 })
    }

    // --- Лимиты ---

    /**
     * Лимиты общие для всех месяцев, а не привязаны к конкретному.
     * Это и есть "переход на следующий месяц с теми же лимитами": план один раз
     * составили — он продолжает действовать, пока его не изменили.
     */
    fun loadLimits(): Map<String, Int> = readJsonObject(KEY_LIMITS) { json, key ->
        json.optInt(key, 0).takeIf { it > 0 }
    }

    fun saveLimit(categoryKey: String, limitRub: Int) {
        val limits = loadLimits().toMutableMap()
        if (limitRub > 0) limits[categoryKey] = limitRub else limits.remove(categoryKey)
        writeJsonObject(KEY_LIMITS, limits)
    }

    /** Полная замена лимитов — по той же причине, что и replaceIncome. */
    fun replaceLimits(limits: Map<String, Int>) {
        writeJsonObject(KEY_LIMITS, limits.filterValues { it > 0 })
    }

    // --- Свои категории ---

    fun loadCustomCategories(): List<CustomCategory> {
        val raw = prefs.getString(KEY_CUSTOM_CATEGORIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CustomCategory(key = item.getString("key"), title = item.getString("title"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCustomCategories(categories: List<CustomCategory>) {
        val array = JSONArray()
        categories.forEach { category ->
            array.put(
                JSONObject().apply {
                    put("key", category.key)
                    put("title", category.title)
                }
            )
        }
        prefs.edit().putString(KEY_CUSTOM_CATEGORIES, array.toString()).apply()
        AppDataChanges.budgetChanged()
    }

    // --- Траты ---

    fun loadExpenses(): List<Expense> {
        val raw = prefs.getString(KEY_EXPENSES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                // Старые записи хранили поле "category" с именем перечисления —
                // оно совпадает с ключом встроенной категории, поэтому читается как есть.
                val key = item.optString("categoryKey").takeIf { it.isNotBlank() }
                    ?: item.optString("category").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Expense(
                    id = item.getString("id"),
                    categoryKey = key,
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
                    put("categoryKey", expense.categoryKey)
                    put("amountRub", expense.amountRub)
                    put("note", expense.note ?: JSONObject.NULL)
                    put("dateEpochDay", expense.dateEpochDay)
                }
            )
        }
        prefs.edit().putString(KEY_EXPENSES, array.toString()).apply()
        AppDataChanges.budgetChanged()
    }

    // --- Общее ---

    fun clearAll() {
        prefs.edit().clear().apply()
        AppDataChanges.budgetChanged()
    }

    private fun readJsonObject(key: String, valueOf: (JSONObject, String) -> Int?): Map<String, Int> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .mapNotNull { name -> valueOf(json, name)?.let { name to it } }
                .toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeJsonObject(key: String, values: Map<String, Int>) {
        val json = JSONObject()
        values.forEach { (name, value) -> json.put(name, value) }
        prefs.edit().putString(key, json.toString()).apply()
        AppDataChanges.budgetChanged()
    }

    private companion object {
        const val KEY_LIMITS = "limits_json"
        const val KEY_EXPENSES = "expenses_json"
        const val KEY_INCOME = "income_json"
        const val KEY_CUSTOM_CATEGORIES = "custom_categories_json"
    }
}
