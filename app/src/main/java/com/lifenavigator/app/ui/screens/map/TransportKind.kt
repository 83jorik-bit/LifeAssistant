package com.lifenavigator.app.ui.screens.map

import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint

/**
 * Вид транспортной точки. Определяется по названию и рубрике из Яндекс Карт.
 *
 * Зачем это нужно:
 * 1. Значок на карте — свой для автобуса, поезда, метро и водного транспорта.
 * 2. Откуда брать расписание. Яндекс Расписания знают междугородний транспорт:
 *    электрички, поезда, автобусы дальнего следования, паромы. Городских автобусных
 *    маршрутов там нет — по обычной остановке он отвечает «рейсов нет», хотя в Яндекс
 *    Картах расписание есть. Поэтому для городских остановок и метро сразу уходим в Карты.
 */
enum class TransportKind(val emoji: String, val usesRaspSchedule: Boolean) {
    /** Автобус, троллейбус, трамвай — городская остановка. */
    BUS_STOP("🚌", usesRaspSchedule = false),

    /** Станция метро: расписания как такового нет, есть схема линий в Картах. */
    METRO("Ⓜ️", usesRaspSchedule = false),

    /** Ж/д станция, платформа, вокзал — здесь Расписания дают реальные рейсы. */
    RAILWAY("🚂", usesRaspSchedule = true),

    /** Причал, пристань, речной или морской вокзал. */
    WATER("⛴️", usesRaspSchedule = true),

    /** Аэропорт и всё остальное междугороднее. */
    OTHER("🚌", usesRaspSchedule = true)
}

/** Определяет вид транспортной точки. Для нетранспортных мест возвращает null. */
fun transportKindOf(place: PlacePoint): TransportKind? {
    if (place.category != NeedCategory.TRANSPORT) return null

    val text = listOfNotNull(place.name, place.categoryDescription)
        .joinToString(" ")
        .lowercase()

    return when {
        "метро" in text -> TransportKind.METRO
        listOf("причал", "пристань", "речной", "морской", "порт", "паром").any { it in text } ->
            TransportKind.WATER
        listOf("железнодорожн", "ж/д", "жд ", "платформа", "вокзал", "электрич", "станция")
            .any { it in text } -> TransportKind.RAILWAY
        listOf("остановка", "автобус", "трамвай", "троллейбус", "маршрутк").any { it in text } ->
            TransportKind.BUS_STOP
        else -> TransportKind.OTHER
    }
}

/** Эмодзи для метки на карте: у транспорта — по виду, у остальных — эмодзи категории. */
fun markerEmojiOf(place: PlacePoint): String =
    transportKindOf(place)?.emoji ?: place.category.emoji
