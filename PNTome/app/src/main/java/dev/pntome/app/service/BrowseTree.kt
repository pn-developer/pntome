package dev.pntome.app.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import dev.pntome.app.data.folders.BookmarkDao
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.domain.model.Folder

/**
 * The Android Auto browse tree (gsound-player design §3, PL-2.2, 2.5, 2.7). Pure of Android
 * services so it is JVM-testable; [LibraryCallback] adapts it to `MediaLibrarySession.Callback`.
 *
 * Node ids:
 * - [ROOT_ID] → [BOOKS_ID], [MUSIC_ID], [RECENT_ID] (browsable);
 * - [BOOKS_ID] / [MUSIC_ID] → folder nodes `folder:<id>` (browsable);
 * - [RECENT_ID] → the last [RECENT_LIMIT] played books, newest bookmark first (folder nodes);
 * - `folder:<id>` → chapters / tracks (playable, `mediaId` = track id, from [QueueBuilder]).
 *
 * Folder nodes carry [QueueBuilder.EXTRA_FOLDER_ID] / [QueueBuilder.EXTRA_AUDIOBOOK] extras so
 * `onSetMediaItems` (task 6.3) can expand them; [folderIdOf] parses a node id.
 *
 * Errors are [LibraryResult.RESULT_ERROR_BAD_VALUE] (unknown / wrong-kind id).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BrowseTree(
    private val folders: FolderRepository,
    private val bookmarks: BookmarkDao,
    private val queueBuilder: QueueBuilder = QueueBuilder(),
    private val artworkUri: Uri? = null,
) {
    /** The root node returned from `onGetLibraryRoot`. */
    fun root(): MediaItem = node(ROOT_ID, "PNTome")

    suspend fun getItem(mediaId: String): LibraryResult<MediaItem> {
        val item: MediaItem? = when {
            mediaId == ROOT_ID -> root()
            mediaId == BOOKS_ID -> node(BOOKS_ID, "Audiobooks")
            mediaId == MUSIC_ID -> node(MUSIC_ID, "Music")
            mediaId == RECENT_ID -> node(RECENT_ID, "Recently played")
            folderIdOf(mediaId) != null -> folders.findFolder(folderIdOf(mediaId)!!)?.let(::folderNode)
            else -> folders.findTrack(mediaId)?.let { track ->
                folders.findFolder(track.folderId)?.let { queueBuilder.build(it, listOf(track)).single() }
            }
        }
        return if (item != null) LibraryResult.ofItem(item, null) else bad()
    }

    /** Children of [parentId], [page] (0-based) of [pageSize] items. */
    suspend fun getChildren(parentId: String, page: Int, pageSize: Int): LibraryResult<ImmutableList<MediaItem>> {
        if (page < 0 || pageSize <= 0) return bad()
        val all: List<MediaItem> = when {
            parentId == ROOT_ID -> listOf(
                node(BOOKS_ID, "Audiobooks"), node(MUSIC_ID, "Music"), node(RECENT_ID, "Recently played")
            )
            parentId == BOOKS_ID -> folders.getFolders().filter { it.isAudiobook }.map(::folderNode)
            parentId == MUSIC_ID -> folders.getFolders().filterNot { it.isAudiobook }.map(::folderNode)
            parentId == RECENT_ID -> recentBooks().map(::folderNode)
            else -> {
                val folderId = folderIdOf(parentId) ?: return bad()
                val folder = folders.findFolder(folderId) ?: return bad()
                queueBuilder.build(folder, folders.getTracks(folderId))
            }
        }
        val from = page.toLong() * pageSize
        val items = if (from >= all.size) emptyList() else all.subList(from.toInt(), minOf(all.size.toLong(), from + pageSize).toInt())
        return LibraryResult.ofItemList(items, null)
    }

    /** Number of search hits for [query] (blank query: 0). */
    suspend fun search(query: String): Int = searchAll(query).size

    /** Search hits, [page] (0-based) of [pageSize]: folders first, then chapters / tracks. */
    suspend fun searchResults(query: String, page: Int, pageSize: Int): LibraryResult<ImmutableList<MediaItem>> {
        if (page < 0 || pageSize <= 0) return bad()
        val all = searchAll(query)
        val from = page.toLong() * pageSize
        val items = if (from >= all.size) emptyList() else all.subList(from.toInt(), minOf(all.size.toLong(), from + pageSize).toInt())
        return LibraryResult.ofItemList(items, null)
    }

    /** Case-insensitive contains over folder names, then track titles (folder order, then queue order). */
    private suspend fun searchAll(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val all = folders.getFolders()
        val folderHits = all.filter { it.displayName.contains(q, ignoreCase = true) }.map(::folderNode)
        val trackHits = all.flatMap { f ->
            val hits = folders.getTracks(f.id).filter { it.title.contains(q, ignoreCase = true) }
            if (hits.isEmpty()) emptyList() else queueBuilder.build(f, hits)
        }
        return folderHits + trackHits
    }

    /** Last [RECENT_LIMIT] books with a bookmark, newest `updatedAt` first; stale rows skipped. */
    private suspend fun recentBooks(): List<Folder> =
        bookmarks.getAllByRecency()
            .mapNotNull { folders.findFolder(it.folderId)?.takeIf(Folder::isAudiobook) }
            .take(RECENT_LIMIT)

    private fun folderNode(folder: Folder): MediaItem =
        node(
            id = FOLDER_PREFIX + folder.id,
            title = folder.displayName,
            extras = Bundle().apply {
                putBoolean(QueueBuilder.EXTRA_AUDIOBOOK, folder.isAudiobook)
                putString(QueueBuilder.EXTRA_FOLDER_ID, folder.id)
            },
        )

    private fun node(id: String, title: String, extras: Bundle? = null): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        if (extras != null) md.setExtras(extras)
        if (artworkUri != null) md.setArtworkUri(artworkUri)
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(md.build()).build()
    }

    private fun <T : Any> bad(): LibraryResult<T> = LibraryResult.ofError<T>(SessionError.ERROR_BAD_VALUE)

    companion object {
        const val ROOT_ID = "root"
        const val BOOKS_ID = "books"
        const val MUSIC_ID = "music"
        const val RECENT_ID = "recent"
        const val FOLDER_PREFIX = "folder:"

        /** Number of books listed under "Recently played" (PL-2.5). */
        const val RECENT_LIMIT = 10

        /** `Folder.id` for a `folder:<id>` node id, else null. */
        fun folderIdOf(mediaId: String): String? =
            if (mediaId.startsWith(FOLDER_PREFIX)) mediaId.removePrefix(FOLDER_PREFIX).ifEmpty { null } else null
    }
}
