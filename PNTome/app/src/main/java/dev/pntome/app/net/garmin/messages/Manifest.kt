package dev.pntome.app.net.garmin.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The sync manifest body served by the `GET /manifest` HTTP endpoint (overview §5.2).
 *
 * The track list has the same shape as [SyncManifest.tracks] sent over the control channel —
 * both reuse [ManifestTrack]. This wrapper exists so the endpoint can return a JSON object
 * (`{ "tracks": [...] }`) rather than a bare array, keeping room for future top-level fields.
 *
 * @property tracks the full list of syncable tracks.
 */
@Serializable
data class Manifest(
    @SerialName("tracks") val tracks: List<ManifestTrack> = emptyList()
)
