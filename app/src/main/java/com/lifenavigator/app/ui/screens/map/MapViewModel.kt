package com.lifenavigator.app.ui.screens.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifenavigator.app.data.local.AppSettings
import com.lifenavigator.app.data.local.PlacesCache
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.lifenavigator.app.data.repository.RaspRepository
import com.lifenavigator.app.data.repository.ScheduleResult
import com.lifenavigator.app.data.repository.YandexSearchRepository
import com.yandex.mapkit.geometry.Point
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val searchRadiusMeters: Float = AppSettings.DEFAULT_RADIUS,
    /**
     * Какие категории реально запрашиваются у Яндекса при обновлении списка —
     * настройка из AppSettings, отдельная от selectedCategories (тот фильтр только
     * показывает/прячет уже загруженные места и сети не трогает).
     */
    val searchCategories: Set<NeedCategory> = NeedCategory.entries.toSet(),
    /** Место, для которого открыто расписание. null — шторка расписания закрыта. */
    val schedulePlace: PlacePoint? = null,
    val isScheduleLoading: Boolean = false,
    val scheduleResult: ScheduleResult? = null
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


class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val placesCache = PlacesCache(application)
    private val settings = AppSettings(application)

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState
    private var placesSearchJob: Job? = null

    init {
        // Никаких выдуманных данных на старте: показываем список из прошлого сеанса.
        // Если приложение запускается впервые, список будет пустым до первого поиска.
        val cached = placesCache.load()
        _uiState.update {
            it.copy(
                places = cached,
                isLoading = false,
                searchRadiusMeters = settings.searchRadiusMeters,
                searchCategories = settings.searchCategories
            )
        }
    }

    /** Какие категории реально искать у Яндекса — влияет на число сетевых запросов. */
    fun setSearchCategories(categories: Set<NeedCategory>) {
        // Пустой набор означал бы "не искать вообще ничего" — это, скорее всего, не то,
        // что имел в виду пользователь, поэтому пустой выбор трактуем как "все категории".
        val safe = categories.ifEmpty { NeedCategory.entries.toSet() }
        settings.searchCategories = safe
        _uiState.update { it.copy(searchCategories = safe) }
        // Список мог отражать уже не те категории, которые теперь ищем — обновляем сразу,
        // если знаем, где пользователь.
        _uiState.value.userPoint?.let { loadRealPlacesNear(it) }
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

    /** Открывает расписание станции и подгружает его из API Яндекс Расписаний. */
    fun openSchedule(place: PlacePoint) {
        _uiState.update {
            it.copy(schedulePlace = place, isScheduleLoading = true, scheduleResult = null)
        }
        viewModelScope.launch {
            val result = RaspRepository.loadSchedule(place.latitude, place.longitude, place.name)
            _uiState.update { it.copy(isScheduleLoading = false, scheduleResult = result) }
        }
    }

    fun dismissSchedule() {
        _uiState.update { it.copy(schedulePlace = null, scheduleResult = null, isScheduleLoading = false) }
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
        // Запоминаем точку для бюджета: он ищет магазины из трат рядом с ней.
        settings.lastUserLatitude = userPoint.latitude
        settings.lastUserLongitude = userPoint.longitude
        // A fresh location/category/radius request supersedes the previous batch.
        // Without cancellation, rapid navigation and filter changes can leave many
        // MapKit Search sessions alive at the same time.
        placesSearchJob?.cancel()
        placesSearchJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingRealData = true, userPoint = userPoint) }
            try {
                val snapshot = _uiState.value
                val radius = snapshot.searchRadiusMeters
                val results = snapshot.searchCategories
                    .map { category ->
                        async {
                            runCatching {
                                YandexSearchRepository.searchCategory(userPoint, category, radius)
                            }.getOrDefault(emptyList())
                        }
                    }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.id }

                _uiState.update { state ->
                    if (results.isNotEmpty()) {
                        placesCache.save(results)
                        state.copy(places = results, isRefreshingRealData = false)
                    } else {
                        // Keep the last good result if the network returns no matches.
                        state.copy(isRefreshingRealData = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isRefreshingRealData = false) }
            }
        }
    }

    override fun onCleared() {
        placesSearchJob?.cancel()
        placesSearchJob = null
        super.onCleared()
    }
}
