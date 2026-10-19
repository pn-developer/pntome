package dev.gsound.a.data.folders

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
