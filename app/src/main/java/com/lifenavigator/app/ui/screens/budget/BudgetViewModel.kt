package com.lifenavigator.app.ui.screens.budget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.BudgetNotifier
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.local.PendingExpense
import com.lifenavigator.app.data.local.PendingExpenseStore
import com.lifenavigator.app.data.model.BudgetLine
import com.lifenavigator.app.data.model.CustomCategory
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** Цвет и значок для категорий, заведённых вручную. */
private const val CUSTOM_COLOR = 0xFF5D6D7E
private const val CUSTOM_EMOJI = "🏷"

data class BudgetUiState(
    val month: YearMonth = YearMonth.now(),
    /** Доход по месяцам: ключ — "2026-09". */
    val incomeByMonth: Map<String, Int> = emptyMap(),
    /** Лимиты общие для всех месяцев — план переходит на следующий месяц сам. */
    val limits: Map<String, Int> = emptyMap(),
    val customCategories: List<CustomCategory> = emptyList(),
    val allExpenses: List<Expense> = emptyList(),
    /** Траты, распознанные из банковских уведомлений и ждущие подтверждения. */
    val pending: List<PendingExpense> = emptyList()
) {
    val income: Int get() = incomeByMonth[month.toString()] ?: 0

    val expensesOfMonth: List<Expense>
        get() = allExpenses
            .filter { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == month }
            .sortedByDescending { it.dateEpochDay }

    /** Все категории, о которых мы знаем: встроенные плюс заведённые вручную. */
    val allCategoryKeys: List<Pair<String, String>>
        get() = NeedCategory.entries.map { it.name to "${it.emoji} ${it.displayName}" } +
            customCategories.map { it.key to "$CUSTOM_EMOJI ${it.title}" }

    fun titleOf(key: String): String = resolve(key).let { "${it.emoji} ${it.title}" }

    /** Строки бюджета: показываем только те, где есть лимит или траты. */
    val lines: List<BudgetLine>
        get() {
            val spentByKey = expensesOfMonth.groupBy { it.categoryKey }
                .mapValues { (_, list) -> list.sumOf { it.amountRub } }

            val keys = (limits.keys + spentByKey.keys).distinct()
            return keys
                .map { key ->
                    val info = resolve(key)
                    BudgetLine(
                        key = key,
                        title = info.title,
                        emoji = info.emoji,
                        colorArgb = info.colorArgb,
                        limitRub = limits[key] ?: 0,
                        spentRub = spentByKey[key] ?: 0
                    )
                }
                .sortedByDescending { it.spentRub }
        }

    private data class CategoryInfo(val title: String, val emoji: String, val colorArgb: Long)

    private fun resolve(key: String): CategoryInfo {
        NeedCategory.entries.firstOrNull { it.name == key }?.let {
            return CategoryInfo(it.displayName, it.emoji, it.colorArgb)
        }
        val custom = customCategories.firstOrNull { it.key == key }
        return CategoryInfo(custom?.title ?: "Без категории", CUSTOM_EMOJI, CUSTOM_COLOR)
    }

    val totalSpent: Int get() = expensesOfMonth.sumOf { it.amountRub }
    val totalLimit: Int get() = limits.values.sum()
    val remaining: Int get() = totalLimit - totalSpent
    val overLimitCount: Int get() = lines.count { it.isOverLimit }

    /** Сколько от дохода осталось нераспределённым по лимитам. */
    val unplanned: Int get() = income - totalLimit

    /** Сколько от дохода осталось на руках с учётом фактических трат. */
    val incomeLeft: Int get() = income - totalSpent

    val isCurrentMonth: Boolean get() = month == YearMonth.now()

    val daysLeft: Int
        get() = if (isCurrentMonth) month.lengthOfMonth() - LocalDate.now().dayOfMonth + 1 else 0

    val dailyAllowance: Int?
        get() {
            val base = if (income > 0) incomeLeft else remaining
            return if (isCurrentMonth && daysLeft > 0 && base > 0) base / daysLeft else null
        }
}

class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = BudgetStorage(application)
    private val pendingStore = PendingExpenseStore(application)

    private val _uiState = MutableStateFlow(BudgetUiState())
    val uiState: StateFlow<BudgetUiState> = _uiState

    init {
        reload()
    }

    private fun reload() {
        _uiState.update {
            it.copy(
                incomeByMonth = storage.loadIncome(),
                limits = storage.loadLimits(),
                customCategories = storage.loadCustomCategories(),
                allExpenses = storage.loadExpenses(),
                pending = pendingStore.load()
            )
        }
    }

    fun setIncome(amountRub: Int) {
        storage.saveIncome(_uiState.value.month, amountRub)
        _uiState.update { it.copy(incomeByMonth = storage.loadIncome()) }
    }

    fun setLimit(categoryKey: String, limitRub: Int) {
        storage.saveLimit(categoryKey, limitRub)
        _uiState.update { it.copy(limits = storage.loadLimits()) }
    }

    /** Заводит свою категорию и сразу задаёт ей лимит. */
    fun addCustomCategory(title: String, limitRub: Int) {
        val category = CustomCategory(key = "custom:${UUID.randomUUID()}", title = title.trim())
        val updated = _uiState.value.customCategories + category
        storage.saveCustomCategories(updated)
        if (limitRub > 0) storage.saveLimit(category.key, limitRub)
        _uiState.update {
            it.copy(customCategories = updated, limits = storage.loadLimits())
        }
    }

    fun addExpense(categoryKey: String, amountRub: Int, note: String?, date: LocalDate) {
        val expense = Expense(
            id = UUID.randomUUID().toString(),
            categoryKey = categoryKey,
            amountRub = amountRub,
            note = note?.takeIf { it.isNotBlank() },
            dateEpochDay = date.toEpochDay()
        )
        val updated = _uiState.value.allExpenses + expense
        storage.saveExpenses(updated)
        _uiState.update { it.copy(allExpenses = updated) }
        maybeNotifyOverLimit(categoryKey, amountRub, date)
    }

    /**
     * Уведомляет о превышении лимита, только если эта трата стала той самой, что
     * перевела категорию за черту (а не сотой подряд трате уже давно просроченной
     * категории — иначе уведомление сыпалось бы на каждую мелкую покупку).
     */
    private fun maybeNotifyOverLimit(categoryKey: String, amountRub: Int, date: LocalDate) {
        val app = getApplication<Application>()
        if (!AppSettings(app).budgetAlertsEnabled) return
        if (YearMonth.from(date) != _uiState.value.month) return

        val line = _uiState.value.lines.firstOrNull { it.key == categoryKey } ?: return
        if (!line.isOverLimit) return
        // Превышение уже было ДО этой траты — значит уведомление по ней уже показывалось раньше.
        if (line.spentRub - amountRub >= line.limitRub) return

        BudgetNotifier.notifyOverLimit(app, line.title, line.spentRub, line.limitRub)
    }

    /** Подтверждает распознанную трату: переносит её в бюджет. */
    fun confirmPending(item: PendingExpense, categoryKey: String) {
        addExpense(
            categoryKey = categoryKey,
            amountRub = item.amountRub,
            note = item.merchant,
            date = LocalDate.ofEpochDay(item.dateEpochDay)
        )
        pendingStore.remove(item.id)
        _uiState.update { it.copy(pending = pendingStore.load()) }
    }

    fun dismissPending(id: String) {
        pendingStore.remove(id)
        _uiState.update { it.copy(pending = pendingStore.load()) }
    }

    /** Перечитывает очередь: уведомления приходят, пока экран уже открыт. */
    fun refreshPending() {
        _uiState.update { it.copy(pending = pendingStore.load()) }
    }

    fun deleteExpense(id: String) {
        val updated = _uiState.value.allExpenses.filterNot { it.id == id }
        storage.saveExpenses(updated)
        _uiState.update { it.copy(allExpenses = updated) }
    }

    fun showPreviousMonth() {
        _uiState.update { it.copy(month = it.month.minusMonths(1)) }
    }

    fun showNextMonth() {
        _uiState.update {
            if (it.month < YearMonth.now()) it.copy(month = it.month.plusMonths(1)) else it
        }
    }
}
