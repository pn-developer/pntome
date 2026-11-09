package dev.pntome.app.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pntome.app.ui.theme.StatusOnline

/** Formats [ms] as `m:ss` (or `h:mm:ss` from one hour). Negative values are treated as 0. */
fun formatPlayerTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The Player tab, stateless and driven by [PlayerViewModel] state.
 *
 * Top: a "Folders" list (add a folder to the queue, or play it now replacing the queue) and the
 * "Queue" list (tap to jump; finished audiobook chapters carry a green dot), with a control to
 * clear the queue. Middle: the album name, cover and current title. Bottom: seek bar and the
 * standard transport controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
    folders: List<PlayerFolder> = emptyList(),
    onAddFolder: (String) -> Unit = {},
    onPlayFolder: (String) -> Unit = {},
    onClearQueue: () -> Unit = {},
    onPlayQueueItem: (Int) -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onSeekTo: (Long) -> Unit = {},
    onMessageShown: () -> Unit = {},
    onAttachPlayer: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }
    // Attach to playback that is already running whenever the tab is entered.
    LaunchedEffect(Unit) { onAttachPlayer() }

    var tab by rememberSaveable { mutableIntStateOf(TAB_FOLDERS) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Player") },
                actions = {
                    IconButton(onClick = onClearQueue, enabled = state.queue.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear queue")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == TAB_FOLDERS,
                    onClick = { tab = TAB_FOLDERS },
                    text = { Text("Folders") },
                )
                Tab(
                    selected = tab == TAB_QUEUE,
                    onClick = { tab = TAB_QUEUE },
                    text = { Text("Queue (${state.queue.size})") },
                )
            }

            Box(modifier = Modifier.weight(LIST_WEIGHT).fillMaxWidth()) {
                if (tab == TAB_FOLDERS) {
                    FolderPicker(folders, onAddFolder, onPlayFolder)
                } else {
                    QueueList(state, onPlayQueueItem)
                }
            }
            HorizontalDivider()

            NowPlaying(state, modifier = Modifier.weight(ART_WEIGHT).fillMaxWidth())

            Controls(
                state = state,
                onTogglePlayPause = onTogglePlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onSeekTo = onSeekTo,
            )
        }
    }
}

private const val TAB_FOLDERS = 0
private const val TAB_QUEUE = 1
private const val LIST_WEIGHT = 0.9f
private const val ART_WEIGHT = 1.1f

@Composable
private fun FolderPicker(
    folders: List<PlayerFolder>,
    onAdd: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    if (folders.isEmpty()) {
        CenteredMessage("No folders yet", "Add a folder on the Folders tab.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(folders, key = { _, f -> f.id }) { _, folder ->
            ListItem(
                headlineContent = {
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    val kind = if (folder.isAudiobook) " · Audiobook" else ""
                    Text("${folder.trackCount} tracks$kind")
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onAdd(folder.id) },
                            enabled = folder.trackCount > 0,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add ${folder.name} to queue",
                            )
                        }
                        IconButton(
                            onClick = { onPlay(folder.id) },
                            enabled = folder.trackCount > 0,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Play ${folder.name}, replacing the queue",
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun QueueList(state: PlayerUiState, onPlayQueueItem: (Int) -> Unit) {
    if (state.queue.isEmpty()) {
        CenteredMessage("The queue is empty", "Add a folder from the Folders list.")
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) {
        // Keep the previous item in view for context.
        if (state.currentIndex in state.queue.indices) {
            listState.scrollToItem((state.currentIndex - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(state.queue, key = { index, entry -> "$index:${entry.mediaId}" }) { index, entry ->
            val current = index == state.currentIndex
            // Done when queued as finished, or when playback has already moved past this chapter.
            val done = entry.done || (entry.isBook && index < state.currentIndex)
            ListItem(
                modifier = Modifier.clickable(onClickLabel = "Play") { onPlayQueueItem(index) },
                colors = ListItemDefaults.colors(
                    containerColor = if (current) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                leadingContent = {
                    when {
                        done -> DoneDot()
                        current -> Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Now playing",
                            modifier = Modifier.size(DOT_BOX),
                        )
                        else -> Spacer(Modifier.size(DOT_BOX))
                    }
                },
                headlineContent = {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(entry.folder, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

private val DOT_BOX = 24.dp

/** The green "finished" dot shown next to a completed audiobook chapter. */
@Composable
private fun DoneDot() {
    Box(
        modifier = Modifier.size(DOT_BOX),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(StatusOnline)
                .semantics { contentDescription = "Done" },
        )
    }
}

@Composable
private fun CenteredMessage(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** Middle section: cover, album name and the current title. */
@Composable
private fun NowPlaying(state: PlayerUiState, modifier: Modifier = Modifier) {
    if (!state.hasMedia) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CenteredMessage("Nothing is playing", "Add a folder to the queue to start.")
        }
        return
    }
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = state.cover
            if (cover != null) {
                val image = remember(cover) { cover.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = "Cover of ${state.folder}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = state.folder.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = state.folder,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.title,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bottom section: seek bar and transport controls (audiobooks add ±30 s). */
@Composable
private fun Controls(
    state: PlayerUiState,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeekTo = onSeekTo,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = state.hasPrev) {
                Icon(PlayerIcons.SkipPrevious, contentDescription = "Previous")
            }
            if (state.isBook) {
                TextButton(
                    onClick = onSkipBack,
                    modifier = Modifier.semantics { contentDescription = "Back 30 seconds" },
                ) { Text("−30 s") }
            }
            FilledIconButton(onClick = onTogglePlayPause, enabled = state.hasMedia) {
                if (state.isPlaying) {
                    Icon(PlayerIcons.Pause, contentDescription = "Pause")
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                }
            }
            if (state.isBook) {
                TextButton(
                    onClick = onSkipForward,
                    modifier = Modifier.semantics { contentDescription = "Forward 30 seconds" },
                ) { Text("+30 s") }
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(PlayerIcons.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
) {
    // While dragging, show the drag value; commit once on release so the 500 ms position poll
    // doesn't fight the thumb and the player isn't flooded with seeks.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val enabled = durationMs > 0
    val shown = dragging ?: positionMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1).toFloat())
    val shownMs = shown.toLong()

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription =
                    "Playback position ${formatPlayerTime(shownMs)} of ${formatPlayerTime(durationMs)}"
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatPlayerTime(shownMs), style = MaterialTheme.typography.bodySmall)
            Text(formatPlayerTime(durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}
