package dev.gsound.a.ui.sync

import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.model.SyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure [SyncUiState] mapping and derived gating flags (task 10.1,
 * Req 6.1, 2.4, 6.2–6.5, 5.8).
 *
 * Covers only the framework-free projection ([SyncUiState.from] + the derived properties). The
 * screen rendering and the full gating UI are task 10.3; the service start/cancel wiring in
 * [SyncViewModel] needs an Android `Context` and is exercised there.
 */
class SyncUiStateTest {

    private fun state(
        syncState: SyncState = SyncState.Idle,
        isPaired: Boolean = true,
        hasFolders: Boolean = true,
        lastSummary: SyncSummary? = null,
        watchStats: WatchStats? = null,
    ) = SyncUiState.from(syncState, isPaired, hasFolders, lastSummary, watchStats)

    // --- Gating (Req 6.1, 2.4) --------------------------------------------------------------

    @Test
    fun `canSync requires both paired and folders`() {
        assertTrue(state(isPaired = true, hasFolders = true).canSync)
        assertFalse(state(isPaired = false, hasFolders = true).canSync)
        assertFalse(state(isPaired = true, hasFolders = false).canSync)
        assertFalse(state(isPaired = false, hasFolders = false).canSync)
    }

    @Test
    fun `canStartSync is false while a sync is active even when gated true`() {
        val running = state(syncState = SyncState.Running(1, 10, 0L))
        assertTrue(running.canSync)
        assertFalse(running.canStartSync)

        assertFalse(state(syncState = SyncState.Preparing).canStartSync)
        assertTrue(state(syncState = SyncState.Idle).canStartSync)
    }

    @Test
    fun `disabledReason explains the missing precondition`() {
        assertEquals(
            "Pair your watch to sync.",
            state(isPaired = false, hasFolders = true).disabledReason,
        )
        assertEquals(
            "Add a music folder to sync.",
            state(isPaired = true, hasFolders = false).disabledReason,
        )
        assertEquals(
            "Pair your watch and add a music folder to sync.",
            state(isPaired = false, hasFolders = false).disabledReason,
        )
        assertNull(state(isPaired = true, hasFolders = true).disabledReason)
    }

    @Test
    fun `disabledReason is null while syncing`() {
        assertNull(
            state(syncState = SyncState.Running(0, 5, 0L), isPaired = false, hasFolders = false)
                .disabledReason,
        )
    }

    // --- Derived flags (Req 6.2–6.5, 5.8) ---------------------------------------------------

    @Test
    fun `isSyncing is true only for Preparing and Running`() {
        assertTrue(state(syncState = SyncState.Preparing).isSyncing)
        assertTrue(state(syncState = SyncState.Running(0, 1, 0L)).isSyncing)
        assertFalse(state(syncState = SyncState.Idle).isSyncing)
        assertFalse(state(syncState = SyncState.Cancelled).isSyncing)
        assertFalse(state(syncState = SyncState.Error("x")).isSyncing)
    }

    @Test
    fun `showCancel is true while active and showRetry only on error`() {
        assertTrue(state(syncState = SyncState.Running(0, 1, 0L)).showCancel)
        assertTrue(state(syncState = SyncState.Preparing).showCancel)
        assertFalse(state(syncState = SyncState.Error("x")).showCancel)

        assertTrue(state(syncState = SyncState.Error("boom")).showRetry)
        assertFalse(state(syncState = SyncState.Idle).showRetry)
    }

    @Test
    fun `serverUrl is exposed only from a Running state (Req 5_8)`() {
        assertEquals(
            "http://192.168.1.5:8080",
            state(
                syncState = SyncState.Running(0, 1, 0L, serverUrl = "http://192.168.1.5:8080"),
            ).serverUrl,
        )
        assertNull(state(syncState = SyncState.Idle).serverUrl)
        assertNull(state(syncState = SyncState.Running(0, 1, 0L)).serverUrl)
    }

    @Test
    fun `lastSummary is carried through for idle display (Req 6_3)`() {
        val summary = SyncSummary(
            syncedCount = 3,
            skippedCount = 1,
            removedCount = 0,
            lastSyncAt = 1_700_000_000_000L,
            error = null,
        )
        assertEquals(summary, state(lastSummary = summary).lastSummary)
    }

    // --- Watch stats mapping (Req 7.1, 7.3) -------------------------------------------------

    @Test
    fun `watchStats defaults to null meaning unknown (Req 7_3)`() {
        assertNull(state().watchStats)
    }

    @Test
    fun `watchStats known values are carried through when paired (Req 7_1)`() {
        val stats = WatchStats(fileCount = 42, folderCount = 3, lastSyncAt = 1_700_000_000_000L)
        val mapped = state(isPaired = true, watchStats = stats).watchStats
        assertEquals(stats, mapped)
    }

    @Test
    fun `old watch - STATS only - still shows stats and the phone's own bookmarks (PL-4_6)`() {
        val stats = WatchStats(fileCount = 7, folderCount = 2, lastSyncAt = null)
        val rows = listOf(BookmarkRow("f1", "Dune", "Ch 2 · 01:30", "Phone"))
        val mapped = SyncUiState.from(
            SyncState.Idle, isPaired = true, hasFolders = true, lastSummary = null,
            watchStats = stats, bookmarks = rows,
        )
        assertEquals(stats, mapped.watchStats)
        assertEquals(rows, mapped.bookmarks)
    }

    @Test
    fun `phone bookmarks are shown even when watch stats are unknown (PL-4_6)`() {
        val rows = listOf(BookmarkRow("f1", "Dune", "Finished", "Phone"))
        val mapped = SyncUiState.from(SyncState.Idle, true, true, null, null, rows)
        assertNull(mapped.watchStats)
        assertEquals(rows, mapped.bookmarks)
    }

    @Test
    fun `watchStats is forced to unknown when not paired (Req 7_3)`() {
        val stats = WatchStats(fileCount = 42, folderCount = 3, lastSyncAt = null)
        // Even if stats were somehow supplied, an unpaired state must not surface them as current.
        assertNull(state(isPaired = false, watchStats = stats).watchStats)
    }
}
