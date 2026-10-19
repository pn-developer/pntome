package dev.gsound.a.data.folders

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's Room database (`gsound.db`, design §5.7).
 *
 * Holds the relational, queryable persistence for folders and their cached tracks, surviving
 * app and device restarts (Req 8.1, 8.2). Small structured state (pairing, settings, sync
 * summary) lives in Proto DataStore instead (see `data/pairing`, `data/settings`).
 */
@Database(
    entities = [FolderEntity::class, TrackEntity::class],
    version = 1,
    exportSchema = true
)
abstract class GsoundDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "gsound.db"
    }
}
