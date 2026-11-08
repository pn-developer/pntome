package dev.gsound.a.data.folders

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Room v1 → v2 migration on the JVM (spec gsound-audiobooks AB-1.4).
 *
 * Builds a REAL version-1 database from the exported `schemas/.../1.json` (the same create
 * statements Room shipped in v1, plus its identity hash), seeds rows, then opens it with the
 * production builder + [GsoundDatabase.MIGRATION_1_2]. Room runs the migration and validates the
 * resulting tables against the v2 entities on open — a missing/mistyped column or default fails
 * this test. (The instrumented variant with MigrationTestHelper lives in androidTest.)
 */
@RunWith(RobolectricTestRunner::class)
class GsoundDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context
    private var db: GsoundDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    private fun createVersion1(seed: (SQLiteDatabase) -> Unit) = createVersion(1, seed)

    /** Create the given version's schema exactly as exported, with Room's master table + hash. */
    private fun createVersion(version: Int, seed: (SQLiteDatabase) -> Unit) {
        val schemaFile = File("schemas/dev.gsound.a.data.folders.GsoundDatabase/$version.json")
        val database = JSONObject(schemaFile.readText()).getJSONObject("database")
        val path = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    sql.execSQL(
                        indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)
                    )
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            seed(sql)
            sql.version = version
        }
    }

    @Test
    fun `migrate 1 to 2 keeps rows, applies defaults and matches the v2 schema`() = runBlocking {
        createVersion1 { sql ->
            sql.execSQL(
                "INSERT INTO folders (id, treeUri, displayName, trackCount, totalBytes, addedAt) " +
                    "VALUES ('f1', 'content://tree/x', 'Dune', 2, 300, 1)"
            )
            sql.execSQL(
                "INSERT INTO tracks (id, folderId, documentUri, relativeFolder, title, artist, " +
                    "album, durationMs, sizeBytes, mime) VALUES ('t1', 'f1', 'content://d/t1', " +
                    "'Dune', 'Chapter 1', NULL, NULL, 1800000, 150, 'audio/mpeg')"
            )
        }

        val migrated = Room.databaseBuilder(context, GsoundDatabase::class.java, dbName)
            .addMigrations(GsoundDatabase.MIGRATION_1_2, GsoundDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

        // First access opens the DB: migration + schema validation happen here.
        val folder = migrated.folderDao().findById("f1")!!
        assertEquals("Dune", folder.displayName)
        assertEquals(2, folder.trackCount)
        assertFalse(folder.isAudiobook)

        val track = migrated.trackDao().findById("t1")!!
        assertEquals("Chapter 1", track.title)
        assertEquals("", track.fileName)

        // The new column is writable through the v2 DAO.
        migrated.folderDao().setAudiobook("f1", true)
        assertEquals(true, migrated.folderDao().findById("f1")!!.isAudiobook)
    }

    /** Spec gsound-player PL-7.6: existing data kept on upgrade; bookmarks table created. */
    @Test
    fun `migrate 2 to 3 keeps folders and tracks and creates a usable bookmarks table`() = runBlocking {
        createVersion(2) { sql ->
            sql.execSQL(
                "INSERT INTO folders (id, treeUri, displayName, trackCount, totalBytes, addedAt, " +
                    "isAudiobook) VALUES ('f1', 'content://tree/x', 'Dune', 2, 300, 1, 1)"
            )
            sql.execSQL(
                "INSERT INTO tracks (id, folderId, documentUri, relativeFolder, title, artist, " +
                    "album, durationMs, sizeBytes, mime, fileName) VALUES ('t1', 'f1', " +
                    "'content://d/t1', 'Dune', 'Chapter 1', NULL, NULL, 1800000, 150, " +
                    "'audio/mpeg', '01.mp3')"
            )
        }

        val migrated = Room.databaseBuilder(context, GsoundDatabase::class.java, dbName)
            .addMigrations(GsoundDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

        // Opening validates the migrated schema against the v3 entities (matches 3.json).
        val folder = migrated.folderDao().findById("f1")!!
        assertEquals("Dune", folder.displayName)
        assertEquals(true, folder.isAudiobook)
        val track = migrated.trackDao().findById("t1")!!
        assertEquals("Chapter 1", track.title)
        assertEquals("01.mp3", track.fileName)

        // The new table starts empty and is usable through the DAO.
        assertNull(migrated.bookmarkDao().get("f1"))
        val bookmark = BookmarkEntity("f1", "Dune", "t1", 0, 42, false, 1000L, "phone")
        migrated.bookmarkDao().upsert(bookmark)
        assertEquals(bookmark, migrated.bookmarkDao().get("f1"))
    }

    @Test
    fun `migrate 1 to 3 through the full chain keeps rows`() = runBlocking {
        createVersion1 { sql ->
            sql.execSQL(
                "INSERT INTO folders (id, treeUri, displayName, trackCount, totalBytes, addedAt) " +
                    "VALUES ('f1', 'content://tree/x', 'Dune', 0, 0, 1)"
            )
        }
        val migrated = Room.databaseBuilder(context, GsoundDatabase::class.java, dbName)
            .addMigrations(GsoundDatabase.MIGRATION_1_2, GsoundDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { db = it }
        assertEquals("Dune", migrated.folderDao().findById("f1")!!.displayName)
        assertNull(migrated.bookmarkDao().get("f1"))
    }
}
