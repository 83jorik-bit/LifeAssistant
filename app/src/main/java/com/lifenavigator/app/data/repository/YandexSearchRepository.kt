package com.lifenavigator.app.data.repository

import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PaymentMethod
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
import com.yandex.mapkit.search.ToponymObjectMetadata
import com.yandex.runtime.Error
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /**
     * На каждую категорию — несколько формулировок запроса.
     *
     * Почему так: поиск Яндекса текстовый, и одна формулировка выдаёт далеко не всё.
     * По запросу «продуктовый магазин» сети вроде «Магнита» и «Пятёрочки» могут не попасть
     * в первую страницу выдачи, а «кофе с собой» и маленькие пекарни не находятся по слову
     * «кафе». Несколько запросов с объединением и удалением дублей закрывают эти пробелы.
     */
    private val CATEGORY_QUERIES: Map<NeedCategory, List<String>> = mapOf(
        NeedCategory.PHARMACY to listOf("аптека"),
        NeedCategory.GROCERY to listOf("продукты", "супермаркет", "магазин у дома"),
        NeedCategory.CLINIC to listOf("клиника", "поликлиника", "медицинский центр"),
        NeedCategory.BEAUTY to listOf("салон красоты", "парикмахерская"),
        NeedCategory.PICKUP_POINT to listOf("пункт выдачи заказов", "постамат"),
        NeedCategory.CAFE to listOf("кафе", "ресторан", "кофейня", "кофе с собой", "пекарня"),
        NeedCategory.CLOTHING to listOf("магазин одежды", "обувной магазин"),
        NeedCategory.EDUCATION to listOf("школа", "детский сад", "колледж", "курсы"),
        NeedCategory.TRANSPORT to listOf("остановка общественного транспорта", "станция метро")
    )

    /**
     * Для остановок и станций метро ищем не только организации, но и топонимы:
     * остановка — это объект карты, а не компания, и по типу BIZ она не находится.
     */
    private fun searchTypesFor(category: NeedCategory): Int =
        if (category == NeedCategory.TRANSPORT) {
            SearchType.GEO.value or SearchType.BIZ.value
        } else {
            SearchType.BIZ.value
        }

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
        val queries = CATEGORY_QUERIES[category] ?: return emptyList()

        return coroutineScope {
            queries
                .map { query -> async { runQuery(userPoint, category, query, radiusMeters) } }
                .awaitAll()
                .flatten()
                // Одна организация попадает в выдачу сразу по нескольким формулировкам —
                // убираем повторы (id строится на идентификаторе организации либо координатах).
                .distinctBy { it.id }
                .sortedBy { it.distanceKm }
        }
    }

    private suspend fun runQuery(
        userPoint: Point,
        category: NeedCategory,
        query: String,
        radiusMeters: Float
    ): List<PlacePoint> {
        return suspendCancellableCoroutine { continuation ->
            val searchOptions = SearchOptions().apply {
                searchTypes = searchTypesFor(category)
                // Было 10 — из-за этого часть организаций просто не доезжала до списка.
                resultPageSize = 30
                userPosition = userPoint
                // Рейтинг — это "сниппет": без явного запроса MapKit его просто не пришлёт.
                snippets = SNIPPET_BUSINESS_RATING_1X
            }

            lateinit var session: Session
            val listener = object : Session.SearchListener {
                override fun onSearchResponse(response: Response) {
                    val places = response.collection.children
                        .mapNotNull { it.obj }
                        .mapNotNull { geoObject -> geoObject.toPlacePoint(category, userPoint) }
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

    private fun GeoObject.toPlacePoint(category: NeedCategory, userPoint: Point): PlacePoint? {
        val point = geometry.firstOrNull()?.point ?: return null
        val metadata = metadataContainer.getItem(BusinessObjectMetadata::class.java)
        // Остановки и станции метро приходят топонимами — у них нет "бизнесовых" данных.
        val toponym = metadataContainer.getItem(ToponymObjectMetadata::class.java)
        // Рейтинг приходит отдельным сниппетом, а не внутри BusinessObjectMetadata.
        val rating = metadataContainer.getItem(BusinessRating1xObjectMetadata::class.java)

        // Идентификатор организации — самый надёжный ключ для склейки дублей между запросами.
        // Если его нет, опираемся на координаты.
        val stableId = metadata?.oid?.takeIf { it.isNotBlank() }
            ?: "${point.latitude},${point.longitude}"

        return PlacePoint(
            id = "yandex-$stableId",
            name = name ?: metadata?.name ?: "Без названия",
            category = category,
            distanceKm = roundToOneDecimal(haversineKm(userPoint, point)),
            rating = rating?.score?.toDouble() ?: 0.0,
            reviewsCount = rating?.reviews ?: 0,
            onlinePaymentAvailable = false, // MapKit не даёт этот признак напрямую
            hasSubscriptionOption = false,
            approxAvgCheckRub = 0,
            address = metadata?.address?.formattedAddress
                ?: toponym?.address?.formattedAddress
                ?: "",
            latitude = point.latitude,
            longitude = point.longitude,
            ratingsCount = rating?.ratings ?: 0,
            workingHours = metadata?.workingHours?.text,
            phone = metadata?.phones?.firstOrNull()?.formattedNumber,
            yandexOid = metadata?.oid,
            categoryDescription = metadata?.categories
                ?.mapNotNull { it.name }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", "),
            paymentMethods = detectPaymentMethods(metadata)
        )
    }

    /**
     * Пытается понять способы оплаты из списка "особенностей" организации.
     *
     * ВАЖНО об ограничении: распознавание идёт по тексту названия особенности и её
     * перечислимых значений («Оплата картой», «Наличный расчёт», «СБП»). Само значение
     * булевых особенностей здесь НЕ читается — точную сигнатуру Android-класса
     * Feature.VariantValue подтвердить не удалось, а гадать с ней не хочу, иначе
     * рискуем не собрать проект. Практическое следствие: если Яндекс указал
     * «оплата картой — нет», иконка всё равно появится. Как только убедимся, что
     * сборка проходит, это можно уточнить, читая значение особенности.
     */
    private fun detectPaymentMethods(metadata: BusinessObjectMetadata?): Set<PaymentMethod> {
        val features = metadata?.features ?: return emptySet()
        val result = mutableSetOf<PaymentMethod>()

        features.forEach { feature ->
            val texts = buildList {
                feature.name?.let { add(it) }
                feature.value?.enumValue?.forEach { enumValue -> enumValue.name?.let { add(it) } }
                feature.value?.textValue?.forEach { add(it) }
            }.joinToString(" ").lowercase()

            if (texts.isBlank()) return@forEach
            // Отсекаем явные отрицания вида "оплата картой: нет".
            if ("нет" in texts || "не приним" in texts) return@forEach

            if ("карт" in texts) result += PaymentMethod.CARD
            if ("сбп" in texts || "qr" in texts || "быстрых платеж" in texts) result += PaymentMethod.SBP
            if ("налич" in texts) result += PaymentMethod.CASH
        }

        return result
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
