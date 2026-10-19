package dev.gsound.a.domain.scan

import dev.gsound.a.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TrackReconciler] (task 5.3) — the pure id-based diff/aggregate used to cache a
 * folder scan incrementally (Req 3.3, 3.5; design §5.2/§7).
 *
 * These exercise the reconcile maths directly on the JVM (no Android, no DB): an unchanged
 * rescan produces no churn, new/changed/deleted files are classified correctly, and the
 * trackCount/totalBytes aggregates (Req 2.1) are summed from the fresh scan.
 */
class TrackReconcilerTest {

    private fun track(id: String, size: Long = 1_000L): Track = Track(
        id = id,
        folderId = "folder-1",
        documentUri = "content://docs/$id",
        relativeFolder = "Rock",
        title = "Title $id",
        artist = null,
        album = null,
        durationMs = 0L,
        sizeBytes = size,
        mime = "audio/mpeg"
    )

    @Test
    fun `first scan with empty cache adds everything and removes nothing`() {
        val scanned = listOf(track("a"), track("b"), track("c"))

        val result = TrackReconciler.reconcile(cached = emptyList(), scanned = scanned)

        assertEquals(scanned, result.toUpsert)
        assertTrue(result.idsToRemove.isEmpty())
        assertEquals(3, result.addedCount)
        assertEquals(0, result.removedCount)
        assertEquals(3, result.trackCount)
    }

    @Test
    fun `unchanged rescan has no removals and no additions`() {
        val cached = listOf(track("a"), track("b"), track("c"))
        // Same ids => unchanged files (id encodes path|size|lastModified, Req 3.5).
        val scanned = listOf(track("a"), track("b"), track("c"))

        val result = TrackReconciler.reconcile(cached = cached, scanned = scanned)

        assertEquals(0, result.addedCount)
        assertEquals(0, result.removedCount)
        assertTrue(result.idsToRemove.isEmpty())
        // Everything is still upserted (idempotent) so the cache mirrors the current contents.
        assertEquals(scanned, result.toUpsert)
        assertEquals(3, result.trackCount)
    }

    @Test
    fun `a new file is added without removing existing cache`() {
        val cached = listOf(track("a"), track("b"))
        val scanned = listOf(track("a"), track("b"), track("c"))

        val result = TrackReconciler.reconcile(cached = cached, scanned = scanned)

        assertEquals(1, result.addedCount)
        assertEquals(0, result.removedCount)
        assertTrue(result.idsToRemove.isEmpty())
        assertEquals(3, result.trackCount)
    }

    @Test
    fun `a deleted file is removed and not counted as added`() {
        val cached = listOf(track("a"), track("b"), track("c"))
        val scanned = listOf(track("a"), track("b"))

        val result = TrackReconciler.reconcile(cached = cached, scanned = scanned)

        assertEquals(0, result.addedCount)
        assertEquals(1, result.removedCount)
        assertEquals(listOf("c"), result.idsToRemove)
        assertEquals(2, result.trackCount)
    }

    @Test
    fun `a changed file upserts the new id and removes the stale one`() {
        // The file at path X had id "a-old"; editing it changes size/lastModified, so the next
        // scan hashes it to a new id "a-new" (Req 3.5). The old id must be reconciled away.
        val cached = listOf(track("a-old"), track("b"))
        val scanned = listOf(track("a-new"), track("b"))

        val result = TrackReconciler.reconcile(cached = cached, scanned = scanned)

        assertEquals(1, result.addedCount) // a-new
        assertEquals(1, result.removedCount) // a-old
        assertEquals(listOf("a-old"), result.idsToRemove)
        assertTrue(result.toUpsert.any { it.id == "a-new" })
        assertEquals(2, result.trackCount)
    }

    @Test
    fun `totalBytes sums the freshly scanned sizes`() {
        val scanned = listOf(track("a", size = 100L), track("b", size = 250L), track("c", size = 50L))

        val result = TrackReconciler.reconcile(cached = emptyList(), scanned = scanned)

        assertEquals(400L, result.totalBytes)
        assertEquals(3, result.trackCount)
    }

    @Test
    fun `empty scan clears the cache and zeroes the aggregates`() {
        val cached = listOf(track("a", size = 100L), track("b", size = 200L))

        val result = TrackReconciler.reconcile(cached = cached, scanned = emptyList())

        assertEquals(listOf("a", "b"), result.idsToRemove)
        assertEquals(2, result.removedCount)
        assertEquals(0, result.addedCount)
        assertEquals(0, result.trackCount)
        assertEquals(0L, result.totalBytes)
        assertTrue(result.toUpsert.isEmpty())
    }

    @Test
    fun `input order does not affect the diff`() {
        val cached = listOf(track("c"), track("a"), track("b"))
        val scanned = listOf(track("b"), track("d"), track("a"))

        val result = TrackReconciler.reconcile(cached = cached, scanned = scanned)

        assertEquals(1, result.addedCount) // d
        assertEquals(listOf("c"), result.idsToRemove) // only c is gone
        assertEquals(3, result.trackCount)
    }
}
