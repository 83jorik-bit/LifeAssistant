package com.lifenavigator.app.ui.screens.budget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CreditCard
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
import com.lifenavigator.app.data.model.BudgetLine
import com.lifenavigator.app.data.model.Expense
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
    var limitDialogKey by remember { mutableStateOf<String?>(null) }
    var incomeDialogOpen by remember { mutableStateOf(false) }
    var customCategoryDialogOpen by remember { mutableStateOf(false) }
    var paymentStubOpen by remember { mutableStateOf(false) }

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
            item { IncomeCard(state) { incomeDialogOpen = true } }
            item { SummaryCard(state) }
            item { PayButton { paymentStubOpen = true } }

            item {
                Text(
                    "Лимиты по категориям",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                )
            }

            if (state.lines.isEmpty()) {
                item {
                    Text(
                        "Лимиты не заданы. Нажмите «Задать лимит», чтобы распланировать месяц.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(state.lines, key = { it.key }) { line ->
                    BudgetLineCard(line) { limitDialogKey = line.key }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { limitDialogKey = state.allCategoryKeys.first().first },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Задать лимит")
                    }
                    OutlinedButton(
                        onClick = { customCategoryDialogOpen = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Своя категория")
                    }
                }
            }

            item {
                Text(
                    "Лимиты действуют и в следующем месяце — заданный план не нужно вводить заново.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
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
                    ExpenseRow(expense, state.titleOf(expense.categoryKey)) {
                        viewModel.deleteExpense(expense.id)
                    }
                }
            }
        }
    }

    if (expenseDialogOpen) {
        ExpenseDialog(
            month = state.month,
            categories = state.allCategoryKeys,
            onDismiss = { expenseDialogOpen = false },
            onConfirm = { key, amount, note, date ->
                viewModel.addExpense(key, amount, note, date)
                expenseDialogOpen = false
            }
        )
    }

    limitDialogKey?.let { key ->
        LimitDialog(
            initialKey = key,
            categories = state.allCategoryKeys,
            currentLimit = state.limits[key] ?: 0,
            onDismiss = { limitDialogKey = null },
            onConfirm = { chosenKey, limit ->
                viewModel.setLimit(chosenKey, limit)
                limitDialogKey = null
            }
        )
    }

    if (incomeDialogOpen) {
        IncomeDialog(
            currentIncome = state.income,
            onDismiss = { incomeDialogOpen = false },
            onConfirm = { amount ->
                viewModel.setIncome(amount)
                incomeDialogOpen = false
            }
        )
    }

    if (customCategoryDialogOpen) {
        CustomCategoryDialog(
            onDismiss = { customCategoryDialogOpen = false },
            onConfirm = { title, limit ->
                viewModel.addCustomCategory(title, limit)
                customCategoryDialogOpen = false
            }
        )
    }

    if (paymentStubOpen) {
        PaymentStubDialog { paymentStubOpen = false }
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
private fun IncomeCard(state: BudgetUiState, onEdit: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Доход за месяц", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (state.income > 0) state.income.asRub() else "Не указан",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.income > 0) "Изменить" else "Указать")
                }
            }

            if (state.income > 0) {
                Spacer(Modifier.height(10.dp))
                val unplanned = state.unplanned
                Text(
                    when {
                        unplanned > 0 -> "Не распределено по лимитам: ${unplanned.asRub()}"
                        unplanned < 0 -> "Лимиты превышают доход на ${(-unplanned).asRub()}"
                        else -> "Доход полностью распределён по лимитам"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unplanned < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Остаток с учётом трат: ${state.incomeLeft.asRub()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PayButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.CreditCard, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Оплатить из приложения")
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
                    Text("Сумма лимитов", style = MaterialTheme.typography.bodySmall)
                    Text(state.totalLimit.asRub(), style = MaterialTheme.typography.headlineSmall)
                }
            }

            if (state.totalLimit > 0) {
                Spacer(Modifier.height(12.dp))
                val overspent = state.remaining < 0
                Text(
                    if (overspent) {
                        "Перерасход по лимитам: ${(-state.remaining).asRub()}"
                    } else {
                        "Осталось по лимитам: ${state.remaining.asRub()}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (overspent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }

            state.dailyAllowance?.let { perDay ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Это ${perDay.asRub()} в день на оставшиеся ${state.daysLeft} дн.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
private fun BudgetLineCard(line: BudgetLine, onEditLimit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEditLimit) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${line.emoji} ${line.title}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(line.colorArgb)
                )
                Text(
                    if (line.limitRub > 0) {
                        "${line.spentRub.asRub()} / ${line.limitRub.asRub()}"
                    } else {
                        "${line.spentRub.asRub()} · лимит не задан"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.isOverLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (line.limitRub > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { line.progress.coerceAtMost(1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (line.isOverLimit) MaterialTheme.colorScheme.error else Color(line.colorArgb),
                    trackColor = Color.LightGray.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense, categoryTitle: String, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "$categoryTitle · ${expense.amountRub.asRub()}",
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

/** Выпадающий список категорий: пары «ключ — подпись». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<Pair<String, String>>,
    selectedKey: String,
    onSelected: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val title = categories.firstOrNull { it.first == selectedKey }?.second ?: "Категория"

    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = title,
            onValueChange = {},
            readOnly = true,
            label = { Text("Категория") },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            categories.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(key)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AmountField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }.take(9)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun IncomeDialog(currentIncome: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(if (currentIncome > 0) currentIncome.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Доход за месяц") },
        text = {
            Column {
                AmountField(text, "Сумма, ₽") { text = it }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Доход вводится вручную. Автоматическая подгрузка из банка потребует " +
                        "подключения к банковскому API — это отдельная задача с договором " +
                        "и согласием на обработку операций, см. README.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toIntOrNull() ?: 0) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ExpenseDialog(
    month: YearMonth,
    categories: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, String?, LocalDate) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(categories.first().first) }
    val amount = amountText.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая трата") },
        text = {
            Column {
                AmountField(amountText, "Сумма, ₽") { amountText = it }
                Spacer(Modifier.height(12.dp))
                CategoryPicker(categories, key) { key = it }
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
                    onConfirm(key, amount, note, date)
                },
                enabled = amount > 0
            ) {
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun LimitDialog(
    initialKey: String,
    categories: List<Pair<String, String>>,
    currentLimit: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var key by remember { mutableStateOf(initialKey) }
    var limitText by remember { mutableStateOf(if (currentLimit > 0) currentLimit.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Лимит на месяц") },
        text = {
            Column {
                CategoryPicker(categories, key) { key = it }
                Spacer(Modifier.height(12.dp))
                AmountField(limitText, "Сумма в месяц, ₽") { limitText = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    "0 — убрать лимит. Лимит действует и в следующих месяцах.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(key, limitText.toIntOrNull() ?: 0) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun CustomCategoryDialog(onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var limitText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Своя категория") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(30) },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                AmountField(limitText, "Лимит в месяц, ₽ (необязательно)") { limitText = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, limitText.toIntOrNull() ?: 0) },
                enabled = title.isNotBlank()
            ) {
                Text("Добавить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/**
 * Заглушка оплаты.
 *
 * Настоящая оплата из приложения — это не кнопка, а отдельный проект: нужен
 * платёжный провайдер, счёт для приёма денег и соблюдение требований к платёжным
 * данным. Поэтому здесь честное объяснение, а не имитация платежа.
 */
@Composable
private fun PaymentStubDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Оплата из приложения") },
        text = {
            Column {
                Text("Пока не подключена.")
                Spacer(Modifier.height(12.dp))
                Text(
                    "Чтобы платежи заработали, нужен платёжный провайдер (например ЮKassa, " +
                        "CloudPayments или СБП через банк-эквайер), договор с ним и счёт для " +
                        "приёма средств. Провайдер даёт SDK, а приложение получает от него " +
                        "ссылку или форму оплаты.\n\n" +
                        "Экран с полями для ввода карты, который никуда не отправляет деньги, " +
                        "добавлять не стали: он выглядел бы рабочим, но им не был.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } }
    )
}
