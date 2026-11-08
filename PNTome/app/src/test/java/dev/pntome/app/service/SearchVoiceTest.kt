package dev.pntome.app.service

import android.content.Context
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 6.5: search and voice "play <title>" (PL-2.6). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchVoiceTest {
    private fun folder(id: String, name: String, book: Boolean) = Folder(
        id = id, treeUri = "content://t/$id", displayName = name,
        trackCount = 0, totalBytes = 0, addedAt = 0, isAudiobook = book
    )

    private fun track(id: String, folderId: String, title: String) = Track(
        id = id, folderId = folderId, documentUri = "content://doc/$id", relativeFolder = folderId,
        title = title, artist = null, album = null, durationMs = 1000, sizeBytes = 1,
        mime = "audio/mpeg", fileName = "$title.mp3"
    )

    private fun bookmark(folderId: String, trackId: String, index: Int, sec: Int, at: Long, finished: Boolean = false) =
        BookmarkEntity(
            folderId = folderId, folderName = folderId, trackId = trackId, chapterIndex = index,
            positionSec = sec, finished = finished, updatedAt = at, source = "phone"
        )

    private fun voice(query: String?) = MediaItem.Builder()
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
        .build()

    private class Env(val tree: BrowseTree, val resolver: MediaItemResolver, val repo: FolderRepository, val db: PNTomeDatabase)

    private fun run(seed: Boolean = true, block: suspend (Env) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PNTomeDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            runBlocking {
                val repo = FolderRepository(db.folderDao(), db.trackDao())
                if (seed) {
                    repo.upsertFolder(folder("b1", "Dune", true))
                    repo.upsertTracks(listOf(track("d1", "b1", "Dune 1"), track("d2", "b1", "Dune 2")))
                    repo.upsertFolder(folder("b2", "Emma", true))
                    repo.upsertTracks(listOf(track("e1", "b2", "Intro"), track("e2", "b2", "Dune dream")))
                    repo.upsertFolder(folder("m1", "Dune Soundtrack", false))
                    repo.upsertTracks(listOf(track("s1", "m1", "Arrakis"), track("s2", "m1", "Sandworm")))
                }
                block(Env(BrowseTree(repo, db.bookmarkDao()), MediaItemResolver(repo, BookmarkRepository(db.bookmarkDao(), repo)), repo, db))
            }
        } finally {
            db.close()
        }
    }

    private suspend fun hits(tree: BrowseTree, q: String, page: Int = 0, size: Int = 100) =
        tree.searchResults(q, page, size).value!!.map { it.mediaId }

    @Test
    fun search_isCaseInsensitive_foldersFirstThenTracks() = run { e ->
        assertEquals(listOf("folder:b1", "folder:m1", "d1", "d2", "e2"), hits(e.tree, "dUnE"))
        assertEquals(5, e.tree.search("dune"))
        assertEquals(listOf("s2"), hits(e.tree, "SANDw"))
    }

    @Test
    fun search_blankOrNoMatch_isEmpty() = run { e ->
        assertEquals(emptyList<String>(), hits(e.tree, ""))
        assertEquals(emptyList<String>(), hits(e.tree, "   "))
        assertEquals(emptyList<String>(), hits(e.tree, "zzz"))
        assertEquals(0, e.tree.search("zzz"))
    }

    @Test
    fun search_isPaged() = run { e ->
        assertEquals(listOf("folder:b1", "folder:m1"), hits(e.tree, "dune", 0, 2))
        assertEquals(listOf("d1", "d2"), hits(e.tree, "dune", 1, 2))
        assertEquals(listOf("e2"), hits(e.tree, "dune", 2, 2))
        assertEquals(emptyList<String>(), hits(e.tree, "dune", 3, 2))
    }

    @Test
    fun voice_bookMatchBeatsMusicAndStartsAtBookmark() = run { e ->
        e.db.bookmarkDao().upsert(bookmark("b1", "d2", 1, 100, 5))
        val res = e.resolver.setMediaItems(listOf(voice("dune")), -1, -1)!!
        assertEquals(listOf("d1", "d2"), res.mediaItems.map { it.mediaId })
        assertEquals(1, res.startIndex)
        assertEquals(95_000L, res.startPositionMs)
    }

    @Test
    fun voice_musicFolderThenChapterMatch() = run { e ->
        var res = e.resolver.setMediaItems(listOf(voice("soundtrack")), -1, -1)!!
        assertEquals(listOf("s1", "s2"), res.mediaItems.map { it.mediaId })
        assertEquals(0, res.startIndex)
        res = e.resolver.setMediaItems(listOf(voice("dream")), -1, -1)!!
        assertEquals(listOf("e2", "e1"), res.mediaItems.map { it.mediaId }) // natural order by file name
        assertEquals(0, res.startIndex)
    }

    @Test
    fun voice_noMatchOrEmpty_continuesLastUnfinishedBook() = run { e ->
        e.db.bookmarkDao().upsert(bookmark("b1", "d2", 1, 100, 5))
        e.db.bookmarkDao().upsert(bookmark("b2", "e2", 1, 50, 9, finished = true))
        for (q in listOf("nothing like it", "", "  ")) {
            val res = e.resolver.setMediaItems(listOf(voice(q)), -1, -1)!!
            assertEquals(listOf("d1", "d2"), res.mediaItems.map { it.mediaId })
            assertEquals(1, res.startIndex)
            assertEquals(95_000L, res.startPositionMs)
        }
    }

    @Test
    fun voice_addMediaItems_expandsToo() = run { e ->
        assertEquals(listOf("s1", "s2"), e.resolver.addMediaItems(listOf(voice("soundtrack"))).map { it.mediaId })
    }

    @Test
    fun voice_noBookmarks_usesFirstBook_noBooks_isNull() = run { e ->
        assertEquals(listOf("d1", "d2"), e.resolver.setMediaItems(listOf(voice("")), -1, -1)!!.mediaItems.map { it.mediaId })
        e.repo.setAudiobook("b1", false)
        e.repo.setAudiobook("b2", false)
        assertNull(e.resolver.setMediaItems(listOf(voice("")), -1, -1))
    }

    @Test
    fun voice_emptyLibrary_isNull() = run(seed = false) { e ->
        assertNull(e.resolver.setMediaItems(listOf(voice("anything")), -1, -1))
        assertEquals(emptyList<MediaItem>(), e.resolver.addMediaItems(listOf(voice(""))))
    }
}
