package dev.pntome.app.data.folders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [FolderRepository] over an in-memory [PNTomeDatabase].
 *
 * Exercises the domain-facing API callers actually use: dedupe on add (Req 1.4), reactive
 * restore of persisted folders/tracks (Req 8.1, 8.2), rescan replacement, and cascade removal
 * (Req 2.2). Confirms Room entities are mapped to framework-free domain models.
 */
@RunWith(AndroidJUnit4::class)
class FolderRepositoryTest {

    private lateinit var db: PNTomeDatabase
    private lateinit var repository: FolderRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PNTomeDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addFolderIfAbsentDedupesByStableId() = runBlocking {
        assertTrue(repository.addFolderIfAbsent(folder("f1", name = "First")))
        // Second add of the same id is rejected as a duplicate; original metadata is preserved.
        assertFalse(repository.addFolderIfAbsent(folder("f1", name = "Second")))

        val folders = repository.getFolders()
        assertEquals(1, folders.size)
        assertEquals("First", folders.single().displayName)
    }

    @Test
    fun observeFoldersEmitsDomainModels() = runBlocking {
        repository.upsertFolder(folder("f1", name = "Classical"))

        val observed = repository.observeFolders().first()
        assertEquals(1, observed.size)
        assertEquals("Classical", observed.single().displayName)
        assertEquals("content://tree/f1", observed.single().treeUri)
    }

    @Test
    fun replaceTracksSwapsCachedScanResult() = runBlocking {
        repository.upsertFolder(folder("f1"))
        repository.replaceTracks("f1", listOf(track("t1", "f1"), track("t2", "f1")))

        repository.replaceTracks("f1", listOf(track("t3", "f1")))

        assertEquals(listOf("t3"), repository.getTracks("f1").map { it.id })
    }

    @Test
    fun removeFolderCascadesToTracks() = runBlocking {
        repository.upsertFolder(folder("f1"))
        repository.upsertTracks(listOf(track("t1", "f1"), track("t2", "f1")))

        repository.removeFolder("f1")

        assertNull(repository.findFolder("f1"))
        assertTrue(repository.getTracks("f1").isEmpty())
        assertTrue(repository.getAllTracks().isEmpty())
    }

    private fun folder(id: String, name: String = "Folder $id"): Folder = Folder(
        id = id,
        treeUri = "content://tree/$id",
        displayName = name,
        trackCount = 0,
        totalBytes = 0L,
        addedAt = 1_700_000_000_000L
    )

    private fun track(id: String, folderId: String): Track = Track(
        id = id,
        folderId = folderId,
        documentUri = "content://doc/$id",
        relativeFolder = "",
        title = "Track $id",
        artist = null,
        album = null,
        durationMs = 0L,
        sizeBytes = 1_024L,
        mime = "audio/mpeg"
    )
}
