package com.lifenavigator.app.data.money

import android.content.Context
import com.lifenavigator.app.data.repository.YandexSearchRepository
import com.yandex.mapkit.geometry.Point
import org.json.JSONObject
import java.time.LocalDate

/** Реальное место, к которому привязаны траты. */
data class MatchedPlace(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * Сопоставляет магазины из трат с реальными местами на карте.
 *
 * Почему по названию, а не по GPS в момент оплаты: чтобы узнать координаты, когда
 * приложение закрыто, нужно разрешение на фоновую геолокацию — одно из самых
 * чувствительных в Android, с которым сложнее пройти модерацию и которое пользователи
 * часто не дают. К тому же GPS показывает, где был телефон, а не где касса; для
 * онлайн-оплат и подписок он и вовсе бессмыслен. Название из уведомления банка
 * указывает на магазин точнее.
 *
 * Результаты кэшируются, включая «не нашли»: каждое сопоставление — запрос к поиску
 * Яндекса, а бесплатный лимит общий на всех пользователей.
 */
object PlaceMatcher {

    /** Сколько новых магазинов сопоставлять за раз — бережём лимит запросов. */
    private const val MAX_LOOKUPS_PER_RUN = 5

    /** Через сколько дней перепроверять «не нашли» — рядом могли открыть магазин. */
    private const val RETRY_NOT_FOUND_DAYS = 14

    fun cached(context: Context): Map<String, MatchedPlace> {
        val json = readCache(context)
        return json.keys().asSequence().mapNotNull { key ->
            val item = json.optJSONObject(key) ?: return@mapNotNull null
            if (!item.optBoolean("found")) return@mapNotNull null
            key to MatchedPlace(
                name = item.optString("name"),
                address = item.optString("address"),
                latitude = item.optDouble("lat"),
                longitude = item.optDouble("lon")
            )
        }.toMap()
    }

    /**
     * Ищет ещё не сопоставленные магазины рядом с [near]. Возвращает актуальный кэш.
     * Должен вызываться из главного потока — так устроен поиск MapKit.
     */
    suspend fun matchMissing(context: Context, merchants: List<String>, near: Point): Map<String, MatchedPlace> {
        val json = readCache(context)
        val today = LocalDate.now().toEpochDay()

        val toLookup = merchants.filter { merchant ->
            val entry = json.optJSONObject(merchant) ?: return@filter true
            if (entry.optBoolean("found")) return@filter false
            today - entry.optLong("checkedDay") >= RETRY_NOT_FOUND_DAYS
        }.take(MAX_LOOKUPS_PER_RUN)

        toLookup.forEach { merchant ->
            val place = runCatching { YandexSearchRepository.searchByName(near, merchant) }.getOrNull()
            json.put(
                merchant,
                JSONObject().apply {
                    put("found", place != null)
                    put("checkedDay", today)
                    if (place != null) {
                        put("name", place.name)
                        put("address", place.address)
                        put("lat", place.latitude)
                        put("lon", place.longitude)
                    }
                }
            )
        }

        if (toLookup.isNotEmpty()) {
            prefs(context).edit().putString(KEY_CACHE, json.toString()).apply()
        }
        return cached(context)
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun readCache(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_CACHE, null) ?: "{}") }
            .getOrDefault(JSONObject())

    private fun prefs(context: Context) =
        context.getSharedPreferences("merchant_places", Context.MODE_PRIVATE)

    private const val KEY_CACHE = "cache_json"
}
