package com.lifenavigator.app.ui.screens.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.model.Subscription
import com.lifenavigator.app.data.repository.MockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val isLoading: Boolean = true,
    val subscriptions: List<Subscription> = emptyList()
) {
    val activeCount: Int get() = subscriptions.count { it.isActive }
    val monthlyTotal: Int
        get() = subscriptions.filter { it.isActive }.sumOf {
            when (it.billingCycle) {
                com.lifenavigator.app.data.model.BillingCycle.MONTHLY -> it.priceRub
                com.lifenavigator.app.data.model.BillingCycle.EVERY_2_MONTHS -> it.priceRub / 2
                com.lifenavigator.app.data.model.BillingCycle.YEARLY -> it.priceRub / 12
            }
        }
}

class SubscriptionsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SubscriptionsUiState())
    val uiState: StateFlow<SubscriptionsUiState> = _uiState

    init {
        viewModelScope.launch {
            val subs = MockRepository.getSubscriptions()
            _uiState.update { it.copy(subscriptions = subs, isLoading = false) }
        }
    }

    fun toggleSubscription(id: String) {
        _uiState.update { state ->
            state.copy(
                subscriptions = state.subscriptions.map {
                    if (it.id == id) it.copy(isActive = !it.isActive) else it
                }
            )
        }
    }
}
