package com.lifenavigator.app.ui.screens.map

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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

    private const val LOG_TAG = "LifeNavigator"

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
     * Идентификатор остановки внутри URI, который отдаёт MapKit.
     *
     * ВАЖНО: для остановок, найденных как топонимы, MapKit отдаёт непрозрачный URI вида
     * `ymapsbm1://geo?data=…` — идентификатора остановки в нём нет, и регулярка ничего
     * не находит. Поэтому прямая ссылка на вкладку расписания срабатывает не всегда.
     */
    private val STOP_ID_REGEX = Regex("stop__[A-Za-z0-9_]+")

    /**
     * Возвращает идентификатор остановки, если место — городская остановка транспорта.
     * По нему можно открыть в Яндекс Картах сразу вкладку расписания.
     */
    fun stopIdOf(place: PlacePoint): String? {
        if (place.category != NeedCategory.TRANSPORT) return null
        return place.yandexUri?.let { STOP_ID_REGEX.find(it)?.value }
    }

    /**
     * Ссылка, открывающая карточку остановки сразу на расписании.
     * Формат `masstransit[stopId]` + `mode=stop` — тот, что использует сама веб-версия
     * Яндекс Карт; на Android такая ссылка подхватывается приложением Карт,
     * а без него открывается веб-версия с тем же расписанием.
     */
    private fun stopScheduleUrl(place: PlacePoint, stopId: String): String =
        "https://yandex.ru/maps/?ll=${place.longitude},${place.latitude}" +
            "&masstransit%5BstopId%5D=$stopId&mode=stop&z=18"

    /** Идентификатор организации внутри URI вида `ymapsbm1://org?oid=1234567890`. */
    private val OID_REGEX = Regex("oid=([0-9]+)")

    /**
     * Открывает объект в Яндекс Картах.
     *
     * Порядок выстроен по фактам из логов, а не по догадкам:
     *
     * 1. Карточка по `oid` — она же карточка остановки. Логи показали, что MapKit отдаёт
     *    остановки как организации (`ymapsbm1://org/…`), и именно карточка организации
     *    содержит вкладки «Обзор / Расписание / Фото / Отзывы». Раньше для транспорта
     *    этот шаг пропускался, поэтому оставался только поиск — что и раздражало.
     * 2. Ссылка по stopId, если он вдруг есть.
     * 3. Поиск по названию — крайний случай.
     *
     * Схему `ymapsbm1://` из цепочки убрали: в логе видно, что её никто не открывает
     * (`START_INTENT_NOT_RESOLVED`, код −91), то есть это была бесполезная попытка.
     */
    fun openPlaceCard(context: Context, place: PlacePoint) {
        val stopId = stopIdOf(place)
        // oid берём из поля, а если его нет — вытаскиваем из URI объекта.
        val oid = place.yandexOid?.takeIf { it.isNotBlank() }
            ?: place.yandexUri?.let { OID_REGEX.find(it)?.groupValues?.get(1) }

        val candidates = buildList {
            stopId?.let { add(stopScheduleUrl(place, it)) }
            oid?.let {
                // tab=schedule — догадка: в документации URL-схемы такого параметра нет.
                // Неизвестные параметры приложение обычно игнорирует, поэтому худшее,
                // что будет — карточка откроется на «Обзоре», как сейчас.
                add("yandexmaps://maps.yandex.ru/?oid=$it&tab=schedule")
                add("yandexmaps://maps.yandex.ru/?oid=$it")
            }
            add(
                "yandexmaps://maps.yandex.ru/?ll=${place.longitude},${place.latitude}" +
                    "&z=17&text=${Uri.encode(place.name)}"
            )
            add(searchWebUrl(place))
        }

        Log.d(LOG_TAG, "openPlaceCard name=${place.name} rubric=${place.categoryDescription} " +
            "uri=${place.yandexUri} oid=$oid stopId=$stopId")

        openFirstAvailable(context, candidates)
    }

    private fun openFirstAvailable(context: Context, uris: List<String>) {
        for (uri in uris) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                Log.d(LOG_TAG, "opened: $uri")
                return
            } catch (e: ActivityNotFoundException) {
                Log.d(LOG_TAG, "no app for: $uri")
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
