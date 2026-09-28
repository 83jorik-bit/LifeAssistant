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
    val dateEpochDay: Long,
    /** PURCHASE, TRANSFER или SUBSCRIPTION — см. PaymentKind. */
    val kind: String = "PURCHASE",
    /** Последние 4 цифры карты, если банк их указал. */
    val cardTail: String? = null,
    /** Название подписки, если списание узнано как подписка. */
    val subscriptionService: String? = null,
    /** Откуда пришла операция: PUSH или SMS. */
    val source: String = "PUSH"
) {
    val isTransfer: Boolean get() = kind == "TRANSFER"
}

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
                    dateEpochDay = item.getLong("dateEpochDay"),
                    kind = item.optString("kind", "PURCHASE").ifBlank { "PURCHASE" },
                    cardTail = if (item.isNull("cardTail")) null else item.optString("cardTail").takeIf { it.isNotBlank() },
                    subscriptionService = if (item.isNull("subscription")) null else item.optString("subscription").takeIf { it.isNotBlank() },
                    source = item.optString("source", "PUSH").ifBlank { "PUSH" }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(
        amountRub: Int,
        merchant: String?,
        category: NeedCategory?,
        kind: String = "PURCHASE",
        cardTail: String? = null,
        subscriptionService: String? = null,
        source: String = "PUSH"
    ) {
        val today = LocalDate.now().toEpochDay()
        val current = load()
        // Банк нередко шлёт об одной операции и пуш, и SMS, или два пуша подряд
        // («оплата» и следом «списание»). Одинаковая сумма + магазин + день = дубль.
        val isDuplicate = current.any {
            it.amountRub == amountRub && it.merchant == merchant && it.dateEpochDay == today
        }
        if (isDuplicate) return

        val updated = current + PendingExpense(
            id = UUID.randomUUID().toString(),
            amountRub = amountRub,
            merchant = merchant,
            suggestedCategoryKey = category?.name,
            dateEpochDay = today,
            kind = kind,
            cardTail = cardTail,
            subscriptionService = subscriptionService,
            source = source
        )
        // Ограничиваем длину: если долго не разбирать, старые записи теряют смысл.
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
                    put("kind", item.kind)
                    put("cardTail", item.cardTail ?: JSONObject.NULL)
                    put("subscription", item.subscriptionService ?: JSONObject.NULL)
                    put("source", item.source)
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
