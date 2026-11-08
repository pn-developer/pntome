package dev.gsound.a.service

import dev.gsound.a.data.folders.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors the watch's `testResolveRewindsAndFallsBack` (PL-3.7, PL-2.3, PL-6.6). */
class BookmarkResolverTest {
    private val ids = listOf("c1", "c2", "c3")

    private fun bm(track: String, pos: Int, idx: Int, fin: Boolean = false) =
        BookmarkEntity("f", "Book", track, idx, pos, fin, 100L, "phone")

    @Test
    fun `rewinds 5 seconds in the bookmarked chapter`() {
        assertEquals(ResumePoint(1, 115_000L), BookmarkResolver.resolve(bm("c2", 120, 1), ids))
    }

    @Test
    fun `rewind clamps at zero`() {
        assertEquals(ResumePoint(1, 0L), BookmarkResolver.resolve(bm("c2", 3, 1), ids))
    }

    @Test
    fun `missing chapter falls back to same index from zero`() {
        assertEquals(ResumePoint(1, 0L), BookmarkResolver.resolve(bm("gone", 500, 1), ids))
    }

    @Test
    fun `missing chapter with index out of range yields none`() {
        assertNull(BookmarkResolver.resolve(bm("gone", 500, 3), ids))
    }

    @Test
    fun `finished, absent bookmark or empty chapters yield none`() {
        assertNull(BookmarkResolver.resolve(bm("c3", 0, 2, fin = true), ids))
        assertNull(BookmarkResolver.resolve(null, ids))
        assertNull(BookmarkResolver.resolve(bm("c1", 10, 0), emptyList()))
    }
}
