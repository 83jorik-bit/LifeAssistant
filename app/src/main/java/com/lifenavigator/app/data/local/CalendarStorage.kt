package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.CalendarEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Локальное хранилище событий календаря. */
class CalendarStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lifeassistant_calendar", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<CalendarEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(CalendarEvent(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        startsAtEpochMillis = item.getLong("startsAt"),
                        endsAtEpochMillis = item.optLong("endsAt").takeIf { it > 0L },
                        notes = item.optString("notes").takeIf { it.isNotBlank() },
                        reminderAtEpochMillis = item.optLong("reminderAt").takeIf { it > 0L }
                    ))
                }
            }.sortedBy { it.startsAtEpochMillis }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(title: String, startsAt: Long, endsAt: Long? = null, notes: String? = null, reminderAt: Long? = startsAt - 15 * 60 * 1000L): CalendarEvent {
        require(title.isNotBlank()) { "Название события не может быть пустым" }
        val event = CalendarEvent(UUID.randomUUID().toString(), title.trim(), startsAt, endsAt, notes?.trim()?.takeIf { it.isNotBlank() }, reminderAt)
        save(load() + event)
        return event
    }

    @Synchronized
    fun delete(id: String) = save(load().filterNot { it.id == id })

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_EVENTS).apply()
        _changes.value = _changes.value + 1L
    }

    @Synchronized
    private fun save(events: List<CalendarEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(JSONObject()
                .put("id", event.id)
                .put("title", event.title)
                .put("startsAt", event.startsAtEpochMillis)
                .put("endsAt", event.endsAtEpochMillis ?: JSONObject.NULL)
                .put("notes", event.notes ?: JSONObject.NULL)
                .put("reminderAt", event.reminderAtEpochMillis ?: JSONObject.NULL))
        }
        // commit() гарантирует, что следующее чтение увидит записанный список.
        check(prefs.edit().putString(KEY_EVENTS, array.toString()).commit()) { "Не удалось записать календарь" }
        _changes.value = _changes.value + 1L
    }

    companion object {
        private const val KEY_EVENTS = "events_json"
        private val _changes = MutableStateFlow(0L)
        val changes: StateFlow<Long> = _changes
    }
}
