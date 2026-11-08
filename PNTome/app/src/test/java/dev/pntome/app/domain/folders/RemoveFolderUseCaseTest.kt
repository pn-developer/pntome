package dev.pntome.app.domain.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.data.folders.PNTomeDatabase
import dev.pntome.app.domain.model.Folder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [RemoveFolderUseCase] (Req 2.2) running on the JVM via Robolectric.
 *
 * Verifies the removal lifecycle against a real in-memory [PNTomeDatabase]: removing a folder
 * releases its persisted read permission for the stored tree URI and deletes the row; a
 * [SecurityException] while releasing (grant already dropped) still removes the folder; and
 * removing an unknown id reports [RemoveFolderResult.NotFound] without touching permissions.
 */
@RunWith(RobolectricTestRunner::class)
class RemoveFolderUseCaseTest {

    private lateinit var db: PNTomeDatabase
    private lateinit var repository: FolderRepository
    private lateinit var resolver: RecordingContentResolver
    private lateinit var useCase: RemoveFolderUseCase

    private val treeUriString =
        "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    private val treeUri: Uri = Uri.parse(treeUriString)

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, PNTomeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
        resolver = RecordingContentResolver(app, app.contentResolver)
        val context = RecordingResolverContext(app, resolver)
        useCase = RemoveFolderUseCase(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedFolder(id: String = "folder-1"): Folder {
        val folder = Folder(
            id = id,
            treeUri = treeUriString,
            displayName = "Music",
            trackCount = 3,
            totalBytes = 4_096L,
            addedAt = 1_700_000_000_000L,
        )
        repository.upsertFolder(folder)
        return folder
    }

    @Test
    fun `remove releases the tree uri permission then deletes the row`() = runBlocking {
        val folder = seedFolder()

        val result = useCase.remove(folder.id)

        assertEquals(RemoveFolderResult.Removed, result)

        // Req 2.2: released the persisted read grant for exactly the folder's tree URI.
        assertEquals(1, resolver.released.size)
        assertEquals(treeUri, resolver.released.single().uri)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            resolver.released.single().flags,
        )

        // Row removed.
        assertNull(repository.findFolder(folder.id))
    }

    @Test
    fun `a SecurityException while releasing does not prevent removal`() = runBlocking {
        val folder = seedFolder()
        resolver.throwOnRelease = true

        val result = useCase.remove(folder.id)

        // Best-effort release: removal still succeeds (Req 2.2).
        assertEquals(RemoveFolderResult.Removed, result)
        assertTrue("release should not have been recorded", resolver.released.isEmpty())
        assertNull(repository.findFolder(folder.id))
    }

    @Test
    fun `removing an unknown id returns NotFound without releasing any permission`() = runBlocking {
        val result = useCase.remove("does-not-exist")

        assertEquals(RemoveFolderResult.NotFound, result)
        assertTrue("no permission should be released", resolver.released.isEmpty())
    }
}
