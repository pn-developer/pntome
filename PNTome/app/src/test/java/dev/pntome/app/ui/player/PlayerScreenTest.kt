package dev.pntome.app.ui.player

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.pntome.app.ui.theme.PNTomeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compose tests for the Player tab ([PlayerScreen]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PlayerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val queue = listOf(
        QueueEntry("c1", "Chapter 1", "My Book", "f1", isBook = true, done = true),
        QueueEntry("c2", "Chapter 2", "My Book", "f1", isBook = true, done = false),
        QueueEntry("c3", "Chapter 3", "My Book", "f1", isBook = true, done = false),
    )

    private val playing = PlayerUiState(
        hasMedia = true, title = "Chapter 2", folder = "My Book",
        positionMs = 65_000, durationMs = 3_725_000, isPlaying = true,
        queue = queue, currentIndex = 1,
    )

    private val folders = listOf(
        PlayerFolder("f1", "My Book", 3, isAudiobook = true),
        PlayerFolder("f2", "Empty", 0, isAudiobook = false),
    )

    private fun screen(
        state: PlayerUiState,
        calls: MutableList<String> = mutableListOf(),
        folders: List<PlayerFolder> = this.folders,
    ) {
        composeRule.setContent {
            PNTomeTheme(dynamicColor = false) {
                PlayerScreen(
                    state = state,
                    folders = folders,
                    onAddFolder = { calls += "add:$it" },
                    onPlayFolder = { calls += "play:$it" },
                    onClearQueue = { calls += "clear" },
                    onPlayQueueItem = { calls += "item:$it" },
                    onTogglePlayPause = { calls += "toggle" },
                    onPrevious = { calls += "prev" },
                    onNext = { calls += "next" },
                    onSkipBack = { calls += "back30" },
                    onSkipForward = { calls += "fwd30" },
                    onAttachPlayer = { calls += "attach" },
                )
            }
        }
    }

    @Test
    fun attachesPlayerOnEntry() {
        val calls = mutableListOf<String>()
        screen(PlayerUiState(), calls)
        composeRule.waitForIdle()
        assertEquals(listOf("attach"), calls)
    }

    @Test
    fun showsAlbumTitleAndTimes() {
        screen(playing)
        // Once in the folder list and once as the album name above the title.
        composeRule.onAllNodesWithText("My Book").assertCountEquals(2)
        composeRule.onNodeWithText("Chapter 2").assertExists()
        composeRule.onNodeWithText("1:05").assertExists()
        composeRule.onNodeWithText("1:02:05").assertExists()
    }

    @Test
    fun emptyStateWhenNoMedia() {
        screen(PlayerUiState())
        composeRule.onNodeWithText("Nothing is playing").assertExists()
    }

    @Test
    fun folderRowAddsAndReplaces() {
        val calls = mutableListOf<String>()
        screen(PlayerUiState(), calls)
        composeRule.onNodeWithContentDescription("Add My Book to queue").performClick()
        composeRule.onNodeWithContentDescription("Play My Book, replacing the queue").performClick()
        assertEquals(listOf("attach", "add:f1", "play:f1"), calls)
    }

    @Test
    fun folderWithoutTracksCannotBeQueued() {
        screen(PlayerUiState())
        composeRule.onNodeWithContentDescription("Add Empty to queue").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Play Empty, replacing the queue")
            .assertIsNotEnabled()
    }

    @Test
    fun clearQueueDisabledWhenEmptyAndInvokedOtherwise() {
        screen(PlayerUiState())
        composeRule.onNodeWithContentDescription("Clear queue").assertIsNotEnabled()
    }

    @Test
    fun clearQueueInvokesCallback() {
        val calls = mutableListOf<String>()
        screen(playing, calls)
        composeRule.onNodeWithContentDescription("Clear queue").assertIsEnabled().performClick()
        assertEquals(listOf("attach", "clear"), calls)
    }

    @Test
    fun queueShowsGreenDotOnlyOnFinishedChapters() {
        screen(playing)
        composeRule.onNodeWithText("Queue (3)").performClick()
        // Chapter 1 was queued as done; the current and upcoming chapters have no dot.
        composeRule.onNodeWithContentDescription("Done", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithContentDescription("Now playing", useUnmergedTree = true).assertExists()
    }

    @Test
    fun chaptersBeforeCurrentCountAsDone() {
        val moved = playing.copy(
            queue = queue.map { it.copy(done = false) }, currentIndex = 1,
        )
        screen(moved)
        composeRule.onNodeWithText("Queue (3)").performClick()
        // Chapter 1 is behind the current one, so it counts as done even though it wasn't flagged.
        composeRule.onAllNodesWithContentDescription("Done", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun queueItemTapJumpsToIt() {
        val calls = mutableListOf<String>()
        screen(playing, calls)
        composeRule.onNodeWithText("Queue (3)").performClick()
        composeRule.onNodeWithText("Chapter 1").performClick()
        assertEquals(listOf("attach", "item:0"), calls)
    }

    @Test
    fun skipButtonsHiddenForMusic() {
        screen(playing.copy(isBook = false))
        composeRule.onNodeWithContentDescription("Back 30 seconds").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Forward 30 seconds").assertDoesNotExist()
    }

    @Test
    fun skipButtonsShownForBooksAndInvokeCallbacks() {
        val calls = mutableListOf<String>()
        screen(playing.copy(isBook = true), calls)
        composeRule.onNodeWithContentDescription("Back 30 seconds").performClick()
        composeRule.onNodeWithContentDescription("Forward 30 seconds").performClick()
        assertEquals(listOf("attach", "back30", "fwd30"), calls)
    }

    @Test
    fun prevNextEnabledStateFollowsQueue() {
        screen(playing.copy(hasPrev = false, hasNext = true))
        composeRule.onNodeWithContentDescription("Previous").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next").assertIsEnabled()
    }

    @Test
    fun transportButtonsInvokeCallbacks() {
        val calls = mutableListOf<String>()
        screen(playing.copy(hasPrev = true, hasNext = true), calls)
        composeRule.onNodeWithContentDescription("Previous").performClick()
        composeRule.onNodeWithContentDescription("Pause").performClick()
        composeRule.onNodeWithContentDescription("Next").performClick()
        assertEquals(listOf("attach", "prev", "toggle", "next"), calls)
    }

    @Test
    fun playLabelWhenPaused() {
        screen(playing.copy(isPlaying = false))
        composeRule.onNodeWithContentDescription("Play").assertExists()
    }
}
