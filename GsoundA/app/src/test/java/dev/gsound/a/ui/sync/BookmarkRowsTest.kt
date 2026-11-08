package dev.gsound.a.ui.sync

import dev.gsound.a.data.folders.BookmarkEntity
import dev.gsound.a.domain.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkRowsTest {
    private fun folder(id: String, name: String, book: Boolean = true) =
        Folder(id, "content://$id", name, 1, 1L, 1L, isAudiobook = book)

    private fun bm(id: String, ch: Int = 0, sec: Int = 0, fin: Boolean = false, src: String = "phone", at: Long = 1) =
        BookmarkEntity(id, "x", "t", ch, sec, fin, at, src)

    @Test fun formatsChapterAndPosition() {
        val r = BookmarkRows.from(listOf(bm("a", 2, 754)), listOf(folder("a", "Dune"))).single()
        assertEquals("Dune", r.title)
        assertEquals("Ch 3 · 12:34", r.position)
        assertEquals("Phone", r.device)
    }

    @Test fun hoursUseHMmSs() {
        assertEquals("1:01:01", BookmarkRows.formatPosition(3661))
        assertEquals("00:00", BookmarkRows.formatPosition(-5))
        assertEquals("59:59", BookmarkRows.formatPosition(3599))
    }

    @Test fun watchAndFinished() {
        val r = BookmarkRows.from(listOf(bm("a", fin = true, src = "watch")), listOf(folder("a", "Dune"))).single()
        assertEquals("Finished", r.position)
        assertEquals("Watch", r.device)
    }

    @Test fun skipsStaleAndNonBookRowsAndSortsByRecency() {
        val rows = BookmarkRows.from(
            listOf(bm("gone"), bm("music"), bm("a", at = 1), bm("b", at = 9)),
            listOf(folder("a", "A"), folder("b", "B"), folder("music", "M", book = false)),
        )
        assertEquals(listOf("b", "a"), rows.map { it.folderId })
    }

    @Test fun emptyInputs() {
        assertTrue(BookmarkRows.from(emptyList(), emptyList()).isEmpty())
    }
}
