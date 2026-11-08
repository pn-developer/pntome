package dev.pntome.app.data.folders

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Data access for the `bookmarks` table (design §4.1). */
@Dao
interface BookmarkDao {

    /** One-shot lookup of the bookmark for a folder. */
    @Query("SELECT * FROM bookmarks WHERE folderId = :folderId")
    suspend fun get(folderId: String): BookmarkEntity?

    /** Every bookmark, most recently updated first ("Recently played", PL-2.5). */
    @Query("SELECT * FROM bookmarks ORDER BY updatedAt DESC, folderId ASC")
    suspend fun getAllByRecency(): List<BookmarkEntity>

    /** Observe every bookmark. */
    @Query("SELECT * FROM bookmarks")
    fun observeAll(): Flow<List<BookmarkEntity>>

    /** Insert or replace a bookmark (one per folder). */
    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    /** Remove the bookmark of a folder (folder delete / un-mark, PL-3.6). */
    @Query("DELETE FROM bookmarks WHERE folderId = :folderId")
    suspend fun delete(folderId: String)

    /** Record the `updatedAt` last exchanged with the watch. */
    @Query("UPDATE bookmarks SET lastSentAt = :lastSentAt WHERE folderId = :folderId")
    suspend fun markSent(folderId: String, lastSentAt: Long)
}
