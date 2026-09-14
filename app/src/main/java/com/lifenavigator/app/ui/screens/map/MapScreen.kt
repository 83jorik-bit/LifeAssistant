package com.lifenavigator.app.ui.screens.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lifenavigator.app.R
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapObjectCollection
import com.yandex.mapkit.mapview.MapView
import com.yandex.mapkit.user_location.UserLocationLayer
import com.yandex.runtime.image.ImageProvider

private val MOSCOW_CENTER = Point(55.7530, 37.6215)

@SuppressLint("MissingPermission") // проверка разрешения выполняется через hasLocationPermission перед вызовом
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Держим ссылки на слой геолокации и сам MapView, чтобы кнопка "моя геолокация" могла ими управлять.
    var userLocationLayer by remember { mutableStateOf<UserLocationLayer?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    // Запрашивает текущие координаты, плавно перемещает камеру к ним
    // и подтягивает реальные точки рядом (вместо тестовых данных).
    val centerOnUser: () -> Unit = {
        userLocationLayer?.isVisible = true
        val cancellationTokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val userPoint = Point(location.latitude, location.longitude)
                    mapViewRef?.map?.move(
                        CameraPosition(userPoint, 16f, 0f, 0f),
                        Animation(Animation.Type.SMOOTH, 0.5f),
                        null
                    )
                    viewModel.loadRealPlacesNear(userPoint)
                } else {
                    Toast.makeText(context, "Не удалось определить местоположение — проверьте, включена ли геолокация в системе", Toast.LENGTH_LONG).show()
                }
            }
    }

    // Если разрешение уже выдано раньше — сразу подтягиваем реальные точки при открытии экрана,
    // не дожидаясь нажатия на кнопку "моя геолокация". Ждём, пока карта реально смонтируется
    // (mapViewRef появится чуть позже первого кадра), иначе центрирование камеры не сработает.
    var hasAutoLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(mapViewRef, hasLocationPermission) {
        if (!hasAutoLoaded && hasLocationPermission && mapViewRef != null) {
            hasAutoLoaded = true
            centerOnUser()
        }
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
        TopAppBar(title = { Text("Карта потребностей") })

        Box(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            YandexMapView(
                places = state.filteredPlaces,
                hasLocationPermission = hasLocationPermission,
                onLocationLayerReady = { layer -> userLocationLayer = layer },
                onMapViewReady = { view -> mapViewRef = view }
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
        }

        // Фильтр по категориям
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = state.selectedCategory == null,
                    onClick = { viewModel.selectCategory(null) },
                    label = { Text("Все") }
                )
            }
            items(NeedCategory.entries) { category ->
                FilterChip(
                    selected = state.selectedCategory == category,
                    onClick = { viewModel.selectCategory(category) },
                    label = { Text("${category.emoji} ${category.displayName}") }
                )
            }
        }

        FilterChip(
            modifier = Modifier.padding(horizontal = 12.dp),
            selected = state.onlyOnlinePayment,
            onClick = { viewModel.toggleOnlinePaymentFilter() },
            leadingIcon = { Icon(Icons.Filled.CreditCard, contentDescription = null, modifier = Modifier.size(16.dp)) },
            label = { Text("Онлайн-оплата и подписки") }
        )

        Spacer(Modifier.height(8.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.filteredPlaces, key = { it.id }) { place ->
                    PlaceCard(place, onClick = { viewModel.selectPlace(place) })
                }
            }
        }
    }

    state.selectedPlace?.let { place ->
        PlaceDetailsSheet(
            place = place,
            userPoint = state.userPoint,
            onDismiss = { viewModel.dismissPlaceDetails() }
        )
    }
}

/**
 * Карточка места: то, что MapKit отдаёт напрямую (рейтинг, часы, телефон),
 * плюс кнопки перехода в Яндекс Карты за маршрутом и отзывами.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceDetailsSheet(
    place: PlacePoint,
    userPoint: Point?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "${place.category.emoji} ${place.name}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YandexMapsLauncher.RouteType.entries.forEach { type ->
                    Button(
                        onClick = { YandexMapsLauncher.buildRoute(context, userPoint, place, type) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
                    ) {
                        Text(type.title, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { YandexMapsLauncher.openPlaceCard(context, place) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Отзывы и фото в Яндекс Картах")
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Тексты отзывов и фотографии MapKit SDK не передаёт — их можно посмотреть " +
                    "в приложении Яндекс Карты по кнопке выше.",
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
 * Управляет жизненным циклом (MapKitFactory.onStart/onStop) и перерисовывает
 * маркеры при изменении списка точек.
 */
@Composable
private fun YandexMapView(
    places: List<PlacePoint>,
    hasLocationPermission: Boolean,
    onLocationLayerReady: (UserLocationLayer) -> Unit,
    onMapViewReady: (MapView) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            MapView(context).also { view ->
                mapView.value = view
                view.map.move(
                    CameraPosition(MOSCOW_CENTER, /* zoom = */ 13.5f, /* azimuth = */ 0f, /* tilt = */ 0f)
                )

                val locationLayer = MapKitFactory.getInstance()
                    .createUserLocationLayer(view.mapWindow)
                locationLayer.isVisible = hasLocationPermission
                onLocationLayerReady(locationLayer)
                onMapViewReady(view)
            }
        },
        update = { view ->
            renderPlaceMarkers(view.context, view.map.mapObjects, places)
        }
    )

    DisposableEffect(lifecycleOwner) {
        MapKitFactory.getInstance().onStart()
        mapView.value?.onStart()

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

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.value?.onStop()
            MapKitFactory.getInstance().onStop()
        }
    }
}

private fun renderPlaceMarkers(context: Context, collection: MapObjectCollection, places: List<PlacePoint>) {
    collection.clear()
    val icon = ImageProvider.fromResource(context, R.drawable.ic_map_marker)
    places.forEach { place ->
        val placemark = collection.addPlacemark(Point(place.latitude, place.longitude), icon)
        placemark.setText(place.name)
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
                Text(
                    "${place.category.emoji} ${place.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text("${place.distanceKm} км", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))
            Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (place.rating > 0) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("${place.rating} (${place.reviewsCount})", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                }
                if (place.onlinePaymentAvailable) {
                    AssistChip(onClick = {}, label = { Text("Онлайн-оплата", style = MaterialTheme.typography.labelSmall) })
                }
                if (place.hasSubscriptionOption) {
                    Spacer(Modifier.width(4.dp))
                    AssistChip(onClick = {}, label = { Text("Подписка", style = MaterialTheme.typography.labelSmall) })
                }
            }

            if (place.approxAvgCheckRub > 0) {
                Spacer(Modifier.height(4.dp))
                Text("Средний чек: ~${place.approxAvgCheckRub} ₽", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
