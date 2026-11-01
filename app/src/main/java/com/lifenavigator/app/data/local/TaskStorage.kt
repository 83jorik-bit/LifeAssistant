package com.lifenavigator.app.data.local

import android.content.Context
import com.lifenavigator.app.data.model.TaskItem
import com.lifenavigator.app.data.model.TaskPriority
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Надёжное локальное хранилище задач. Не требует аккаунта или сети. */
class TaskStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lifeassistant_tasks", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<TaskItem> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(
                        TaskItem(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            title = item.optString("title"),
                            createdAtEpochMillis = item.optLong("createdAt", 0L),
                            dueAtEpochMillis = item.nullableLong("dueAt"),
                            reminderAtEpochMillis = item.nullableLong("reminderAt"),
                            isCompleted = item.optBoolean("completed", false),
                            priority = runCatching { TaskPriority.valueOf(item.optString("priority", "NORMAL")) }
                                .getOrDefault(TaskPriority.NORMAL)
                        )
                    )
                }
            }.filter { it.title.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(title: String, dueAt: Long? = null, reminderAt: Long? = dueAt, priority: TaskPriority = TaskPriority.NORMAL): TaskItem {
        val task = TaskItem(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            createdAtEpochMillis = System.currentTimeMillis(),
            dueAtEpochMillis = dueAt,
            reminderAtEpochMillis = reminderAt,
            priority = priority
        )
        save(load() + task)
        return task
    }

    @Synchronized
    fun update(task: TaskItem) = save(load().map { if (it.id == task.id) task else it })

    @Synchronized
    fun delete(id: String) = save(load().filterNot { it.id == id })

    @Synchronized
    fun clearAll() {
        prefs.edit().remove(KEY_TASKS).apply()
        AppDataChanges.tasksChanged()
    }

    @Synchronized
    private fun save(tasks: List<TaskItem>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject().apply {
                    put("id", task.id)
                    put("title", task.title)
                    put("createdAt", task.createdAtEpochMillis)
                    put("dueAt", task.dueAtEpochMillis ?: JSONObject.NULL)
                    put("reminderAt", task.reminderAtEpochMillis ?: JSONObject.NULL)
                    put("completed", task.isCompleted)
                    put("priority", task.priority.name)
                }
            )
        }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
        AppDataChanges.tasksChanged()
    }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key).takeIf { it > 0L }

    companion object {
        private const val KEY_TASKS = "tasks_json"
    }
}
