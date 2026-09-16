package com.lifenavigator.app.ui.screens.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.PlacesCache
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
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
    val selectedPlace: PlacePoint? = null,
    /** Радиус поиска в метрах — задаётся в настройках. */
    val searchRadiusMeters: Float = AppSettings.DEFAULT_RADIUS
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

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val placesCache = PlacesCache(application)
    private val settings = AppSettings(application)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState

    init {
        // Никаких выдуманных данных на старте: показываем список из прошлого сеанса.
        // Если приложение запускается впервые, список будет пустым до первого поиска.
        val cached = placesCache.load()
        _uiState.update {
            it.copy(
                places = cached,
                isLoading = false,
                searchRadiusMeters = settings.searchRadiusMeters
            )
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

    /** Выбор места по тапу на метку на карте — там на руках только идентификатор. */
    fun selectPlaceById(id: String) {
        val place = _uiState.value.places.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(selectedPlace = place) }
    }

    fun dismissPlaceDetails() {
        _uiState.update { it.copy(selectedPlace = null) }
    }

    fun setSearchRadius(radiusMeters: Float) {
        settings.searchRadiusMeters = radiusMeters
        _uiState.update { it.copy(searchRadiusMeters = radiusMeters) }
        // Новый радиус применяем сразу, если уже знаем, где пользователь.
        _uiState.value.userPoint?.let { loadRealPlacesNear(it) }
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
            val radius = _uiState.value.searchRadiusMeters

            val results = SEARCHABLE_CATEGORIES
                .map { category ->
                    async { YandexSearchRepository.searchCategory(userPoint, category, radius) }
                }
                .awaitAll()
                .flatten()

            _uiState.update { state ->
                if (results.isNotEmpty()) {
                    // Сохраняем, чтобы при следующем запуске показать этот же список.
                    placesCache.save(results)
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
