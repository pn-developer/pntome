package dev.gsound.a.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.gsound.a.domain.model.PairingRecord
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
                        PairedPeer(peer = state.paired, onUnpair = onUnpair)
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
 * Paired display: show the paired [peer]'s name and when pairing completed, plus an Unpair
 * affordance that sends `UNPAIR` and clears the record (Req 4.7). The [peer]'s `sharedSecret` is
 * deliberately never rendered (Req 8.4).
 */
@Composable
private fun PairedPeer(
    peer: PairingRecord,
    onUnpair: () -> Unit,
    header: String = "Paired watch",
) {
    Text(
        text = header,
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = peer.peerName,
        style = MaterialTheme.typography.titleLarge,
    )
    Text(
        text = "Paired ${formatTimestamp(peer.createdAt)}",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedButton(onClick = onUnpair) {
        Text("Unpair")
    }
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
