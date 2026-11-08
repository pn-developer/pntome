package dev.pntome.app.service


import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.model.Track
import dev.pntome.app.domain.sync.NaturalOrder

/**
 * Builds the playback queue for a folder (gsound-player design §2, PL-1.6).
 *
 * - Audiobook folders: chapters in natural file-name order ([NaturalOrder] on
 *   [Track.fileName], falling back to [Track.title]). Tracks are grouped by
 *   [Track.relativeFolder] in order of first appearance and sorted within each group, exactly
 *   like `ManifestBuilder`, so the chapter index equals the watch's `seq` index.
 * - Music folders: the stored order, i.e. the order the repository returns the tracks in.
 *
 * Each [MediaItem] has `mediaId` = the track id (stable, shared with the watch), the SAF
 * `content://` document URI as `uri`, and [MediaMetadata] with title, artist, album = folder
 * display name, and the default artwork.
 *
 * @param artworkUri default artwork shown for every item (a bundled icon); null for none.
 */
class QueueBuilder(private val artworkUri: Uri? = null) {
    /** Loads the folder and its tracks; returns an empty list if the folder is unknown. */
    suspend fun build(repository: FolderRepository, folderId: String): List<MediaItem> {
        val folder = repository.findFolder(folderId) ?: return emptyList()
        return build(folder, repository.getTracks(folderId))
    }

    /** Pure mapping used by [build] and by tests. */
    fun build(folder: Folder, tracks: List<Track>): List<MediaItem> =
        order(folder, tracks).map { toMediaItem(folder, it) }

    internal fun order(folder: Folder, tracks: List<Track>): List<Track> {
        if (!folder.isAudiobook) return tracks
        return tracks
            .groupBy { it.relativeFolder }
            .values
            .flatMap { group -> group.sortedWith(compareBy(NaturalOrder) { sortName(it) }) }
    }

    private fun sortName(t: Track): String = t.fileName.ifBlank { t.title }

    private fun toMediaItem(folder: Folder, t: Track): MediaItem {
        val title = if (folder.isAudiobook && t.fileName.isNotBlank()) {
            t.fileName.substringBeforeLast('.', t.fileName).ifBlank { t.title }
        } else {
            t.title
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(t.artist)
            .setAlbumTitle(folder.displayName)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(Bundle().apply {
                putBoolean(EXTRA_AUDIOBOOK, folder.isAudiobook)
                putString(EXTRA_FOLDER_ID, folder.id)
            })
        if (artworkUri != null) metadata.setArtworkUri(artworkUri)
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setUri(Uri.parse(t.documentUri))
            .setMediaMetadata(metadata.build())
            .build()
    }

    companion object {
        /** [MediaMetadata] extras key: true when the item belongs to an audiobook folder. */
        const val EXTRA_AUDIOBOOK = "dev.pntome.app.extra.AUDIOBOOK"

        /** [MediaMetadata] extras key: `Folder.id` of the item's folder (the bookmark key). */
        const val EXTRA_FOLDER_ID = "dev.pntome.app.extra.FOLDER_ID"
    }
}
