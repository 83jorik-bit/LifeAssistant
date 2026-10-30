package com.lifenavigator.app.data.repository

import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.lifenavigator.app.data.model.transitStopId
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
import com.yandex.mapkit.uri.UriObjectMetadata
import com.yandex.runtime.Error
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    // SearchManager requests are native MapKit sessions. Limit concurrent requests
    // across all categories/queries rather than launching dozens at once.
    private val requestSemaphore = Semaphore(4)

    /**
     * На каждую категорию — несколько формулировок запроса.
     *
     * Почему так: поиск Яндекса текстовый, и одна формулировка выдаёт далеко не всё.
     * По запросу «продуктовый магазин» сети вроде «Магнита» и «Пятёрочки» могут не попасть
     * в первую страницу выдачи, а «кофе с собой» и маленькие пекарни не находятся по слову
     * «кафе». Несколько запросов с объединением и удалением дублей закрывают эти пробелы.
     */
    private val CATEGORY_QUERIES: Map<NeedCategory, List<String>> = mapOf(
        NeedCategory.GROCERY to listOf("продукты", "супермаркет", "магазин у дома"),
        NeedCategory.CAFE to listOf("кафе", "ресторан", "кофейня", "кофе с собой", "пекарня"),
        NeedCategory.PHARMACY to listOf("аптека"),
        NeedCategory.CLOTHING to listOf("магазин одежды", "обувной магазин"),
        NeedCategory.BEAUTY to listOf("салон красоты", "парикмахерская"),
        NeedCategory.PICKUP_POINT to listOf("пункт выдачи заказов", "постамат"),
        NeedCategory.ATM to listOf("банкомат"),
        // Транспорт: наземный, метро, пригородные поезда и водный.
        NeedCategory.TRANSPORT to listOf(
            "остановка общественного транспорта",
            "трамвайная остановка",
            "станция метро",
            "железнодорожная станция",
            "платформа электричек",
            "автовокзал",
            "речной вокзал",
            "причал"
        ),
        NeedCategory.EDUCATION to listOf("школа", "детский сад", "колледж", "курсы"),
        NeedCategory.CLINIC to listOf("клиника", "поликлиника", "медицинский центр")
    )

    /**
     * Для остановок и станций метро ищем не только организации, но и топонимы:
     * остановка — это объект карты, а не компания, и по типу BIZ она не находится.
     */
    private fun searchTypesFor(category: NeedCategory): Int =
        if (category == NeedCategory.TRANSPORT) {
            // Только GEO и BIZ: в Android-версии SearchType других значений НЕТ
            // (официальный справочник перечисляет NONE, GEO, BIZ — и всё).
            // Тип TRANSIT существует в биндингах для других платформ, но там же
            // указано, что реализованы только none/geo/biz. Поэтому получить через
            // поиск сам объект остановки с идентификатором stop__… невозможно:
            // остановка приходит либо топонимом с непрозрачным URI, либо одноимённой
            // организацией, у карточки которой вкладки «Расписание» нет.
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
                // Остановка приходит дважды: как транспортный объект и как одноимённая
                // организация. Оставляем транспортный — только у него есть расписание.
                .let(::preferTransitObjects)
                // Геометрия в запросе для Яндекса — подсказка о том, где искать, а не жёсткий
                // фильтр: особенно для топонимов (остановки, станции) он спокойно возвращает
                // объекты далеко за пределами области. Поэтому режем по фактическому
                // расстоянию сами, иначе транспорт лез на весь город независимо от радиуса.
                .filter { it.distanceKm * 1000 <= radiusMeters }
                .sortedBy { it.distanceKm }
        }
    }

    /**
     * Схлопывает дубли «остановка + одноимённая организация» в одну запись,
     * оставляя ту, у которой есть идентификатор остановки.
     */
    private fun preferTransitObjects(places: List<PlacePoint>): List<PlacePoint> =
        places
            .groupBy { place ->
                // Ключ — название плюс огрублённые координаты: у остановки и организации
                // они совпадают, а точные координаты могут отличаться на пару метров.
                val lat = kotlin.math.round(place.latitude * 1000) / 1000.0
                val lon = kotlin.math.round(place.longitude * 1000) / 1000.0
                "${place.name.lowercase()}@$lat,$lon"
            }
            .map { (_, group) -> group.firstOrNull { it.transitStopId != null } ?: group.first() }

    /**
     * Ищет конкретное место по названию рядом с точкой — для сопоставления трат
     * с реальными магазинами («Пятёрочка» из банковского уведомления → ближайшая
     * Пятёрочка на карте).
     *
     * Возвращает ближайшее место, в названии которого действительно есть искомое.
     * Если такого нет — null, а не «что-то похожее рядом»: неверно привязать трату
     * к чужому магазину хуже, чем не привязать вовсе.
     */
    suspend fun searchByName(
        userPoint: Point,
        name: String,
        radiusMeters: Float = 3000f
    ): PlacePoint? {
        // Категория здесь служебная: runQuery требует её для типа поиска (организации),
        // в результат сопоставления она не попадает.
        val results = runQuery(userPoint, NeedCategory.GROCERY, name, radiusMeters)
        val needle = name.lowercase().replace("ё", "е")
        return results
            .filter { it.name.lowercase().replace("ё", "е").contains(needle) }
            .minByOrNull { it.distanceKm }
    }

    private suspend fun runQuery(
        userPoint: Point,
        category: NeedCategory,
        query: String,
        radiusMeters: Float
    ): List<PlacePoint> {
        return requestSemaphore.withPermit {
            suspendCancellableCoroutine { continuation ->
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
            isOpen24 = isRoundTheClock(metadata?.workingHours?.text),
            // Собственный URI объекта. Для остановки он ведёт на карточку остановки,
            // а не на организацию — именно там показываются проходящие маршруты.
            yandexUri = metadataContainer.getItem(UriObjectMetadata::class.java)
                ?.uris
                ?.firstOrNull()
                ?.value
        )
    }

    /**
     * Круглосуточность определяем по тексту графика работы ("ежедневно, круглосуточно").
     * Разбирать структуру расписания через WorkingHours.availabilities точнее, но текст
     * Яндекс формирует сам и для русской выдачи он предсказуем.
     */
    private fun isRoundTheClock(workingHoursText: String?): Boolean {
        val text = workingHoursText?.lowercase() ?: return false
        return "круглосуточно" in text || "круглосуточн" in text || "24 часа" in text
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
