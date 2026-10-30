package com.lifenavigator.app.data.model

/**
 * Локальная задача LifeAssistant. dueAtEpochMillis — необязательный срок;
 * reminderAtEpochMillis — необязательное время уведомления.
 */
data class TaskItem(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val dueAtEpochMillis: Long? = null,
    val reminderAtEpochMillis: Long? = null,
    val isCompleted: Boolean = false,
    val priority: TaskPriority = TaskPriority.NORMAL
)

enum class TaskPriority(val label: String) {
    LOW("Низкий"),
    NORMAL("Обычный"),
    HIGH("Важный")
}
