package dev.pntome.app.ui.player

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Task 3.3: Compose tests for [PlayerScreen] and [MiniPlayer] (PL-1.1, 1.3, 1.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PlayerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val playing = PlayerUiState(
        hasMedia = true, title = "Chapter 3", folder = "My Book",
        positionMs = 65_000, durationMs = 3_725_000, isPlaying = true,
    )

    private fun screen(
        state: PlayerUiState,
        calls: MutableList<String> = mutableListOf(),
    ) {
        composeRule.setContent {
            PNTomeTheme(dynamicColor = false) {
                PlayerScreen(
                    state = state,
                    onBack = { calls += "back" },
                    onTogglePlayPause = { calls += "toggle" },
                    onPrevious = { calls += "prev" },
                    onNext = { calls += "next" },
                    onSkipBack = { calls += "back30" },
                    onSkipForward = { calls += "fwd30" },
                )
            }
        }
    }

    @Test
    fun showsTitleFolderAndTimes() {
        screen(playing)
        composeRule.onNodeWithText("Chapter 3").assertExists()
        composeRule.onNodeWithText("My Book").assertExists()
        composeRule.onNodeWithText("1:05").assertExists()
        composeRule.onNodeWithText("1:02:05").assertExists()
    }

    @Test
    fun emptyStateWhenNoMedia() {
        screen(PlayerUiState())
        composeRule.onNodeWithText("Nothing is playing").assertExists()
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
        assertEquals(listOf("back30", "fwd30"), calls)
    }

    @Test
    fun prevNextEnabledStateFollowsQueue() {
        screen(playing.copy(hasPrev = false, hasNext = true))
        composeRule.onNodeWithText("Previous").assertIsNotEnabled()
        composeRule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun transportButtonsInvokeCallbacks() {
        val calls = mutableListOf<String>()
        screen(playing.copy(hasPrev = true, hasNext = true), calls)
        composeRule.onNodeWithText("Previous").performClick()
        composeRule.onNodeWithText("Pause").performClick()
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("prev", "toggle", "next", "back"), calls)
    }

    @Test
    fun playLabelWhenPaused() {
        screen(playing.copy(isPlaying = false))
        composeRule.onNodeWithText("Play").assertExists()
    }

    // --- MiniPlayer ---

    private fun mini(state: PlayerUiState, calls: MutableList<String> = mutableListOf()) {
        composeRule.setContent {
            PNTomeTheme(dynamicColor = false) {
                MiniPlayer(
                    state = state,
                    onOpen = { calls += "open" },
                    onTogglePlayPause = { calls += "toggle" },
                )
            }
        }
    }

    @Test
    fun miniPlayerHiddenWithoutMedia() {
        mini(PlayerUiState(title = "Ghost"))
        composeRule.onNodeWithText("Ghost").assertDoesNotExist()
    }

    @Test
    fun miniPlayerShowsTitleAndTogglesAndOpens() {
        val calls = mutableListOf<String>()
        mini(playing, calls)
        composeRule.onNodeWithText("Pause").performClick()
        composeRule.onNodeWithText("Chapter 3").performClick()
        assertEquals(listOf("toggle", "open"), calls)
    }

    @Test
    fun miniPlayerShowsPlayWhenPaused() {
        val calls = mutableListOf<String>()
        mini(playing.copy(isPlaying = false), calls)
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertEquals(listOf("toggle"), calls)
    }
}
