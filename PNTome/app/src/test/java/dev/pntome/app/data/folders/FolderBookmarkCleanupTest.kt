package dev.pntome.app.data.folders

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pntome.app.domain.model.Folder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PL-3.6: bookmarks are deleted when a folder is removed or un-marked as an audiobook. */
@RunWith(RobolectricTestRunner::class)
class FolderBookmarkCleanupTest {

    private lateinit var db: PNTomeDatabase
    private lateinit var repository: FolderRepository

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, PNTomeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(id: String) {
        repository.upsertFolder(
            Folder(id, "content://tree/$id", "Book $id", 1, 10L, 1L, isAudiobook = true)
        )
        db.bookmarkDao().upsert(
            BookmarkEntity(id, "Book $id", "t1", 0, 30, false, 100L, "phone")
        )
    }

    @Test
    fun `removing a folder deletes only its bookmark`() = runBlocking {
        seed("a")
        seed("b")

        repository.removeFolder("a")

        assertNull(db.bookmarkDao().get("a"))
        assertNotNull(db.bookmarkDao().get("b"))
    }

    @Test
    fun `un-marking audiobook deletes its bookmark`() = runBlocking {
        seed("a")
        seed("b")

        repository.setAudiobook("a", false)

        assertNull(db.bookmarkDao().get("a"))
        assertNotNull(db.bookmarkDao().get("b"))
        assertNotNull(repository.findFolder("a"))
    }

    @Test
    fun `marking audiobook keeps the bookmark`() = runBlocking {
        seed("a")

        repository.setAudiobook("a", true)

        assertNotNull(db.bookmarkDao().get("a"))
    }
}
