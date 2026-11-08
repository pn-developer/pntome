package dev.gsound.a.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gsound.a.data.folders.BookmarkEntity
import dev.gsound.a.data.folders.BookmarkRepository
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Task 6.6: `onSetMediaItems` end to end through [LibraryCallback] (what Android Auto triggers
 * when a book is tapped), against a real in-memory database. The session and controller
 * arguments are not used by `onSetMediaItems`, so they are allocated without running a
 * constructor (they cannot be built without a running service).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryCallbackSetItemsTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> bare(cls: Class<T>): T {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls) as T
    }

    private fun withCallback(block: suspend (LibraryCallback, GsoundDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), GsoundDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            runBlocking {
                val repo = FolderRepository(db.folderDao(), db.trackDao())
                repo.upsertFolder(
                    Folder(
                        id = "book", treeUri = "content://t/book", displayName = "Book",
                        trackCount = 0, totalBytes = 0, addedAt = 0, isAudiobook = true
                    )
                )
                repo.upsertTracks(
                    listOf("c2", "c1", "c3").map { id ->
                        Track(
                            id = id, folderId = "book", documentUri = "content://doc/$id",
                            relativeFolder = "", title = id, artist = null, album = null,
                            durationMs = 1000, sizeBytes = 1, mime = "audio/mpeg",
                            fileName = "${id.removePrefix("c")}.mp3"
                        )
                    }
                )
                val callback = LibraryCallback(
                    BrowseTree(repo, db.bookmarkDao(), QueueBuilder()),
                    CoroutineScope(Dispatchers.Default),
                    MediaItemResolver(repo, BookmarkRepository(db.bookmarkDao(), repo)),
                )
                block(callback, db)
            }
        } finally {
            db.close()
        }
    }

    private fun setItems(cb: LibraryCallback, vararg ids: String) = cb.onSetMediaItems(
        bare(MediaSession::class.java), bare(MediaSession.ControllerInfo::class.java),
        ids.map { MediaItem.Builder().setMediaId(it).build() }.toMutableList(), -1, -1L
    ).get(5, TimeUnit.SECONDS)

    @Test
    fun tappingBook_expandsQueue_andStartsAtBookmark() = withCallback { cb, db ->
        db.bookmarkDao().upsert(
            BookmarkEntity(
                folderId = "book", folderName = "Book", trackId = "c2", chapterIndex = 1,
                positionSec = 62, finished = false, updatedAt = 1, source = "phone"
            )
        )
        val res = setItems(cb, "folder:book")
        assertEquals(listOf("c1", "c2", "c3"), res.mediaItems.map { it.mediaId })
        assertTrue(res.mediaItems.all { it.localConfiguration != null })
        assertEquals(1, res.startIndex)
        assertEquals(57_000L, res.startPositionMs)
    }

    @Test
    fun unknownId_failsTheFuture_insteadOfCrashing() = withCallback { cb, _ ->
        try {
            setItems(cb, "folder:nope")
            fail("expected failure")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is IllegalArgumentException)
        }
    }
}
