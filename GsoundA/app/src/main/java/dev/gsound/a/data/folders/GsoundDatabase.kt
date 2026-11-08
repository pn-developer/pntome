package dev.gsound.a.data.folders

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database (`gsound.db`, design §5.7).
 *
 * Holds the relational, queryable persistence for folders and their cached tracks, surviving
 * app and device restarts (Req 8.1, 8.2). Small structured state (pairing, settings, sync
 * summary) lives in Proto DataStore instead (see `data/pairing`, `data/settings`).
 *
 * Versions:
 * - 1: folders + tracks.
 * - 2: `folders.isAudiobook`, `tracks.fileName` (spec gsound-audiobooks AB-1.4, AB-2.1) — added
 *   by [MIGRATION_1_2] without touching existing rows.
 */
@Database(
    entities = [FolderEntity::class, TrackEntity::class],
    version = 2,
    exportSchema = true
)
abstract class GsoundDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "gsound.db"

        /**
         * v1 → v2: two additive columns with defaults. Existing folders become music folders
         * (`isAudiobook = 0`); existing tracks get an empty `fileName` until the next scan, and
         * a sync always rescans first (spec gsound-playback-fixes PF-4.4).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folders ADD COLUMN isAudiobook INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN fileName TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
