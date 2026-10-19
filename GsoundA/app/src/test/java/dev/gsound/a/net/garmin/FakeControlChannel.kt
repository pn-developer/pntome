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
     * @return true if the message was delivered to the buffer (false only if no buffer space
     *   and no active collector — not expected in tests that collect first).
     */
    fun emitIncoming(message: ControlMessage): Boolean = _incoming.tryEmit(message)
}
