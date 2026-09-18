package com.lifenavigator.app.ui.screens.budget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lifenavigator.app.data.local.BudgetStorage
import com.lifenavigator.app.data.model.BudgetCategory
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class BudgetUiState(
    /** Месяц, который сейчас смотрим. */
    val month: YearMonth = YearMonth.now(),
    val limits: Map<NeedCategory, Int> = emptyMap(),
    /** Все траты, включая прошлые месяцы — фильтруем при показе. */
    val allExpenses: List<Expense> = emptyList()
) {
    /** Траты выбранного месяца, свежие сверху. */
    val expensesOfMonth: List<Expense>
        get() = allExpenses
            .filter { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == month }
            .sortedByDescending { it.dateEpochDay }

    /** Категории с лимитом или тратами — пустые не показываем, чтобы не засорять экран. */
    val categories: List<BudgetCategory>
        get() {
            val spentByCategory = expensesOfMonth.groupBy { it.category }
                .mapValues { (_, list) -> list.sumOf { it.amountRub } }

            return NeedCategory.entries
                .map { category ->
                    BudgetCategory(
                        category = category,
                        monthlyLimitRub = limits[category] ?: 0,
                        spentRub = spentByCategory[category] ?: 0
                    )
                }
                .filter { it.monthlyLimitRub > 0 || it.spentRub > 0 }
        }

    val totalSpent: Int get() = expensesOfMonth.sumOf { it.amountRub }
    val totalLimit: Int get() = limits.values.sum()
    val remaining: Int get() = totalLimit - totalSpent
    val overLimitCount: Int get() = categories.count { it.isOverLimit }

    val isCurrentMonth: Boolean get() = month == YearMonth.now()

    /** Сколько дней осталось до конца месяца (для текущего месяца — включая сегодня). */
    val daysLeft: Int
        get() = if (isCurrentMonth) {
            month.lengthOfMonth() - LocalDate.now().dayOfMonth + 1
        } else {
            0
        }

    /**
     * Сколько можно тратить в день, чтобы уложиться в лимит.
     * Считается только для текущего месяца и только если остаток положительный.
     */
    val dailyAllowance: Int?
        get() = if (isCurrentMonth && daysLeft > 0 && remaining > 0) remaining / daysLeft else null
}

class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = BudgetStorage(application)

    private val _uiState = MutableStateFlow(BudgetUiState())
    val uiState: StateFlow<BudgetUiState> = _uiState

    init {
        _uiState.update {
            it.copy(limits = storage.loadLimits(), allExpenses = storage.loadExpenses())
        }
    }

    fun setLimit(category: NeedCategory, limitRub: Int) {
        storage.saveLimit(category, limitRub)
        _uiState.update { it.copy(limits = storage.loadLimits()) }
    }

    fun addExpense(category: NeedCategory, amountRub: Int, note: String?, date: LocalDate) {
        val expense = Expense(
            id = UUID.randomUUID().toString(),
            category = category,
            amountRub = amountRub,
            note = note?.takeIf { it.isNotBlank() },
            dateEpochDay = date.toEpochDay()
        )
        val updated = _uiState.value.allExpenses + expense
        storage.saveExpenses(updated)
        _uiState.update { it.copy(allExpenses = updated) }
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
        // В будущее не пускаем: планировать траты вперёд этот экран пока не умеет.
        _uiState.update {
            if (it.month < YearMonth.now()) it.copy(month = it.month.plusMonths(1)) else it
        }
    }
}
