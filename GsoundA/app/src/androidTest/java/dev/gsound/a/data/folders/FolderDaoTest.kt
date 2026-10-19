package dev.gsound.a.data.folders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [FolderDao] (Req 8.1 persist folder list, Req 8.2 restore on launch).
 *
 * Runs against an in-memory [GsoundDatabase] so each test starts from a clean, isolated schema.
 * Verifies: insert + reactive observe via [kotlinx.coroutines.flow.Flow], dedupe/upsert keyed on
 * the stable folder id (Req 1.4), insert-ignore semantics, and that removing a folder cascades to
 * its cached tracks via the entity foreign key (Req 2.2).
 */
@RunWith(AndroidJUnit4::class)
class FolderDaoTest {

    private lateinit var db: GsoundDatabase
    private lateinit var folderDao: FolderDao
    private lateinit var trackDao: TrackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GsoundDatabase::class.java
        ).allowMainThreadQueries().build()
        folderDao = db.folderDao()
        trackDao = db.trackDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertedFolderIsObservedViaFlow() = runBlocking {
        val folder = folder(id = "f1", name = "Rock")

        folderDao.upsert(folder)

        val observed = folderDao.observeAll().first()
        assertEquals(1, observed.size)
        assertEquals(folder, observed.single())
    }

    @Test
    fun observeAllOrdersNewestFirst() = runBlocking {
        folderDao.upsert(folder(id = "old", name = "Old", addedAt = 1_000L))
        folderDao.upsert(folder(id = "new", name = "New", addedAt = 2_000L))

        val ids = folderDao.observeAll().first().map { it.id }

        assertEquals(listOf("new", "old"), ids)
    }

    @Test
    fun observeByIdEmitsNullWhenAbsent() = runBlocking {
        assertNull(folderDao.observeById("missing").first())

        folderDao.upsert(folder(id = "f1"))

        assertEquals("f1", folderDao.observeById("f1").first()?.id)
    }

    @Test
    fun upsertByStableIdUpdatesInPlaceWithoutDuplicates() = runBlocking {
        folderDao.upsert(folder(id = "f1", name = "Original", trackCount = 1))

        // Re-adding the same tree URI (same id) refreshes metadata rather than duplicating (Req 1.4).
        folderDao.upsert(folder(id = "f1", name = "Renamed", trackCount = 42))

        val all = folderDao.getAll()
        assertEquals(1, all.size)
        assertEquals("Renamed", all.single().displayName)
        assertEquals(42, all.single().trackCount)
    }

    @Test
    fun insertIgnoreKeepsExistingRowAndReportsIgnored() = runBlocking {
        val inserted = folderDao.insertIgnore(folder(id = "f1", name = "First"))
        assertNotEquals(-1L, inserted)

        // A second insert with the same id is ignored: row id -1 and the original row is preserved.
        val ignored = folderDao.insertIgnore(folder(id = "f1", name = "Second"))
        assertEquals(-1L, ignored)

        val all = folderDao.getAll()
        assertEquals(1, all.size)
        assertEquals("First", all.single().displayName)
    }

    @Test
    fun findByIdReturnsPersistedFolderOrNull() = runBlocking {
        assertNull(folderDao.findById("f1"))

        folderDao.upsert(folder(id = "f1", name = "Jazz"))

        assertEquals("Jazz", folderDao.findById("f1")?.displayName)
    }

    @Test
    fun deleteByIdRemovesFolder() = runBlocking {
        folderDao.upsert(folder(id = "f1"))

        folderDao.deleteById("f1")

        assertTrue(folderDao.getAll().isEmpty())
        assertNull(folderDao.findById("f1"))
    }

    @Test
    fun deletingFolderCascadesToItsCachedTracks() = runBlocking {
        folderDao.upsert(folder(id = "f1"))
        folderDao.upsert(folder(id = "f2"))
        trackDao.upsertAll(
            listOf(
                track(id = "t1", folderId = "f1"),
                track(id = "t2", folderId = "f1"),
                track(id = "t3", folderId = "f2")
            )
        )

        folderDao.deleteById("f1")

        // f1's tracks are gone; f2's track remains untouched (Req 2.2 cascade).
        assertTrue(trackDao.getByFolder("f1").isEmpty())
        assertEquals(listOf("t3"), trackDao.getByFolder("f2").map { it.id })
    }

    private fun folder(
        id: String,
        name: String = "Folder $id",
        trackCount: Int = 0,
        totalBytes: Long = 0L,
        addedAt: Long = 1_700_000_000_000L
    ): FolderEntity = FolderEntity(
        id = id,
        treeUri = "content://tree/$id",
        displayName = name,
        trackCount = trackCount,
        totalBytes = totalBytes,
        addedAt = addedAt
    )

    private fun track(id: String, folderId: String): TrackEntity = TrackEntity(
        id = id,
        folderId = folderId,
        documentUri = "content://doc/$id",
        relativeFolder = "",
        title = "Track $id",
        artist = null,
        album = null,
        durationMs = 0L,
        sizeBytes = 0L,
        mime = "audio/mpeg"
    )
}
