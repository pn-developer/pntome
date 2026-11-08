package dev.pntome.app.data.folders

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the `folders` table.
 *
 * Reads return [Flow] so the UI reactively reflects persisted state (design §5.7) and restores
 * the folder list on launch (Req 8.2). Writes are `suspend` and performed off the main thread.
 */
@Dao
interface FolderDao {

    /** Observe all folders, newest first. Emits a fresh list whenever the table changes. */
    @Query("SELECT * FROM folders ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FolderEntity>>

    /** Observe a single folder by id, or null if it has been removed. */
    @Query("SELECT * FROM folders WHERE id = :id")
    fun observeById(id: String): Flow<FolderEntity?>

    /** One-shot lookup used for dedupe checks (Req 1.4) and sync manifest building. */
    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun findById(id: String): FolderEntity?

    /** One-shot snapshot of all folders. */
    @Query("SELECT * FROM folders ORDER BY addedAt DESC")
    suspend fun getAll(): List<FolderEntity>

    /**
     * Insert or replace a folder. Keyed on the stable [FolderEntity.id] so re-adding the same
     * tree URI updates metadata in place rather than creating a duplicate row (Req 1.4).
     */
    @Upsert
    suspend fun upsert(folder: FolderEntity)

    /** Insert, ignoring the row if a folder with the same id already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(folder: FolderEntity): Long

    /** Remove a folder; cascades to its cached tracks via the entity foreign key. */
    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Update only the scan aggregates. Used by the rescan instead of a full-row upsert so a flag
     * changed while the (slow) scan ran — e.g. the audiobook toggle — is never overwritten with
     * the value read before the scan.
     */
    @Query("UPDATE folders SET trackCount = :trackCount, totalBytes = :totalBytes WHERE id = :id")
    suspend fun updateAggregates(id: String, trackCount: Int, totalBytes: Long)

    /** Set only the audiobook flag, leaving counts/metadata untouched (AB-1.2). */
    @Query("UPDATE folders SET isAudiobook = :isAudiobook WHERE id = :id")
    suspend fun setAudiobook(id: String, isAudiobook: Boolean)

    /** Delete the bookmark of a folder; used inside the transactional helpers below (PL-3.6). */
    @Query("DELETE FROM bookmarks WHERE folderId = :folderId")
    suspend fun deleteBookmarkFor(folderId: String)

    /** Remove a folder and its bookmark atomically (PL-3.6). */
    @Transaction
    suspend fun deleteWithBookmark(id: String) {
        deleteBookmarkFor(id)
        deleteById(id)
    }

    /** Set the audiobook flag; un-marking also drops the bookmark, atomically (PL-3.6). */
    @Transaction
    suspend fun setAudiobookAndPruneBookmark(id: String, isAudiobook: Boolean) {
        if (!isAudiobook) deleteBookmarkFor(id)
        setAudiobook(id, isAudiobook)
    }
}
