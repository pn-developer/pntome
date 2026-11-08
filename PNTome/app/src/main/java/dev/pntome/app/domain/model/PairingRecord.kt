package dev.pntome.app.domain.model

/**
 * Persistent record of a completed pairing with a watch peer (Req 4.5).
 *
 * Pure domain model — no framework/persistence annotations (Proto DataStore schema lives in
 * the data layer, see task 3).
 *
 * @property pairingId UUID identifying this pairing; echoed in every control message (Req 10.3).
 * @property peerName watch model/name shown to the user.
 * @property sharedSecret secret that authenticates HTTP data-channel requests (Req 5.3).
 * @property createdAt epoch millis when pairing completed.
 */
data class PairingRecord(
    val pairingId: String,
    val peerName: String,
    val sharedSecret: String,
    val createdAt: Long
)
