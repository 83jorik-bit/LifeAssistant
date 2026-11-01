package com.lifenavigator.app.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.data.model.TaskPriority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TasksScreen(viewModel: TasksViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Добавить задачу")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Мои задачи", style = MaterialTheme.typography.headlineSmall)
            Text(
                "LifeAssistant хранит поручения на устройстве и помогает не забывать о важном.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            val active = state.tasks.filterNot { it.isCompleted }
            val completed = state.tasks.filter { it.isCompleted }
            if (active.isEmpty() && completed.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "Пока задач нет. Добавь поручение или попроси помощника голосом: «Добавь задачу…».",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active.isNotEmpty()) {
                        item { Text("К выполнению · ${active.size}", style = MaterialTheme.typography.titleMedium) }
                        items(active, key = { it.id }) { task ->
                            TaskRow(task, onChecked = { viewModel.setCompleted(task, it) }, onDelete = { viewModel.deleteTask(task) })
                        }
                    } else if (completed.isNotEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Text(
                                    "Все задачи выполнены. Отличная работа!",
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    if (completed.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Выполнено · ${completed.size}",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { showCompleted = !showCompleted }) {
                                    Icon(
                                        imageVector = if (showCompleted) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = if (showCompleted) "Скрыть выполненные задачи" else "Показать выполненные задачи"
                                    )
                                }
                            }
                        }
                        if (showCompleted) {
                            items(completed, key = { it.id }) { task ->
                                TaskRow(task, onChecked = { viewModel.setCompleted(task, it) }, onDelete = { viewModel.deleteTask(task) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новое поручение") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Что нужно сделать?") },
                    placeholder = { Text("Например, оплатить интернет") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addTask(title)
                    title = ""
                    showAdd = false
                }, enabled = title.isNotBlank()) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun TaskRow(
    task: com.lifenavigator.app.data.model.TaskItem,
    onChecked: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = task.isCompleted, onCheckedChange = onChecked)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.bodyLarge)
                if (task.priority == TaskPriority.HIGH) {
                    Text("Важное", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                task.dueAtEpochMillis?.let {
                    Text(
                        "Срок: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить задачу")
            }
        }
    }
}
