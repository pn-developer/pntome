package dev.pntome.app.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.session.LibraryResult
import dev.pntome.app.data.folders.BookmarkEntity
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.data.folders.PNTomeDatabase
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 6.2: Android Auto browse tree (PL-2.2, 2.5, 2.7). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BrowseTreeTest {
    private fun folder(id: String, name: String, book: Boolean) = Folder(
        id = id, treeUri = "content://t/$id", displayName = name,
        trackCount = 0, totalBytes = 0, addedAt = 0, isAudiobook = book
    )

    private fun track(id: String, folderId: String, file: String) = Track(
        id = id, folderId = folderId, documentUri = "content://doc/$id", relativeFolder = folderId,
        title = file, artist = null, album = null, durationMs = 1000, sizeBytes = 1,
        mime = "audio/mpeg", fileName = file
    )

    private fun bookmark(folderId: String, at: Long) = BookmarkEntity(
        folderId = folderId, folderName = folderId, trackId = "t", chapterIndex = 0,
        positionSec = 0, finished = false, updatedAt = at, source = "phone"
    )

    private fun withTree(block: suspend (BrowseTree, FolderRepository, PNTomeDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), PNTomeDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val repo = FolderRepository(db.folderDao(), db.trackDao())
            runBlocking { block(BrowseTree(repo, db.bookmarkDao(), QueueBuilder()), repo, db) }
        } finally {
            db.close()
        }
    }

    private suspend fun ids(tree: BrowseTree, parent: String, page: Int = 0, size: Int = 100) =
        tree.getChildren(parent, page, size).value!!.map { it.mediaId }

    @Test
    fun rootChildren_areThreeBrowsableNodes() = withTree { tree, _, _ ->
        assertEquals("root", tree.root().mediaId)
        val items = tree.getChildren("root", 0, 10).value!!
        assertEquals(listOf("books", "music", "recent"), items.map { it.mediaId })
        assertTrue(items.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
    }

    @Test
    fun booksAndMusic_areSplitByAudiobookFlag() = withTree { tree, repo, _ ->
        repo.upsertFolder(folder("b1", "Book", true))
        repo.upsertFolder(folder("m1", "Album", false))
        assertEquals(listOf("folder:b1"), ids(tree, "books"))
        assertEquals(listOf("folder:m1"), ids(tree, "music"))
        val node = tree.getChildren("books", 0, 10).value!!.single()
        assertEquals("Book", node.mediaMetadata.title.toString())
        assertEquals(true, node.mediaMetadata.isBrowsable)
        assertEquals("b1", node.mediaMetadata.extras!!.getString(QueueBuilder.EXTRA_FOLDER_ID))
    }

    @Test
    fun folderChildren_areChaptersInQueueOrder_andPaged() = withTree { tree, repo, _ ->
        repo.upsertFolder(folder("b1", "Book", true))
        repo.upsertTracks(listOf(track("c", "b1", "10.mp3"), track("a", "b1", "2.mp3"), track("b", "b1", "1.mp3")))
        assertEquals(listOf("b", "a", "c"), ids(tree, "folder:b1"))
        assertEquals(listOf("b", "a"), ids(tree, "folder:b1", 0, 2))
        assertEquals(listOf("c"), ids(tree, "folder:b1", 1, 2))
        assertEquals(emptyList<String>(), ids(tree, "folder:b1", 2, 2))
        val first = tree.getChildren("folder:b1", 0, 1).value!!.single()
        assertEquals(true, first.mediaMetadata.isPlayable)
        assertEquals(false, first.mediaMetadata.isBrowsable)
    }

    @Test
    fun paging_edgeCases_hugePageSize_exactBoundary_emptyFolders() = withTree { tree, repo, _ ->
        for (i in 1..4) repo.upsertFolder(folder("b$i", "Book $i", true))
        repo.upsertFolder(folder("empty", "Empty", true))
        val all = ids(tree, "books")
        assertEquals(5, all.size)
        assertEquals(all, ids(tree, "books", 0, Int.MAX_VALUE))
        assertEquals(emptyList<String>(), ids(tree, "books", 1, Int.MAX_VALUE))
        assertEquals(emptyList<String>(), ids(tree, "books", Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(all.subList(0, 5), ids(tree, "books", 0, 5))
        assertEquals(emptyList<String>(), ids(tree, "books", 1, 5))
        assertEquals(all.subList(4, 5), ids(tree, "books", 2, 2))
        assertEquals(emptyList<String>(), ids(tree, "music"))
        assertEquals(emptyList<String>(), ids(tree, "folder:empty"))
        assertEquals(emptyList<String>(), ids(tree, "recent"))
    }

    @Test
    fun recent_isNewestFirst_booksOnly_andLimited() = withTree { tree, repo, db ->
        repo.upsertFolder(folder("old", "Old", true))
        repo.upsertFolder(folder("new", "New", true))
        repo.upsertFolder(folder("music", "Music", false))
        db.bookmarkDao().upsert(bookmark("old", 100))
        db.bookmarkDao().upsert(bookmark("new", 200))
        db.bookmarkDao().upsert(bookmark("music", 300))
        db.bookmarkDao().upsert(bookmark("gone", 400))
        assertEquals(listOf("folder:new", "folder:old"), ids(tree, "recent"))
        for (i in 0 until 12) {
            repo.upsertFolder(folder("x$i", "X$i", true))
            db.bookmarkDao().upsert(bookmark("x$i", 1000L + i))
        }
        val recent = ids(tree, "recent")
        assertEquals(BrowseTree.RECENT_LIMIT, recent.size)
        assertEquals("folder:x11", recent.first())
    }

    @Test
    fun getItem_resolvesNodesFoldersAndTracks() = withTree { tree, repo, _ ->
        repo.upsertFolder(folder("b1", "Book", true))
        repo.upsertTracks(listOf(track("a", "b1", "1.mp3")))
        assertEquals("books", tree.getItem("books").value!!.mediaId)
        assertEquals("folder:b1", tree.getItem("folder:b1").value!!.mediaId)
        val t = tree.getItem("a").value!!
        assertEquals("a", t.mediaId)
        assertEquals(true, t.mediaMetadata.isPlayable)
    }

    @Test
    fun unknownIds_andBadPaging_areErrors() = withTree { tree, _, _ ->
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, tree.getItem("nope").resultCode)
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, tree.getItem("folder:nope").resultCode)
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, tree.getChildren("nope", 0, 10).resultCode)
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, tree.getChildren("folder:nope", 0, 10).resultCode)
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, tree.getChildren("root", -1, 10).resultCode)
        assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, tree.getChildren("root", 0, 0).resultCode)
    }
}
