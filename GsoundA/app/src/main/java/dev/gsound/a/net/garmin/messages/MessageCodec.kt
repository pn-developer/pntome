package dev.gsound.a.net.garmin.messages

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

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

    /**
     * Encode [message] to a structured [Map] suitable for [com.garmin.android.connectiq.ConnectIQ.sendMessage].
     *
     * ## Why a Map, not a JSON String (the pairing bug fix)
     * The Connect IQ Mobile SDK MARSHALS structured values across the link: a phone-side
     * `Map<String, Any?>` arrives on the watch as a Monkey C `Dictionary`, a `List` as an
     * `Array`, primitives as themselves. GsoundG's `ControlChannel`/`Protocol.validate`
     * REQUIRES the inbound payload to be a `Dictionary` and silently drops anything else
     * (`INVALID_NOT_DICT`). Previously GsoundA pre-serialized each message to a JSON *String*
     * and sent that string, so the watch received a `String`, failed `instanceof Dictionary`,
     * and dropped every message (e.g. a `PAIR_REQUEST` never surfaced a code on the watch).
     * Sending a Map — which the SDK delivers as a Dictionary — matches GsoundG's contract.
     *
     * The message is first serialized to a [JsonObject] with the SAME [GsoundJson] codec (so the
     * `type` discriminator and every `@SerialName` field name are identical to before), then
     * converted to a plain [Map] of JSON-primitive Kotlin values ([String]/[Int]/[Long]/
     * [Double]/[Boolean]/nested [Map]/[List]). The `v` field serializes as a number, matching
     * `Protocol.validate`'s `v instanceof Number` check on the watch.
     */
    fun encodeToMap(message: ControlMessage): Map<String, Any?> {
        val element = GsoundJson.encodeToJsonElement(ControlMessage.serializer(), message)
        @Suppress("UNCHECKED_CAST")
        return jsonElementToKotlin(element as JsonObject) as Map<String, Any?>
    }

    /**
     * Decode + validate an incoming payload that the SDK delivered as a structured value
     * (a [Map], as produced by a watch-side `Dictionary`), OR as a JSON [String] (backward/
     * forward compatibility with a peer that still sends strings). Mirrors [decodeAndValidate]'s
     * version/pairing rules.
     *
     * @param data the raw payload object from the SDK message batch (expected: a `Map`; also
     *   tolerates a JSON `String`).
     * @param expectedPairingId the active pairing's id, or null when unpaired.
     */
    fun decodeAndValidateFromAny(data: Any?, expectedPairingId: String?): DecodeResult {
        val element: JsonElement = when (data) {
            is String -> return decodeAndValidate(data, expectedPairingId)
            is Map<*, *> -> try {
                kotlinToJsonElement(data)
            } catch (t: Throwable) {
                return DecodeResult.Invalid("unconvertible map payload: ${t.message ?: t::class.simpleName}")
            }
            else -> return DecodeResult.Invalid(
                "unsupported payload type: ${data?.let { it::class.simpleName } ?: "null"}",
            )
        }

        val message = try {
            GsoundJson.decodeFromJsonElement(ControlMessage.serializer(), element)
        } catch (t: Throwable) {
            return DecodeResult.Invalid("undecodable payload: ${t.message ?: t::class.simpleName}")
        }

        if (message.v != PROTOCOL_VERSION) {
            return DecodeResult.Invalid(
                "unsupported protocol version ${message.v} (expected $PROTOCOL_VERSION)",
            )
        }

        val messagePairingId = message.pairingId
        if (messagePairingId != null &&
            expectedPairingId != null &&
            messagePairingId != expectedPairingId
        ) {
            return DecodeResult.UnpairedPeer(pairingId = messagePairingId)
        }
        return DecodeResult.Valid(message)
    }

    // --- JsonElement <-> plain Kotlin value conversion -----------------------------------------
    // The Connect IQ SDK carries plain Kotlin values (Map/List/String/Number/Boolean), so we
    // bridge between kotlinx.serialization's JsonElement tree and those plain values. This keeps
    // the @Serializable classes + GsoundJson as the single source of truth for the wire shape.

    /** Convert a [JsonElement] tree to plain Kotlin values (Map/List/String/Long/Double/Boolean/null). */
    private fun jsonElementToKotlin(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> element.mapValues { jsonElementToKotlin(it.value) }
        is JsonArray -> element.map { jsonElementToKotlin(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
    }

    /** Convert plain Kotlin values (as delivered by the SDK) back into a [JsonElement] tree. */
    private fun kotlinToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Byte -> JsonPrimitive(value.toInt())
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            for ((k, v) in value) {
                put(k.toString(), kotlinToJsonElement(v))
            }
        }
        is Iterable<*> -> JsonArray(value.map { kotlinToJsonElement(it) })
        is Array<*> -> JsonArray(value.map { kotlinToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
