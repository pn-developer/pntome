package dev.gsound.a.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.gsound.a.ui.folders.FolderListScreen
import dev.gsound.a.ui.pairing.PairingScreen
import dev.gsound.a.ui.sync.SyncScreen

/**
 * Root composable: a Material 3 [Scaffold] with a bottom [NavigationBar] hosting the three
 * top-level destinations (Folders, Pairing, Sync) wired through Navigation-Compose.
 *
 * Screens are empty placeholder stubs for task 1.3; feature content and ViewModels arrive
 * in later tasks.
 */
@Composable
fun GsoundApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            NavigationBar {
                topLevelDestinations.forEach { destination ->
                    val selected = currentDestination
                        ?.hierarchyContainsRoute(destination.route) ?: false

                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Pop up to the start destination to avoid building a
                                // large back stack of top-level destinations.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Route.Folders,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable<Route.Folders> { FolderListScreen() }
            composable<Route.Pairing> { PairingScreen() }
            composable<Route.Sync> { SyncScreen() }
        }
    }
}

/**
 * True when the current destination hierarchy includes [route], used to highlight the
 * matching bottom-navigation item.
 */
private fun androidx.navigation.NavDestination.hierarchyContainsRoute(route: Route): Boolean =
    hierarchy.any { it.hasRoute(route::class) }
