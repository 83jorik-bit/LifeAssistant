package com.lifenavigator.app.data.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Единый сигнал об изменениях локальных данных.
 * UI-модули подписываются на него, поэтому запись из голоса, фонового сервиса
 * или другого экрана сразу отражается во всех открытых разделах.
 */
object AppDataChanges {
    private val _tasks = MutableStateFlow(0L)
    private val _budget = MutableStateFlow(0L)
    private val _places = MutableStateFlow(0L)

    val tasks: StateFlow<Long> = _tasks
    val budget: StateFlow<Long> = _budget
    val places: StateFlow<Long> = _places

    fun tasksChanged() = _tasks.update { it + 1L }
    fun budgetChanged() = _budget.update { it + 1L }
    fun placesChanged() = _places.update { it + 1L }
}
