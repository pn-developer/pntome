package dev.gsound.a.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.model.SyncSummary
import dev.gsound.a.net.server.LanKind
import dev.gsound.a.ui.permissions.rememberNotificationPermissionRequester
import dev.gsound.a.ui.util.ByteSizeFormatter
import java.text.DateFormat
import java.util.Date

/**
 * Sync destination (Req 5.8, 6).
 *
 * Renders the live sync lifecycle owned by [SyncViewModel]: a gated Sync button that is enabled
 * only when paired AND folders are non-empty (Req 6.1, 2.4) with a hint explaining why it is
 * disabled; live progress for Preparing (indeterminate) and Running (determinate tracks + bytes,
 * current track, and the LAN address for connectivity verification — Req 5.8, 6.2); a success
 * summary with the last-sync timestamp on completion (Req 6.3); an error message with Retry
 * (Req 6.4); a Cancel affordance while a sync is active (Req 6.5); when idle, the previous run's
 * last-sync summary if present (Req 6.3); and an "On your watch" stats card showing the watch's
 * file/folder counts and last-sync time, or "unknown" when unavailable, with a Refresh action that
 * requests fresh stats (Req 7.1–7.3).
 *
 * This is the stateful entry point used by navigation; it hoists state from [SyncViewModel] and
 * delegates rendering to the stateless [SyncContent]. The paired `sharedSecret` is never displayed
 * (Req 8.4).
 */
@Composable
fun SyncScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Request POST_NOTIFICATIONS (API 33+) right before starting a sync so the foreground service's
    // progress notification is visible, with a rationale and graceful degrade (task 11.1, Req 9.3).
    // The sync starts regardless of the grant result — a denial only hides the shade progress.
    val notifications = rememberNotificationPermissionRequester()

    SyncContent(
        state = uiState,
        modifier = modifier,
        onSyncClicked = { notifications.request { viewModel.onSyncClicked() } },
        onCancelClicked = viewModel::onCancelClicked,
        onRetryClicked = { notifications.request { viewModel.onRetryClicked() } },
        onRefreshStatsClicked = viewModel::onRefreshStatsClicked,
    )

    // Hosts the rationale dialog; renders nothing until a rationale is pending.
    notifications.RationaleHost()
}

/**
 * Stateless sync UI. Separated from [SyncScreen] so it can be previewed and tested without Hilt
 * (task 10.3). Renders the current [SyncUiState] and dispatches the user's intents back out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncContent(
    state: SyncUiState,
    modifier: Modifier = Modifier,
    onSyncClicked: () -> Unit,
    onCancelClicked: () -> Unit,
    onRetryClicked: () -> Unit,
    onRefreshStatsClicked: () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Sync") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val sync = state.syncState) {
                is SyncState.Preparing -> PreparingState()

                is SyncState.Running -> RunningState(running = sync)

                is SyncState.Error -> ErrorState(
                    message = sync.message,
                    canRetry = state.canSync,
                    onRetry = onRetryClicked,
                )

                is SyncState.Completed -> CompletedState(summary = sync.summary)

                // Idle / Cancelled: resting state — offer Sync (gated) and show the last run.
                is SyncState.Idle,
                is SyncState.Cancelled -> IdleState(state = state)
            }

            // While a sync is active, always offer Cancel (Req 6.5). Otherwise, in resting
            // states, offer the gated Sync button (Req 6.1, 2.4).
            if (state.showCancel) {
                OutlinedButton(
                    onClick = onCancelClicked,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
                }
            } else if (!state.showRetry) {
                SyncButton(state = state, onSyncClicked = onSyncClicked)
            }

            // Watch stats: file/folder counts + last-sync, or "unknown" when unavailable
            // (Req 7.1, 7.3), with a Refresh affordance that requests fresh stats (Req 7.2).
            WatchStatsSection(
                stats = state.watchStats,
                canRefresh = state.isPaired,
                onRefresh = onRefreshStatsClicked,
            )

            // Bookmarks per book with the device they came from (PL-4.5); hidden when none.
            BookmarkSection(rows = state.bookmarks)
        }
    }
}

/**
 * The "On your watch" card: the watch's current file and folder counts and last-sync time (Req
 * 7.1), or an explicit "unknown" for each value when [stats] is null — never stale numbers without
 * indication (Req 7.3). The Refresh button asks the watch for fresh stats (Req 7.2) and is enabled
 * only while paired ([canRefresh]); the view model additionally no-ops the request unless the watch
 * is reachable, so the control never fabricates data when offline.
 */
@Composable
private fun WatchStatsSection(
    stats: WatchStats?,
    canRefresh: Boolean,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "On your watch",
                style = MaterialTheme.typography.titleMedium,
            )
            WatchStatRow(label = "Files", value = stats?.fileCount?.toString() ?: UNKNOWN)
            WatchStatRow(label = "Folders", value = stats?.folderCount?.toString() ?: UNKNOWN)
            WatchStatRow(
                label = "Last sync",
                value = when {
                    stats == null -> UNKNOWN
                    stats.lastSyncAt == null -> "never"
                    else -> formatTimestamp(stats.lastSyncAt)
                },
            )
            OutlinedButton(
                onClick = onRefresh,
                enabled = canRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Refresh stats")
            }
        }
    }
}

/** "Bookmarks" card: one row per book with "Ch N · mm:ss" and the source device (PL-4.5, 4.6). */
@Composable
internal fun BookmarkSection(rows: List<BookmarkRow>) {
    if (rows.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { contentDescription = row.description },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = row.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = row.position,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = row.device,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** A single `label: value` line in the watch-stats card. */
@Composable
private fun WatchStatRow(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Shown for any watch stat that is unavailable rather than stale (Req 7.3). */
private const val UNKNOWN = "unknown"

/** Idle/Cancelled resting content: a heading plus the previous run's summary if present. */
@Composable
private fun IdleState(state: SyncUiState) {
    Text(
        text = "Sync your music",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "Send the tracks in your folders to your paired watch over Wi-Fi.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    state.lastSummary?.let { summary ->
        LastSyncSummary(summary = summary)
    }
}

/**
 * The gated Sync button plus a hint explaining why it is disabled (Req 6.1, 2.4). Enabled only
 * when [SyncUiState.canStartSync] is true (paired + folders non-empty + not already syncing).
 */
@Composable
private fun SyncButton(
    state: SyncUiState,
    onSyncClicked: () -> Unit,
) {
    Button(
        onClick = onSyncClicked,
        enabled = state.canStartSync,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Sync now")
    }
    state.disabledReason?.let { reason ->
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Preparing: building the manifest and starting the server — indeterminate (Req 6.1). */
@Composable
private fun PreparingState() {
    CircularProgressIndicator()
    Text(
        text = "Preparing sync…",
        style = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * Running: determinate track progress with bytes and the current track, plus the LAN address the
 * server is bound to for connectivity verification (Req 5.8, 6.2).
 */
@Composable
private fun RunningState(running: SyncState.Running) {
    Text(
        text = "Syncing…",
        style = MaterialTheme.typography.headlineSmall,
    )

    if (running.tracksTotal > 0) {
        val fraction = (running.tracksDone.toFloat() / running.tracksTotal).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${running.tracksDone} of ${running.tracksTotal} tracks",
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    Text(
        text = bytesLabel(running.bytesSent, running.bytesTotal),
        style = MaterialTheme.typography.bodySmall,
    )

    running.speedBytesPerSec?.takeIf { it > 0 }?.let { speed ->
        Text(
            text = "${ByteSizeFormatter.format(speed)}/s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    running.currentTrackTitle?.let { title ->
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }

    running.serverUrl?.let { url ->
        LanAddress(url = url, kind = running.networkKind)
    }
}

/** Completed: success summary with counts and the last-sync timestamp (Req 6.3). */
@Composable
private fun CompletedState(summary: SyncSummary) {
    Text(
        text = "Sync complete",
        style = MaterialTheme.typography.headlineSmall,
    )
    LastSyncSummary(summary = summary)
}

/** Error: surface an actionable [message] with Retry when a retry is possible (Req 6.4). */
@Composable
private fun ErrorState(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    Text(
        text = "Sync problem",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onRetry,
        enabled = canRetry,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Retry")
    }
}

/**
 * Render a [SyncSummary] card: synced/skipped/removed counts and the last-sync timestamp
 * (Req 6.3). Reused by both the completed state and the idle "previous run" display.
 */
@Composable
private fun LastSyncSummary(summary: SyncSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Last sync",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatTimestamp(summary.lastSyncAt),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${summary.syncedCount} synced · " +
                    "${summary.skippedCount} skipped · " +
                    "${summary.removedCount} removed",
                style = MaterialTheme.typography.bodyMedium,
            )
            summary.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Show the LAN `http://ip:port` the server is bound to so the user can verify both devices are on
 * the same network (Req 5.8). Only the address is shown — never the shared secret (Req 8.4).
 */
@Composable
private fun LanAddress(url: String, kind: LanKind?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Serving on",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = lanAddressLabel(url, kind),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

/**
 * Format the serving address as `Wi-Fi · host:port` or `Hotspot · host:port` (HS-3.2). The
 * scheme is stripped from [url]; when [kind] is null the bare [url] is returned unchanged.
 */
internal fun lanAddressLabel(url: String, kind: LanKind?): String {
    val prefix = when (kind) {
        LanKind.WIFI -> "Wi-Fi"
        LanKind.HOTSPOT -> "Hotspot"
        null -> return url
    }
    return "$prefix · ${url.substringAfter("://")}"
}

/** Format the transferred/total bytes for the running progress line (Req 6.2). */
private fun bytesLabel(bytesSent: Long, bytesTotal: Long?): String =
    if (bytesTotal != null && bytesTotal > 0) {
        "${ByteSizeFormatter.format(bytesSent)} of ${ByteSizeFormatter.format(bytesTotal)}"
    } else {
        ByteSizeFormatter.format(bytesSent)
    }

/** Format a sync timestamp as a human-readable local date/time (Req 6.3). */
private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMillis))
