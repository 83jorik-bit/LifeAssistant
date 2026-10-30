package com.lifenavigator.app.ui.screens.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lifenavigator.app.data.local.TaskStorage
import com.lifenavigator.app.data.model.TaskItem
import com.lifenavigator.app.data.model.TaskPriority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TasksUiState(val tasks: List<TaskItem> = emptyList())

class TasksViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = TaskStorage(application)
    private val _state = MutableStateFlow(TasksUiState(storage.load()))
    val state: StateFlow<TasksUiState> = _state

    fun refresh() = _state.update { it.copy(tasks = storage.load()) }

    fun addTask(title: String, priority: TaskPriority = TaskPriority.NORMAL) {
        if (title.isBlank()) return
        storage.add(title, priority = priority)
        refresh()
    }

    fun setCompleted(task: TaskItem, completed: Boolean) {
        storage.update(task.copy(isCompleted = completed))
        refresh()
    }

    fun deleteTask(task: TaskItem) {
        storage.delete(task.id)
        refresh()
    }
}
