package dev.gsound.a.ui.folders

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.domain.folders.AddFolderResult
import dev.gsound.a.domain.folders.AddFolderUseCase
import dev.gsound.a.domain.folders.RemoveFolderResult
import dev.gsound.a.domain.folders.RemoveFolderUseCase
import dev.gsound.a.domain.folders.ScanAndCacheResult
import dev.gsound.a.domain.folders.ScanAndCacheUseCase
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.ui.util.ByteSizeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A single folder row ready for display (Req 2.1): the resolved name, a human-readable derived
 * path, the eligible track count, and the formatted total size.
 *
 * @property id stable folder id, used as the stable list key and to target removal.
 * @property displayName human-readable folder name.
 * @property path derived, readable path from the SAF tree URI (e.g. `primary:Music/Rock`).
 * @property trackCount number of eligible tracks found (0 until the folder is scanned in task 5).
 * @property sizeLabel [ByteSizeFormatter] rendering of the folder's total eligible-track size.
 */
data class FolderListItem(
    val id: String,
    val displayName: String,
    val path: String,
    val trackCount: Int,
    val sizeLabel: String,
    val isAudiobook: Boolean = false,
)

/**
 * Immutable UI state for the folder-list screen, driven as a [StateFlow] (design §6).
 *
 * @property isLoading true until the first folder snapshot arrives from storage.
 * @property folders the folders to render, newest first.
 * @property isEmpty true when there are no folders — the screen shows the empty state and the
 *   Sync action is disabled (Req 2.4).
 * @property isSyncEnabled whether Sync should be enabled; false while empty (Req 2.4). The Sync
 *   screen (task 10) applies the additional "paired" condition.
 * @property message a transient user notice (duplicate add or an error) to show once in a
 *   Snackbar, cleared via [FolderListViewModel.onMessageShown] (Req 1.4).
 * @property isRefreshing true while a rescan of the folders is in progress, so the UI can show
 *   a progress indicator and disable the refresh action.
 */
data class FolderListUiState(
    val isLoading: Boolean = true,
    val folders: List<FolderListItem> = emptyList(),
    val message: String? = null,
    val isRefreshing: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && folders.isEmpty()

    /** Sync is gated on having at least one folder (Req 2.4). */
    val isSyncEnabled: Boolean get() = folders.isNotEmpty()
}

/**
 * Backs [FolderListScreen]: observes the persisted folder list and drives add/remove (Req 1, 2).
 *
 * Reads flow reactively from [FolderRepository.observeFolders] so the list restores on launch
 * and reflects every mutation (Req 2.5, 8.2). Add and remove delegate to the respective use
 * cases — [AddFolderUseCase] takes the persistable permission and dedupes (Req 1.2–1.4),
 * [RemoveFolderUseCase] releases the permission before deleting (Req 2.2). Transient outcomes
 * (duplicate, error) surface through [FolderListUiState.message] for a Snackbar (Req 1.4).
 */
@HiltViewModel
class FolderListViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
    private val addFolderUseCase: AddFolderUseCase,
    private val removeFolderUseCase: RemoveFolderUseCase,
    private val scanAndCacheUseCase: ScanAndCacheUseCase,
) : ViewModel() {

    /** Transient one-shot message overlaid on the folder snapshot (duplicate/error notices). */
    private val message = MutableStateFlow<String?>(null)

    /** True while [refresh] is rescanning folders; drives the progress indicator (Req 2.1). */
    private val refreshing = MutableStateFlow(false)

    /** Guards against overlapping rescans (e.g. tab re-entry fired while one is still running). */
    private var refreshJob: Job? = null

    val uiState: StateFlow<FolderListUiState> =
        combine(
            folderRepository.observeFolders().map { folders -> folders.map(::toItem) },
            message,
            refreshing,
        ) { items, msg, isRefreshing ->
            FolderListUiState(
                isLoading = false,
                folders = items,
                message = msg,
                isRefreshing = isRefreshing,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FolderListUiState(),
        )

    /**
     * Add the folder behind [treeUri] returned by the SAF picker (Req 1.2–1.4). Reports a
     * "already added" notice on a duplicate and the error text on failure; the list itself
     * updates reactively via the repository flow on success.
     */
    fun onFolderSelected(treeUri: Uri) {
        viewModelScope.launch {
            when (val result = addFolderUseCase.add(treeUri)) {
                is AddFolderResult.Added -> {
                    // A folder is persisted with trackCount=0; scan it now so its real count is
                    // reflected instead of staying 0 until the next manual/tab refresh.
                    refresh()
                }
                is AddFolderResult.Duplicate ->
                    message.value = "That folder is already in your list."
                is AddFolderResult.Error -> message.value = result.message
            }
        }
    }

    /**
     * Rescan every persisted folder and refresh its cached track count/size (Req 2.1).
     *
     * The folder's `trackCount`/`totalBytes` are denormalized columns only updated by
     * [ScanAndCacheUseCase]; nothing updates them automatically when files are added to or
     * removed from a folder on disk. This re-runs the scan for each folder so the list reflects
     * the current contents. The reactive [FolderRepository.observeFolders] flow repaints the UI
     * as each folder's row is rewritten.
     *
     * Safe to call repeatedly (e.g. on every switch to the Folders tab): a rescan already in
     * flight is left to finish rather than starting a second concurrent pass.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshing.value = true
            try {
                val folders = folderRepository.getFolders()
                for (folder in folders) {
                    when (val result = scanAndCacheUseCase.scanAndCache(folder.id)) {
                        is ScanAndCacheResult.Success,
                        is ScanAndCacheResult.FolderNotFound -> Unit // Flow repaints the row.
                        is ScanAndCacheResult.Error -> message.value = result.message
                    }
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * Remove the folder with [id] after the user confirmed in the dialog (Req 2.2, 2.3).
     * Releases the persisted permission via [RemoveFolderUseCase]; surfaces an error notice if
     * the delete fails.
     */
    fun onRemoveConfirmed(id: String) {
        viewModelScope.launch {
            when (val result = removeFolderUseCase.remove(id)) {
                is RemoveFolderResult.Removed, RemoveFolderResult.NotFound -> Unit
                is RemoveFolderResult.Error -> message.value = result.message
            }
        }
    }

    /**
     * Mark or unmark the folder with [id] as an audiobook (spec gsound-audiobooks AB-1.1, 1.2).
     * Persisted immediately; the row repaints via the repository flow, and the watch picks the
     * change up at the next sync (AB-1.5).
     */
    fun onAudiobookToggled(id: String, isAudiobook: Boolean) {
        viewModelScope.launch {
            try {
                folderRepository.setAudiobook(id, isAudiobook)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message.value = "Couldn't update the folder: ${e.message ?: "error"}"
            }
        }
    }

    /** Clear the transient message after it has been shown in the Snackbar (Req 1.4). */
    fun onMessageShown() {
        message.update { null }
    }

    private fun toItem(folder: Folder): FolderListItem = FolderListItem(
        id = folder.id,
        displayName = folder.displayName,
        path = derivePath(folder.treeUri),
        trackCount = folder.trackCount,
        sizeLabel = ByteSizeFormatter.format(folder.totalBytes),
        isAudiobook = folder.isAudiobook,
    )

    companion object {
        /**
         * Derive a readable path from a SAF tree URI for display (Req 2.1).
         *
         * A tree URI looks like
         * `content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRock`; the
         * last path segment holds the document id (`primary:Music/Rock` once decoded). We show
         * that decoded id, which reads naturally as a path. Falls back to the raw URI if it has
         * no recognizable segment.
         */
        fun derivePath(treeUri: String): String {
            val decoded = runCatching { Uri.parse(treeUri).lastPathSegment }.getOrNull()
                ?: return treeUri
            return decoded
        }
    }
}
