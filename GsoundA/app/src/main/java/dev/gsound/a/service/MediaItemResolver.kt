package dev.gsound.a.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import dev.gsound.a.data.folders.BookmarkRepository
import dev.gsound.a.data.folders.FolderRepository

/**
 * Turns the (possibly id-only) items a controller sends into a playable queue
 * (gsound-player design §3, PL-2.3, PL-6.6). Pure of Android services, so it is JVM-testable;
 * [LibraryCallback] adapts it to `onSetMediaItems` / `onAddMediaItems`.
 *
 * Android Auto and Assistant send only a `mediaId`, so URIs must be resolved here:
 * - every item already has a URI (in-app `PlayerViewModel`, the 2.1 spike) -> passed through
 *   untouched, with the controller's start index and position;
 * - a single `folder:<id>` item -> the whole folder queue; books start at the bookmark
 *   ([BookmarkRepository.resolve]: 5 s rewind, finished -> chapter 1 at 0), music at 0;
 * - a single track id -> the whole folder queue starting at that track, position 0;
 * - several id-only items -> each resolved on its own (folders expanded in place), unknown
 *   ids dropped, the controller's start index kept when still in range.
 *
 * A null result means nothing playable could be resolved.
 */
class MediaItemResolver(
    private val folders: FolderRepository,
    private val bookmarks: BookmarkRepository,
    private val queueBuilder: QueueBuilder = QueueBuilder(),
) {
    suspend fun setMediaItems(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): MediaSession.MediaItemsWithStartPosition? {
        if (items.isEmpty()) return null
        if (items.all(::isResolved)) {
            return MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs)
        }
        if (items.size == 1) return expandSingle(items.single())
        val resolved = addMediaItems(items)
        if (resolved.isEmpty()) return null
        val index = if (startIndex in resolved.indices) startIndex else C.INDEX_UNSET
        return MediaSession.MediaItemsWithStartPosition(
            resolved, index, if (index == C.INDEX_UNSET) C.TIME_UNSET else startPositionMs
        )
    }

    /** Resolves each item to a playable one; folders expand in place, unknown ids are dropped. */
    suspend fun addMediaItems(items: List<MediaItem>): List<MediaItem> = items.flatMap { item ->
        when {
            isResolved(item) -> listOf(item)
            isVoice(item) -> voice(item.requestMetadata.searchQuery)?.mediaItems ?: emptyList()
            else -> {
                val folderId = BrowseTree.folderIdOf(item.mediaId)
                if (folderId != null) {
                    queueBuilder.build(folders, folderId)
                } else {
                    resolveTrack(item.mediaId)?.let { listOf(it) } ?: emptyList()
                }
            }
        }
    }

    private suspend fun expandSingle(item: MediaItem): MediaSession.MediaItemsWithStartPosition? {
        if (isVoice(item)) return voice(item.requestMetadata.searchQuery)
        val folderId = BrowseTree.folderIdOf(item.mediaId)
        if (folderId != null) return expandFolder(folderId)
        return expandTrack(item.mediaId)
    }

    private suspend fun expandFolder(folderId: String): MediaSession.MediaItemsWithStartPosition? {
        val queue = queueBuilder.build(folders, folderId)
        if (queue.isEmpty()) return null
        val resume = bookmarks.resolve(folderId)
        return if (resume != null && resume.index in queue.indices) {
            MediaSession.MediaItemsWithStartPosition(queue, resume.index, resume.positionMs)
        } else {
            MediaSession.MediaItemsWithStartPosition(queue, 0, 0L)
        }
    }

    private suspend fun expandTrack(trackId: String): MediaSession.MediaItemsWithStartPosition? {
        val track = folders.findTrack(trackId) ?: return null
        val queue = queueBuilder.build(folders, track.folderId)
        val index = queue.indexOfFirst { it.mediaId == track.id }
        if (index < 0) return null
        return MediaSession.MediaItemsWithStartPosition(queue, index, 0L)
    }

    /** Assistant / Auto voice request: no URI, no id, a search query (possibly empty). */
    private fun isVoice(item: MediaItem): Boolean =
        !isResolved(item) && item.mediaId.isEmpty() && item.requestMetadata.searchQuery != null

    /**
     * "Play <query>": best match (book folder, music folder, then chapter); otherwise, and for an
     * empty query, continue the last book. Null when there is nothing to play.
     */
    private suspend fun voice(query: String?): MediaSession.MediaItemsWithStartPosition? {
        val q = query?.trim().orEmpty()
        if (q.isNotEmpty()) {
            val all = folders.getFolders()
            val matches = all.filter { it.displayName.contains(q, ignoreCase = true) }
            fun best(list: List<dev.gsound.a.domain.model.Folder>) =
                list.firstOrNull { it.displayName.equals(q, ignoreCase = true) } ?: list.firstOrNull()
            val folder = best(matches.filter { it.isAudiobook }) ?: best(matches.filterNot { it.isAudiobook })
            if (folder != null) expandFolder(folder.id)?.let { return it }
            for (f in all) {
                val track = folders.getTracks(f.id).firstOrNull { it.title.contains(q, ignoreCase = true) }
                if (track != null) expandTrack(track.id)?.let { return it }
            }
        }
        return continueLastBook()
    }

    private suspend fun continueLastBook(): MediaSession.MediaItemsWithStartPosition? {
        val books = bookmarks.recent().mapNotNull { b ->
            folders.findFolder(b.folderId)?.takeIf { it.isAudiobook }?.let { b to it }
        }
        val pick = books.firstOrNull { !it.first.finished } ?: books.firstOrNull()
        val folderId = pick?.second?.id
            ?: folders.getFolders().firstOrNull { it.isAudiobook }?.id
            ?: return null
        return expandFolder(folderId)
    }

    private suspend fun resolveTrack(trackId: String): MediaItem? {
        val track = folders.findTrack(trackId) ?: return null
        val folder = folders.findFolder(track.folderId) ?: return null
        return queueBuilder.build(folder, listOf(track)).singleOrNull()
    }

    private fun isResolved(item: MediaItem): Boolean = item.localConfiguration != null
}
