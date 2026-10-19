package dev.gsound.a.net.garmin

import dev.gsound.a.net.garmin.messages.ControlMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin, hardware-independent abstraction over the Garmin control channel (design §5.3, §10).
 *
 * The control channel carries small JSON control messages ([ControlMessage]) between GsoundA
 * (phone) and GsoundG (watch) over the Connect IQ Mobile SDK Bluetooth message channel. All
 * bulk audio data goes over the separate HTTP data channel — never here.
 *
 * This interface is deliberately **free of any Connect IQ SDK types** so that:
 * - the real `ConnectIqManager` implements it against the SDK (task 6.2), and
 * - a fake implementation drives pairing/sync logic in unit tests (task 6.3).
 *
 * Per design §10, keep this abstraction thin and swappable; the implementation is responsible
 * for the behavior the interface only makes *expressible*:
 * - (de)serialization of [ControlMessage] JSON via `GsoundJson` (task 6.3),
 * - validation of `v`/`type`/`pairingId` and dropping messages from unpaired peers so they
 *   never reach [incoming] (Req 10.3),
 * - retrying outgoing messages with exponential backoff while the channel is temporarily
 *   unavailable, reflected via [connectionState] (Req 10.2).
 */
interface ControlChannel {

    /**
     * Current connection state, hot and conflated so late subscribers immediately observe the
     * latest value. Implementations map SDK/device/app status into [ConnectionState] (Req 4.1,
     * Req 4.2); the mapping itself is implemented in task 6.4.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Stream of **validated** incoming control messages (Req 10.3).
     *
     * Implementations deserialize each SDK payload, validate the envelope (`v`, `type`,
     * `pairingId`), drop anything from an unpaired peer, and emit only the surviving
     * [ControlMessage]s here. Expected to be a hot, broadcast-style flow (e.g. `SharedFlow`)
     * so multiple collectors (pairing, sync) can observe the same messages; messages emitted
     * before a collector subscribes are not replayed.
     */
    val incoming: Flow<ControlMessage>

    /**
     * Establish the channel: initialize the SDK, resolve the Garmin device, and locate the
     * GsoundG app (Req 4.1). Progress and outcome are reported through [connectionState]
     * ([ConnectionState.Connecting] → [ConnectionState.Connected], or
     * [ConnectionState.Error]). Suspends until the attempt resolves; safe to call again to
     * reconnect after an error or [close].
     */
    suspend fun connect()

    /**
     * Serialize and send a single control [message] to the watch.
     *
     * Returns a [Result] rather than throwing so callers (and the retry/backoff logic in the
     * implementation, Req 10.2) can react to transient failures without exception handling.
     * A successful result means the message was handed to the transport, not that the peer
     * has processed it — delivery/acknowledgement is observed via [incoming] replies
     * (e.g. `PONG`, `PAIR_CONFIRM`, `SYNC_STATUS`).
     *
     * @param message the control message to transmit.
     * @return [Result.success] once accepted by the transport, or [Result.failure] carrying
     *   the cause when sending ultimately failed.
     */
    suspend fun send(message: ControlMessage): Result<Unit>

    /**
     * Tear down the channel and release SDK resources. After this call [connectionState]
     * transitions to [ConnectionState.Disconnected]. Idempotent.
     */
    suspend fun close()
}
