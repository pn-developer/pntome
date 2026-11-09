package dev.pntome.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

/**
 * Type-safe routes for the four top-level destinations (Navigation-Compose, Kotlin
 * serialization based routes). See design.md §6.
 */
sealed interface Route {
    @Serializable
    data object Folders : Route

    @Serializable
    data object Pairing : Route

    @Serializable
    data object Sync : Route

    /** Player tab: queue management, now-playing graphics and transport controls. */
    @Serializable
    data object Player : Route
}

/**
 * Describes a bottom-navigation entry: its route, label, and icon.
 */
data class TopLevelDestination(
    val route: Route,
    val label: String,
    val icon: ImageVector,
)

/** The four top-level destinations rendered in the bottom navigation bar. */
val topLevelDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(Route.Folders, "Folders", Icons.AutoMirrored.Filled.List),
    TopLevelDestination(Route.Pairing, "Pairing", Icons.Filled.Share),
    TopLevelDestination(Route.Sync, "Sync", Icons.Filled.Refresh),
    TopLevelDestination(Route.Player, "Player", Icons.Filled.PlayArrow),
)
