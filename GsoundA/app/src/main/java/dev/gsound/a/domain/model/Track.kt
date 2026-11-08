package dev.gsound.a.domain.model

/**
 * A single syncable audio track discovered during a folder scan.
 *
 * Pure domain model — no framework/persistence annotations (Room entities live in the data
 * layer, see task 3).
 *
 * @property id stable content/path hash (see overview §5.1); identical across scans while the
 *   file's path/size/lastModified are unchanged (Req 3.5).
 * @property folderId id of the owning [Folder].
 * @property documentUri SAF document `content://` URI used to open the audio stream.
 * @property relativeFolder playlist/folder name used to group the track on the watch.
 * @property title track title (falls back to filename when metadata is missing, Req 3.4).
 * @property artist track artist, or null when unavailable.
 * @property album track album, or null when unavailable.
 * @property durationMs track duration in milliseconds.
 * @property sizeBytes file size in bytes.
 * @property mime MIME type (e.g. `audio/mpeg`).
 * @property fileName the file's display name (e.g. `01 - Intro.mp3`), used for natural
 *   chapter/track ordering and audiobook chapter titles (spec gsound-audiobooks AB-2). Empty
 *   for rows cached before it was recorded, until the next scan.
 */
data class Track(
    val id: String,
    val folderId: String,
    val documentUri: String,
    val relativeFolder: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String,
    val fileName: String = ""
)
