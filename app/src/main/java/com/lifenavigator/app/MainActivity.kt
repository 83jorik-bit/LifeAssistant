package com.lifenavigator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lifenavigator.app.ui.navigation.Destination
import com.lifenavigator.app.ui.navigation.bottomNavItems
import com.lifenavigator.app.ui.screens.assistant.AssistantScreen
import com.lifenavigator.app.ui.screens.budget.BudgetScreen
import com.lifenavigator.app.ui.screens.map.MapScreen
import com.lifenavigator.app.ui.screens.subscriptions.SubscriptionsScreen
import com.lifenavigator.app.ui.theme.LifeNavigatorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LifeNavigatorTheme {
                LifeNavigatorApp()
            }
        }
    }
}

@Composable
fun LifeNavigatorApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                bottomNavItems.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.MapScreen.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.MapScreen.route) { MapScreen() }
            composable(Destination.BudgetScreen.route) { BudgetScreen() }
            composable(Destination.SubscriptionsScreen.route) { SubscriptionsScreen() }
            composable(Destination.AssistantScreen.route) { AssistantScreen() }
        }
    }
}
