package dev.gsound.a.ui.pairing

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.gsound.a.R
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.ui.theme.StatusOffline
import dev.gsound.a.ui.theme.StatusOnline
import java.text.DateFormat
import java.util.Date

/**
 * Pairing destination (Req 4).
 *
 * Renders the pairing handshake state machine owned by [PairingViewModel]: a prompt to pair
 * (Req 4.1), the live 6-digit confirmation code with a Confirm affordance (Req 4.3, 4.4), the
 * dual-confirmation completion into a paired peer (Req 4.5), an Unpair affordance for the active
 * pairing (Req 4.7, 4.8), and actionable errors (Req 4.2, 4.6).
 *
 * This is the stateful entry point used by navigation; it hoists state from [PairingViewModel]
 * and delegates rendering to the stateless [PairingContent]. The [sharedSecret] of a
 * [PairingRecord] is never displayed (Req 8.4).
 */
@Composable
fun PairingScreen(
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    PairingContent(
        state = uiState,
        modifier = modifier,
        onStartPairing = viewModel::startPairing,
        onConfirm = viewModel::confirm,
        onCancel = viewModel::cancel,
        onUnpair = viewModel::unpair,
        onRefreshStatus = viewModel::refreshReachability,
    )
}

/**
 * Stateless pairing UI. Separated from [PairingScreen] so it can be previewed and tested without
 * Hilt. Exhaustively renders every [PairingUiState] and dispatches the user's intents back to the
 * caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingContent(
    state: PairingUiState,
    modifier: Modifier = Modifier,
    onStartPairing: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUnpair: () -> Unit,
    onRefreshStatus: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Pairing") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            when (state) {
                is PairingUiState.Idle ->
                    if (state.paired != null) {
                        PairedPeer(
                            peer = state.paired,
                            reachable = state.reachable,
                            refreshing = state.refreshing,
                            onRefreshStatus = onRefreshStatus,
                            onUnpair = onUnpair,
                        )
                    } else {
                        PairPrompt(onStartPairing = onStartPairing)
                    }

                is PairingUiState.Discovering -> Discovering()

                is PairingUiState.AwaitingConfirm ->
                    AwaitingConfirm(
                        code = state.code,
                        onConfirm = onConfirm,
                        onCancel = onCancel,
                    )

                is PairingUiState.Paired ->
                    PairedPeer(
                        peer = state.peer,
                        // Pairing just completed over a live channel, so show it online; tapping
                        // the watch still re-checks on demand.
                        reachable = true,
                        refreshing = false,
                        onRefreshStatus = onRefreshStatus,
                        header = "Paired with your watch",
                        onUnpair = onUnpair,
                    )

                is PairingUiState.Error ->
                    ErrorState(
                        message = state.message,
                        onRetry = onStartPairing,
                        onDismiss = onCancel,
                    )
            }
        }
    }
}

/** Unpaired resting state: prompt the user to begin pairing (Req 4.1). */
@Composable
private fun PairPrompt(onStartPairing: () -> Unit) {
    Text(
        text = "Pair your watch",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = "Connect GsoundA to your Garmin watch so it can download your music.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(onClick = onStartPairing) {
        Text("Pair")
    }
}

/** Discovering state: resolving the watch and GsoundG app before a code is shown (Req 4.1). */
@Composable
private fun Discovering() {
    CircularProgressIndicator(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "Looking for your watch"
        },
    )
    Text(
        text = "Looking for your watch…",
        style = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * Awaiting-confirmation state: show the 6-digit [code] prominently and ask the user to verify it
 * matches the watch before confirming (Req 4.4). Pairing completes only once the user taps Confirm
 * and the watch's `PAIR_CONFIRM` arrives (Req 4.5) — handled by the view model.
 */
@Composable
private fun AwaitingConfirm(
    code: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = "Confirm this code on your watch",
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
    )
    Text(
        text = code,
        style = MaterialTheme.typography.displayMedium.copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 8.sp,
        ),
        modifier = Modifier.clearAndSetSemantics {
            // Read the code digit-by-digit so it's unambiguous to screen readers.
            contentDescription = "Pairing code ${code.toCharArray().joinToString(" ")}"
        },
    )
    Text(
        text = "Make sure this matches the code shown on your watch, then tap Confirm.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onConfirm,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Confirm")
    }
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Cancel")
    }
}

/**
 * Paired display: show a picture of the paired watch ringed in green (online) or red (offline)
 * per live reachability, the [peer]'s name, when pairing completed, and an Unpair affordance that
 * sends `UNPAIR` and clears the record (Req 4.7). The [peer]'s `sharedSecret` is deliberately
 * never rendered (Req 8.4).
 *
 * Tapping the watch icon calls [onRefreshStatus] to re-check connectivity on demand, so the user
 * can quickly diagnose whether the watch is online.
 *
 * @param reachable `true` = online (green ring), `false` = offline (red ring), `null` = unknown
 *   (neutral ring) — e.g. before the first check.
 * @param refreshing `true` while a re-check is in flight; shows a spinner over the icon.
 */
@Composable
private fun PairedPeer(
    peer: PairingRecord,
    reachable: Boolean?,
    refreshing: Boolean,
    onRefreshStatus: () -> Unit,
    onUnpair: () -> Unit,
    header: String = "Paired watch",
) {
    Text(
        text = header,
        style = MaterialTheme.typography.headlineSmall,
    )

    WatchStatusBadge(
        reachable = reachable,
        refreshing = refreshing,
        onRefreshStatus = onRefreshStatus,
    )

    Text(
        text = peer.peerName,
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = statusLabel(reachable, refreshing),
        style = MaterialTheme.typography.bodyMedium,
        color = statusColor(reachable),
    )
    Text(
        text = "Tap the watch to refresh its status",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "Paired ${formatTimestamp(peer.createdAt)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onUnpair) {
        Text("Unpair")
    }
}

/**
 * A circular watch picture surrounded by a status ring (green = online, red = offline, neutral =
 * unknown), tappable to refresh reachability. While [refreshing] a spinner overlays the icon and
 * the ring dims slightly. The whole badge is a single semantic button for accessibility.
 */
@Composable
private fun WatchStatusBadge(
    reachable: Boolean?,
    refreshing: Boolean,
    onRefreshStatus: () -> Unit,
) {
    val ringColor = statusColor(reachable)
    val semantics = "Paired watch, ${statusLabel(reachable, refreshing)}. Tap to refresh status."

    Box(
        modifier = Modifier
            .size(112.dp)
            .clip(CircleShape)
            .border(width = 4.dp, color = ringColor, shape = CircleShape)
            .clickable(enabled = !refreshing, onClick = onRefreshStatus)
            .padding(8.dp)
            .semantics { contentDescription = semantics },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_watch),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(56.dp),
        )
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(84.dp),
                strokeWidth = 3.dp,
                color = ringColor,
            )
        }
    }
}

/** Green when online, red when offline, neutral when unknown. */
@Composable
private fun statusColor(reachable: Boolean?) = when (reachable) {
    true -> StatusOnline
    false -> StatusOffline
    null -> MaterialTheme.colorScheme.outline
}

/** Human-readable connectivity label for the status line and accessibility. */
private fun statusLabel(reachable: Boolean?, refreshing: Boolean): String = when {
    refreshing -> "Checking…"
    reachable == true -> "Online"
    reachable == false -> "Offline"
    else -> "Status unknown"
}

/** Error state: surface an actionable [message] with Retry and Dismiss (Req 4.2, 4.6). */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Text(
        text = "Pairing problem",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Try again")
    }
    OutlinedButton(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Dismiss")
    }
}

/** Format a pairing timestamp as a human-readable local date/time. */
private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMillis))
