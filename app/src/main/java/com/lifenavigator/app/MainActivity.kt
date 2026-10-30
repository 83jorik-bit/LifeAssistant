package com.lifenavigator.app

import android.content.Intent
import android.os.Bundle
import com.yandex.mapkit.MapKitFactory
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.lifenavigator.app.data.auth.PkceOAuthClient
import com.lifenavigator.app.data.notifications.BankNotificationListener
import com.lifenavigator.app.data.local.ConsentStore
import com.lifenavigator.app.data.local.LocalProfileStore
import com.lifenavigator.app.data.local.SessionStore
import com.lifenavigator.app.data.local.ThemeController
import com.lifenavigator.app.data.local.ThemeMode
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
import com.lifenavigator.app.ui.screens.calendar.CalendarScreen
import com.lifenavigator.app.ui.screens.map.MapScreen
import com.lifenavigator.app.ui.screens.subscriptions.SubscriptionsScreen
import com.lifenavigator.app.ui.screens.tasks.TasksScreen
import com.lifenavigator.app.ui.theme.LifeNavigatorTheme

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthRedirect(intent)
    }

    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "lifenavigator" || data.scheme?.startsWith("vk") == true) {
            PkceOAuthClient.redirect.value = data
        }
    }

    override fun onStart() {
        super.onStart()
        MapKitFactory.getInstance().onStart()
    }

    override fun onStop() {
        MapKitFactory.getInstance().onStop()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.load(this)
        LocalProfileStore.load(this)
        ConsentStore.load(this)
        SessionStore.load(this)
        BankNotificationListener.requestRebindIfAllowed(this)
        handleAuthRedirect(intent)
        setContent {
            val themeMode by ThemeController.mode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LifeNavigatorTheme(darkTheme = darkTheme) {
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
            startDestination = Destination.BudgetScreen.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Destination.MapScreen.route) { MapScreen() }
            composable(Destination.BudgetScreen.route) { BudgetScreen() }
            composable(Destination.TasksScreen.route) { TasksScreen() }
            composable(Destination.CalendarScreen.route) { CalendarScreen() }
            composable(Destination.SubscriptionsScreen.route) { SubscriptionsScreen() }
            composable(Destination.AssistantScreen.route) { AssistantScreen() }
        }
    }
}
