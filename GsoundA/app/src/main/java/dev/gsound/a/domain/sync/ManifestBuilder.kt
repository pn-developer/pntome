package dev.gsound.a.domain.sync

import dev.gsound.a.domain.model.Track
import dev.gsound.a.domain.scan.ScanPaths
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
 * ### Ordering (spec gsound-audiobooks AB-2.2, AB-2.3)
 * Tracks are grouped by watch folder ([Track.relativeFolder]); groups keep the order in which
 * they first appear in the input. Within a group, tracks are sorted by [NaturalOrder] on
 * [Track.fileName] (falling back to [Track.title] for rows cached before file names were
 * recorded) and numbered with `seq` = 0..n-1. The manifest lists tracks in exactly that order, so
 * even a watch that ignores `seq` stores them in play order.
 *
 * ### Mapping (per track)
 * - `id` ← [Track.id] (stable content/path hash, Req 3.5) — also the key the server resolves for
 *   `/media/{trackId}` and the key the orchestrator uses to look up the SAF `documentUri`.
 * - `folder` ← [Track.relativeFolder] (playlist/folder grouping on the watch).
 * - `title` ← [Track.title]; for an audiobook folder the file name without extension instead
 *   (chapter titles must not depend on ID3 tags, AB-2.4).
 * - `artist`/`album`/`durationMs`/`sizeBytes`/`mime` ← copied verbatim.
 * - `path` ← [MEDIA_PATH_PREFIX]`{id}` (e.g. `/media/abc123`).
 * - `seq` ← position within the folder group; `book` ← whether the owning folder
 *   ([Track.folderId]) is in `audiobookFolderIds`.
 */
object ManifestBuilder {

    /** HTTP path prefix for a track's audio bytes; `path` = `"$MEDIA_PATH_PREFIX$trackId"`. */
    const val MEDIA_PATH_PREFIX: String = "/media/"

    /**
     * Build a [Manifest] from the given cached [tracks].
     *
     * @param tracks snapshot of cached tracks (e.g. from `FolderRepository.getAllTracks()`); an
     *   empty list yields a manifest with an empty track list.
     * @param audiobookFolderIds ids of the folders marked as audiobooks ([dev.gsound.a.domain.model.Folder.isAudiobook]).
     * @return the wire [Manifest], grouped by folder and naturally ordered within each folder.
     */
    fun build(tracks: List<Track>, audiobookFolderIds: Set<String> = emptySet()): Manifest {
        // LinkedHashMap keeps groups in first-seen input order.
        val groups = LinkedHashMap<String, MutableList<Track>>()
        for (track in tracks) {
            groups.getOrPut(track.relativeFolder) { mutableListOf() } += track
        }
        val out = ArrayList<ManifestTrack>(tracks.size)
        for (group in groups.values) {
            val ordered = group.sortedWith(compareBy(NaturalOrder) { orderKey(it) })
            ordered.forEachIndexed { seq, track ->
                out += toManifestTrack(track, seq, track.folderId in audiobookFolderIds)
            }
        }
        return Manifest(tracks = out)
    }

    /** The name a track is ordered by: its file name, or its title for legacy rows. */
    private fun orderKey(track: Track): String =
        track.fileName.ifBlank { track.title }

    /** Map a single domain [Track] to its wire [ManifestTrack]. */
    private fun toManifestTrack(track: Track, seq: Int, book: Boolean): ManifestTrack =
        ManifestTrack(
            id = track.id,
            folder = track.relativeFolder,
            title = if (book && track.fileName.isNotBlank()) {
                ScanPaths.titleFromFileName(track.fileName)
            } else {
                track.title
            },
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            sizeBytes = track.sizeBytes,
            mime = track.mime,
            path = "$MEDIA_PATH_PREFIX${track.id}",
            seq = seq,
            book = book
        )
}
