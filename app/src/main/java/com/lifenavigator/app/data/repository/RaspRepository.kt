package com.lifenavigator.app.data.repository

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
    /** Рейс уже на завтра — сегодня рейсов не осталось. */
    val isTomorrow: Boolean = false,
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

    // Development compatibility only. Production API credentials should be kept server-side.
    private val raspApiKey: String by lazy {
        System.getenv("RASP_API_KEY")
            ?: runCatching {
                val configFile = File("local.properties")
                if (configFile.exists()) {
                    Properties().apply {
                        configFile.inputStream().use { load(it) }
                    }.getProperty("RASP_API_KEY")
                } else null
            }.getOrNull()
            ?: ""
    }


    /**
     * Основной домен и запасной. Яндекс сам предупреждает, что доступность
     * api.rasp.yandex.net не гарантируется и рекомендует yandex-net.ru — но если
     * основной молчит, имеет смысл попробовать второй, прежде чем показывать ошибку.
     */
    private val HOSTS = listOf(
        "https://api.rasp.yandex-net.ru/v3.0",
        "https://api.rasp.yandex.net/v3.0"
    )
    // 10 секунд не хватало: запрос расписания станции возвращает до сотни рейсов,
    // и на медленной сети он не укладывался — пользователь видел «timeout».
    private const val TIMEOUT_MS = 25_000

    /** Сколько ближайших рейсов показываем в карточке. */
    private const val MAX_DEPARTURES = 12

    /** Размер страницы расписания и предел числа страниц — чтобы не вычитывать сутки целиком. */
    private const val PAGE_SIZE = 100
    private const val MAX_PAGES = 6

    suspend fun loadSchedule(
        latitude: Double,
        longitude: Double,
        placeName: String
    ): ScheduleResult = withContext(Dispatchers.IO) {
        if (RASP_API_KEY.isBlank()) {
            return@withContext ScheduleResult.Error(
                "Не задан ключ API Яндекс Расписаний (RASP_API_KEY)"
            )
        }

        try {
            val station = findStation(latitude, longitude, placeName)
                ?: return@withContext ScheduleResult.NotFound

            // Вечером сегодняшние рейсы уже закончились, и пустой список выглядит
            // как поломка. Поэтому если на сегодня ничего не осталось — показываем завтрашние.
            val today = loadDepartures(station.code, LocalDate.now(), onlyFuture = true)
            val departures = today.ifEmpty {
                loadDepartures(station.code, LocalDate.now().plusDays(1), onlyFuture = false)
                    .map { it.copy(isTomorrow = true) }
            }
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
            val readable = when (e) {
                is java.net.SocketTimeoutException ->
                    "Сервис расписаний не ответил вовремя. Попробуйте ещё раз."
                is java.net.UnknownHostException ->
                    "Нет связи с сервисом расписаний. Проверьте интернет."
                else -> e.message ?: "Не удалось получить расписание"
            }
            ScheduleResult.Error(readable)
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
        val path = "/nearest_stations/?" +
            "apikey=${raspApiKey}" +
            "&format=json&lang=ru_RU" +
            "&lat=$latitude&lng=$longitude" +
            // 20 станций достаточно, а ответ заметно легче, чем на 50.
            "&distance=2&limit=20"

        val stations = JSONObject(httpGet(path)).optJSONArray("stations") ?: return null
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

    /**
     * Загружает отправления со станции за указанный день.
     *
     * ВАЖНО про постраничность: API отдаёт расписание страницами (по умолчанию 100 рейсов),
     * отсортированными с начала суток. У крупной станции за день бывает 200-300 рейсов,
     * поэтому к вечеру вся первая страница — это уже ушедшие утренние поезда. Раньше мы
     * читали только её, получали пустой список и показывали расписание на завтра.
     * Теперь идём по страницам, пока не наберём ближайшие рейсы.
     */
    private fun loadDepartures(
        stationCode: String,
        date: LocalDate,
        onlyFuture: Boolean
    ): List<Departure> {
        val now = OffsetDateTime.now()
        val result = mutableListOf<Departure>()

        var offset = 0
        var total = Int.MAX_VALUE
        var pagesRead = 0

        while (result.size < MAX_DEPARTURES && offset < total && pagesRead < MAX_PAGES) {
            val path = "/schedule/?" +
                "apikey=${raspApiKey}" +
                "&format=json&lang=ru_RU" +
                "&station=${URLEncoder.encode(stationCode, "UTF-8")}" +
                "&date=${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}&event=departure" +
                "&offset=$offset&limit=$PAGE_SIZE"

            val response = JSONObject(httpGet(path))
            val schedule = response.optJSONArray("schedule") ?: break
            if (schedule.length() == 0) break

            // Сколько всего рейсов за день — из этого понимаем, есть ли ещё страницы.
            // Если сервер не прислал pagination, ограничиваемся одной страницей.
            total = response.optJSONObject("pagination")?.optInt("total", 0) ?: 0

            for (i in 0 until schedule.length()) {
                val item = schedule.optJSONObject(i) ?: continue
                val departureRaw = item.optString("departure").takeIf { it.isNotBlank() } ?: continue
                val departureTime = runCatching { OffsetDateTime.parse(departureRaw) }.getOrNull() ?: continue
                // Уже ушедшие рейсы не показываем (для завтрашнего дня фильтр не нужен).
                if (onlyFuture && departureTime.isBefore(now)) continue

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

            offset += schedule.length()
            pagesRead++
        }

        return result
    }

    /**
     * [path] — часть запроса после версии API, например "/schedule/?apikey=…".
     *
     * Две повторные попытки на каждый домен: первый запрос нередко упирается в холодное
     * соединение и отваливается по таймауту, хотя повтор проходит нормально. Если домен
     * не отвечает совсем — пробуем запасной.
     */
    private fun httpGet(path: String): String {
        var lastError: Exception? = null

        for (host in HOSTS) {
            repeat(2) { attempt ->
                try {
                    return httpGetOnce(host + path)
                } catch (e: Exception) {
                    lastError = e
                    if (attempt == 0) Thread.sleep(500)
                }
            }
        }
        throw lastError ?: IllegalStateException("Не удалось выполнить запрос")
    }

    private fun httpGetOnce(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
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
