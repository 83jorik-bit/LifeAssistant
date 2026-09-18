package com.lifenavigator.app.ui.screens.budget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.data.model.BudgetCategory
import com.lifenavigator.app.data.model.Expense
import com.lifenavigator.app.data.model.NeedCategory
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** «1 234 ₽» — с пробелами между тысячами, чтобы суммы читались с одного взгляда. */
private fun Int.asRub(): String = "%,d ₽".format(this).replace(',', ' ')

private val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))
private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

@Composable
fun BudgetScreen(viewModel: BudgetViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    var expenseDialogOpen by remember { mutableStateOf(false) }
    var limitDialogCategory by remember { mutableStateOf<NeedCategory?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { expenseDialogOpen = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Трата") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { MonthSwitcher(state, viewModel) }
            item { SummaryCard(state) }

            item {
                Text(
                    "Лимиты по категориям",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                )
            }

            if (state.categories.isEmpty()) {
                item {
                    Text(
                        "Лимиты не заданы. Нажмите «Задать лимит», чтобы распланировать месяц.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(state.categories, key = { it.category }) { item ->
                    BudgetCategoryCard(item) { limitDialogCategory = item.category }
                }
            }

            item {
                OutlinedButton(
                    onClick = { limitDialogCategory = NeedCategory.entries.first() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Задать лимит")
                }
            }

            if (state.expensesOfMonth.isNotEmpty()) {
                item {
                    Text(
                        "Траты за месяц",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                    )
                }
                items(state.expensesOfMonth, key = { it.id }) { expense ->
                    ExpenseRow(expense) { viewModel.deleteExpense(expense.id) }
                }
            }
        }
    }

    if (expenseDialogOpen) {
        ExpenseDialog(
            month = state.month,
            onDismiss = { expenseDialogOpen = false },
            onConfirm = { category, amount, note, date ->
                viewModel.addExpense(category, amount, note, date)
                expenseDialogOpen = false
            }
        )
    }

    limitDialogCategory?.let { category ->
        LimitDialog(
            initialCategory = category,
            currentLimit = state.limits[category] ?: 0,
            onDismiss = { limitDialogCategory = null },
            onConfirm = { chosenCategory, limit ->
                viewModel.setLimit(chosenCategory, limit)
                limitDialogCategory = null
            }
        )
    }
}

@Composable
private fun MonthSwitcher(state: BudgetUiState, viewModel: BudgetViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = { viewModel.showPreviousMonth() }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий месяц")
        }
        Text(
            state.month.atDay(1).format(MONTH_FORMAT).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(
            onClick = { viewModel.showNextMonth() },
            enabled = !state.isCurrentMonth
        ) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий месяц")
        }
    }
}

@Composable
private fun SummaryCard(state: BudgetUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Потрачено", style = MaterialTheme.typography.bodySmall)
                    Text(
                        state.totalSpent.asRub(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Лимит", style = MaterialTheme.typography.bodySmall)
                    Text(state.totalLimit.asRub(), style = MaterialTheme.typography.headlineSmall)
                }
            }

            if (state.totalLimit > 0) {
                Spacer(Modifier.height(12.dp))
                val overspent = state.remaining < 0
                Text(
                    if (overspent) {
                        "Перерасход: ${(-state.remaining).asRub()}"
                    } else {
                        "Осталось: ${state.remaining.asRub()}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (overspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )

                state.dailyAllowance?.let { perDay ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Это ${perDay.asRub()} в день на оставшиеся ${state.daysLeft} дн.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.overLimitCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Категорий сверх лимита: ${state.overLimitCount}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetCategoryCard(item: BudgetCategory, onEditLimit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEditLimit) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${item.category.emoji} ${item.category.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(item.category.colorArgb)
                )
                Text(
                    if (item.monthlyLimitRub > 0) {
                        "${item.spentRub.asRub()} / ${item.monthlyLimitRub.asRub()}"
                    } else {
                        "${item.spentRub.asRub()} · лимит не задан"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isOverLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (item.monthlyLimitRub > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { item.progress.coerceAtMost(1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (item.isOverLimit) MaterialTheme.colorScheme.error else Color(item.category.colorArgb),
                    trackColor = Color.LightGray.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${expense.category.emoji} ${expense.amountRub.asRub()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    buildString {
                        append(LocalDate.ofEpochDay(expense.dateEpochDay).format(DAY_FORMAT))
                        expense.note?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить трату")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseDialog(
    month: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (NeedCategory, Int, String?, LocalDate) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(NeedCategory.entries.first()) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    val amount = amountText.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая трата") },
        text = {
            Column {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { text -> amountText = text.filter { it.isDigit() }.take(7) },
                    label = { Text("Сумма, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = categoryMenuOpen,
                    onExpandedChange = { categoryMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = "${category.emoji} ${category.displayName}",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Категория") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        NeedCategory.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option.emoji} ${option.displayName}") },
                                onClick = {
                                    category = option
                                    categoryMenuOpen = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(60) },
                    label = { Text("Комментарий (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Трату записываем на сегодня, а если смотрим прошлый месяц —
                    // на его последний день, иначе она "уедет" из просматриваемого месяца.
                    val date = if (month == YearMonth.now()) LocalDate.now() else month.atEndOfMonth()
                    onConfirm(category, amount, note, date)
                },
                enabled = amount > 0
            ) {
                Text("Добавить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitDialog(
    initialCategory: NeedCategory,
    currentLimit: Int,
    onDismiss: () -> Unit,
    onConfirm: (NeedCategory, Int) -> Unit
) {
    var category by remember { mutableStateOf(initialCategory) }
    var limitText by remember { mutableStateOf(if (currentLimit > 0) currentLimit.toString() else "") }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лимит на месяц") },
        text = {
            Column {
                ExposedDropdownMenuBox(
                    expanded = categoryMenuOpen,
                    onExpandedChange = { categoryMenuOpen = it }
                ) {
                    OutlinedTextField(
                        value = "${category.emoji} ${category.displayName}",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Категория") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryMenuOpen,
                        onDismissRequest = { categoryMenuOpen = false }
                    ) {
                        NeedCategory.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text("${option.emoji} ${option.displayName}") },
                                onClick = {
                                    category = option
                                    categoryMenuOpen = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = limitText,
                    onValueChange = { text -> limitText = text.filter { it.isDigit() }.take(7) },
                    label = { Text("Сумма в месяц, ₽") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "0 — убрать лимит с категории.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(category, limitText.toIntOrNull() ?: 0) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
