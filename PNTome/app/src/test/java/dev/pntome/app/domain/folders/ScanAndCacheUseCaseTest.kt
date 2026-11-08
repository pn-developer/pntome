package dev.gsound.a.domain.folders

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.domain.model.AppSettings
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.Track
import dev.gsound.a.domain.scan.ScanResult
import dev.gsound.a.domain.scan.TrackScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ScanAndCacheUseCase] (task 5.3) on the JVM via Robolectric.
 *
 * The Android-coupled scanner is replaced by a [FakeTrackScanner] so the orchestration +
 * persistence is exercised against a real in-memory [GsoundDatabase] without an emulator (the
 * instrumented scan is task 5.4). These verify: the fresh scan is cached and the folder's
 * aggregates refreshed (Req 2.1); the skipped count propagates (Req 3.3); an incremental rescan
 * reconciles added/changed/deleted tracks by stable id (Req 3.5); and a missing folder is
 * reported without touching the cache.
 */
@RunWith(RobolectricTestRunner::class)
class ScanAndCacheUseCaseTest {

    private lateinit var db: GsoundDatabase
    private lateinit var repository: FolderRepository
    private lateinit var scanner: FakeTrackScanner
    private lateinit var useCase: ScanAndCacheUseCase

    private val folderId = "folder-1"
    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"

    /** A [TrackScanner] that returns a scripted [ScanResult], recording what it was asked to scan. */
    private class FakeTrackScanner : TrackScanner {
        var result: ScanResult = ScanResult(emptyList(), 0)
        var lastTreeUri: Uri? = null
        var lastFolderId: String? = null
        var lastRootName: String? = null
        var callCount = 0

        /** Runs while "scanning" — lets a test change the folder mid-scan. */
        var duringScan: suspend () -> Unit = {}

        override suspend fun scanToResult(
            treeUri: Uri,
            folderId: String,
            rootName: String,
            settings: AppSettings?
        ): ScanResult {
            callCount++
            duringScan()
            lastTreeUri = treeUri
            lastFolderId = folderId
            lastRootName = rootName
            return result
        }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, GsoundDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
        scanner = FakeTrackScanner()
        useCase = ScanAndCacheUseCase(scanner, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun track(id: String, size: Long = 1_000L): Track = Track(
        id = id,
        folderId = folderId,
        documentUri = "content://docs/$id",
        relativeFolder = "Rock",
        title = "Title $id",
        artist = null,
        album = null,
        durationMs = 0L,
        sizeBytes = size,
        mime = "audio/mpeg"
    )

    private suspend fun seedFolder() {
        repository.upsertFolder(
            Folder(
                id = folderId,
                treeUri = treeUri,
                displayName = "Music",
                trackCount = 0,
                totalBytes = 0L,
                addedAt = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `first scan caches tracks and updates folder aggregates`() = runBlocking {
        seedFolder()
        scanner.result = ScanResult(
            tracks = listOf(track("a", 100L), track("b", 200L), track("c", 300L)),
            skippedCount = 2
        )

        val result = useCase.scanAndCache(folderId)

        val success = result as ScanAndCacheResult.Success
        assertEquals(folderId, success.folderId)
        assertEquals(3, success.trackCount)
        assertEquals(600L, success.totalBytes)
        assertEquals(2, success.skippedCount) // Req 3.3
        assertEquals(3, success.addedCount)
        assertEquals(0, success.removedCount)

        // Tracks are persisted.
        val cached = repository.getTracks(folderId).map { it.id }.sorted()
        assertEquals(listOf("a", "b", "c"), cached)

        // Folder aggregates reflect the cache (Req 2.1).
        val folder = repository.findFolder(folderId)!!
        assertEquals(3, folder.trackCount)
        assertEquals(600L, folder.totalBytes)

        // The scanner was invoked for this folder's tree.
        assertEquals(Uri.parse(treeUri), scanner.lastTreeUri)
        assertEquals(folderId, scanner.lastFolderId)
        assertEquals("Music", scanner.lastRootName)
    }

    @Test
    fun `incremental rescan reconciles added changed and deleted tracks by id`() = runBlocking {
        seedFolder()
        // Initial cache: a, b-old, c
        scanner.result = ScanResult(listOf(track("a"), track("b-old"), track("c")), skippedCount = 0)
        useCase.scanAndCache(folderId)

        // Rescan: a unchanged, b changed (b-old -> b-new), c deleted, d added.
        scanner.result = ScanResult(listOf(track("a"), track("b-new"), track("d")), skippedCount = 1)

        val result = useCase.scanAndCache(folderId) as ScanAndCacheResult.Success

        assertEquals(2, result.addedCount) // b-new, d
        assertEquals(2, result.removedCount) // b-old, c
        assertEquals(3, result.trackCount)
        assertEquals(1, result.skippedCount)

        val cached = repository.getTracks(folderId).map { it.id }.sorted()
        assertEquals(listOf("a", "b-new", "d"), cached)
    }

    @Test
    fun `unchanged rescan keeps the cache stable with no churn`() = runBlocking {
        seedFolder()
        val tracks = listOf(track("a", 100L), track("b", 200L))
        scanner.result = ScanResult(tracks, skippedCount = 0)
        useCase.scanAndCache(folderId)

        // Same ids again (unchanged files, Req 3.5).
        scanner.result = ScanResult(tracks, skippedCount = 0)
        val result = useCase.scanAndCache(folderId) as ScanAndCacheResult.Success

        assertEquals(0, result.addedCount)
        assertEquals(0, result.removedCount)
        assertEquals(2, result.trackCount)
        assertEquals(300L, result.totalBytes)

        val folder = repository.findFolder(folderId)!!
        assertEquals(2, folder.trackCount)
        assertEquals(300L, folder.totalBytes)
    }

    @Test
    fun `empty rescan clears the cache and zeroes aggregates`() = runBlocking {
        seedFolder()
        scanner.result = ScanResult(listOf(track("a", 100L), track("b", 200L)), skippedCount = 0)
        useCase.scanAndCache(folderId)

        scanner.result = ScanResult(emptyList(), skippedCount = 5)
        val result = useCase.scanAndCache(folderId) as ScanAndCacheResult.Success

        assertEquals(0, result.trackCount)
        assertEquals(0L, result.totalBytes)
        assertEquals(2, result.removedCount)
        assertEquals(5, result.skippedCount)
        assertTrue(repository.getTracks(folderId).isEmpty())

        val folder = repository.findFolder(folderId)!!
        assertEquals(0, folder.trackCount)
        assertEquals(0L, folder.totalBytes)
    }

    // --- spec gsound-audiobooks AB-1.2, AB-2.1 ------------------------------------------------

    @Test
    fun `audiobook flag survives a rescan`() = runBlocking {
        seedFolder()
        repository.setAudiobook(folderId, true)
        scanner.result = ScanResult(listOf(track("a")), skippedCount = 0)

        useCase.scanAndCache(folderId)

        assertTrue(repository.findFolder(folderId)!!.isAudiobook)
    }

    @Test
    fun `audiobook toggled while a scan runs is not overwritten by the scan`() = runBlocking {
        seedFolder()
        scanner.result = ScanResult(listOf(track("a", 100L)), skippedCount = 0)
        // The user flips the toggle after the use case read the folder but before it writes
        // the refreshed aggregates back.
        scanner.duringScan = { repository.setAudiobook(folderId, true) }

        useCase.scanAndCache(folderId)

        val folder = repository.findFolder(folderId)!!
        assertTrue(folder.isAudiobook)
        assertEquals(1, folder.trackCount)
        assertEquals(100L, folder.totalBytes)
    }

    @Test
    fun `scanned file names are cached`() = runBlocking {
        seedFolder()
        scanner.result = ScanResult(listOf(track("a").copy(fileName = "01 Intro.mp3")), 0)

        useCase.scanAndCache(folderId)

        assertEquals("01 Intro.mp3", repository.getTracks(folderId).single().fileName)
    }

    @Test
    fun `scanning an unknown folder returns FolderNotFound and does not scan`() = runBlocking {
        val result = useCase.scanAndCache("does-not-exist")

        assertEquals(ScanAndCacheResult.FolderNotFound, result)
        assertEquals(0, scanner.callCount)
        assertNull(repository.findFolder("does-not-exist"))
    }
}
