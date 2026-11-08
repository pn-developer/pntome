package dev.gsound.a.net.server

import dev.gsound.a.net.garmin.messages.Manifest

/**
 * Immutable snapshot of the data a single sync "session" serves over HTTP: the [manifest] the
 * watch fetches from `/manifest`, and the per-track info needed to open bytes for
 * `/media/{trackId}` (design §5.5).
 *
 * The sync orchestration (task 9) builds a [SyncSession] when a sync starts and installs it via
 * [SyncSessionHolder.set]; the HTTP routes (task 8.2) read it indirectly through a
 * [MediaSource]. Keeping it a plain immutable value (no Android types) means the holder/session
 * can be constructed and asserted in JVM tests, while the Android-specific opening of SAF
 * documents lives in [ContentResolverMediaSource].
 *
 * @property manifest the manifest body served verbatim at `/manifest`.
 * @property tracks lookup from `trackId` → [TrackRef] for `/media/{trackId}`. Only ids present
 *   here are servable; anything else is a `404` (Req 5.5). Built from the same track list as
 *   [manifest] so the two never disagree.
 * @property secret the `sharedSecret` the watch must present in the `X-Gsound-Secret` header to
 *   be served this session's `/manifest` and `/media` (Req 5.3). It is the paired
 *   [dev.gsound.a.domain.model.PairingRecord.sharedSecret], carried on the session because the
 *   same value is sent to the watch in `SYNC_MANIFEST { secret }` (design §5.6). `null` only in
 *   legacy/test constructions that predate auth; the [SecretProvider] then falls back to the
 *   persisted pairing. Never logged (Req 8.4).
 */
data class SyncSession(
    val manifest: Manifest,
    val tracks: Map<String, TrackRef>,
    val secret: String? = null
) {
    companion object {
        /**
         * Build a session from a [manifest] and a function mapping each track id to the SAF
         * document URI string that opens its bytes.
         *
         * @param manifest the manifest to serve.
         * @param secret the per-session shared secret (Req 5.3); carried so [SecretProvider] can
         *   authenticate requests against the active session. Defaults to `null` for callers
         *   (e.g. older tests) that do not exercise auth.
         * @param documentUriFor returns the SAF `content://` document URI (as a string) for a
         *   given [dev.gsound.a.net.garmin.messages.ManifestTrack.id]; the orchestrator supplies
         *   this from the Room track cache (where `Track.documentUri` is stored, design §4).
         */
        fun from(
            manifest: Manifest,
            secret: String? = null,
            documentUriFor: (trackId: String) -> String
        ): SyncSession {
            val refs = manifest.tracks.associate { track ->
                track.id to TrackRef(
                    documentUri = documentUriFor(track.id),
                    contentType = track.mime,
                    sizeBytes = track.sizeBytes
                )
            }
            return SyncSession(manifest = manifest, tracks = refs, secret = secret)
        }
    }

    /**
     * Everything needed to open and stream one track's bytes.
     *
     * @property documentUri the SAF document `content://` URI string; opened lazily by
     *   [ContentResolverMediaSource] so the file is never read until requested.
     * @property contentType the MIME type for the HTTP `Content-Type` header.
     * @property sizeBytes exact file size for `Content-Length`/`Range` handling.
     */
    data class TrackRef(
        val documentUri: String,
        val contentType: String,
        val sizeBytes: Long
    )
}
