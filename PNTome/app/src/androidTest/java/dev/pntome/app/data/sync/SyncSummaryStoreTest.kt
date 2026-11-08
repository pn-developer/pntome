package dev.pntome.app.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pntome.app.data.DataStoreTestContext
import dev.pntome.app.domain.model.SyncSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented round-trip tests for [SyncSummaryStore] (Req 6.3 last-sync summary, 8.1 persist,
 * 8.2 restore).
 *
 * Verifies the "no sync yet" default reads as null, and that a saved summary — including the
 * success case (null error) and the failure case (non-null error) — round-trips intact. Each
 * test uses an isolated DataStore file.
 */
@RunWith(AndroidJUnit4::class)
class SyncSummaryStoreTest {

    private lateinit var filesDir: File
    private lateinit var store: SyncSummaryStore

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        filesDir = File(base.cacheDir, "sync-${System.nanoTime()}")
        store = SyncSummaryStore(DataStoreTestContext(base, filesDir))
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun noSyncYetReadsAsNull() = runTest {
        assertNull(store.get())
        assertNull(store.summary.first())
    }

    @Test
    fun saveThenGetRoundTripsSuccessSummary() = runTest {
        val summary = SyncSummary(
            syncedCount = 12,
            skippedCount = 3,
            removedCount = 1,
            lastSyncAt = 1_700_000_000_000L,
            error = null
        )

        store.save(summary)

        assertEquals(summary, store.get())
        assertEquals(summary, store.summary.first())
    }

    @Test
    fun saveThenGetRoundTripsErrorSummary() = runTest {
        val summary = SyncSummary(
            syncedCount = 0,
            skippedCount = 0,
            removedCount = 0,
            lastSyncAt = 1_700_000_123_000L,
            error = "Watch disconnected mid-sync"
        )

        store.save(summary)

        assertEquals(summary, store.get())
    }

    @Test
    fun saveReplacesPreviousSummary() = runTest {
        store.save(SyncSummary(1, 0, 0, 100L, null))
        val latest = SyncSummary(9, 2, 1, 200L, null)

        store.save(latest)

        assertEquals(latest, store.get())
    }
}
