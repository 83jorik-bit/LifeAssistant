package com.lifenavigator.app.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.lifenavigator.app.data.repository.MockRepository
import com.lifenavigator.app.data.repository.YandexSearchRepository
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MapUiState(
    val isLoading: Boolean = true,
    val isRefreshingRealData: Boolean = false,
    val places: List<PlacePoint> = emptyList(),
    /** Пустое множество — показываем все категории. Иначе — объединение выбранных. */
    val selectedCategories: Set<NeedCategory> = emptySet(),
    /** Последние известные координаты пользователя — нужны как начало маршрута. */
    val userPoint: Point? = null,
    /** Место, карточка которого сейчас открыта. null — карточка закрыта. */
    val selectedPlace: PlacePoint? = null
) {
    val filteredPlaces: List<PlacePoint>
        get() = places
            .filter { selectedCategories.isEmpty() || it.category in selectedCategories }
            // Сначала по удалённости (distanceKm уже округлён до 0,1 км — это и есть шаг),
            // внутри одного шага — по оценке, от высокой к низкой.
            .sortedWith(
                compareBy<PlacePoint> { it.distanceKm }
                    .thenByDescending { it.rating }
            )
}

private val SEARCHABLE_CATEGORIES = listOf(
    NeedCategory.PHARMACY,
    NeedCategory.GROCERY,
    NeedCategory.CLINIC,
    NeedCategory.BEAUTY,
    NeedCategory.PICKUP_POINT,
    NeedCategory.CAFE,
    NeedCategory.CLOTHING,
    NeedCategory.EDUCATION,
    NeedCategory.TRANSPORT
)

class MapViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    init {
        viewModelScope.launch {
            // Показываем тестовые данные сразу, не дожидаясь геолокации и сети —
            // как только придут реальные точки (loadRealPlacesNear), они их заменят.
            val places = MockRepository.getNearbyPlaces()
            _uiState.update { it.copy(places = places, isLoading = false) }
        }
    }

    /** Категории включаются и выключаются независимо — их можно комбинировать. */
    fun toggleCategory(category: NeedCategory) {
        _uiState.update { state ->
            val updated = if (category in state.selectedCategories) {
                state.selectedCategories - category
            } else {
                state.selectedCategories + category
            }
            state.copy(selectedCategories = updated)
        }
    }

    /** Сброс фильтра — показываем все категории. */
    fun clearCategories() {
        _uiState.update { it.copy(selectedCategories = emptySet()) }
    }

    fun selectPlace(place: PlacePoint) {
        _uiState.update { it.copy(selectedPlace = place) }
    }

    fun dismissPlaceDetails() {
        _uiState.update { it.copy(selectedPlace = null) }
    }

    /**
     * Подтягивает настоящие организации рядом с [userPoint] через MapKit Search
     * и заменяет ими текущий список точек. Вызывается, когда становятся известны
     * реальные координаты пользователя (при старте с уже выданным разрешением
     * и при каждом нажатии на кнопку "моя геолокация").
     */
    fun loadRealPlacesNear(userPoint: Point) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingRealData = true, userPoint = userPoint) }

            val results = SEARCHABLE_CATEGORIES
                .map { category -> async { YandexSearchRepository.searchCategory(userPoint, category) } }
                .awaitAll()
                .flatten()

            _uiState.update { state ->
                if (results.isNotEmpty()) {
                    state.copy(places = results, isRefreshingRealData = false)
                } else {
                    // Поиск не дал результатов (нет сети, пустая выдача и т.п.) —
                    // оставляем то, что уже показано, чтобы экран не опустел.
                    state.copy(isRefreshingRealData = false)
                }
            }
        }
    }
}

