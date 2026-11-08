package dev.pntome.app.ui.folders

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.data.folders.PNTomeDatabase
import dev.pntome.app.domain.folders.AddFolderUseCase
import dev.pntome.app.domain.folders.RemoveFolderUseCase
import dev.pntome.app.domain.folders.ScanAndCacheUseCase
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.scan.ScanResult
import dev.pntome.app.domain.scan.TrackScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [FolderListViewModel] audiobook toggle (spec gsound-audiobooks AB-1.1, AB-1.2): the toggle
 * persists on the folder and the UI item reflects it. Real in-memory Room with synchronous
 * executors so each write has completed when the call returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FolderListViewModelTest {

    private lateinit var db: PNTomeDatabase
    private lateinit var repository: FolderRepository
    private lateinit var viewModel: FolderListViewModel
    private val folderId = "folder-1"

    private object NoopScanner : TrackScanner {
        override suspend fun scanToResult(
            treeUri: android.net.Uri,
            folderId: String,
            rootName: String,
            settings: dev.pntome.app.domain.model.AppSettings?
        ): ScanResult = ScanResult(emptyList(), 0)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PNTomeDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
        viewModel = FolderListViewModel(
            folderRepository = repository,
            addFolderUseCase = AddFolderUseCase(context, repository),
            removeFolderUseCase = RemoveFolderUseCase(context, repository),
            scanAndCacheUseCase = ScanAndCacheUseCase(NoopScanner, repository),
        )
        runBlocking {
            repository.upsertFolder(
                Folder(
                    id = folderId,
                    treeUri = "content://tree/primary%3ABooks%2FDune",
                    displayName = "Dune",
                    trackCount = 3,
                    totalBytes = 300L,
                    addedAt = 1L
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `new folders are music by default`() = runBlocking {
        assertFalse(repository.findFolder(folderId)!!.isAudiobook)
        val item = viewModel.uiState.first { it.folders.isNotEmpty() }.folders.single()
        assertFalse(item.isAudiobook)
    }

    @Test
    fun `toggling marks and unmarks the folder and the item reflects it`() = runBlocking {
        viewModel.onAudiobookToggled(folderId, true)
        assertTrue(repository.findFolder(folderId)!!.isAudiobook)
        assertTrue(viewModel.uiState.first { it.folders.singleOrNull()?.isAudiobook == true }
            .folders.single().isAudiobook)

        viewModel.onAudiobookToggled(folderId, false)
        assertFalse(repository.findFolder(folderId)!!.isAudiobook)
    }

    @Test
    fun `toggle keeps the folder's counts`() = runBlocking {
        viewModel.onAudiobookToggled(folderId, true)
        val folder = repository.findFolder(folderId)!!
        assertTrue(folder.isAudiobook)
        assertEquals(3, folder.trackCount)
        assertEquals(300L, folder.totalBytes)
    }
}
