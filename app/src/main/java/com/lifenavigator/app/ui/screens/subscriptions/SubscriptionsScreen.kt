package com.lifenavigator.app.ui.screens.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifenavigator.app.data.model.Subscription

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(viewModel: SubscriptionsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Подписки и автоплатежи") })

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Активных подписок: ${state.activeCount}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Итого в месяц: ~${state.monthlyTotal} ₽",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.subscriptions, key = { it.id }) { sub ->
                SubscriptionCard(
                    subscription = sub,
                    onToggle = { viewModel.toggleSubscription(sub.id) }
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCard(subscription: Subscription, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    subscription.serviceName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${subscription.priceRub} ₽ · ${subscription.billingCycle.displayName}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (subscription.isActive) {
                    Text(
                        "Списание через ${subscription.nextChargeDaysLeft} дн.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (subscription.nextChargeDaysLeft <= 3)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Отключена", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = subscription.isActive, onCheckedChange = { onToggle() })
        }
    }
}
