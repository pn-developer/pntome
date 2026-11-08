package dev.pntome.app.ui.sync

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import dev.pntome.app.domain.model.SyncState
import dev.pntome.app.ui.theme.PNTomeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BookmarkSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun show(rows: List<BookmarkRow>, paired: Boolean = true) {
        composeRule.setContent {
            PNTomeTheme(dynamicColor = false) {
                SyncContent(
                    state = SyncUiState.from(SyncState.Idle, paired, true, null, null, rows),
                    onSyncClicked = {}, onCancelClicked = {}, onRetryClicked = {}, onRefreshStatsClicked = {},
                )
            }
        }
    }

    @Test fun emptyHidesSection() {
        show(emptyList())
        composeRule.onNodeWithText("Bookmarks").assertDoesNotExist()
    }

    @Test fun showsPhoneWatchAndFinishedRows() {
        show(
            listOf(
                BookmarkRow("a", "Dune", "Ch 3 · 12:34", "Watch"),
                BookmarkRow("b", "Emma", "Finished", "Phone"),
            ),
            paired = false, // phone bookmarks show without pairing/STATS (PL-4.6)
        )
        composeRule.onNodeWithText("Bookmarks").assertExists()
        composeRule.onNodeWithContentDescription("Dune, Ch 3 · 12:34, from Watch").assertExists()
        composeRule.onNodeWithContentDescription("Emma, Finished, from Phone").assertExists()
    }
}
