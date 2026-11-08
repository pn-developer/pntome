package dev.pntome.app.domain.bookmarks

import dev.pntome.app.data.folders.BookmarkDao
import dev.pntome.app.data.folders.BookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkSyncStateTest {
    private fun bm(id: String, at: Long, sent: Long) =
        BookmarkEntity(id, "n", "t", 0, 1, false, at, "phone", sent)

    private class Dao(val rows: MutableMap<String, BookmarkEntity>) : BookmarkDao {
        override suspend fun get(folderId: String) = rows[folderId]
        override suspend fun getAllByRecency() = rows.values.toList()
        override fun observeAll(): Flow<List<BookmarkEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun upsert(bookmark: BookmarkEntity) { rows[bookmark.folderId] = bookmark }
        override suspend fun delete(folderId: String) { rows.remove(folderId) }
        override suspend fun markSent(folderId: String, lastSentAt: Long) {
            rows[folderId] = rows.getValue(folderId).copy(lastSentAt = lastSentAt)
        }
    }

    @Test
    fun needsSending_onlyWhenNewerThanLastSent() {
        assertTrue(BookmarkSyncState.needsSending(bm("a", 10, 0)))
        assertTrue(BookmarkSyncState.needsSending(bm("a", 10, 9)))
        assertFalse(BookmarkSyncState.needsSending(bm("a", 10, 10)))
        assertFalse(BookmarkSyncState.needsSending(bm("a", 10, 11)))
        assertEquals(listOf("a"), BookmarkSyncState.pending(listOf(bm("a", 10, 0), bm("b", 5, 5))).map { it.folderId })
    }

    @Test
    fun markSent_recordsAtAndKeepsNewerWrite() = runBlocking {
        val dao = Dao(mutableMapOf("a" to bm("a", 10, 0)))
        BookmarkSyncState.markSent(dao, bm("a", 10, 0))
        assertEquals(10L, dao.rows["a"]!!.lastSentAt)
        // A newer recorder write after the snapshot stays pending.
        dao.rows["a"] = bm("a", 20, 10)
        BookmarkSyncState.markSent(dao, bm("a", 10, 0))
        assertTrue(BookmarkSyncState.needsSending(dao.rows.getValue("a")))
        // Missing row is a no-op.
        BookmarkSyncState.markSent(dao, bm("zz", 1, 0))
    }
}
