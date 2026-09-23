package com.hrvrm.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import com.hrvrm.app.ui.history.HistoryScreen
import com.hrvrm.app.ui.history.MeasurementDetailScreen
import com.hrvrm.app.ui.measure.MeasureScreen
import com.hrvrm.app.ui.settings.SettingsScreen

private sealed class Destination(val route: String, val label: String) {
    data object Measure : Destination("measure", "Measure")
    data object History : Destination("history", "History")
    data object Settings : Destination("settings", "Settings")
}

private val destinations = listOf(Destination.Measure, Destination.History, Destination.Settings)

@Composable
fun AppNavHost(startAtSettings: Boolean = false) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            NavigationBar {
                destinations.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            // No saveState/restoreState: "measurementDetail/{id}" is a flat
                            // destination alongside History, not nested inside it, so that
                            // pattern (meant for per-tab back stacks) would restore straight
                            // into the last-viewed measurement instead of the History list.
                            // Tapping a tab should always land on that tab's own root screen.
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        },
                        icon = {
                            val icon = when (destination) {
                                Destination.Measure -> Icons.Filled.Favorite
                                Destination.History -> Icons.Filled.History
                                Destination.Settings -> Icons.Filled.Settings
                            }
                            Icon(icon, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (startAtSettings) Destination.Settings.route else Destination.Measure.route,
            modifier = androidx.compose.ui.Modifier.padding(innerPadding),
        ) {
            composable(Destination.Measure.route) { MeasureScreen() }
            composable(Destination.History.route) {
                HistoryScreen(
                    onMeasurementClick = { id -> navController.navigate("measurementDetail/$id") },
                )
            }
            composable(Destination.Settings.route) { SettingsScreen() }
            composable("measurementDetail/{id}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")?.toLongOrNull()
                if (id != null) {
                    MeasurementDetailScreen(measurementId = id, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
