package dev.gsound.a.data.folders

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [TrackDao] (Req 8.1 persist cached tracks, Req 8.2 restore on launch).
 *
 * Runs against an in-memory [GsoundDatabase]. Verifies: insert + reactive observe via
 * [kotlinx.coroutines.flow.Flow], per-folder scoping, dedupe/upsert keyed on the stable track id
 * (Req 3.5), and the delete helpers used by rescan/reconciliation.
 *
 * Tracks require an owning folder (foreign key), so each test seeds the parent folder first.
 */
@RunWith(AndroidJUnit4::class)
class TrackDaoTest {

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
    fun insertedTracksAreObservedViaFlow() = runBlocking {
        seedFolder("f1")

        trackDao.upsertAll(listOf(track("t1", "f1"), track("t2", "f1")))

        val observed = trackDao.observeAll().first()
        assertEquals(setOf("t1", "t2"), observed.map { it.id }.toSet())
    }

    @Test
    fun observeByFolderScopesToThatFolder() = runBlocking {
        seedFolder("f1")
        seedFolder("f2")
        trackDao.upsertAll(
            listOf(track("t1", "f1"), track("t2", "f1"), track("t3", "f2"))
        )

        val f1Tracks = trackDao.observeByFolder("f1").first()
        assertEquals(setOf("t1", "t2"), f1Tracks.map { it.id }.toSet())

        val f2Tracks = trackDao.observeByFolder("f2").first()
        assertEquals(listOf("t3"), f2Tracks.map { it.id })
    }

    @Test
    fun upsertByStableIdDedupesAndRefreshesInPlace() = runBlocking {
        seedFolder("f1")
        trackDao.upsert(track("t1", "f1", title = "Original"))

        // Re-scanning the unchanged file (same id) refreshes metadata without duplicating (Req 3.5).
        trackDao.upsert(track("t1", "f1", title = "Refreshed"))

        val tracks = trackDao.getByFolder("f1")
        assertEquals(1, tracks.size)
        assertEquals("Refreshed", tracks.single().title)
    }

    @Test
    fun findByIdResolvesSingleTrackOrNull() = runBlocking {
        seedFolder("f1")
        assertNull(trackDao.findById("t1"))

        trackDao.upsert(track("t1", "f1", title = "Hit"))

        assertEquals("Hit", trackDao.findById("t1")?.title)
    }

    @Test
    fun deleteByFolderClearsOnlyThatFolder() = runBlocking {
        seedFolder("f1")
        seedFolder("f2")
        trackDao.upsertAll(
            listOf(track("t1", "f1"), track("t2", "f1"), track("t3", "f2"))
        )

        trackDao.deleteByFolder("f1")

        assertTrue(trackDao.getByFolder("f1").isEmpty())
        assertEquals(listOf("t3"), trackDao.getByFolder("f2").map { it.id })
    }

    @Test
    fun deleteByIdsRemovesSelectedTracks() = runBlocking {
        seedFolder("f1")
        trackDao.upsertAll(
            listOf(track("t1", "f1"), track("t2", "f1"), track("t3", "f1"))
        )

        trackDao.deleteByIds(listOf("t1", "t3"))

        assertEquals(listOf("t2"), trackDao.getAll().map { it.id })
    }

    private suspend fun seedFolder(id: String) {
        folderDao.upsert(
            FolderEntity(
                id = id,
                treeUri = "content://tree/$id",
                displayName = "Folder $id",
                trackCount = 0,
                totalBytes = 0L,
                addedAt = 1_700_000_000_000L
            )
        )
    }

    private fun track(id: String, folderId: String, title: String = "Track $id"): TrackEntity =
        TrackEntity(
            id = id,
            folderId = folderId,
            documentUri = "content://doc/$id",
            relativeFolder = "",
            title = title,
            artist = null,
            album = null,
            durationMs = 0L,
            sizeBytes = 1_024L,
            mime = "audio/mpeg"
        )
}
