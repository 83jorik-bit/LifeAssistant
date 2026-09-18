package com.lifenavigator.app.ui.screens.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.R
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.MarkerStyle
import com.lifenavigator.app.data.model.PlacePoint
import com.lifenavigator.app.data.model.allMarkerStyles
import com.lifenavigator.app.data.model.markerStyleFor
import com.lifenavigator.app.ui.screens.menu.SettingsSheet
import com.lifenavigator.app.ui.screens.menu.SignInDialog
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.location.Location
import com.yandex.mapkit.location.LocationListener
import com.yandex.mapkit.location.LocationStatus
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.map.TextStyle
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.runtime.image.ImageProvider
import java.lang.ref.WeakReference

private val MOSCOW_CENTER = Point(55.7530, 37.6215)

/** Что занимает экран: и карта, и список; только карта; только список. */
private enum class LayoutMode { SPLIT, MAP_ONLY, LIST_ONLY }

/**
 * Стиль карты: прячем чужие точки интереса и подписи транспорта, чтобы на карте
 * остались только те места, что показаны в списке под ней.
 *
 * Почему правил несколько: скрытия точек (`types: point`) оказалось мало — на большом
 * приближении названия станций и части остановок всё равно проступали. По документации
 * подписи — это отдельный элемент, поэтому они гасятся явно через `elements: "label"`.
 * Тег `transit_location` — станции метро, остановки и входы; `transit` — транспорт целиком.
 *
 * Линии транспорта, дороги, дома и названия улиц не трогаются.
 */
private const val HIDE_POI_STYLE =
    """[{"types":"point","tags":{"all":["poi"]},"stylers":{"visibility":"off"}},""" +
        """{"types":"point","tags":{"all":["transit"]},"stylers":{"visibility":"off"}},""" +
        """{"tags":{"any":["transit_location"]},"elements":"label","stylers":{"visibility":"off"}},""" +
        """{"tags":{"any":["transit"]},"elements":"label","stylers":{"visibility":"off"}}]"""

/**
 * Начиная с какого зума показывать подписи. Пока карта обзорная, видны только
 * значки категорий — так карта остаётся читаемой.
 */
private const val LABELS_MIN_ZOOM = 18f

@SuppressLint("MissingPermission") // проверка разрешения выполняется через hasLocationPermission перед вызовом
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // Геолокацию берём у MapKit, а не у Google Play Services: приложение должно работать
    // на устройствах без сервисов Google — это обычная ситуация для аудитории RuStore.
    val locationManager = remember { MapKitFactory.getInstance().createLocationManager() }
    // MapKit держит слушателей по слабой ссылке — храним свою, иначе ответ не придёт.
    var locationListener by remember { mutableStateOf<LocationListener?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var userLocationLayer by remember { mutableStateOf<UserLocationLayer?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    var layoutMode by remember { mutableStateOf(LayoutMode.SPLIT) }
    val listState = rememberLazyListState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }

    val centerOnUser: () -> Unit = {
        userLocationLayer?.isVisible = true
        val listener = object : LocationListener {
            override fun onLocationUpdated(location: Location) {
                val userPoint = location.position
                mapViewRef?.map?.move(
                    CameraPosition(userPoint, 16f, 0f, 0f),
                    Animation(Animation.Type.SMOOTH, 0.5f),
                    null
                )
                viewModel.loadRealPlacesNear(userPoint)
            }

            override fun onLocationStatusUpdated(status: LocationStatus) {
                if (status == LocationStatus.NOT_AVAILABLE) {
                    Toast.makeText(
                        context,
                        "Не удалось определить местоположение — проверьте, включена ли геолокация в системе",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        // Сильную ссылку держим в locationListener: SDK принимает слушателя слабой
        // ссылкой и сам его не удерживает — без этого ответ может не прийти.
        locationListener = listener
        locationManager.requestSingleUpdate(WeakReference(listener))
    }

    var hasAutoLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(mapViewRef, hasLocationPermission) {
        if (!hasAutoLoaded && hasLocationPermission && mapViewRef != null) {
            hasAutoLoaded = true
            centerOnUser()
        }
    }

    // При смене набора категорий список начинают читать сначала — возвращаем его наверх,
    // иначе пользователь остаётся где-то в середине уже другого списка.
    LaunchedEffect(state.selectedCategories) {
        listState.animateScrollToItem(0)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        if (granted) {
            centerOnUser()
        } else {
            Toast.makeText(context, "Без доступа к геолокации карта покажет только точки, без «где я»", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Компактная шапка вместо полноразмерного TopAppBar: он занимал заметную
        // часть экрана впустую. Здесь же живёт кнопка меню.
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Карта потребностей",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Меню")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Войти") },
                        onClick = {
                            menuExpanded = false
                            showSignIn = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Настройки") },
                        onClick = {
                            menuExpanded = false
                            showSettings = true
                        }
                    )
                }
            }
        }

        if (layoutMode != LayoutMode.LIST_ONLY) {
        Box(
            if (layoutMode == LayoutMode.MAP_ONLY) {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            }
        ) {
            YandexMapView(
                places = state.filteredPlaces,
                hasLocationPermission = hasLocationPermission,
                onLocationLayerReady = { layer -> userLocationLayer = layer },
                onMapViewReady = { view -> mapViewRef = view },
                onPlaceTapped = { id -> viewModel.selectPlaceById(id) }
            )

            FloatingActionButton(
                onClick = {
                    if (hasLocationPermission) {
                        centerOnUser()
                    } else {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Моя геолокация")
            }

            // Развернуть карту на весь экран и обратно.
            FilledTonalIconButton(
                onClick = {
                    layoutMode = if (layoutMode == LayoutMode.MAP_ONLY) {
                        LayoutMode.SPLIT
                    } else {
                        LayoutMode.MAP_ONLY
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    if (layoutMode == LayoutMode.MAP_ONLY) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (layoutMode == LayoutMode.MAP_ONLY) "Свернуть карту" else "Карта на весь экран"
                )
            }
        }
        }

        if (layoutMode != LayoutMode.MAP_ONLY) {

        // Категории можно комбинировать: выбранные складываются, «Все» сбрасывает фильтр.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = state.selectedCategories.isEmpty(),
                    onClick = { viewModel.clearCategories() },
                    label = { Text("Все") }
                )
            }
            items(NeedCategory.entries) { category ->
                val selected = category in state.selectedCategories
                FilterChip(
                    selected = selected,
                    onClick = { viewModel.toggleCategory(category) },
                    label = {
                        Text(
                            "${category.emoji} ${category.displayName}",
                            // Надпись категории — её собственным цветом, тем же,
                            // что у каймы метки и подписи на карте.
                            color = Color(category.colorArgb)
                        )
                    }
                )
            }
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.filteredPlaces.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.isRefreshingRealData) {
                        "Ищем места рядом…"
                    } else {
                        "Список пуст. Нажмите кнопку геолокации, чтобы найти места рядом."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Заголовок списка с кнопкой «на весь экран»: когда мест много,
            // удобнее читать список без карты.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Рядом: ${state.filteredPlaces.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = {
                        layoutMode = if (layoutMode == LayoutMode.LIST_ONLY) {
                            LayoutMode.SPLIT
                        } else {
                            LayoutMode.LIST_ONLY
                        }
                    }
                ) {
                    Icon(
                        if (layoutMode == LayoutMode.LIST_ONLY) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (layoutMode == LayoutMode.LIST_ONLY) {
                            "Показать карту"
                        } else {
                            "Список на весь экран"
                        }
                    )
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.filteredPlaces, key = { it.id }) { place ->
                    PlaceCard(place, onClick = { viewModel.selectPlace(place) })
                }
            }
        }

        }
    }

    state.selectedPlace?.let { place ->
        PlaceDetailsSheet(
            place = place,
            userPoint = state.userPoint,
            onShowSchedule = {
                viewModel.dismissPlaceDetails()
                viewModel.openSchedule(place)
            },
            onDismiss = { viewModel.dismissPlaceDetails() }
        )
    }

    state.schedulePlace?.let { place ->
        ScheduleSheet(
            place = place,
            isLoading = state.isScheduleLoading,
            result = state.scheduleResult,
            onDismiss = { viewModel.dismissSchedule() }
        )
    }

    if (showSettings) {
        SettingsSheet(
            currentRadiusMeters = state.searchRadiusMeters,
            onRadiusSelected = { viewModel.setSearchRadius(it) },
            onDismiss = { showSettings = false }
        )
    }

    if (showSignIn) {
        SignInDialog(onDismiss = { showSignIn = false })
    }
}

/**
 * Карточка места: то, что MapKit отдаёт напрямую (рейтинг, часы, телефон),
 * плюс кнопки перехода в Яндекс Карты.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceDetailsSheet(
    place: PlacePoint,
    userPoint: Point?,
    onShowSchedule: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isTransport = place.category == NeedCategory.TRANSPORT

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "${markerStyleFor(place).glyph} ${place.name}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            place.categoryDescription?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            if (place.rating > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        String.format("%.1f", place.rating),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            if (place.ratingsCount > 0) append("${place.ratingsCount} оценок")
                            if (place.reviewsCount > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${place.reviewsCount} отзывов")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            DetailRow(Icons.Filled.Place, place.address.ifBlank { "Адрес не указан" })
            DetailRow(Icons.Filled.Straighten, "${place.distanceKm} км от вас")
            place.workingHours?.takeIf { it.isNotBlank() }?.let {
                DetailRow(Icons.Filled.Schedule, it)
            }
            place.phone?.takeIf { it.isNotBlank() }?.let {
                DetailRow(Icons.Filled.Phone, it)
            }

            Spacer(Modifier.height(20.dp))

            Text("Как добраться", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            // До остановки логично добираться своим ходом, поэтому для транспорта
            // оставляем только пешком и самокат — машина и такси до остановки бессмысленны.
            val routeTypes = if (isTransport) {
                listOf(YandexMapsLauncher.RouteType.WALK, YandexMapsLauncher.RouteType.SCOOTER)
            } else {
                YandexMapsLauncher.RouteType.entries.toList()
            }

            routeTypes.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { type ->
                        Button(
                            onClick = { YandexMapsLauncher.buildRoute(context, userPoint, place, type) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(type.title, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    // Если в строке одна кнопка, не даём ей растянуться на всю ширину.
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (!isTransport) {
                FilledTonalButton(
                    onClick = { YandexMapsLauncher.orderTaxi(context, userPoint, place) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.LocalTaxi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Вызвать такси")
                }
                Spacer(Modifier.height(12.dp))
            }

            // Городские остановки и метро отправляем сразу в Яндекс Карты: расписания
            // городского транспорта в Яндекс Расписаниях нет в принципе, и попытка его
            // запросить заканчивалась бесполезным «рейсов больше нет».
            // Вокзалы, ж/д станции и причалы — наоборот, есть в Расписаниях, там показываем
            // реальные времена прямо в приложении.
            // Расписание пробуем показать в приложении для ЛЮБОЙ транспортной точки.
            // Для вокзалов и станций данные есть в Яндекс Расписаниях; для городской
            // остановки их, скорее всего, не будет — тогда шторка честно это скажет
            // и предложит открыть карточку остановки в Яндекс Картах.
            val useRaspSchedule = isTransport

            OutlinedButton(
                onClick = {
                    when {
                        !isTransport -> YandexMapsLauncher.openPlaceCard(context, place)
                        useRaspSchedule -> onShowSchedule()
                        else -> YandexMapsLauncher.openPlaceCard(context, place)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isTransport) {
                    Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isTransport) "Расписание и маршруты" else "Отзывы и фото в Яндекс Картах")
            }

            if (isTransport && useRaspSchedule) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { YandexMapsLauncher.openPlaceCard(context, place) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Показать остановку на карте Яндекса")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                if (isTransport) {
                    "Для вокзалов и станций расписание берётся из Яндекс Расписаний и показывается " +
                        "здесь. Для городских остановок таких данных нет ни у Расписаний, ни в " +
                        "поиске Карт — вторая кнопка откроет карту вплотную к остановке, где по её " +
                        "значку открывается прибытие транспорта."
                } else {
                    "Тексты отзывов и фотографии MapKit SDK не передаёт — их можно посмотреть " +
                        "в приложении Яндекс Карты по кнопке выше."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Обёртка над нативным Yandex MapView для использования в Compose.
 */
@Composable
private fun YandexMapView(
    places: List<PlacePoint>,
    hasLocationPermission: Boolean,
    onLocationLayerReady: (UserLocationLayer) -> Unit,
    onMapViewReady: (MapView) -> Unit,
    onPlaceTapped: (String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val mapView = remember { mutableStateOf<MapView?>(null) }
    var showLabels by remember { mutableStateOf(false) }

    // Значки категорий рисуем один раз и переиспользуем: создавать растр на каждую
    // метку при каждой перерисовке — лишняя работа на ровном месте.
    val markerIcons = remember(context) {
        allMarkerStyles().associate { style ->
            style.key to ImageProvider.fromBitmap(markerBitmap(context, style))
        }
    }

    // MapKit держит слушателей по слабой ссылке, поэтому храним их в remember,
    // иначе сборщик мусора уберёт их и метки перестанут реагировать.
    val cameraListener = remember {
        CameraListener { _, cameraPosition, _, _ ->
            showLabels = cameraPosition.zoom >= LABELS_MIN_ZOOM
        }
    }
    val tapListener = remember {
        MapObjectTapListener { mapObject, _ ->
            (mapObject.userData as? String)?.let(onPlaceTapped)
            true
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).also { view ->
                mapView.value = view
                view.map.setMapStyle(HIDE_POI_STYLE)
                view.map.move(
                    CameraPosition(MOSCOW_CENTER, /* zoom = */ 13.5f, /* azimuth = */ 0f, /* tilt = */ 0f)
                )
                // SDK принимает слушателя камеры именно слабой ссылкой.
                view.map.addCameraListener(WeakReference(cameraListener))

                val locationLayer = MapKitFactory.getInstance()
                    .createUserLocationLayer(view.mapWindow)
                locationLayer.isVisible = hasLocationPermission
                onLocationLayerReady(locationLayer)
                onMapViewReady(view)
            }
        },
        update = { view ->
            renderPlaceMarkers(view.map.mapObjects, places, markerIcons, showLabels, tapListener)
        }
    )

    DisposableEffect(lifecycleOwner) {
        // Важно: onStart/onStop вызываем ТОЛЬКО из наблюдателя жизненного цикла.
        // Раньше здесь был ещё и прямой вызов onStart, из-за чего при некоторых
        // сценариях старт/стоп могли задваиваться.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    MapKitFactory.getInstance().onStart()
                    mapView.value?.onStart()
                }
                Lifecycle.Event.ON_STOP -> {
                    mapView.value?.onStop()
                    MapKitFactory.getInstance().onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Если экран открылся, когда приложение уже в состоянии STARTED,
        // событие ON_START не придёт — запускаем вручную.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            MapKitFactory.getInstance().onStart()
            mapView.value?.onStart()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                mapView.value?.onStop()
                MapKitFactory.getInstance().onStop()
            }
        }
    }
}

/**
 * Рисует значок категории: белый кружок с цветной каймой и эмодзи внутри.
 * Эмодзи берём из самой категории — так значок сразу узнаваем и не нужно
 * рисовать девять векторных иконок вручную.
 */
private fun markerBitmap(context: Context, markerStyle: MarkerStyle): Bitmap {
    val density = context.resources.displayMetrics.density
    // 24dp — на 30% меньше прежних 34dp.
    val sizePx = (24 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val borderWidth = 2f * density

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center - borderWidth, fillPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = markerStyle.colorArgb.toInt()
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    canvas.drawCircle(center, center, center - borderWidth, borderPaint)

    // У банкоматов вместо эмодзи — своя картинка: как выглядит эмодзи 🏧, решает
    // шрифт устройства, и на разных телефонах это разные значки. Встроенный
    // вектор рисуется одинаково везде.
    val iconRes = if (markerStyle.key == NeedCategory.ATM.name) R.drawable.ic_atm_marker else null

    if (iconRes != null) {
        val drawable = ContextCompat.getDrawable(context, iconRes)
        if (drawable != null) {
            val inset = (sizePx * 0.24f).toInt()
            drawable.setBounds(inset, inset, sizePx - inset, sizePx - inset)
            drawable.draw(canvas)
            return bitmap
        }
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx * 0.5f
        textAlign = Paint.Align.CENTER
        // Глиф метро — это буква «М», её красим в цвет метки; эмодзи цвет игнорируют.
        color = markerStyle.colorArgb.toInt()
        isFakeBoldText = true
    }
    val metrics = textPaint.fontMetrics
    val baseline = center - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText(markerStyle.glyph, center, baseline, textPaint)

    return bitmap
}

private fun renderPlaceMarkers(
    collection: MapObjectCollection,
    places: List<PlacePoint>,
    markerIcons: Map<String, ImageProvider>,
    showLabels: Boolean,
    tapListener: MapObjectTapListener
) {
    collection.clear()
    places.forEach { place ->
        val icon = markerIcons[markerStyleFor(place).key] ?: return@forEach
        val placemark = collection.addPlacemark(Point(place.latitude, place.longitude), icon)
        // По идентификатору в userData находим место, когда по метке тапнули.
        // Слушатель, как и у камеры, передаётся слабой ссылкой — сильную держим
        // в remember выше, иначе метки перестанут реагировать на тапы.
        placemark.userData = place.id
        placemark.addTapListener(WeakReference(tapListener))

        if (showLabels) {
            placemark.setText(
                place.name,
                TextStyle().apply {
                    size = 9f
                    color = markerStyleFor(place).colorArgb.toInt()
                    outlineWidth = 2f
                    placement = TextStyle.Placement.BOTTOM
                    offset = 2f
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceCard(place: PlacePoint, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${markerStyleFor(place).glyph} ${place.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    // Значок круглосуточной работы — только для продуктовых и аптек:
                    // именно там это важно посреди ночи.
                    if (place.isOpen24 && place.category in ROUND_THE_CLOCK_CATEGORIES) {
                        Spacer(Modifier.width(6.dp))
                        Badge24(color = Color(place.category.colorArgb))
                    }
                }
                Text("${place.distanceKm} км", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            place.categoryDescription?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                place.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (place.rating > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        String.format("%.1f", place.rating) +
                            if (place.reviewsCount > 0) " (${place.reviewsCount})" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

/** Категории, для которых круглосуточный график действительно важен. */
private val ROUND_THE_CLOCK_CATEGORIES = setOf(NeedCategory.GROCERY, NeedCategory.PHARMACY)

/** Значок «24» — работает круглосуточно. */
@Composable
private fun Badge24(color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            "24",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
