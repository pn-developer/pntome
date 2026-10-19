package dev.gsound.a.domain.sync

import dev.gsound.a.domain.model.Track
import dev.gsound.a.net.garmin.messages.Manifest
import dev.gsound.a.net.garmin.messages.ManifestTrack

/**
 * Pure, framework-free mapping from the cached domain [Track] list to the wire [Manifest] the
 * watch consumes — both over the control channel (`SYNC_MANIFEST { tracks }`) and the
 * `GET /manifest` HTTP endpoint, which share the same [ManifestTrack] shape (overview §5.1/§5.2,
 * design §5.6).
 *
 * Extracted from [SyncOrchestrator] so the Track→ManifestTrack mapping is unit-testable on the
 * JVM with no Android, no server, and no control channel. The orchestrator calls [build] with a
 * one-shot snapshot of the Room track cache (`FolderRepository.getAllTracks()`).
 *
 * ### Mapping (per track)
 * - `id` ← [Track.id] (stable content/path hash, Req 3.5) — also the key the server resolves for
 *   `/media/{trackId}` and the key the orchestrator uses to look up the SAF `documentUri`.
 * - `folder` ← [Track.relativeFolder] (playlist/folder grouping on the watch).
 * - `title`/`artist`/`album`/`durationMs`/`sizeBytes`/`mime` ← copied verbatim.
 * - `path` ← [MEDIA_PATH_PREFIX]`{id}` (e.g. `/media/abc123`) — the HTTP path the watch fetches
 *   bytes from, relative to the `serverUrl` carried alongside the manifest.
 *
 * The order of [Manifest.tracks] follows the input [tracks] order so callers can present a
 * deterministic manifest.
 */
object ManifestBuilder {

    /** HTTP path prefix for a track's audio bytes; `path` = `"$MEDIA_PATH_PREFIX$trackId"`. */
    const val MEDIA_PATH_PREFIX: String = "/media/"

    /**
     * Build a [Manifest] from the given cached [tracks].
     *
     * @param tracks snapshot of cached tracks (e.g. from `FolderRepository.getAllTracks()`); an
     *   empty list yields a manifest with an empty track list.
     * @return the wire [Manifest] with one [ManifestTrack] per input track, in input order.
     */
    fun build(tracks: List<Track>): Manifest =
        Manifest(tracks = tracks.map(::toManifestTrack))

    /** Map a single domain [Track] to its wire [ManifestTrack]. */
    private fun toManifestTrack(track: Track): ManifestTrack =
        ManifestTrack(
            id = track.id,
            folder = track.relativeFolder,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            sizeBytes = track.sizeBytes,
            mime = track.mime,
            path = "$MEDIA_PATH_PREFIX${track.id}"
        )
}
