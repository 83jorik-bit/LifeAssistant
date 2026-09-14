package com.lifenavigator.app.data.repository

import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.yandex.mapkit.GeoObject
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.BusinessObjectMetadata
import com.yandex.mapkit.search.BusinessRating1xObjectMetadata
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManager
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.runtime.Error
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Реальный поиск организаций рядом с пользователем через MapKit Search
 * (доступен только в full-версии SDK — см. app/build.gradle.kts).
 *
 * У Yandex нет прямого способа получить "все точки поблизости" одним запросом:
 * поиск устроен по текстовому запросу (например "аптека") в пределах геометрии.
 * Поэтому на каждую категорию — свой запрос.
 */
object YandexSearchRepository {

    /**
     * Битовая маска сниппета с рейтингом организации (BusinessRating1x).
     * Значение 1 shl 1 задокументировано Яндексом одинаково для всех платформ.
     * Использую число, а не константу перечисления Snippet, потому что точное имя
     * константы в Android-версии SDK мне подтвердить не удалось — документация
     * справочника по Android не открылась, а гадать с именем не хочу.
     */
    private const val SNIPPET_BUSINESS_RATING_1X = 1 shl 1

    private val searchManager: SearchManager by lazy {
        // ONLINE выбран потому, что офлайн-карты мы нигде не скачиваем, и искать
        // в офлайн-кэше попросту не в чем. В документации в примерах используется COMBINED
        // (онлайн + офлайн) — если понадобится офлайн-режим, менять нужно здесь.
        SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
    }

    // MapKit хранит только слабые ссылки на сессии и слушателей — держим их здесь,
    // иначе ответ может не дойти (сборщик мусора соберёт объект раньше времени).
    private val activeSessions = mutableListOf<Session>()
    private val activeListeners = mutableListOf<Session.SearchListener>()

    private val CATEGORY_QUERIES: Map<NeedCategory, String> = mapOf(
        NeedCategory.PHARMACY to "аптека",
        NeedCategory.GROCERY to "продуктовый магазин",
        NeedCategory.CLINIC to "клиника",
        NeedCategory.BEAUTY to "салон красоты",
        NeedCategory.PICKUP_POINT to "пункт выдачи заказов",
        NeedCategory.CAFE to "кафе"
        // TRANSPORT и SELF_DEVELOPMENT сюда сознательно не включены — это скорее
        // категории бюджета/подписок, чем тип точки на карте (так же было и в моковых данных).
    )

    /**
     * Ищет реальные организации указанной категории в радиусе [radiusMeters] от [userPoint].
     * Возвращает пустой список при ошибке сети или отсутствии результатов —
     * вызывающий код в этом случае может оставить прежние (например, моковые) данные.
     */
    suspend fun searchCategory(
        userPoint: Point,
        category: NeedCategory,
        radiusMeters: Float = 3000f
    ): List<PlacePoint> {
        val query = CATEGORY_QUERIES[category] ?: return emptyList()

        return suspendCancellableCoroutine { continuation ->
            val searchOptions = SearchOptions().apply {
                searchTypes = SearchType.BIZ.value
                resultPageSize = 10
                userPosition = userPoint
                // Рейтинг — это "сниппет": без явного запроса MapKit его просто не пришлёт.
                snippets = SNIPPET_BUSINESS_RATING_1X
            }

            lateinit var session: Session
            val listener = object : Session.SearchListener {
                override fun onSearchResponse(response: Response) {
                    val places = response.collection.children
                        .mapNotNull { it.obj }
                        .mapIndexedNotNull { index, geoObject ->
                            geoObject.toPlacePoint(category, userPoint, index)
                        }
                    release(session, this)
                    if (continuation.isActive) continuation.resume(places)
                }

                override fun onSearchError(error: Error) {
                    release(session, this)
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }

            session = searchManager.submit(
                query,
                // ВАЖНО: поиск поддерживает только point, boundingBox, polyline и polygon.
                // Circle в этот список не входит — передача круга роняет нативную библиотеку
                // без внятной ошибки, поэтому радиус переводим в прямоугольник.
                Geometry.fromBoundingBox(boundingBoxAround(userPoint, radiusMeters)),
                searchOptions,
                listener
            )
            activeSessions.add(session)
            activeListeners.add(listener)

            continuation.invokeOnCancellation {
                session.cancel()
                release(session, listener)
            }
        }
    }

    private fun release(session: Session, listener: Session.SearchListener) {
        activeSessions.remove(session)
        activeListeners.remove(listener)
    }

    /**
     * Переводит "радиус в метрах вокруг точки" в прямоугольник, который понимает поиск MapKit.
     * Приблизительный расчёт: 1° широты ≈ 111 320 м, долгота сжимается пропорционально косинусу широты.
     */
    private fun boundingBoxAround(center: Point, radiusMeters: Float): BoundingBox {
        val metersPerDegreeLat = 111_320.0
        val latDelta = radiusMeters / metersPerDegreeLat
        val lonScale = cos(Math.toRadians(center.latitude)).coerceAtLeast(0.000001)
        val lonDelta = radiusMeters / (metersPerDegreeLat * lonScale)

        return BoundingBox(
            Point(center.latitude - latDelta, center.longitude - lonDelta),
            Point(center.latitude + latDelta, center.longitude + lonDelta)
        )
    }

    private fun GeoObject.toPlacePoint(category: NeedCategory, userPoint: Point, index: Int): PlacePoint? {
        val point = geometry.firstOrNull()?.point ?: return null
        val metadata = metadataContainer.getItem(BusinessObjectMetadata::class.java)
        // Рейтинг приходит отдельным сниппетом, а не внутри BusinessObjectMetadata.
        val rating = metadataContainer.getItem(BusinessRating1xObjectMetadata::class.java)

        return PlacePoint(
            id = "search-${category.name}-$index-${point.latitude}-${point.longitude}",
            name = name ?: metadata?.name ?: "Без названия",
            category = category,
            distanceKm = roundToOneDecimal(haversineKm(userPoint, point)),
            rating = rating?.score?.toDouble() ?: 0.0,
            reviewsCount = rating?.reviews ?: 0,
            onlinePaymentAvailable = false, // MapKit не даёт этот признак напрямую
            hasSubscriptionOption = false,
            approxAvgCheckRub = 0,
            address = metadata?.address?.formattedAddress ?: "",
            latitude = point.latitude,
            longitude = point.longitude,
            ratingsCount = rating?.ratings ?: 0,
            workingHours = metadata?.workingHours?.text,
            phone = metadata?.phones?.firstOrNull()?.formattedNumber,
            yandexOid = metadata?.oid
        )
    }

    private fun roundToOneDecimal(value: Double): Double = (value * 10).let { kotlin.math.round(it) } / 10.0

    private fun haversineKm(a: Point, b: Point): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earthRadiusKm * asin(sqrt(h))
    }
}
