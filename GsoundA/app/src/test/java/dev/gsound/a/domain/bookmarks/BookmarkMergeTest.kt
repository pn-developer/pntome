package dev.gsound.a.domain.bookmarks

import dev.gsound.a.data.folders.BookmarkEntity
import dev.gsound.a.net.garmin.messages.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [BookmarkMerge] (spec gsound-player 9.3, PL-6.1–6.5, design §8).
 *
 * The numbered cases C01..C13 and their constants (NOW, L) are IDENTICAL to
 * GsoundG/source/test/BookmarkMergeTests.mc (testBmMergeC01.. etc.); keep both files in sync.
 *
 *   NOW = 10000, LIMIT = NOW + 300 = 10300, L = 5000 unless stated.
 *   C01 no local                       in=4000          -> take 4000
 *   C02 incoming newer                 L,  in=L+1       -> take L+1
 *   C03 equal keeps local              L,  in=L         -> keep
 *   C04 older keeps local              L,  in=L-1       -> keep
 *   C05 far future, local < LIMIT      L,  in=NOW+1e6   -> take LIMIT
 *   C06 far future, local == LIMIT     LIMIT, in=NOW+1e6 -> keep
 *   C07 far future, local > LIMIT      LIMIT+100, far   -> keep
 *   C08 finished local, newer unfin.   L fin, in=L+1000 unfin -> take
 *   C09 older finished incoming        L unfin, in=L-1000 fin -> keep
 *   C10 idempotent repeat              take, apply as local, same incoming again -> keep
 *   C11 boundary NOW+300 exactly       L,  in=LIMIT     -> take LIMIT
 *   C12 NOW+301 clamped                L,  in=LIMIT+1   -> take LIMIT
 *   C13 no local, future at            null, in=NOW+1e6 -> take LIMIT
 */
class BookmarkMergeTest {
    private val now = 10_000L
    private val limit = now + 300
    private val l = 5_000L
    private val far = now + 1_000_000

    private fun local(at: Long, fin: Boolean = false) = BookmarkEntity(
        folderId = "f1", folderName = "Book", trackId = "tl", chapterIndex = 1, positionSec = 11,
        finished = fin, updatedAt = at, source = "phone", lastSentAt = 0,
    )

    private fun incoming(at: Long, fin: Boolean = false) = Bookmark(
        pairingId = "p", folder = "Book", fid = "f1", trk = "ti", idx = 4, pos = 77, fin = fin, at = at,
    )

    @Test fun c01_noLocal() {
        assertEquals(4000L, BookmarkMerge.decide(null, 4000, now))
        assertEquals(4000L, BookmarkMerge.merge(null, incoming(4000), "f1", "Book", now)?.updatedAt)
    }

    @Test fun c02_incomingNewer() {
        assertEquals(l + 1, BookmarkMerge.decide(l, l + 1, now))
        assertNotNull(BookmarkMerge.merge(local(l), incoming(l + 1), "f1", "Book", now))
    }

    @Test fun c03_equalKeepsLocal() {
        assertNull(BookmarkMerge.decide(l, l, now))
        assertNull(BookmarkMerge.merge(local(l), incoming(l), "f1", "Book", now))
    }

    @Test fun c04_olderKeepsLocal() {
        assertNull(BookmarkMerge.decide(l, l - 1, now))
        assertNull(BookmarkMerge.merge(local(l), incoming(l - 1), "f1", "Book", now))
    }

    @Test fun c05_farFutureClampedTakenWhenLocalBelowLimit() {
        assertEquals(limit, BookmarkMerge.decide(l, far, now))
        assertEquals(limit, BookmarkMerge.merge(local(l), incoming(far), "f1", "Book", now)?.updatedAt)
    }

    @Test fun c06_farFutureClampedKeptWhenLocalEqualsLimit() {
        assertNull(BookmarkMerge.decide(limit, far, now))
        assertNull(BookmarkMerge.merge(local(limit), incoming(far), "f1", "Book", now))
    }

    @Test fun c07_farFutureClampedKeptWhenLocalAboveLimit() {
        assertNull(BookmarkMerge.decide(limit + 100, far, now))
        assertNull(BookmarkMerge.merge(local(limit + 100), incoming(far), "f1", "Book", now))
    }

    @Test fun c08_finishedLocalVsNewerUnfinishedIncomingTakes() {
        assertEquals(l + 1000, BookmarkMerge.decide(l, l + 1000, now))
        val m = BookmarkMerge.merge(local(l, fin = true), incoming(l + 1000, fin = false), "f1", "Book", now)
        assertNotNull(m)
        assertEquals(false, m!!.finished)
    }

    @Test fun c09_olderFinishedIncomingVsUnfinishedLocalKeeps() {
        assertNull(BookmarkMerge.decide(l, l - 1000, now))
        assertNull(BookmarkMerge.merge(local(l, fin = false), incoming(l - 1000, fin = true), "f1", "Book", now))
    }

    @Test fun c10_idempotentRepeat() {
        val at = BookmarkMerge.decide(l, l + 50, now)
        assertEquals(l + 50, at)
        assertNull(BookmarkMerge.decide(at, l + 50, now))
    }

    @Test fun c11_boundaryNowPlus300Exactly() {
        assertEquals(limit, BookmarkMerge.decide(l, limit, now))
        assertEquals(limit, BookmarkMerge.merge(local(l), incoming(limit), "f1", "Book", now)?.updatedAt)
    }

    @Test fun c12_nowPlus301ClampedToNowPlus300() {
        assertEquals(limit, BookmarkMerge.decide(l, limit + 1, now))
        assertEquals(limit, BookmarkMerge.merge(local(l), incoming(limit + 1), "f1", "Book", now)?.updatedAt)
    }

    @Test fun c13_noLocalWithFutureAt() {
        assertEquals(limit, BookmarkMerge.decide(null, far, now))
        assertEquals(limit, BookmarkMerge.merge(null, incoming(far), "f1", "Book", now)?.updatedAt)
    }

    // ---- entity-level mapping (Kotlin only) ----

    @Test fun merge_mapsIncomingToEntity() {
        val m = BookmarkMerge.merge(local(l), incoming(l + 5, fin = true), "folder-id", "Name", now)!!
        assertEquals(
            BookmarkEntity(
                folderId = "folder-id", folderName = "Name", trackId = "ti", chapterIndex = 4,
                positionSec = 77, finished = true, updatedAt = l + 5, source = "watch", lastSentAt = l + 5,
            ),
            m,
        )
        assertEquals("watch", m.source)
        assertEquals(m.updatedAt, m.lastSentAt)
    }

    @Test fun merge_clampedAtIsStoredAndSent() {
        val m = BookmarkMerge.merge(null, incoming(far), "f1", "Book", now)!!
        assertEquals(limit, m.updatedAt)
        assertEquals(limit, m.lastSentAt)
    }

    @Test fun merge_idempotentAtEntityLevel() {
        val first = BookmarkMerge.merge(local(l), incoming(l + 5), "f1", "Book", now)!!
        assertNull(BookmarkMerge.merge(first, incoming(l + 5), "f1", "Book", now))
        // also for a clamped far-future repeat
        val clamped = BookmarkMerge.merge(local(l), incoming(far), "f1", "Book", now)!!
        assertNull(BookmarkMerge.merge(clamped, incoming(far), "f1", "Book", now))
    }
}
