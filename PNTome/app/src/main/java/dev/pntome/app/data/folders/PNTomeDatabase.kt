package dev.pntome.app.data.folders

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The app's Room database (`pntome.db`, design §5.7).
 *
 * Holds the relational, queryable persistence for folders and their cached tracks, surviving
 * app and device restarts (Req 8.1, 8.2). Small structured state (pairing, settings, sync
 * summary) lives in Proto DataStore instead (see `data/pairing`, `data/settings`).
 *
 * Versions:
 * - 1: folders + tracks.
 * - 2: `folders.isAudiobook`, `tracks.fileName` (spec gsound-audiobooks AB-1.4, AB-2.1) — added
 *   by [MIGRATION_1_2] without touching existing rows.
 * - 3: `bookmarks` table (spec gsound-player PL-3), created by [MIGRATION_2_3].
 */
@Database(
    entities = [FolderEntity::class, TrackEntity::class, BookmarkEntity::class],
    version = 3,
    exportSchema = true
)
abstract class PNTomeDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun trackDao(): TrackDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val NAME = "pntome.db"

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

        /** v2 → v3: new `bookmarks` table; existing tables are untouched. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`folderId` TEXT NOT NULL, " +
                        "`folderName` TEXT NOT NULL, " +
                        "`trackId` TEXT NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`positionSec` INTEGER NOT NULL, " +
                        "`finished` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`lastSentAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`folderId`))"
                )
            }
        }
    }
}
