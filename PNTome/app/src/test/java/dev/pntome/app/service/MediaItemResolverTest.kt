package dev.pntome.app.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pntome.app.data.folders.BookmarkEntity
import dev.pntome.app.data.folders.BookmarkRepository
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.data.folders.PNTomeDatabase
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 6.3: onSetMediaItems expansion and resume start (PL-2.3, PL-6.6). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaItemResolverTest {
    private fun folder(id: String, book: Boolean) = Folder(
        id = id, treeUri = "content://t/$id", displayName = id,
        trackCount = 0, totalBytes = 0, addedAt = 0, isAudiobook = book
    )

    private fun track(id: String, folderId: String, file: String) = Track(
        id = id, folderId = folderId, documentUri = "content://doc/$id", relativeFolder = folderId,
        title = file, artist = null, album = null, durationMs = 1000, sizeBytes = 1,
        mime = "audio/mpeg", fileName = file
    )

    private fun bookmark(folderId: String, trackId: String, index: Int, sec: Int, finished: Boolean = false) =
        BookmarkEntity(
            folderId = folderId, folderName = folderId, trackId = trackId, chapterIndex = index,
            positionSec = sec, finished = finished, updatedAt = 1, source = "phone"
        )

    private fun idOnly(id: String) = MediaItem.Builder().setMediaId(id).build()

    private fun withResolver(block: suspend (MediaItemResolver, PNTomeDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PNTomeDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            runBlocking {
                val repo = FolderRepository(db.folderDao(), db.trackDao())
                repo.upsertFolder(folder("book", true))
                repo.upsertTracks(listOf(track("c3", "book", "3.mp3"), track("c1", "book", "1.mp3"), track("c2", "book", "2.mp3")))
                repo.upsertFolder(folder("album", false))
                repo.upsertTracks(listOf(track("m1", "album", "b.mp3"), track("m2", "album", "a.mp3")))
                block(MediaItemResolver(repo, BookmarkRepository(db.bookmarkDao(), repo)), db)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun bookFolder_withBookmark_startsAtRewoundPosition() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("book", "c2", 1, 100))
        val res = r.setMediaItems(listOf(idOnly("folder:book")), -1, -1)!!
        assertEquals(listOf("c1", "c2", "c3"), res.mediaItems.map { it.mediaId })
        assertEquals(1, res.startIndex)
        assertEquals(95_000L, res.startPositionMs)
    }

    @Test
    fun bookFolder_finishedOrNoBookmark_startsAtChapterOne() = withResolver { r, db ->
        var res = r.setMediaItems(listOf(idOnly("folder:book")), -1, -1)!!
        assertEquals(0, res.startIndex)
        assertEquals(0L, res.startPositionMs)
        db.bookmarkDao().upsert(bookmark("book", "c3", 2, 50, finished = true))
        res = r.setMediaItems(listOf(idOnly("folder:book")), -1, -1)!!
        assertEquals(0, res.startIndex)
        assertEquals(0L, res.startPositionMs)
    }

    @Test
    fun musicFolder_startsAtZero_inStoredOrder() = withResolver { r, _ ->
        val res = r.setMediaItems(listOf(idOnly("folder:album")), -1, -1)!!
        assertEquals(listOf("m1", "m2"), res.mediaItems.map { it.mediaId })
        assertEquals(0, res.startIndex)
        assertEquals(0L, res.startPositionMs)
    }

    @Test
    fun chapterId_queuesWholeFolderFromThatChapter_ignoringBookmark() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("book", "c1", 0, 40))
        val res = r.setMediaItems(listOf(idOnly("c3")), -1, -1)!!
        assertEquals(listOf("c1", "c2", "c3"), res.mediaItems.map { it.mediaId })
        assertEquals(2, res.startIndex)
        assertEquals(0L, res.startPositionMs)
        assertNotNull(res.mediaItems[2].localConfiguration)
    }

    @Test
    fun musicTrackId_queuesFolderFromThatTrack() = withResolver { r, _ ->
        val res = r.setMediaItems(listOf(idOnly("m2")), -1, -1)!!
        assertEquals(listOf("m1", "m2"), res.mediaItems.map { it.mediaId })
        assertEquals(1, res.startIndex)
    }

    @Test
    fun unknownIds_yieldNullOrAreDropped() = withResolver { r, _ ->
        assertNull(r.setMediaItems(listOf(idOnly("nope")), -1, -1))
        assertNull(r.setMediaItems(listOf(idOnly("folder:nope")), -1, -1))
        assertNull(r.setMediaItems(emptyList(), 0, 0))
        val res = r.setMediaItems(listOf(idOnly("nope"), idOnly("m1")), 1, 500)!!
        assertEquals(listOf("m1"), res.mediaItems.map { it.mediaId })
    }

    private fun sub(id: String, folderId: String, dir: String, file: String) =
        track(id, folderId, file).copy(relativeFolder = dir)

    @Test
    fun multiSubfolderBook_resumesAtIndexInSubfolderGroupedOrder() = withResolver { r, db ->
        val repo = FolderRepository(db.folderDao(), db.trackDao())
        repo.upsertFolder(folder("multi", true))
        repo.upsertTracks(
            listOf(
                sub("a10", "multi", "CD1", "10.mp3"), sub("a2", "multi", "CD1", "2.mp3"),
                sub("b2", "multi", "CD2", "2.mp3"), sub("b1", "multi", "CD2", "1.mp3"),
            )
        )
        db.bookmarkDao().upsert(bookmark("multi", "b1", 2, 30))
        val res = r.setMediaItems(listOf(idOnly("folder:multi")), -1, -1)!!
        assertEquals(listOf("a2", "a10", "b1", "b2"), res.mediaItems.map { it.mediaId })
        assertEquals(2, res.startIndex)
        assertEquals(25_000L, res.startPositionMs)
    }

    @Test
    fun missingBookmarkedChapter_fallsBackToChapterAtOldIndexFromZero() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("book", "removed", 1, 300))
        val res = r.setMediaItems(listOf(idOnly("folder:book")), -1, -1)!!
        assertEquals(1, res.startIndex)
        assertEquals(0L, res.startPositionMs)
    }

    @Test
    fun missingBookmarkedChapter_indexOutOfRange_startsAtChapterOne() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("book", "removed", 9, 300))
        val res = r.setMediaItems(listOf(idOnly("folder:book")), -1, -1)!!
        assertEquals(0, res.startIndex)
        assertEquals(0L, res.startPositionMs)
    }

    @Test
    fun bookmarkUnder5Seconds_clampsToZero() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("book", "c3", 2, 3))
        val res = r.setMediaItems(listOf(idOnly("folder:book")), -1, -1)!!
        assertEquals(2, res.startIndex)
        assertEquals(0L, res.startPositionMs)
    }

    @Test
    fun musicFolder_ignoresStrayBookmark() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("album", "m2", 1, 60))
        val res = r.setMediaItems(listOf(idOnly("folder:album")), -1, -1)!!
        assertEquals(0, res.startIndex)
        assertEquals(0L, res.startPositionMs)
    }

    @Test
    fun addMediaItems_expandsFoldersInPlace_andDropsUnknown() = withResolver { r, _ ->
        val out = r.addMediaItems(listOf(idOnly("nope"), idOnly("folder:album"), idOnly("c1")))
        assertEquals(listOf("m1", "m2", "c1"), out.map { it.mediaId })
    }

    @Test
    fun resolvedItems_passThroughWithControllerStart() = withResolver { r, db ->
        db.bookmarkDao().upsert(bookmark("book", "c2", 1, 100))
        val items = listOf(
            MediaItem.Builder().setMediaId("x").setUri(Uri.parse("content://x")).build(),
            MediaItem.Builder().setMediaId("y").setUri(Uri.parse("content://y")).build(),
        )
        val res = r.setMediaItems(items, 1, 1234L)!!
        assertEquals(items, res.mediaItems)
        assertEquals(1, res.startIndex)
        assertEquals(1234L, res.startPositionMs)
    }
}
