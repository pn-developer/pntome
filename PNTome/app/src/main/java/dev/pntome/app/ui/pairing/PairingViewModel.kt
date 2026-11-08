package dev.pntome.app.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pntome.app.data.pairing.PairingStore
import dev.pntome.app.domain.model.PairingRecord
import dev.pntome.app.log.PNTomeLog
import dev.pntome.app.log.Logger
import dev.pntome.app.net.garmin.ConnectionState
import dev.pntome.app.net.garmin.ControlChannel
import dev.pntome.app.net.garmin.messages.PairConfirm
import dev.pntome.app.net.garmin.messages.PairRequest
import dev.pntome.app.net.garmin.messages.Unpair
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
     * @property reachable the watch's live reachability when [paired] is non-null: `true` =
     *   online (control channel Connected), `false` = offline/unreachable, `null` = not yet
     *   checked (resting/unknown). Ignored when [paired] is null.
     * @property refreshing `true` while an on-demand reachability re-check is in flight, so the
     *   UI can show a spinner over the watch icon (Req: tap-to-refresh status).
     */
    data class Idle(
        val paired: PairingRecord? = null,
        val reachable: Boolean? = null,
        val refreshing: Boolean = false,
    ) : PairingUiState

    /**
     * A pairing attempt has started: connecting the control channel and resolving the PNTomeGo
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
    private val logger: Logger = PNTomeLog,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    /** Logcat tag for pairing diagnostics (filter: `adb logcat -s PNTome.Pair`). */
    private val tag = "PNTome.Pair"

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

    /** In-flight tap-to-refresh reachability check, so [unpair] can cancel it (Req: unpair must
     *  never be blocked by a connectivity probe). */
    private var refreshJob: Job? = null

    init {
        // Restore the persisted pairing so Idle reflects the current paired peer on launch
        // (Req 4.8, Req 8.2). Only overwrite Idle; never clobber an active attempt. Preserve the
        // last known reachability/refreshing flags so a store re-emit doesn't blank the ring.
        viewModelScope.launch {
            pairingStore.pairing.collect { record ->
                val current = _uiState.value
                if (current is PairingUiState.Idle) {
                    _uiState.value = current.copy(paired = record)
                }
            }
        }

        // Drive the online/offline ring reactively: Connected = online, anything else = offline.
        // Only meaningful while paired; the screen ignores reachability when unpaired. A fresh
        // reachability value also clears any in-flight `refreshing` flag.
        viewModelScope.launch {
            controlChannel.connectionState.collect { state ->
                val current = _uiState.value
                if (current is PairingUiState.Idle && current.paired != null) {
                    _uiState.value = current.copy(
                        reachable = state is ConnectionState.Connected,
                        refreshing = false,
                    )
                }
            }
        }
    }

    /**
     * Re-check whether the paired watch is reachable right now (tap-to-refresh on the watch
     * icon). Triggers an on-demand [ControlChannel.connect] re-enumeration; the resulting
     * [ConnectionState] is projected onto [PairingUiState.Idle.reachable] by the collector in
     * [init]. No-op unless currently paired and resting in [PairingUiState.Idle] — there is
     * nothing to refresh mid-handshake.
     */
    fun refreshReachability() {
        val current = _uiState.value
        if (current !is PairingUiState.Idle || current.paired == null) {
            logger.d(tag, "refreshReachability ignored: state=${current::class.simpleName}")
            return
        }
        if (current.refreshing) return // already in flight

        _uiState.value = current.copy(refreshing = true)
        refreshJob = viewModelScope.launch {
            logger.i(tag, "refreshReachability: re-checking watch connectivity")
            // Bound the check so the UI can never get stuck on "Checking…" if the SDK resolve
            // stalls (the device poll window is ~5s; allow headroom for app-info resolution).
            val timedOut = withTimeoutOrNull(REACHABILITY_TIMEOUT_MILLIS) {
                runCatching { controlChannel.connect() }
                    .onFailure { logger.w(tag, "refreshReachability connect() threw: ${it.javaClass.simpleName}") }
                true
            } == null
            if (timedOut) {
                logger.w(tag, "refreshReachability: timed out after ${REACHABILITY_TIMEOUT_MILLIS}ms")
            }
            // The connectionState collector updates `reachable` + clears `refreshing`. As a
            // backstop (state unchanged → no new emission, or a timeout), clear the flag here too
            // using the latest known connection state.
            val latest = _uiState.value
            if (latest is PairingUiState.Idle && latest.refreshing) {
                _uiState.value = latest.copy(
                    reachable = controlChannel.connectionState.value is ConnectionState.Connected,
                    refreshing = false,
                )
            }
            refreshJob = null
        }
    }

    /**
     * Begin a pairing attempt (Req 4.1, 4.3, 4.4). No-op if already past [PairingUiState.Idle].
     * Refuses to start while a pairing already exists (Req 4.8) — the user must [unpair] first.
     */
    fun startPairing() {
        if (_uiState.value !is PairingUiState.Idle) {
            logger.d(tag, "startPairing ignored: state=${_uiState.value::class.simpleName}")
            return
        }

        viewModelScope.launch {
            logger.i(tag, "startPairing: beginning pairing attempt")
            if (pairingStore.isPaired()) {
                logger.w(tag, "startPairing refused: already paired (unpair first, Req 4.8)")
                _uiState.value = PairingUiState.Error(
                    "Already paired. Unpair the current watch before pairing a new one.",
                )
                return@launch
            }

            _uiState.value = PairingUiState.Discovering
            logger.i(tag, "state=Discovering; ensuring control channel connected")

            val connectError = ensureConnected()
            if (connectError != null) {
                logger.w(tag, "ensureConnected failed -> Error: $connectError")
                _uiState.value = PairingUiState.Error(connectError)
                return@launch
            }
            logger.i(tag, "control channel connected; generating credentials")

            val credentials = credentialGenerator.generate()
            pending = credentials
            localConfirmed = false
            remoteConfirmed = false
            // pairingId is a non-secret UUID and is safe to log; the code/secret are NOT logged
            // (Req 8.4). Use dRedacted to confirm the secret's presence/length only.
            logger.i(tag, "generated credentials: pairingId=${credentials.pairingId}")
            logger.dRedacted(tag, "sharedSecret", credentials.sharedSecret)

            logger.i(tag, "sending PAIR_REQUEST (pairingId=${credentials.pairingId})")
            val sendResult = controlChannel.send(
                PairRequest(
                    code = credentials.code,
                    pairingId = credentials.pairingId,
                    secret = credentials.sharedSecret,
                    peerName = peerNameProvider.peerName(),
                ),
            )
            if (sendResult.isFailure) {
                logger.w(tag, "PAIR_REQUEST send failed: ${sendResult.exceptionOrNull()?.javaClass?.simpleName}")
                clearPending()
                _uiState.value = PairingUiState.Error(
                    "Couldn't reach the watch. Make sure Garmin Connect is running and try again.",
                )
                return@launch
            }

            logger.i(tag, "state=AwaitingConfirm; showing code, waiting for user tap + PAIR_CONFIRM")
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
        if (_uiState.value !is PairingUiState.AwaitingConfirm) {
            logger.d(tag, "confirm ignored: state=${_uiState.value::class.simpleName}")
            return
        }
        logger.i(tag, "local confirm tapped (remoteConfirmed=$remoteConfirmed)")
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
        // Unpair must take effect immediately and must never be blocked by an in-flight
        // reachability check (the probe can take seconds). Cancel any running refresh first so
        // the user is never stuck watching "Checking…" while Unpair appears to do nothing.
        refreshJob?.cancel()
        refreshJob = null

        viewModelScope.launch {
            val record = pairingStore.get() ?: run {
                logger.d(tag, "unpair: nothing to unpair")
                _uiState.value = PairingUiState.Idle(paired = null)
                return@launch
            }
            logger.i(tag, "unpair: clearing store + notifying watch (pairingId=${record.pairingId})")
            // Clear the LOCAL record first so the UI reflects "unpaired" at once, independent of
            // whether the watch is reachable. The UNPAIR notify to the watch is best-effort and
            // must not gate the local state change.
            pairingStore.clear()
            _uiState.value = PairingUiState.Idle(paired = null, reachable = null, refreshing = false)
            // Fire-and-forget the UNPAIR to the watch; a slow/failed send no longer holds up the
            // user-visible unpair.
            launch {
                runCatching { controlChannel.send(Unpair(pairingId = record.pairingId)) }
                    .onFailure { logger.w(tag, "unpair: UNPAIR notify failed: ${it.javaClass.simpleName}") }
            }
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
                        logger.i(tag, "PAIR_CONFIRM received + matches (pairingId=$pairingId, localConfirmed=$localConfirmed)")
                        remoteConfirmed = true
                        maybeComplete()
                    } else {
                        logger.w(tag, "PAIR_CONFIRM ignored: pairingId mismatch (got=${confirm.pairingId}, expected=$pairingId)")
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
                logger.w(tag, "pairing TIMED OUT after ${timeoutMillis}ms (localConfirmed=$localConfirmed, remoteConfirmed=$remoteConfirmed)")
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
            logger.i(tag, "both confirmations present -> completing pairing (pairingId=${credentials.pairingId})")
            val record = PairingRecord(
                pairingId = credentials.pairingId,
                peerName = peerNameProvider.peerName(),
                sharedSecret = credentials.sharedSecret,
                createdAt = clock.nowMillis(),
            )
            pairingStore.save(record)
            logger.i(tag, "pairing PERSISTED -> state=Paired (peer=${record.peerName})")
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
            "PNTomeGo isn't installed on your watch. Install it from the Connect IQ store."
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

        /**
         * Upper bound for a tap-to-refresh reachability check so the watch icon never gets stuck
         * on "Checking…". Covers the control channel's ~5 s device poll window plus headroom for
         * app-info resolution.
         */
        const val REACHABILITY_TIMEOUT_MILLIS: Long = 10_000L
    }
}
