package com.lifenavigator.app.ui.screens.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import com.yandex.mapkit.geometry.Point

/**
 * Запуск приложения Яндекс Карты по URL-схеме `yandexmaps://`.
 *
 * Зачем это нужно: MapKit SDK даёт карту, поиск и данные об организациях, но
 * НЕ отдаёт тексты отзывов и не умеет показывать готовую карточку организации.
 * Поэтому за отзывами и полным описанием отправляем пользователя в Яндекс Карты,
 * а сюда возвращаем то, что SDK отдаёт честно (рейтинг, часы работы, телефон).
 *
 * Если приложение Яндекс Карт не установлено — открываем тот же сценарий в браузере.
 */
object YandexMapsLauncher {

    /**
     * Типы маршрута, которые передаются в приложение Яндекс Карт параметром `rtt`.
     *
     * ВНИМАНИЕ про самокат: в официальной документации URL-схемы перечислены только
     * `auto`, `mt` и `pd`. Значение для самоката там не описано — `sc` взято по аналогии
     * и НЕ проверено. Если приложение откроет маршрут не в том режиме, значит код другой:
     * по документации при нераспознанном `rtt` используется режим из прошлой сессии.
     * Такси сюда не входит — оно уходит в Яндекс Go отдельной ссылкой (см. [orderTaxi]).
     */
    enum class RouteType(val code: String, val title: String) {
        AUTO("auto", "Машина"),
        PUBLIC_TRANSPORT("mt", "Транспорт"),
        WALK("pd", "Пешком"),
        SCOOTER("sc", "Самокат")
    }

    /**
     * Заказ такси через Яндекс Go по официальному диплинку.
     * Если приложение Go не установлено, ссылка сама уводит на его страницу в магазине.
     */
    fun orderTaxi(context: Context, from: Point?, place: PlacePoint) {
        if (from == null) {
            Toast.makeText(
                context,
                "Сначала определите своё местоположение — кнопка с прицелом на карте",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val url = buildString {
            append("https://3.redirect.appmetrica.yandex.com/route")
            append("?start-lat=${from.latitude}&start-lon=${from.longitude}")
            append("&end-lat=${place.latitude}&end-lon=${place.longitude}")
            append("&ref=lifenavigator")
            // Идентификатор из документации Яндекс Go: если приложение не установлено,
            // пользователь попадёт на его страницу в магазине приложений.
            append("&appmetrica_tracking_id=1178268795219780156")
        }

        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Не нашлось приложения, чтобы открыть ссылку", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Строит маршрут от [from] до [place]. Если местоположение пользователя ещё
     * неизвестно, честно говорим об этом вместо того, чтобы строить маршрут "из ниоткуда".
     */
    fun buildRoute(context: Context, from: Point?, place: PlacePoint, routeType: RouteType) {
        if (from == null) {
            Toast.makeText(
                context,
                "Сначала определите своё местоположение — кнопка с прицелом на карте",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Внимание: в rtext координаты идут в порядке "широта,долгота".
        val rtext = "${from.latitude},${from.longitude}~${place.latitude},${place.longitude}"
        open(
            context = context,
            appUri = "yandexmaps://maps.yandex.ru/?rtext=$rtext&rtt=${routeType.code}",
            webUri = "https://yandex.ru/maps/?rtext=$rtext&rtt=${routeType.code}"
        )
    }

    /**
     * Открывает объект в Яндекс Картах.
     *
     * Порядок попыток важен:
     * 1. Собственный URI объекта (`ymapsbm1://…`), который отдаёт сам MapKit. Для остановки
     *    это ссылка именно на карточку остановки — с расписанием и маршрутами.
     * 2. Карточка организации по oid — для магазинов, кафе и прочего бизнеса.
     *    Для остановок этот шаг ПРОПУСКАЕТСЯ: часть станций заведена в Яндексе ещё и как
     *    организация, и открытие по oid приводило на обзор с отзывами вместо расписания.
     * 3. Поиск по названию рядом с координатами.
     * 4. Веб-версия.
     */
    fun openPlaceCard(context: Context, place: PlacePoint) {
        val isTransport = place.category == NeedCategory.TRANSPORT

        val candidates = buildList {
            place.yandexUri?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (!isTransport) {
                place.yandexOid?.takeIf { it.isNotBlank() }?.let { add("yandexmaps://maps.yandex.ru/?oid=$it") }
            }
            add(
                "yandexmaps://maps.yandex.ru/?ll=${place.longitude},${place.latitude}" +
                    "&z=17&text=${Uri.encode(place.name)}"
            )
            add(searchWebUrl(place))
        }

        openFirstAvailable(context, candidates)
    }

    private fun openFirstAvailable(context: Context, uris: List<String>) {
        for (uri in uris) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                return
            } catch (e: ActivityNotFoundException) {
                // пробуем следующий вариант
            }
        }
        Toast.makeText(context, "Не нашлось приложения, чтобы открыть ссылку", Toast.LENGTH_LONG).show()
    }

    private fun searchWebUrl(place: PlacePoint): String =
        "https://yandex.ru/maps/?ll=${place.longitude},${place.latitude}" +
            "&z=17&text=${Uri.encode(place.name)}"

    private fun open(context: Context, appUri: String, webUri: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appUri)))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(context, "Не нашлось приложения, чтобы открыть карты", Toast.LENGTH_LONG).show()
            }
        }
    }
}
