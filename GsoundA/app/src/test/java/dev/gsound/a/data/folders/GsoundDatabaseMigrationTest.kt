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

    /** Create the v1 schema exactly as exported, with Room's master table + v1 identity hash. */
    private fun createVersion1(seed: (SQLiteDatabase) -> Unit) {
        val schemaFile = File("schemas/dev.gsound.a.data.folders.GsoundDatabase/1.json")
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
            sql.version = 1
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
            .addMigrations(GsoundDatabase.MIGRATION_1_2)
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
}
