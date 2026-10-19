package dev.gsound.a.domain.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.domain.scan.FolderIdHasher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [AddFolderUseCase] (Req 1.3, 1.4) running on the JVM via Robolectric.
 *
 * Exercises the dedupe guarantee end-to-end against a real in-memory [GsoundDatabase]: the
 * first add of a tree URI yields [AddFolderResult.Added], and re-selecting the same folder
 * yields [AddFolderResult.Duplicate] without growing the list (Req 1.4). Also confirms the
 * persistable read permission is taken on add (Req 1.2).
 */
@RunWith(RobolectricTestRunner::class)
class AddFolderUseCaseTest {

    private lateinit var db: GsoundDatabase
    private lateinit var repository: FolderRepository
    private lateinit var resolver: RecordingContentResolver
    private lateinit var useCase: AddFolderUseCase

    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic")

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, GsoundDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
        resolver = RecordingContentResolver(app, app.contentResolver)
        val context = RecordingResolverContext(app, resolver)
        useCase = AddFolderUseCase(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `adding a new folder persists it and takes a persistable read permission`() = runBlocking {
        val result = useCase.add(treeUri, addedAt = 1_700_000_000_000L)

        assertTrue("expected Added but was $result", result is AddFolderResult.Added)
        val expectedId = FolderIdHasher.hash(treeUri.toString())
        assertEquals(expectedId, (result as AddFolderResult.Added).folder.id)

        // Req 1.3: persisted.
        assertEquals(1, repository.getFolders().size)
        assertEquals(expectedId, repository.getFolders().single().id)

        // Req 1.2: persistable read permission taken for exactly this tree URI.
        assertEquals(1, resolver.taken.size)
        assertEquals(treeUri, resolver.taken.single().uri)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            resolver.taken.single().flags,
        )
    }

    @Test
    fun `re-adding the same tree uri reports Duplicate and does not grow the list`() = runBlocking {
        val first = useCase.add(treeUri, addedAt = 1_700_000_000_000L)
        assertTrue(first is AddFolderResult.Added)

        val second = useCase.add(treeUri, addedAt = 1_700_000_111_111L)

        assertTrue("expected Duplicate but was $second", second is AddFolderResult.Duplicate)
        val expectedId = FolderIdHasher.hash(treeUri.toString())
        assertEquals(expectedId, (second as AddFolderResult.Duplicate).existingId)

        // Req 1.4: list is not duplicated; the original entry's addedAt is preserved.
        val folders = repository.getFolders()
        assertEquals(1, folders.size)
        assertEquals(1_700_000_000_000L, folders.single().addedAt)
    }

    @Test
    fun `duplicate is reported via the pre-insert findFolder short-circuit`() = runBlocking {
        val id = FolderIdHasher.hash(treeUri.toString())
        // Seed the repository so findFolder(id) returns a folder before any insert attempt.
        assertTrue(
            repository.addFolderIfAbsent(
                dev.gsound.a.domain.model.Folder(
                    id = id,
                    treeUri = treeUri.toString(),
                    displayName = "Seeded",
                    trackCount = 7,
                    totalBytes = 123L,
                    addedAt = 1_600_000_000_000L,
                ),
            ),
        )

        val result = useCase.add(treeUri)

        assertTrue("expected Duplicate but was $result", result is AddFolderResult.Duplicate)
        assertEquals(id, (result as AddFolderResult.Duplicate).existingId)
        // Seeded metadata is untouched by the duplicate add.
        assertEquals("Seeded", repository.getFolders().single().displayName)
        assertEquals(7, repository.getFolders().single().trackCount)
    }

    @Test
    fun `each duplicate add still re-takes the idempotent permission`() = runBlocking {
        useCase.add(treeUri)
        useCase.add(treeUri)

        // takePersistableUriPermission is idempotent at the platform level and the use case calls
        // it before the dedupe check, so two adds record two take calls for the same URI.
        assertEquals(2, resolver.taken.size)
        assertTrue(resolver.taken.all { it.uri == treeUri })
    }
}
