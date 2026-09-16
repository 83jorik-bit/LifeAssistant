package com.lifenavigator.app.data.repository

import com.lifenavigator.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Один рейс в расписании станции. */
data class Departure(
    val time: String,
    val title: String,
    val transportType: String,
    val number: String?,
    val days: String?,
    val platform: String?
)

/** Расписание станции: сама станция + ближайшие рейсы. */
data class StationSchedule(
    val stationTitle: String,
    val stationTypeName: String?,
    val distanceKm: Double,
    val departures: List<Departure>,
    /** Ссылка на полное расписание на сайте Яндекса — там же покупка билетов. */
    val fullScheduleUrl: String?
)

/** Результат попытки получить расписание. */
sealed class ScheduleResult {
    data class Success(val schedule: StationSchedule) : ScheduleResult()
    /** Станции нет в базе Расписаний — обычная городская остановка. */
    data object NotFound : ScheduleResult()
    data class Error(val message: String) : ScheduleResult()
}

/**
 * Клиент API Яндекс Расписаний.
 *
 * Важное ограничение самого сервиса: он про МЕЖДУГОРОДНИЙ транспорт — электрички,
 * поезда, автобусы дальнего следования, паромы, самолёты. Городских автобусных остановок
 * и маршрутов в нём нет, об этом прямо сказано в документации Яндекса. Поэтому для
 * обычной остановки у дома здесь честно вернётся [ScheduleResult.NotFound].
 *
 * Сеть сделана на HttpURLConnection без дополнительных библиотек: запросов всего два,
 * тянуть ради них Retrofit/OkHttp смысла нет.
 */
object RaspRepository {

    private const val BASE_URL = "https://api.rasp.yandex-net.ru/v3.0"
    private const val TIMEOUT_MS = 10_000

    /** Сколько ближайших рейсов показываем в карточке. */
    private const val MAX_DEPARTURES = 12

    suspend fun loadSchedule(
        latitude: Double,
        longitude: Double,
        placeName: String
    ): ScheduleResult = withContext(Dispatchers.IO) {
        if (BuildConfig.RASP_API_KEY.isBlank()) {
            return@withContext ScheduleResult.Error(
                "Не задан ключ API Яндекс Расписаний (RASP_API_KEY)"
            )
        }

        try {
            val station = findStation(latitude, longitude, placeName)
                ?: return@withContext ScheduleResult.NotFound

            val departures = loadDepartures(station.code)
            ScheduleResult.Success(
                StationSchedule(
                    stationTitle = station.title,
                    stationTypeName = station.typeName,
                    distanceKm = station.distanceKm,
                    departures = departures,
                    fullScheduleUrl = station.scheduleUrl
                )
            )
        } catch (e: Exception) {
            ScheduleResult.Error(e.message ?: "Не удалось получить расписание")
        }
    }

    private data class Station(
        val code: String,
        val title: String,
        val typeName: String?,
        val distanceKm: Double,
        val scheduleUrl: String?
    )

    /**
     * Ищет станцию рядом с точкой и сопоставляет её с названием места.
     *
     * Сопоставление важно: если просто взять ближайшую станцию, то для остановки автобуса
     * у дома можно случайно подсунуть железнодорожную платформу в квартале отсюда
     * и показать расписание электричек как расписание этой остановки. Поэтому берём
     * станцию либо с совпадающим названием, либо совсем рядом (до 300 м).
     */
    private fun findStation(latitude: Double, longitude: Double, placeName: String): Station? {
        val url = "$BASE_URL/nearest_stations/?" +
            "apikey=${BuildConfig.RASP_API_KEY}" +
            "&format=json&lang=ru_RU" +
            "&lat=$latitude&lng=$longitude" +
            "&distance=2&limit=50"

        val stations = JSONObject(httpGet(url)).optJSONArray("stations") ?: return null
        val normalizedPlace = normalize(placeName)

        var byName: Station? = null
        var nearest: Station? = null

        for (i in 0 until stations.length()) {
            val item = stations.optJSONObject(i) ?: continue
            val code = item.optString("code").takeIf { it.isNotBlank() } ?: continue
            val title = item.optString("title")
            val distance = item.optDouble("distance", Double.MAX_VALUE)

            val station = Station(
                code = code,
                title = title,
                typeName = item.optString("station_type_name").takeIf { it.isNotBlank() },
                distanceKm = distance,
                scheduleUrl = extractScheduleUrl(item.optJSONObject("type_choices"))
            )

            val normalizedTitle = normalize(title)
            val namesMatch = normalizedTitle.isNotBlank() && normalizedPlace.isNotBlank() &&
                (normalizedPlace.contains(normalizedTitle) || normalizedTitle.contains(normalizedPlace))

            if (namesMatch && (byName == null || distance < byName.distanceKm)) {
                byName = station
            }
            if (nearest == null || distance < nearest.distanceKm) {
                nearest = station
            }
        }

        return byName ?: nearest?.takeIf { it.distanceKm <= 0.3 }
    }

    /** Из type_choices берём любую доступную ссылку на расписание (мобильную версию). */
    private fun extractScheduleUrl(typeChoices: JSONObject?): String? {
        if (typeChoices == null) return null
        val keys = typeChoices.keys()
        while (keys.hasNext()) {
            val entry = typeChoices.optJSONObject(keys.next()) ?: continue
            val url = entry.optString("touch_url").takeIf { it.isNotBlank() }
                ?: entry.optString("desktop_url").takeIf { it.isNotBlank() }
            if (url != null) return url
        }
        return null
    }

    private fun loadDepartures(stationCode: String): List<Departure> {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val url = "$BASE_URL/schedule/?" +
            "apikey=${BuildConfig.RASP_API_KEY}" +
            "&format=json&lang=ru_RU" +
            "&station=${URLEncoder.encode(stationCode, "UTF-8")}" +
            "&date=$today&event=departure"

        val schedule = JSONObject(httpGet(url)).optJSONArray("schedule") ?: return emptyList()
        val now = OffsetDateTime.now()
        val result = mutableListOf<Departure>()

        for (i in 0 until schedule.length()) {
            val item = schedule.optJSONObject(i) ?: continue
            val departureRaw = item.optString("departure").takeIf { it.isNotBlank() } ?: continue
            val departureTime = runCatching { OffsetDateTime.parse(departureRaw) }.getOrNull() ?: continue
            // Уже ушедшие рейсы не показываем.
            if (departureTime.isBefore(now)) continue

            val thread = item.optJSONObject("thread")
            result += Departure(
                time = departureTime.format(DateTimeFormatter.ofPattern("HH:mm")),
                title = thread?.optString("short_title")?.takeIf { it.isNotBlank() }
                    ?: thread?.optString("title").orEmpty(),
                transportType = thread?.optJSONObject("transport_subtype")?.optString("title")
                    ?.takeIf { it.isNotBlank() }
                    ?: thread?.optString("transport_type").orEmpty(),
                number = thread?.optString("number")?.takeIf { it.isNotBlank() },
                days = item.optString("days").takeIf { it.isNotBlank() },
                platform = item.optString("platform").takeIf { it.isNotBlank() }
            )
            if (result.size >= MAX_DEPARTURES) break
        }

        return result
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Яндекс Расписания ответили кодом $code")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Приводим названия к сопоставимому виду: «ст. Лось» и «Лось» должны совпасть. */
    private fun normalize(value: String): String = value
        .lowercase()
        .replace("ё", "е")
        .replace(Regex("(станция|платформа|остановка|ж/д|жд|ст\\.|о\\.п\\.)"), "")
        .replace(Regex("[^а-яa-z0-9]"), "")
        .trim()
}
