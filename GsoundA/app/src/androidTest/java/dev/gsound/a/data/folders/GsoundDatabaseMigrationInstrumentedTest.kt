package dev.gsound.a.data.folders

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1 → v2 migration on a device with Room's [MigrationTestHelper] (spec gsound-audiobooks
 * AB-1.4). Schemas come from app/schemas via the androidTest assets source set.
 */
@RunWith(AndroidJUnit4::class)
class GsoundDatabaseMigrationInstrumentedTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GsoundDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2KeepsRowsAndAppliesDefaults() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO folders (id, treeUri, displayName, trackCount, totalBytes, addedAt) " +
                    "VALUES ('f1', 'content://tree/x', 'Dune', 2, 300, 1)"
            )
            db.execSQL(
                "INSERT INTO tracks (id, folderId, documentUri, relativeFolder, title, artist, " +
                    "album, durationMs, sizeBytes, mime) VALUES ('t1', 'f1', 'content://d/t1', " +
                    "'Dune', 'Chapter 1', NULL, NULL, 1800000, 150, 'audio/mpeg')"
            )
        }

        helper.runMigrationsAndValidate(dbName, 2, true, GsoundDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT isAudiobook FROM folders WHERE id = 'f1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT fileName FROM tracks WHERE id = 't1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("", c.getString(0))
            }
        }
    }
}
