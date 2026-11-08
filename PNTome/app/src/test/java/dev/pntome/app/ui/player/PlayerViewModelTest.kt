package dev.gsound.a.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.Track
import dev.gsound.a.service.QueueBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.lang.reflect.Proxy

/**
 * Task 3.1: [PlayerViewModel] state mapping, lazy binding (PL-7.1) and commands (PL-1.4).
 * The [Player] is a recording proxy; the folder data is a real in-memory Room DB.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerViewModelTest {

    /** Recording stand-in for the service-bound player. */
    private class FakePlayerState {
        val calls = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        var item: MediaItem? = null
        var playing = false
        var position = 0L
        var duration = 100_000L
        var hasPrev = false
        var hasNext = false
    }

    private class FakeConnector(val state: FakePlayerState) : PlayerConnector {
        var connects = 0
        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addListener" -> { state.listeners += args[0] as Player.Listener; null }
                "removeListener" -> { state.listeners -= args[0] as Player.Listener; null }
                "getCurrentMediaItem" -> state.item
                "isPlaying" -> state.playing
                "getCurrentPosition" -> state.position
                "getDuration" -> state.duration
                "hasPreviousMediaItem" -> state.hasPrev
                "hasNextMediaItem" -> state.hasNext
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = args[0] as List<MediaItem>
                    state.item = items[args[1] as Int]
                    state.calls += "setMediaItems:${items.map { it.mediaId }}@${args[1]}"
                    null
                }
                "play" -> { state.playing = true; state.calls += "play"; null }
                "pause" -> { state.playing = false; state.calls += "pause"; null }
                "seekTo" -> { state.position = args[0] as Long; state.calls += "seekTo:${args[0]}"; null }
                else -> {
                    state.calls += method.name
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        else -> null
                    }
                }
            }
        } as Player

        override suspend fun connect(): Player { connects++; return player }
        override fun release(player: Player) { state.calls += "release" }
    }

    private lateinit var db: GsoundDatabase
    private lateinit var repository: FolderRepository
    private lateinit var state: FakePlayerState
    private lateinit var connector: FakeConnector
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GsoundDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())
        state = FakePlayerState()
        connector = FakeConnector(state)
        viewModel = PlayerViewModel(connector, repository, QueueBuilder())
        runBlocking {
            repository.upsertFolder(
                Folder(
                    id = "f1", treeUri = "content://t", displayName = "My Book",
                    trackCount = 2, totalBytes = 2, addedAt = 0, isAudiobook = true,
                )
            )
            repository.upsertTracks(
                listOf(track("a", "1 First.mp3"), track("b", "2 Second.mp3"))
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun track(id: String, file: String) = Track(
        id = id, folderId = "f1", documentUri = "content://doc/$id", relativeFolder = "My Book",
        title = file, artist = "Art", album = "Alb", durationMs = 1000, sizeBytes = 1,
        mime = "audio/mpeg", fileName = file,
    )

    private fun fire() = state.listeners.forEach { it.onEvents(connector.player, Player.Events(androidx.media3.common.FlagSet.Builder().build())) }

    @Test
    fun noControllerUntilPlaybackRequestedOrConnect() {
        viewModel.togglePlayPause()
        viewModel.next()
        assertEquals(0, connector.connects)
        assertFalse(viewModel.uiState.value.hasMedia)
    }

    @Test
    fun playTrack_buildsQueueStartsAtTrackAndMapsState() {
        state.hasNext = true
        state.hasPrev = true
        state.position = 5_000
        viewModel.playTrack("f1", "b")

        assertEquals(1, connector.connects)
        assertTrue(state.calls.contains("setMediaItems:[a, b]@1"))
        assertTrue(state.calls.contains("play"))
        val ui = viewModel.uiState.value
        assertTrue(ui.hasMedia)
        assertEquals("2 Second", ui.title)
        assertEquals("My Book", ui.folder)
        assertTrue(ui.isBook)
        assertTrue(ui.isPlaying)
        assertTrue(ui.hasPrev && ui.hasNext)
        assertEquals(5_000L, ui.positionMs)
        assertEquals(100_000L, ui.durationMs)
    }

    @Test
    fun playFolder_unknownFolder_postsMessageAndDoesNotConnect() {
        viewModel.playFolder("missing")
        assertEquals(0, connector.connects)
        assertTrue(viewModel.uiState.value.message != null)
        viewModel.onMessageShown()
        assertEquals(null, viewModel.uiState.value.message)
    }

    @Test
    fun commands_toggleSeekAndSkip30Clamped() {
        viewModel.playFolder("f1")
        viewModel.togglePlayPause()
        assertFalse(viewModel.uiState.value.isPlaying)
        viewModel.togglePlayPause()
        assertTrue(viewModel.uiState.value.isPlaying)

        state.position = 10_000
        viewModel.skipBack30()
        assertEquals(0L, state.position)
        viewModel.skipForward30()
        assertEquals(30_000L, state.position)
        state.position = 90_000
        viewModel.skipForward30()
        assertEquals(100_000L, state.position)

        viewModel.seekTo(42_000)
        assertEquals(42_000L, state.position)
        viewModel.previous()
        viewModel.next()
        assertTrue(state.calls.containsAll(listOf("seekToPreviousMediaItem", "seekToNextMediaItem")))
    }

    @Test
    fun connectIfRunning_bindsOnlyWhenServiceRunning() {
        viewModel.connectIfRunning { false }
        assertEquals(0, connector.connects)
        viewModel.connectIfRunning { true }
        assertEquals(1, connector.connects)
    }

    @Test
    fun playerEvents_updateState_andOnClearedReleases() {
        viewModel.connect()
        assertFalse(viewModel.uiState.value.hasMedia)
        state.item = MediaItem.Builder().setMediaId("x")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("T").setAlbumTitle("A").build())
            .setUri(Uri.parse("content://doc/x")).build()
        state.playing = true
        fire()
        val ui = viewModel.uiState.value
        assertEquals("T", ui.title)
        assertFalse(ui.isBook)

        val onCleared = PlayerViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(viewModel)
        assertTrue(state.calls.contains("release"))
        assertTrue(state.listeners.isEmpty())
    }
}
