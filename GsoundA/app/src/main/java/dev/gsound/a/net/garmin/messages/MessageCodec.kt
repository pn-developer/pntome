package dev.gsound.a.net.garmin.messages

import kotlinx.serialization.encodeToString

/**
 * Pure, Android-free (de)serialization + validation for control-channel messages
 * (design §5.3, Req 10.3).
 *
 * This object is the single place that turns wire JSON into trusted [ControlMessage]s and
 * back. It is intentionally free of any Connect IQ SDK / Android types so it can be exercised
 * directly in JVM unit tests and reused by [dev.gsound.a.net.garmin.ConnectIqManager] in the
 * incoming listener.
 *
 * ## Validation performed by [decodeAndValidate]
 * 1. **Schema / JSON** — the payload must parse as JSON and decode to a known [ControlMessage]
 *    subtype. Malformed JSON or an unknown `type` discriminator → [DecodeResult.Invalid].
 * 2. **Protocol version** — `v` must equal [PROTOCOL_VERSION]; a mismatch → [DecodeResult.Invalid]
 *    so we never act on a message framed by an incompatible protocol.
 * 3. **Pairing binding (Req 10.3)** — when an [expectedPairingId] is supplied (i.e. GsoundA is
 *    paired), a message carrying a *different* `pairingId` is treated as coming from an
 *    unpaired peer and dropped as [DecodeResult.UnpairedPeer].
 *
 * ## `null` pairingId rule (liveness)
 * `PING`/`PONG` legitimately carry a `null` `pairingId` because liveness may be probed before
 * or independently of a pairing (see [ControlMessage.pairingId]). The rule applied here:
 * **a message whose `pairingId` is `null` is always allowed through** (returned [DecodeResult.Valid]),
 * regardless of whether a pairing exists. Rationale: only `Ping`/`Pong` are declared with a
 * nullable `pairingId`; every pairing-bearing message type (`PAIR_*`, `UNPAIR`, `SYNC_*`,
 * `STATS`) declares `pairingId` as non-null, so a `null` here can only be a liveness message,
 * which must keep flowing so the UI can detect a live-but-not-yet-paired peer. Any *non-null*
 * `pairingId` is still strictly matched against [expectedPairingId].
 */
object MessageCodec {

    /**
     * Outcome of decoding + validating a single incoming payload.
     *
     * Modeled as a sealed interface so callers (the incoming listener) get exhaustive handling
     * and can distinguish "drop silently because it's not our peer" ([UnpairedPeer]) from
     * "drop and optionally log a protocol problem" ([Invalid]).
     */
    sealed interface DecodeResult {

        /** The payload parsed, versioned, and pairing-matched: safe to emit downstream. */
        data class Valid(val message: ControlMessage) : DecodeResult

        /**
         * The payload failed schema/version validation (unparseable JSON, unknown `type`, or
         * a `v` that is not [PROTOCOL_VERSION]). Carries a short, secret-free [reason] for logs.
         */
        data class Invalid(val reason: String) : DecodeResult

        /**
         * The payload was a well-formed control message but its non-null `pairingId` did not
         * match the active pairing — i.e. it came from an unpaired peer and must be ignored
         * (Req 10.3). Carries the offending [pairingId] for diagnostics.
         */
        data class UnpairedPeer(val pairingId: String) : DecodeResult
    }

    /** Serialize [message] to its wire JSON using the shared [GsoundJson] codec. */
    fun encode(message: ControlMessage): String = GsoundJson.encodeToString(message)

    /**
     * Decode and validate a single incoming [json] payload against the active pairing.
     *
     * @param json the raw JSON string received over the control channel.
     * @param expectedPairingId the `pairingId` of the active [dev.gsound.a.domain.model.PairingRecord],
     *   or `null` when GsoundA is not paired. When non-null, messages carrying a different
     *   non-null `pairingId` are rejected as [DecodeResult.UnpairedPeer] (Req 10.3). When null,
     *   pairing matching is skipped (nothing to match against yet).
     * @return a [DecodeResult] describing whether the message is [DecodeResult.Valid],
     *   [DecodeResult.Invalid] (schema/version), or from an [DecodeResult.UnpairedPeer].
     */
    fun decodeAndValidate(json: String, expectedPairingId: String?): DecodeResult {
        val message = try {
            GsoundJson.decodeFromString<ControlMessage>(json)
        } catch (t: Throwable) {
            // Malformed JSON, unknown `type` discriminator, missing required fields, etc.
            return DecodeResult.Invalid(
                reason = "undecodable payload: ${t.message ?: t::class.simpleName}",
            )
        }

        if (message.v != PROTOCOL_VERSION) {
            return DecodeResult.Invalid(
                reason = "unsupported protocol version ${message.v} (expected $PROTOCOL_VERSION)",
            )
        }

        val messagePairingId = message.pairingId
        // Liveness rule: null pairingId (only Ping/Pong) is always allowed through.
        if (messagePairingId != null &&
            expectedPairingId != null &&
            messagePairingId != expectedPairingId
        ) {
            return DecodeResult.UnpairedPeer(pairingId = messagePairingId)
        }

        return DecodeResult.Valid(message)
    }
}
