package dev.gsound.a.net.garmin.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base type for every control-channel message exchanged between GsoundA (phone) and GsoundG
 * (watch) over the Garmin Connect IQ Mobile SDK message channel (overview §5.1).
 *
 * All messages share the common envelope:
 * ```json
 * { "v": 1, "type": <string>, "pairingId": <uuid>, ... }
 * ```
 *
 * The `type` field is the polymorphic class discriminator (configured on the [GsoundJson]
 * instance). Concrete subtypes pin their discriminator value with [SerialName] so the wire
 * strings match the shared contract exactly:
 * `PAIR_REQUEST`, `PAIR_CONFIRM`, `UNPAIR`, `SYNC_MANIFEST`, `SYNC_STATUS`, `STATS`,
 * `PING`, `PONG`.
 *
 * These are the wire types; keep them separate from the domain models (task 2.1).
 *
 * @property v protocol version; always `1` for v1 (emitted on every message).
 * @property pairingId the UUID binding this message to a specific pairing. Nullable because
 *   `PING`/`PONG` liveness checks may be exchanged before/independently of a pairing.
 */
@Serializable
sealed class ControlMessage {
    abstract val v: Int
    abstract val pairingId: String?
}

/**
 * `PAIR_REQUEST` (A↔G): begins a pairing handshake. The initiator generates a 6-digit [code],
 * a [pairingId] (UUID), and a [secret] (shared secret used later to authenticate HTTP
 * requests), and includes its [peerName] so the other side can display it.
 */
@Serializable
@SerialName("PAIR_REQUEST")
data class PairRequest(
    @SerialName("code") val code: String,
    @SerialName("pairingId") override val pairingId: String,
    @SerialName("secret") val secret: String,
    @SerialName("peerName") val peerName: String,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `PAIR_CONFIRM` (both): sent when the user confirms the pairing code on this side.
 */
@Serializable
@SerialName("PAIR_CONFIRM")
data class PairConfirm(
    @SerialName("pairingId") override val pairingId: String,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `UNPAIR` (either): removes the pairing identified by [pairingId].
 */
@Serializable
@SerialName("UNPAIR")
data class Unpair(
    @SerialName("pairingId") override val pairingId: String,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `SYNC_MANIFEST` (A→G): the full list of [tracks] to sync plus the [serverUrl] where the
 * watch should fetch them (e.g. `http://192.168.1.42:8080`).
 */
@Serializable
@SerialName("SYNC_MANIFEST")
data class SyncManifest(
    @SerialName("pairingId") override val pairingId: String,
    @SerialName("serverUrl") val serverUrl: String,
    @SerialName("tracks") val tracks: List<ManifestTrack> = emptyList(),
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `SYNC_STATUS` (G→A): progress reporting during a sync.
 *
 * @property state watch-reported sync state label.
 * @property done number of tracks completed.
 * @property total total number of tracks to sync.
 * @property bytesDone bytes transferred so far.
 * @property error optional error message when the sync failed.
 */
@Serializable
@SerialName("SYNC_STATUS")
data class SyncStatus(
    @SerialName("pairingId") override val pairingId: String,
    @SerialName("state") val state: String,
    @SerialName("done") val done: Int,
    @SerialName("total") val total: Int,
    @SerialName("bytesDone") val bytesDone: Long,
    @SerialName("error") val error: String? = null,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `STATS` (G→A): watch-side stats shown in GsoundA.
 *
 * @property fileCount number of files currently on the watch.
 * @property folderCount number of folders currently on the watch.
 * @property lastSyncAt epoch-millis timestamp of the last successful sync, or null if never.
 */
@Serializable
@SerialName("STATS")
data class Stats(
    @SerialName("pairingId") override val pairingId: String,
    @SerialName("fileCount") val fileCount: Int,
    @SerialName("folderCount") val folderCount: Int,
    @SerialName("lastSyncAt") val lastSyncAt: Long? = null,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `PING` (both): liveness/connectivity check. No extra payload. [pairingId] is optional
 * because liveness may be checked independently of a pairing.
 */
@Serializable
@SerialName("PING")
data class Ping(
    @SerialName("pairingId") override val pairingId: String? = null,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/**
 * `PONG` (both): response to a [Ping]. No extra payload.
 */
@Serializable
@SerialName("PONG")
data class Pong(
    @SerialName("pairingId") override val pairingId: String? = null,
    @SerialName("v") override val v: Int = PROTOCOL_VERSION
) : ControlMessage()

/** The protocol version emitted on every control message (`"v": 1`). */
const val PROTOCOL_VERSION: Int = 1
