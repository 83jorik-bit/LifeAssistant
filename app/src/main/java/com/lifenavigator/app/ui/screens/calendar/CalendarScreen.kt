package com.lifenavigator.app.ui.screens.calendar

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.data.model.CalendarEvent
import com.lifenavigator.app.data.model.TaskItem
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun CalendarScreen(viewModel: CalendarViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var chosenDate by remember { mutableStateOf(state.selectedDate) }
    var chosenHour by remember { mutableStateOf(9) }
    var chosenMinute by remember { mutableStateOf(0) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = {
            chosenDate = state.selectedDate
            showAdd = true
        }) { Icon(Icons.Filled.Add, contentDescription = "Добавить событие") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Календарь", style = MaterialTheme.typography.headlineSmall)
            Text("Планируй встречи и смотри задачи по срокам", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { viewModel.selectDate(state.selectedDate.minusDays(1)) }) { Text("‹") }
                Text(state.selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))).replaceFirstChar { it.uppercase() },
                    modifier = Modifier.align(Alignment.CenterVertically).clickable {
                        DatePickerDialog(context, { _, y, m, d -> viewModel.selectDate(LocalDate.of(y, m + 1, d)) },
                            state.selectedDate.year, state.selectedDate.monthValue - 1, state.selectedDate.dayOfMonth).show()
                    })
                TextButton(onClick = { viewModel.selectDate(state.selectedDate.plusDays(1)) }) { Text("›") }
            }
            TextButton(onClick = { viewModel.selectDate(LocalDate.now()) }) { Text("Сегодня") }
            val zone = ZoneId.systemDefault()
            val events = state.events.filter { Date(it.startsAtEpochMillis).toInstant().atZone(zone).toLocalDate() == state.selectedDate }
            val tasks = state.tasks.filter { task ->
                task.dueAtEpochMillis?.let { Date(it).toInstant().atZone(zone).toLocalDate() == state.selectedDate } == true && !task.isCompleted
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("События · ${events.size}", style = MaterialTheme.typography.titleMedium) }
                if (events.isEmpty()) item { Text("На этот день событий нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(events, key = { it.id }) { event -> EventRow(event) { viewModel.deleteEvent(event) } }
                item { Text("Задачи по сроку · ${tasks.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                if (tasks.isEmpty()) item { Text("Нет задач со сроком на этот день", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(tasks, key = { it.id }) { task -> TaskAgendaRow(task) }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новое событие") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, singleLine = true)
                    TextButton(onClick = {
                        DatePickerDialog(context, { _, y, m, d -> chosenDate = LocalDate.of(y, m + 1, d) },
                            chosenDate.year, chosenDate.monthValue - 1, chosenDate.dayOfMonth).show()
                    }) { Text("Дата: ${chosenDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}") }
                    TextButton(onClick = {
                        TimePickerDialog(context, { _, h, m -> chosenHour = h; chosenMinute = m },
                            chosenHour, chosenMinute, true).show()
                    }) { Text("Время: %02d:%02d".format(chosenHour, chosenMinute)) }
                    Text("Напоминание за 15 минут до события", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
                viewModel.addEvent(title, chosenDate, chosenHour, chosenMinute)
                title = ""; showAdd = false
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Date(event.startsAtEpochMillis).toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(event.title, style = MaterialTheme.typography.bodyLarge)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Удалить событие") }
        }
    }
}

@Composable
private fun TaskAgendaRow(task: TaskItem) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(task.title)
        }
    }
}
