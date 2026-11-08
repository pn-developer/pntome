package dev.pntome.app.net.garmin.messages

import kotlinx.serialization.json.Json

/**
 * The single, reusable [Json] configuration for PNTome wire types (control-channel messages
 * and manifests).
 *
 * Configuration (per the shared protocol contract):
 * - `classDiscriminator = "type"` — maps the kotlinx.serialization polymorphic discriminator
 *   to the `"type"` JSON key so [ControlMessage] subtypes serialize with the exact contract
 *   strings (`PAIR_REQUEST`, `SYNC_MANIFEST`, …) pinned via `@SerialName`.
 * - `ignoreUnknownKeys = true` — tolerate forward-compatible fields added by peers.
 * - `encodeDefaults = true` — always emit defaulted fields, notably `"v": 1`.
 *
 * Round-trip (de)serialization helpers and incoming-message validation live in task 6.3; this
 * object only provides the shared, correctly-configured codec.
 */
val PNTomeJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}
