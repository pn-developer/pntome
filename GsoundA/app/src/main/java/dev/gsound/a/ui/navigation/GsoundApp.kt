package dev.gsound.a.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.hilt.navigation.compose.hiltViewModel
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
import dev.gsound.a.ui.theme.StatusOffline
import dev.gsound.a.ui.theme.StatusOnline

/**
 * Root composable: a Material 3 [Scaffold] with a bottom [NavigationBar] hosting the three
 * top-level destinations (Folders, Pairing, Sync) wired through Navigation-Compose.
 *
 * Screens are empty placeholder stubs for task 1.3; feature content and ViewModels arrive
 * in later tasks.
 */
@Composable
fun GsoundApp(
    modifier: Modifier = Modifier,
    navStatusViewModel: NavBarStatusViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val pairingStatus by navStatusViewModel.pairingStatus.collectAsState()

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
                        icon = {
                            // The Pairing tab carries a live connectivity dot (green = online,
                            // red = offline) whenever a watch is paired, mirroring the ring on
                            // the Pairing screen so status is visible from anywhere.
                            if (destination.route == Route.Pairing && pairingStatus.paired) {
                                PairingTabIcon(
                                    icon = destination.icon,
                                    label = destination.label,
                                    online = pairingStatus.online,
                                )
                            } else {
                                Icon(destination.icon, contentDescription = destination.label)
                            }
                        },
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
 * The Pairing tab's icon with a small green/red connectivity dot badge. Green when the paired
 * watch is reachable, red when it is offline — the same cue as the Pairing screen's status ring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingTabIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    online: Boolean,
) {
    val dotColor = if (online) StatusOnline else StatusOffline
    val statusText = if (online) "watch online" else "watch offline"
    BadgedBox(
        badge = {
            Badge(
                containerColor = dotColor,
                // The dot itself carries no text; describe it for screen readers.
                modifier = Modifier.clearAndSetSemantics { contentDescription = statusText },
            )
        },
    ) {
        Icon(icon, contentDescription = label)
    }
}

/**
 * True when the current destination hierarchy includes [route], used to highlight the
 * matching bottom-navigation item.
 */
private fun androidx.navigation.NavDestination.hierarchyContainsRoute(route: Route): Boolean =
    hierarchy.any { it.hasRoute(route::class) }
