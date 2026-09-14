package com.lifenavigator.app.ui.screens.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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

    /** Типы маршрута, которые понимает приложение Яндекс Карт. */
    enum class RouteType(val code: String, val title: String) {
        AUTO("auto", "На машине"),
        PUBLIC_TRANSPORT("mt", "На транспорте"),
        WALK("pd", "Пешком")
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
     * Открывает карточку организации — там будут отзывы, фотографии и полное описание.
     * Если Яндекс не дал идентификатор организации, открываем поиск по названию рядом с точкой.
     */
    fun openPlaceCard(context: Context, place: PlacePoint) {
        val oid = place.yandexOid
        if (!oid.isNullOrBlank()) {
            open(
                context = context,
                appUri = "yandexmaps://maps.yandex.ru/?oid=$oid",
                // Веб-версия параметр oid официально не документирует, поэтому
                // в браузере подстраховываемся поиском по названию рядом с координатами.
                webUri = searchWebUrl(place)
            )
        } else {
            open(
                context = context,
                appUri = "yandexmaps://maps.yandex.ru/?ll=${place.longitude},${place.latitude}" +
                    "&z=17&text=${Uri.encode(place.name)}",
                webUri = searchWebUrl(place)
            )
        }
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
