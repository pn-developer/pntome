package dev.gsound.a.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.ControlChannel
import dev.gsound.a.net.garmin.messages.PairConfirm
import dev.gsound.a.net.garmin.messages.PairRequest
import dev.gsound.a.net.garmin.messages.Unpair
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * Immutable UI state for the pairing screen, exposed as a [StateFlow] (design §5.4, §6).
 *
 * Modeled as a sealed interface so the screen (task 7.2) gets exhaustive `when` rendering and
 * so the state machine's legal states are explicit:
 *
 * ```
 * Idle → Discovering → AwaitingConfirm(code) → Paired(peer)
 *                                 └→ Error(message)   (timeout / channel error / mismatch)
 * ```
 */
sealed interface PairingUiState {

    /**
     * Resting state. Carries the already-persisted [paired] record (if any) so the screen can
     * show the current paired peer and an Unpair affordance without a separate state (Req 4.8:
     * only one active pairing — a non-null [paired] means the user must unpair before starting
     * a new attempt).
     *
     * @property paired the persisted pairing restored from [PairingStore], or null when unpaired.
     */
    data class Idle(val paired: PairingRecord? = null) : PairingUiState

    /**
     * A pairing attempt has started: connecting the control channel and resolving the GsoundG
     * app before credentials are generated (Req 4.1).
     */
    data object Discovering : PairingUiState

    /**
     * Credentials were generated and `PAIR_REQUEST` was sent; the 6-digit [code] is shown for
     * the user to compare against the watch (Req 4.3, Req 4.4). Pairing completes only once the
     * user taps Confirm **and** a `PAIR_CONFIRM` arrives (Req 4.5).
     *
     * @property code the 6-digit confirmation code to display.
     */
    data class AwaitingConfirm(val code: String) : PairingUiState

    /**
     * Pairing completed and the [PairingRecord] was persisted (Req 4.5).
     *
     * @property peer the freshly persisted pairing.
     */
    data class Paired(val peer: PairingRecord) : PairingUiState

    /**
     * The attempt failed or timed out; the temporary credentials have been discarded (Req 4.6).
     *
     * @property message an actionable, user-facing explanation (Req 4.2).
     */
    data class Error(val message: String) : PairingUiState
}

/**
 * Drives the pairing handshake state machine (design §5.4, Req 4).
 *
 * ## State machine
 * `Idle → Discovering → AwaitingConfirm → Paired`, with any step able to fall to `Error`.
 * On [startPairing] the view model ensures the [ControlChannel] is connected (mapping
 * [ConnectionState.Error] to an actionable [PairingUiState.Error], Req 4.2), then generates the
 * temporary credentials (6-digit code, `pairingId`, `sharedSecret` — Req 4.3) via the injected
 * [PairingCredentialGenerator], sends a [PairRequest], and moves to [PairingUiState.AwaitingConfirm]
 * showing the code (Req 4.4).
 *
 * ## Dual confirmation (Req 4.5)
 * Persisting requires **both** signals, tracked as two booleans:
 * - the user tapping Confirm locally ([confirm]), and
 * - a [PairConfirm] arriving over [incoming][ControlChannel.incoming] whose `pairingId` matches
 *   the pending attempt.
 * When both are true the view model builds a [PairingRecord] (`pairingId`, `peerName`,
 * `sharedSecret`, `createdAt`), saves it via [PairingStore], cancels the timeout, and moves to
 * [PairingUiState.Paired]. Order-independent: either signal may arrive first.
 *
 * ## Timeout (Req 4.6)
 * A [timeoutMillis] watchdog (default ~120 s) runs for the whole attempt; if pairing has not
 * completed when it fires, the attempt is aborted, the temporary code/secret are discarded, and
 * the state becomes [PairingUiState.Error]. The timeout is a plain [delay] inside
 * [viewModelScope], so a `TestDispatcher` can drive it with `advanceTimeBy` (task 7.3) — this,
 * together with the injectable [PairingCredentialGenerator] and [PairingClock], are the testing
 * seams.
 *
 * ## Single active pairing (Req 4.8)
 * v1 supports one pairing. The persisted record is reflected in [PairingUiState.Idle.paired];
 * [startPairing] refuses to begin a new attempt while one exists, surfacing a message that asks
 * the user to unpair first. [unpair] sends `UNPAIR` and clears the store (the Unpair screen
 * affordance lives in task 7.2; the logic is implemented here for cohesion).
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val controlChannel: ControlChannel,
    private val pairingStore: PairingStore,
    private val credentialGenerator: PairingCredentialGenerator,
    private val peerNameProvider: PeerNameProvider,
    private val clock: PairingClock,
    @Named(PAIRING_TIMEOUT_MILLIS) private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    /** The in-flight attempt's temporary credentials, or null when not awaiting confirmation. */
    private var pending: PairingCredentials? = null

    /** Local Confirm tapped for the pending attempt (one half of Req 4.5). */
    private var localConfirmed = false

    /** `PAIR_CONFIRM` received for the pending attempt (other half of Req 4.5). */
    private var remoteConfirmed = false

    /** Watchdog that aborts the attempt on timeout (Req 4.6). */
    private var timeoutJob: Job? = null

    /** Collector of matching `PAIR_CONFIRM` messages for the current attempt. */
    private var incomingJob: Job? = null

    init {
        // Restore the persisted pairing so Idle reflects the current paired peer on launch
        // (Req 4.8, Req 8.2). Only overwrite Idle; never clobber an active attempt.
        viewModelScope.launch {
            pairingStore.pairing.collect { record ->
                if (_uiState.value is PairingUiState.Idle) {
                    _uiState.value = PairingUiState.Idle(paired = record)
                }
            }
        }
    }

    /**
     * Begin a pairing attempt (Req 4.1, 4.3, 4.4). No-op if already past [PairingUiState.Idle].
     * Refuses to start while a pairing already exists (Req 4.8) — the user must [unpair] first.
     */
    fun startPairing() {
        if (_uiState.value !is PairingUiState.Idle) return

        viewModelScope.launch {
            if (pairingStore.isPaired()) {
                _uiState.value = PairingUiState.Error(
                    "Already paired. Unpair the current watch before pairing a new one.",
                )
                return@launch
            }

            _uiState.value = PairingUiState.Discovering

            val connectError = ensureConnected()
            if (connectError != null) {
                _uiState.value = PairingUiState.Error(connectError)
                return@launch
            }

            val credentials = credentialGenerator.generate()
            pending = credentials
            localConfirmed = false
            remoteConfirmed = false

            val sendResult = controlChannel.send(
                PairRequest(
                    code = credentials.code,
                    pairingId = credentials.pairingId,
                    secret = credentials.sharedSecret,
                    peerName = peerNameProvider.peerName(),
                ),
            )
            if (sendResult.isFailure) {
                clearPending()
                _uiState.value = PairingUiState.Error(
                    "Couldn't reach the watch. Make sure Garmin Connect is running and try again.",
                )
                return@launch
            }

            _uiState.value = PairingUiState.AwaitingConfirm(credentials.code)
            observePairConfirm(credentials.pairingId)
            startTimeout()
        }
    }

    /**
     * Record the user's local Confirm (Req 4.5). If a matching `PAIR_CONFIRM` already arrived,
     * this completes the pairing; otherwise it waits for the remote half.
     */
    fun confirm() {
        if (_uiState.value !is PairingUiState.AwaitingConfirm) return
        localConfirmed = true
        maybeComplete()
    }

    /**
     * Cancel the in-flight attempt or dismiss an error and return to [PairingUiState.Idle],
     * discarding any temporary credentials (Req 4.6). Reflects the persisted pairing (if any).
     */
    fun cancel() {
        clearPending()
        viewModelScope.launch {
            _uiState.value = PairingUiState.Idle(paired = pairingStore.get())
        }
    }

    /** Alias for [cancel]; resets the flow to [PairingUiState.Idle]. */
    fun reset() = cancel()

    /**
     * Unpair the current peer (Req 4.7, 4.8): send `UNPAIR` then clear the stored record. The
     * send result is best-effort — the local record is cleared regardless so the user is no
     * longer paired even if the watch is unreachable.
     */
    fun unpair() {
        viewModelScope.launch {
            val record = pairingStore.get() ?: run {
                _uiState.value = PairingUiState.Idle(paired = null)
                return@launch
            }
            controlChannel.send(Unpair(pairingId = record.pairingId))
            pairingStore.clear()
            _uiState.value = PairingUiState.Idle(paired = null)
        }
    }

    /**
     * Ensure the control channel is connected, returning null on success or an actionable error
     * message mapped from [ConnectionState.Error] (Req 4.2). Calls [ControlChannel.connect] when
     * not already connected and waits for the state to resolve.
     */
    private suspend fun ensureConnected(): String? {
        when (val state = controlChannel.connectionState.value) {
            is ConnectionState.Connected -> return null
            is ConnectionState.Error -> return messageFor(state)
            else -> Unit // Disconnected / Connecting: attempt to connect below.
        }

        controlChannel.connect()

        // Wait until the channel resolves out of the transient Connecting state.
        val resolved = controlChannel.connectionState.first { it !is ConnectionState.Connecting }
        return when (resolved) {
            is ConnectionState.Connected -> null
            is ConnectionState.Error -> messageFor(resolved)
            else -> "Couldn't connect to the watch. Open Garmin Connect and try again."
        }
    }

    /** Collect `PAIR_CONFIRM` messages matching [pairingId] (the remote half of Req 4.5). */
    private fun observePairConfirm(pairingId: String) {
        incomingJob?.cancel()
        incomingJob = viewModelScope.launch {
            controlChannel.incoming
                .filterIsInstance<PairConfirm>()
                .collect { confirm ->
                    if (confirm.pairingId == pairingId && pending?.pairingId == pairingId) {
                        remoteConfirmed = true
                        maybeComplete()
                    }
                }
        }
    }

    /** Start the ~120 s abort watchdog for the current attempt (Req 4.6). */
    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(timeoutMillis)
            // Only fire if still awaiting confirmation (completion cancels this job).
            if (_uiState.value is PairingUiState.AwaitingConfirm) {
                clearPending()
                _uiState.value = PairingUiState.Error(
                    "Pairing timed out. Check the code on your watch and try again.",
                )
            }
        }
    }

    /**
     * Complete pairing once **both** confirmations are present (Req 4.5): build and persist the
     * [PairingRecord], cancel the watchdog, and move to [PairingUiState.Paired]. No-op until both
     * signals are in.
     */
    private fun maybeComplete() {
        if (!localConfirmed || !remoteConfirmed) return
        val credentials = pending ?: return

        viewModelScope.launch {
            val record = PairingRecord(
                pairingId = credentials.pairingId,
                peerName = peerNameProvider.peerName(),
                sharedSecret = credentials.sharedSecret,
                createdAt = clock.nowMillis(),
            )
            pairingStore.save(record)
            timeoutJob?.cancel()
            incomingJob?.cancel()
            pending = null
            localConfirmed = false
            remoteConfirmed = false
            _uiState.value = PairingUiState.Paired(record)
        }
    }

    /** Discard the temporary credentials and tear down the attempt's jobs (Req 4.6). */
    private fun clearPending() {
        pending = null
        localConfirmed = false
        remoteConfirmed = false
        timeoutJob?.cancel()
        timeoutJob = null
        incomingJob?.cancel()
        incomingJob = null
    }

    private fun messageFor(error: ConnectionState.Error): String = when (error.reason) {
        ConnectionState.Error.Reason.NO_DEVICE ->
            "No Garmin watch found. Open Garmin Connect and make sure your watch is connected."
        ConnectionState.Error.Reason.APP_NOT_INSTALLED ->
            "GsoundG isn't installed on your watch. Install it from the Connect IQ store."
        ConnectionState.Error.Reason.BLUETOOTH_OFF ->
            "Bluetooth is off. Turn it on to pair with your watch."
        ConnectionState.Error.Reason.DEVICE_DISCONNECTED ->
            "Your watch disconnected. Reconnect it and try again."
        ConnectionState.Error.Reason.SDK_FAILURE ->
            "Couldn't reach the watch. Open Garmin Connect and try again."
    }

    companion object {
        /** Hilt qualifier name for the injectable pairing timeout (overridable in tests). */
        const val PAIRING_TIMEOUT_MILLIS = "pairingTimeoutMillis"

        /** Default pairing timeout, ~120 s (Req 4.6). */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 120_000L
    }
}
