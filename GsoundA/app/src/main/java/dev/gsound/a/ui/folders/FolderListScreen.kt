package dev.gsound.a.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.gsound.a.ui.player.MiniPlayer
import dev.gsound.a.ui.player.PlayerUiState

/**
 * Folders destination (Req 1, 2).
 *
 * Lists each folder's name, derived path, eligible track count, and total size (Req 2.1); a FAB
 * launches the SAF picker to add a folder (Req 1.1); each row offers remove with a confirmation
 * dialog that releases the persisted permission (Req 2.2, 2.3); an empty state prompts the user
 * to add a folder (Req 2.4); and a Snackbar surfaces duplicate/error notices (Req 1.4).
 *
 * This is the stateful entry point used by navigation; it hoists state from
 * [FolderListViewModel] and delegates rendering to the stateless [FolderListContent].
 */
@Composable
fun FolderListScreen(
    modifier: Modifier = Modifier,
    viewModel: FolderListViewModel = hiltViewModel(),
    playerState: PlayerUiState = PlayerUiState(),
    onPlayFolder: (String) -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    onAttachPlayer: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val addFolderLauncher = rememberAddFolderLauncher(onFolderSelected = viewModel::onFolderSelected)

    // Rescan whenever this destination is entered (first composition and every switch back to
    // the Folders tab), so a folder whose files changed on disk shows an up-to-date count
    // without the user tapping refresh. keyed on Unit = once per entry into the composition.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        // Attach to playback already running so the mini-player reflects it (PL-1.4).
        onAttachPlayer()
    }

    FolderListContent(
        uiState = uiState,
        modifier = modifier,
        onAddFolder = { addFolderLauncher.launch() },
        onRemoveFolder = viewModel::onRemoveConfirmed,
        onMessageShown = viewModel::onMessageShown,
        onRefresh = viewModel::refresh,
        onToggleAudiobook = viewModel::onAudiobookToggled,
        playerState = playerState,
        onPlayFolder = onPlayFolder,
        onTogglePlayPause = onTogglePlayPause,
        onOpenPlayer = onOpenPlayer,
    )
}

/**
 * Stateless folder-list UI. Separated from [FolderListScreen] so it can be previewed and tested
 * without Hilt or the SAF launcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListContent(
    uiState: FolderListUiState,
    modifier: Modifier = Modifier,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onMessageShown: () -> Unit,
    onRefresh: () -> Unit,
    onToggleAudiobook: (id: String, isAudiobook: Boolean) -> Unit = { _, _ -> },
    playerState: PlayerUiState = PlayerUiState(),
    onPlayFolder: (String) -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show transient duplicate/error notices once, then clear them (Req 1.4).
    LaunchedEffect(uiState.message) {
        val message = uiState.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    // The folder pending removal confirmation, or null when no dialog is shown (Req 2.3).
    var pendingRemoval by remember { mutableStateOf<FolderListItem?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        // Disable while a rescan is running so repeated taps don't queue up.
                        enabled = !uiState.isRefreshing,
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Refresh folders",
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            MiniPlayer(
                state = playerState,
                onOpen = onOpenPlayer,
                onTogglePlayPause = onTogglePlayPause,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFolder) {
                Icon(Icons.Filled.Add, contentDescription = "Add folder")
            }
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> Unit // Transient; the first snapshot arrives promptly.
            uiState.isEmpty -> EmptyState(modifier = Modifier.padding(innerPadding))
            else -> FolderList(
                folders = uiState.folders,
                contentPadding = innerPadding,
                onRequestRemove = { pendingRemoval = it },
                onToggleAudiobook = onToggleAudiobook,
                onPlay = onPlayFolder,
            )
        }
    }

    pendingRemoval?.let { folder ->
        RemoveFolderDialog(
            folder = folder,
            onConfirm = {
                onRemoveFolder(folder.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
}

@Composable
private fun FolderList(
    folders: List<FolderListItem>,
    contentPadding: PaddingValues,
    onRequestRemove: (FolderListItem) -> Unit,
    onToggleAudiobook: (id: String, isAudiobook: Boolean) -> Unit,
    onPlay: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(folders, key = { it.id }) { folder ->
            ListItem(
                headlineContent = {
                    Text(
                        text = folder.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column {
                        Text(
                            text = folder.path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "${folder.trackCount} tracks · ${folder.sizeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // AB-1.1: mark the folder as one audiobook (chapters by file name).
                        // A labelled chip: no extra icon dependency, and its text + selected
                        // state are announced by TalkBack.
                        FilterChip(
                            selected = folder.isAudiobook,
                            onClick = { onToggleAudiobook(folder.id, !folder.isAudiobook) },
                            label = { Text("Audiobook") },
                            leadingIcon = if (folder.isAudiobook) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onPlay(folder.id) },
                            enabled = folder.trackCount > 0,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play ${folder.displayName}",
                            )
                        }
                        IconButton(onClick = { onRequestRemove(folder) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Remove ${folder.displayName}",
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "No folders yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Tap + to add a music folder to sync to your watch.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RemoveFolderDialog(
    folder: FolderListItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove folder?") },
        text = {
            Text(
                "\"${folder.displayName}\" will no longer be available to sync. " +
                    "Your files are not deleted.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
