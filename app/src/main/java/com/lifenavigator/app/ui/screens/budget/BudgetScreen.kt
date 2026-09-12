package com.lifenavigator.app.ui.screens.budget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.data.model.BudgetCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(viewModel: BudgetViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Бюджет") })

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        // Сводка
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Потрачено в этом месяце", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${state.totalSpent} ₽",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Лимит", style = MaterialTheme.typography.bodySmall)
                        Text("${state.totalLimit} ₽", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                if (state.overLimitCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ ${state.overLimitCount} категории превышают лимит",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.categories, key = { it.category }) { budgetCategory ->
                BudgetCategoryCard(budgetCategory)
            }
        }
    }
}

@Composable
private fun BudgetCategoryCard(item: BudgetCategory) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${item.category.emoji} ${item.category.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${item.spentRub} / ${item.monthlyLimitRub} ₽",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.isOverLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress.coerceAtMost(1f) },
                modifier = Modifier.fillMaxWidth(),
                color = if (item.isOverLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = Color.LightGray.copy(alpha = 0.3f)
            )
        }
    }
}
