package com.lifenavigator.app.data.model

/**
 * Вид транспортной точки. Нужен для двух вещей: значок на карте и выбор источника
 * расписания (городской транспорт живёт в Яндекс Картах, междугородний — в Яндекс Расписаниях).
 */
enum class TransportKind(val glyph: String, val markerColorArgb: Long) {
    /** Автобус, троллейбус, трамвай — городская остановка. */
    BUS("🚌", 0xFF1A3E8C),

    /** Станция метро. */
    METRO("🚇", 0xFFD0021B),

    /** Ж/д станция, платформа, вокзал. */
    RAIL("🚂", 0xFF6D4C41),

    /** Причал, пристань, речной или морской вокзал. */
    WATER("⛴", 0xFF0277BD)
}

/**
 * Ищет слово целиком, а не как часть другого слова.
 *
 * Это не придирка: подстрочный поиск давал ложные срабатывания на ровном месте.
 * Рубрика «Остановка общественного транспорта» содержит «транспорта», внутри которого
 * есть «порт» — и все обычные автобусные остановки получали значок кораблика.
 * Аналогично «Спортивная» содержит «порт», а «Метро Котельники» — это название
 * автобусной остановки, а вовсе не станция метро.
 */
private fun containsWord(text: String, vararg roots: String): Boolean =
    roots.any { root -> Regex("(^|[^а-яёa-z])$root", RegexOption.IGNORE_CASE).containsMatchIn(text) }

/**
 * Определяет вид транспорта.
 *
 * Сначала смотрим рубрику Яндекса — она описывает сам объект («Станция метро»,
 * «Железнодорожная станция», «Остановка общественного транспорта»). И только если рубрики
 * нет, разбираем название: названия обманчивы — остановка «Метро Котельники» это автобус,
 * а не метро.
 */
fun PlacePoint.transportKind(): TransportKind? {
    if (category != NeedCategory.TRANSPORT) return null

    val rubric = categoryDescription?.lowercase().orEmpty()
    if (rubric.isNotBlank()) {
        kindFromText(rubric)?.let { return it }
    }
    return kindFromText(name.lowercase()) ?: TransportKind.BUS
}

private fun kindFromText(text: String): TransportKind? = when {
    // Остановка — всегда городской транспорт, даже если названа «Метро Котельники».
    containsWord(text, "остановк", "автобус", "трамва", "троллейбус", "маршрутк") -> TransportKind.BUS
    containsWord(text, "метро", "мцк", "мцд", "подземк") -> TransportKind.METRO
    containsWord(text, "причал", "пристан", "порт", "паром", "речн", "морск", "судоход") -> TransportKind.WATER
    containsWord(text, "вокзал", "железнодорож", "ж/д", "жд", "платформ", "электричк",
        "станци", "поезд", "полустан") -> TransportKind.RAIL
    else -> null
}

/**
 * Есть ли смысл искать расписание в Яндекс Расписаниях.
 *
 * Расписания знают только МЕЖДУГОРОДНИЙ транспорт. Для городского автобуса и метро
 * там пусто — их расписание и маршруты есть только в Яндекс Картах, поэтому для них
 * сразу уходим туда, а не показываем пустой список.
 */
fun PlacePoint.hasIntercitySchedule(): Boolean =
    when (transportKind()) {
        TransportKind.RAIL, TransportKind.WATER -> true
        else -> false
    }

/** Значок и цвет метки на карте: для транспорта — по виду, для остального — по категории. */
data class MarkerStyle(val key: String, val glyph: String, val colorArgb: Long)

fun markerStyleFor(place: PlacePoint): MarkerStyle {
    val kind = place.transportKind()
    return if (kind != null) {
        MarkerStyle("TRANSPORT_${kind.name}", kind.glyph, kind.markerColorArgb)
    } else {
        MarkerStyle(place.category.name, place.category.emoji, place.category.colorArgb)
    }
}

/** Все возможные стили меток — чтобы заранее нарисовать растры и переиспользовать их. */
fun allMarkerStyles(): List<MarkerStyle> =
    NeedCategory.entries.map { MarkerStyle(it.name, it.emoji, it.colorArgb) } +
        TransportKind.entries.map { MarkerStyle("TRANSPORT_${it.name}", it.glyph, it.markerColorArgb) }

/**
 * Идентификатор транспортной остановки (`stop__9643291`) внутри URI объекта.
 *
 * Появляется только у объектов, найденных с типом поиска TRANSIT. У организации
 * с тем же названием его нет — и именно поэтому раньше вместо карточки остановки
 * с расписанием открывалась карточка организации без него.
 */
private val STOP_ID_REGEX = Regex("stop__[A-Za-z0-9_-]+")

val PlacePoint.transitStopId: String?
    get() = yandexUri?.let { STOP_ID_REGEX.find(it)?.value }
