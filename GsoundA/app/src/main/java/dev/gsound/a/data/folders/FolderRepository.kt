package dev.gsound.a.data.folders

import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the persisted folder list and cached track metadata.
 *
 * Exposes reactive [Flow] reads over domain models so the UI restores and reflects persisted
 * state (Req 8.1, 8.2), and `suspend` writes for mutations. Translates between Room entities
 * and the framework-free domain models so callers never see persistence types.
 */
@Singleton
class FolderRepository @Inject constructor(
    private val folderDao: FolderDao,
    private val trackDao: TrackDao
) {

    /** Observe the persisted folder list, newest first. */
    fun observeFolders(): Flow<List<Folder>> =
        folderDao.observeAll().map { entities -> entities.map(FolderEntity::toDomain) }

    /** Observe a single folder by id, or null if removed. */
    fun observeFolder(id: String): Flow<Folder?> =
        folderDao.observeById(id).map { it?.toDomain() }

    /** Observe the cached tracks for a folder. */
    fun observeTracks(folderId: String): Flow<List<Track>> =
        trackDao.observeByFolder(folderId).map { entities -> entities.map(TrackEntity::toDomain) }

    /** Observe every cached track across all folders (e.g. to build a sync manifest). */
    fun observeAllTracks(): Flow<List<Track>> =
        trackDao.observeAll().map { entities -> entities.map(TrackEntity::toDomain) }

    /** One-shot snapshot of all folders. */
    suspend fun getFolders(): List<Folder> =
        folderDao.getAll().map(FolderEntity::toDomain)

    /** One-shot lookup used for dedupe checks before adding a folder (Req 1.4). */
    suspend fun findFolder(id: String): Folder? =
        folderDao.findById(id)?.toDomain()

    /** True when a folder with [id] is already persisted. */
    suspend fun folderExists(id: String): Boolean =
        folderDao.findById(id) != null

    /** One-shot snapshot of the cached tracks for a folder. */
    suspend fun getTracks(folderId: String): List<Track> =
        trackDao.getByFolder(folderId).map(TrackEntity::toDomain)

    /** One-shot snapshot of every cached track. */
    suspend fun getAllTracks(): List<Track> =
        trackDao.getAll().map(TrackEntity::toDomain)

    /** Resolve a single track by its stable id (e.g. for a `/media/{trackId}` request). */
    suspend fun findTrack(id: String): Track? =
        trackDao.findById(id)?.toDomain()

    /** Insert or update a folder; keyed on stable id so re-adds dedupe (Req 1.4). */
    suspend fun upsertFolder(folder: Folder) =
        folderDao.upsert(folder.toEntity())

    /**
     * Add a folder only if it is not already persisted.
     *
     * @return true when the folder was newly inserted, false when it already existed (duplicate).
     */
    suspend fun addFolderIfAbsent(folder: Folder): Boolean =
        folderDao.insertIgnore(folder.toEntity()) != -1L

    /** Update a folder's scan aggregates only (other columns untouched). */
    suspend fun updateFolderAggregates(id: String, trackCount: Int, totalBytes: Long) =
        folderDao.updateAggregates(id, trackCount, totalBytes)

    /** Mark/unmark a folder as an audiobook (spec gsound-audiobooks AB-1.2). */
    suspend fun setAudiobook(id: String, isAudiobook: Boolean) =
        folderDao.setAudiobook(id, isAudiobook)

    /** Remove a folder and its cached tracks (cascade via the entity foreign key, Req 2.2). */
    suspend fun removeFolder(id: String) =
        folderDao.deleteById(id)

    /** Replace the cached tracks for a folder with a fresh scan result. */
    suspend fun replaceTracks(folderId: String, tracks: List<Track>) {
        trackDao.deleteByFolder(folderId)
        trackDao.upsertAll(tracks.map(Track::toEntity))
    }

    /** Insert or update cached tracks, deduping on stable id across scans (Req 3.5). */
    suspend fun upsertTracks(tracks: List<Track>) =
        trackDao.upsertAll(tracks.map(Track::toEntity))

    /** Remove cached tracks by id (e.g. reconciling files deleted since the last scan). */
    suspend fun removeTracks(ids: List<String>) =
        trackDao.deleteByIds(ids)
}
