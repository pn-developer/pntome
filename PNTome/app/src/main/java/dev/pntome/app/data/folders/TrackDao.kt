package dev.pntome.app.data.folders

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the `tracks` table.
 *
 * Reads return [Flow] for reactive UI (design §5.7). Writes use upsert so re-scanning a folder
 * refreshes cached tracks in place, keyed on the stable [TrackEntity.id] (Req 3.5), which also
 * dedupes unchanged tracks across scans.
 */
@Dao
interface TrackDao {

    /** Observe every cached track. */
    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    /** Observe the tracks belonging to a single folder. */
    @Query("SELECT * FROM tracks WHERE folderId = :folderId")
    fun observeByFolder(folderId: String): Flow<List<TrackEntity>>

    /** One-shot lookup of a single track, e.g. to resolve a `/media/{trackId}` request. */
    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun findById(id: String): TrackEntity?

    /** One-shot snapshot of the tracks for a folder, used when building the sync manifest. */
    @Query("SELECT * FROM tracks WHERE folderId = :folderId")
    suspend fun getByFolder(folderId: String): List<TrackEntity>

    /** One-shot snapshot of all cached tracks. */
    @Query("SELECT * FROM tracks")
    suspend fun getAll(): List<TrackEntity>

    /** Insert or replace a batch of tracks (dedupe/refresh by stable id). */
    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    /** Insert or replace a single track. */
    @Upsert
    suspend fun upsert(track: TrackEntity)

    /** Remove every cached track for a folder (e.g. before a full rescan). */
    @Query("DELETE FROM tracks WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: String)

    /** Remove specific tracks by id (e.g. reconciling files deleted since the last scan). */
    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
