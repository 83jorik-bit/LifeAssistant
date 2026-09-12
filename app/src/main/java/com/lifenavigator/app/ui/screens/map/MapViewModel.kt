package com.lifenavigator.app.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.lifenavigator.app.data.repository.MockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapUiState(
    val isLoading: Boolean = true,
    val places: List<PlacePoint> = emptyList(),
    val selectedCategory: NeedCategory? = null,
    val onlyOnlinePayment: Boolean = false
) {
    val filteredPlaces: List<PlacePoint>
        get() = places
            .filter { selectedCategory == null || it.category == selectedCategory }
            .filter { !onlyOnlinePayment || it.onlinePaymentAvailable }
            .sortedBy { it.distanceKm }
}

class MapViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    init {
        viewModelScope.launch {
            val places = MockRepository.getNearbyPlaces()
            _uiState.update { it.copy(places = places, isLoading = false) }
        }
    }

    fun selectCategory(category: NeedCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun toggleOnlinePaymentFilter() {
        _uiState.update { it.copy(onlyOnlinePayment = !it.onlyOnlinePayment) }
    }
}
