package dev.gsound.a.net.garmin

import dev.gsound.a.net.garmin.messages.ControlMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test-only [ControlChannel] used by unit tests to drive pairing/sync/control logic without the
 * Connect IQ Mobile SDK (design §9, task 6.3).
 *
 * Capabilities:
 * - **Push incoming**: [emitIncoming] replays a validated [ControlMessage] to [incoming]
 *   collectors (callers decide what is "valid"; the real validation lives in
 *   [dev.gsound.a.net.garmin.messages.MessageCodec]).
 * - **Record sent**: every [send] appends to [sent] so tests can assert what was transmitted.
 * - **Script send results**: [sendResults] is a FIFO queue of outcomes [send] returns in order
 *   (e.g. two failures then a success to exercise retry); when empty, [defaultSendResult] is
 *   used. The payload is still recorded on every attempt (including scripted failures).
 * - **Drive connection state**: [setConnectionState] mutates the observable [connectionState].
 *
 * Not thread-safe; intended for single-threaded coroutine tests.
 */
class FakeControlChannel(
    initialState: ConnectionState = ConnectionState.Disconnected,
    /** Result returned by [send] once [sendResults] is exhausted. */
    var defaultSendResult: Result<Unit> = Result.success(Unit),
) : ControlChannel {

    private val _connectionState = MutableStateFlow(initialState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<ControlMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: Flow<ControlMessage> = _incoming.asSharedFlow()

    /**
     * Number of active collectors of [incoming]. A `SharedFlow` with `replay = 0` drops values
     * emitted while there are no subscribers (the `extraBufferCapacity` buffer only absorbs
     * back-pressure for *existing* subscribers). Tests must therefore wait for a collector to be
     * present before [emitIncoming], otherwise the emission is lost and the collector parks
     * forever. Expose the live subscription count so tests can await it deterministically.
     */
    val subscriptionCount: StateFlow<Int> = _incoming.subscriptionCount

    /** Every message handed to [send], in order, including scripted-failure attempts. */
    val sent: MutableList<ControlMessage> = mutableListOf()

    /** FIFO queue of scripted [send] outcomes; drained one per [send] call. */
    val sendResults: ArrayDeque<Result<Unit>> = ArrayDeque()

    /** Number of times [connect] was called. */
    var connectCount: Int = 0
        private set

    /** Number of times [close] was called. */
    var closeCount: Int = 0
        private set

    override suspend fun connect() {
        connectCount++
    }

    override suspend fun send(message: ControlMessage): Result<Unit> {
        sent += message
        return if (sendResults.isNotEmpty()) sendResults.removeFirst() else defaultSendResult
    }

    override suspend fun close() {
        closeCount++
        _connectionState.value = ConnectionState.Disconnected
    }

    /** Drive the observable connection state (e.g. Connecting → Connected → Error). */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * Push a [message] to [incoming] subscribers.
     *
     * Because [incoming] has `replay = 0`, this only reaches a collector that is *already*
     * subscribed; emit after awaiting [subscriptionCount] > 0 (see its doc). The
     * `extraBufferCapacity` means `tryEmit` still succeeds when a subscriber is present but busy.
     *
     * @return true if the message was accepted into the buffer / delivered to a collector.
     */
    fun emitIncoming(message: ControlMessage): Boolean = _incoming.tryEmit(message)
}
