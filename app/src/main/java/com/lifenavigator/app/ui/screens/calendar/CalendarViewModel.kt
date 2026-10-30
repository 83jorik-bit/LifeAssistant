package com.lifenavigator.app.ui.screens.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lifenavigator.app.data.local.CalendarReminderScheduler
import com.lifenavigator.app.data.local.CalendarStorage
import com.lifenavigator.app.data.local.TaskStorage
import com.lifenavigator.app.data.model.CalendarEvent
import com.lifenavigator.app.data.model.TaskItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId

data class CalendarUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val events: List<CalendarEvent> = emptyList(),
    val tasks: List<TaskItem> = emptyList()
)

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = CalendarStorage(application)
    private val taskStorage = TaskStorage(application)
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state

    init { refresh() }

    fun selectDate(date: LocalDate) = _state.update { it.copy(selectedDate = date) }.also { refresh() }

    fun refresh() {
        _state.update { it.copy(events = storage.load(), tasks = taskStorage.load()) }
    }

    fun addEvent(title: String, date: LocalDate, hour: Int, minute: Int) {
        if (title.isBlank()) return
        val start = date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val event = storage.add(title, start)
        CalendarReminderScheduler.schedule(getApplication(), event)
        refresh()
    }

    fun deleteEvent(event: CalendarEvent) {
        CalendarReminderScheduler.cancel(getApplication(), event)
        storage.delete(event.id)
        refresh()
    }
}
