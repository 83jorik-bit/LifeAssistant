package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.NeedCategory
import com.lifenavigator.app.data.model.PlacePoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Сохраняет последний показанный список мест, чтобы при следующем запуске
 * приложение не было пустым и не показывало выдуманные данные.
 *
 * Данные лежат в SharedPreferences в виде JSON — для списка на несколько десятков
 * записей этого достаточно, отдельная база (Room) тут была бы избыточной.
 */
class PlacesCache(context: Context) {

    private val prefs = context.getSharedPreferences("places_cache", Context.MODE_PRIVATE)

    fun save(places: List<PlacePoint>) {
        val array = JSONArray()
        places.forEach { place ->
            array.put(
                JSONObject().apply {
                    put("id", place.id)
                    put("name", place.name)
                    put("category", place.category.name)
                    put("distanceKm", place.distanceKm)
                    put("rating", place.rating)
                    put("reviewsCount", place.reviewsCount)
                    put("ratingsCount", place.ratingsCount)
                    put("address", place.address)
                    put("latitude", place.latitude)
                    put("longitude", place.longitude)
                    put("workingHours", place.workingHours ?: JSONObject.NULL)
                    put("phone", place.phone ?: JSONObject.NULL)
                    put("yandexOid", place.yandexOid ?: JSONObject.NULL)
                    put("categoryDescription", place.categoryDescription ?: JSONObject.NULL)
                    put("isOpen24", place.isOpen24)
                    put("yandexUri", place.yandexUri ?: JSONObject.NULL)
                }
            )
        }
        prefs.edit().putString(KEY_PLACES, array.toString()).apply()
    }

    fun load(): List<PlacePoint> {
        val raw = prefs.getString(KEY_PLACES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val category = runCatching { NeedCategory.valueOf(item.getString("category")) }
                    .getOrNull() ?: return@mapNotNull null

                PlacePoint(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    category = category,
                    distanceKm = item.optDouble("distanceKm", 0.0),
                    rating = item.optDouble("rating", 0.0),
                    reviewsCount = item.optInt("reviewsCount", 0),
                    address = item.optString("address", ""),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    ratingsCount = item.optInt("ratingsCount", 0),
                    workingHours = item.optStringOrNull("workingHours"),
                    phone = item.optStringOrNull("phone"),
                    yandexOid = item.optStringOrNull("yandexOid"),
                    categoryDescription = item.optStringOrNull("categoryDescription"),
                    isOpen24 = item.optBoolean("isOpen24", false),
                    yandexUri = item.optStringOrNull("yandexUri")
                )
            }
        } catch (e: Exception) {
            // Формат мог измениться между версиями приложения — тогда просто
            // начинаем с пустого списка, а не падаем.
            emptyList()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_PLACES = "places_json"
    }
}
