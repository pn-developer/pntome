package dev.pntome.app.net.garmin.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single track entry in a sync manifest. This is the **wire** representation shared by the
 * control-channel [SyncManifest] message and the `/manifest` HTTP endpoint — both use the
 * exact same shape (overview §5.1 / §5.2).
 *
 * JSON shape (authoritative, from the shared protocol contract):
 * ```json
 * {
 *   "id": "sha1-or-stable-hash",
 *   "folder": "Workout",
 *   "title": "Song Title",
 *   "artist": "Artist",
 *   "album": "Album",
 *   "durationMs": 215000,
 *   "sizeBytes": 5123456,
 *   "mime": "audio/mpeg",
 *   "path": "/media/<trackId>",
 *   "seq": 0,
 *   "book": false
 * }
 * ```
 *
 * This is a protocol/wire type — keep it separate from the domain model
 * `dev.pntome.app.domain.model.Track` (task 2.1).
 *
 * @property id stable content/path hash of the track (overview §5.1).
 * @property folder playlist/folder name used to group the track on the watch.
 * @property title track title.
 * @property artist track artist, or null when unavailable.
 * @property album track album, or null when unavailable.
 * @property durationMs track duration in milliseconds.
 * @property sizeBytes file size in bytes.
 * @property mime MIME type (e.g. `audio/mpeg`).
 * @property path HTTP path on the server to fetch the audio bytes (e.g. `/media/<trackId>`).
 * @property seq 0-based play order of the track within its [folder] (natural file-name order,
 *   spec gsound-audiobooks AB-2.3, AB-3.1). Optional/additive: a watch that doesn't know it
 *   ignores it; null from an older phone means "index order".
 * @property book true when the track's folder is marked as an audiobook (AB-3.1). Optional.
 * @property fid id of the owning phone folder ([dev.pntome.app.domain.model.Folder.id]); the
 *   stable join key for bookmarks (spec gsound-player PL-4.3, design R2). [folder] is not
 *   usable for that: it is a sub-folder path for nested files. Optional/additive.
 */
@Serializable
data class ManifestTrack(
    @SerialName("id") val id: String,
    @SerialName("folder") val folder: String,
    @SerialName("title") val title: String,
    @SerialName("artist") val artist: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("durationMs") val durationMs: Long,
    @SerialName("sizeBytes") val sizeBytes: Long,
    @SerialName("mime") val mime: String,
    @SerialName("path") val path: String,
    @SerialName("seq") val seq: Int? = null,
    @SerialName("book") val book: Boolean? = null,
    @SerialName("fid") val fid: String? = null
)
