package com.lifenavigator.app.ui.screens.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.model.BudgetCategory
import com.lifenavigator.app.data.repository.MockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BudgetUiState(
    val isLoading: Boolean = true,
    val categories: List<BudgetCategory> = emptyList()
) {
    val totalSpent: Int get() = categories.sumOf { it.spentRub }
    val totalLimit: Int get() = categories.sumOf { it.monthlyLimitRub }
    val overLimitCount: Int get() = categories.count { it.isOverLimit }
}

class BudgetViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(BudgetUiState())
    val uiState: StateFlow<BudgetUiState> = _uiState

    init {
        viewModelScope.launch {
            val categories = MockRepository.getBudgetCategories()
            _uiState.update { it.copy(categories = categories, isLoading = false) }
        }
    }
}
