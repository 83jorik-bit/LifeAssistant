package com.lifenavigator.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object MapScreen : Destination("map", "Карта", Icons.Filled.Map)
    data object BudgetScreen : Destination("budget", "Бюджет", Icons.Filled.AccountBalanceWallet)
    data object SubscriptionsScreen : Destination("subscriptions", "Подписки", Icons.Filled.Subscriptions)
    data object AssistantScreen : Destination("assistant", "Помощник", Icons.Filled.Chat)
}

val bottomNavItems = listOf(
    Destination.MapScreen,
    Destination.BudgetScreen,
    Destination.SubscriptionsScreen,
    Destination.AssistantScreen
)
