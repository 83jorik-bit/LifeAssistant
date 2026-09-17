package com.lifenavigator.app.data.model

/**
 * Вид транспортной точки. Нужен для двух вещей: значок на карте и выбор источника
 * расписания (городской транспорт живёт в Яндекс Картах, междугородний — в Яндекс Расписаниях).
 */
enum class TransportKind(val glyph: String, val markerColorArgb: Long) {
    /** Автобус, троллейбус, трамвай — городская остановка. */
    BUS("🚌", 0xFF1A3E8C),

    /** Станция метро. Значок — красная «М», как эмблема метрополитена. */
    METRO("М", 0xFFD0021B),

    /** Ж/д станция, платформа, вокзал. */
    RAIL("🚂", 0xFF6D4C41),

    /** Причал, пристань, речной или морской вокзал. */
    WATER("⛴", 0xFF0277BD)
}

/**
 * Определяет вид транспорта по названию и рубрике из Яндекса.
 *
 * Порядок проверок важен: «станция метро» содержит и «станци», и «метро», поэтому
 * метро проверяется раньше железной дороги.
 */
fun PlacePoint.transportKind(): TransportKind? {
    if (category != NeedCategory.TRANSPORT) return null

    val text = listOfNotNull(name, categoryDescription).joinToString(" ").lowercase()

    return when {
        "метро" in text || "мцк" in text || "мцд" in text -> TransportKind.METRO
        listOf("причал", "пристан", "порт", "паром", "речн", "морск").any { it in text } -> TransportKind.WATER
        listOf("вокзал", "железнодорож", "ж/д", "жд ", "платформ", "электричк", "станция")
            .any { it in text } -> TransportKind.RAIL
        else -> TransportKind.BUS
    }
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
