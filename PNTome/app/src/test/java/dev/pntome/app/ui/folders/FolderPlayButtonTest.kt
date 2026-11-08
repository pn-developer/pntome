package dev.gsound.a.ui.folders

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gsound.a.ui.player.PlayerUiState
import dev.gsound.a.ui.theme.GsoundATheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Task 3.3: folder-row play button and the mini-player on the Folders screen (PL-1.1, 1.4). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FolderPlayButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun folder(id: String, name: String, tracks: Int) =
        FolderListItem(id, name, "/p/$name", tracks, "1 MB")

    private fun render(
        player: PlayerUiState = PlayerUiState(),
        played: MutableList<String> = mutableListOf(),
    ) {
        composeRule.setContent {
            GsoundATheme(dynamicColor = false) {
                FolderListContent(
                    uiState = FolderListUiState(
                        isLoading = false,
                        folders = listOf(folder("f1", "Book", 3), folder("f2", "Empty", 0)),
                    ),
                    onAddFolder = {},
                    onRemoveFolder = {},
                    onMessageShown = {},
                    onRefresh = {},
                    playerState = player,
                    onPlayFolder = { played += it },
                )
            }
        }
    }

    @Test
    fun playButtonInvokesCallbackWithFolderId() {
        val played = mutableListOf<String>()
        render(played = played)
        composeRule.onNodeWithContentDescription("Play Book").assertIsEnabled().performClick()
        assertEquals(listOf("f1"), played)
    }

    @Test
    fun playButtonDisabledForFolderWithoutTracks() {
        render()
        composeRule.onNodeWithContentDescription("Play Empty").assertIsNotEnabled()
    }

    @Test
    fun miniPlayerHiddenWhenNoMedia() {
        render()
        composeRule.onNodeWithText("Chapter 1").assertDoesNotExist()
    }

    @Test
    fun miniPlayerShownWhenMediaLoaded() {
        render(PlayerUiState(hasMedia = true, title = "Chapter 1", folder = "Book"))
        composeRule.onNodeWithText("Chapter 1").assertExists()
    }
}
