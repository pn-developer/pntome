package dev.gsound.a.ui.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.model.SyncSummary
import dev.gsound.a.net.server.LanKind
import dev.gsound.a.ui.theme.GsoundATheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for the stateless [SyncContent] (task 10.3). These exercise button gating,
 * disabled-reason hints, live-progress rendering, error/retry, completion summary, the LAN address
 * (Req 5.8), and the watch-stats card including the "unknown"/known rendering and the paired-only
 * Refresh affordance (Req 2.4/6.1, 6.2–6.5, 7.1/7.3).
 *
 * ## Why Robolectric (JVM), not an emulator
 * These run as plain JVM unit tests via [RobolectricTestRunner] + `createComposeRule`, so they
 * execute with `./gradlew :app:testDebugUnitTest` without a device. `unitTests.isIncludeAndroidResources`
 * is already enabled in `build.gradle.kts`, and `GraphicsMode.NATIVE` lets Compose render headlessly.
 * Dynamic color is disabled in the theme wrapper so the tests don't depend on an SDK 31+ runtime.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SyncContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Render [SyncContent] with the given [state]; intents default to no-ops but can be captured. */
    private fun setContent(
        state: SyncUiState,
        onSyncClicked: () -> Unit = {},
        onCancelClicked: () -> Unit = {},
        onRetryClicked: () -> Unit = {},
        onRefreshStatsClicked: () -> Unit = {},
    ) {
        composeRule.setContent {
            GsoundATheme(dynamicColor = false) {
                SyncContent(
                    state = state,
                    onSyncClicked = onSyncClicked,
                    onCancelClicked = onCancelClicked,
                    onRetryClicked = onRetryClicked,
                    onRefreshStatsClicked = onRefreshStatsClicked,
                )
            }
        }
    }

    private fun idle(
        isPaired: Boolean = true,
        hasFolders: Boolean = true,
        lastSummary: SyncSummary? = null,
        watchStats: WatchStats? = null,
    ) = SyncUiState.from(
        syncState = SyncState.Idle,
        isPaired = isPaired,
        hasFolders = hasFolders,
        lastSummary = lastSummary,
        watchStats = watchStats,
    )

    // --- Gating: Sync button disabled when unpaired / empty (Req 2.4, 6.1) -----------------

    @Test
    fun `sync button is disabled and reason shown when unpaired`() {
        setContent(idle(isPaired = false, hasFolders = true))

        composeRule.onNodeWithText("Sync now").assertIsNotEnabled()
        composeRule.onNodeWithText("Pair your watch to sync.").assertExists()
    }

    @Test
    fun `sync button is disabled and reason shown when no folders`() {
        setContent(idle(isPaired = true, hasFolders = false))

        composeRule.onNodeWithText("Sync now").assertIsNotEnabled()
        composeRule.onNodeWithText("Add a music folder to sync.").assertExists()
    }

    @Test
    fun `sync button is disabled with combined reason when unpaired and no folders`() {
        setContent(idle(isPaired = false, hasFolders = false))

        composeRule.onNodeWithText("Sync now").assertIsNotEnabled()
        composeRule
            .onNodeWithText("Pair your watch and add a music folder to sync.")
            .assertExists()
    }

    @Test
    fun `sync button is enabled and clickable when paired with folders`() {
        var clicks = 0
        setContent(idle(isPaired = true, hasFolders = true), onSyncClicked = { clicks++ })

        composeRule.onNodeWithText("Sync now").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    // --- Running state: progress, LAN address (Req 5.8), Cancel (Req 6.2, 6.5) -------------

    @Test
    fun `running state shows progress, LAN address, and cancel - not sync`() {
        var cancels = 0
        setContent(
            SyncUiState.from(
                syncState = SyncState.Running(
                    tracksDone = 3,
                    tracksTotal = 10,
                    bytesSent = 0L,
                    serverUrl = "http://192.168.1.5:8080",
                ),
                isPaired = true,
                hasFolders = true,
                lastSummary = null,
            ),
            onCancelClicked = { cancels++ },
        )

        composeRule.onNodeWithText("Syncing…").assertExists()
        composeRule.onNodeWithText("3 of 10 tracks").assertExists()
        composeRule.onNodeWithText("Serving on").assertExists()
        composeRule.onNodeWithText("http://192.168.1.5:8080").assertExists()

        // Sync button is replaced by Cancel while active.
        composeRule.onNodeWithText("Sync now").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        assertEquals(1, cancels)
    }

    private fun runningWith(kind: LanKind?) = SyncUiState.from(
        syncState = SyncState.Running(
            tracksDone = 1,
            tracksTotal = 2,
            bytesSent = 0L,
            serverUrl = "http://192.168.43.1:8080",
            networkKind = kind,
        ),
        isPaired = true,
        hasFolders = true,
        lastSummary = null,
    )

    @Test
    fun `running hotspot shows Hotspot host-port label`() {
        setContent(runningWith(LanKind.HOTSPOT))

        composeRule.onNodeWithText("Hotspot · 192.168.43.1:8080").assertExists()
    }

    @Test
    fun `running wifi shows Wi-Fi host-port label`() {
        setContent(runningWith(LanKind.WIFI))

        composeRule.onNodeWithText("Wi-Fi · 192.168.43.1:8080").assertExists()
    }

    @Test
    fun `lanAddressLabel formats by kind and keeps bare url when kind unknown`() {
        assertEquals("Hotspot · 10.0.0.1:80", lanAddressLabel("http://10.0.0.1:80", LanKind.HOTSPOT))
        assertEquals("Wi-Fi · 10.0.0.1:80", lanAddressLabel("http://10.0.0.1:80", LanKind.WIFI))
        assertEquals("http://10.0.0.1:80", lanAddressLabel("http://10.0.0.1:80", null))
    }

    // --- Error state: message + Retry (Req 6.4) --------------------------------------------

    @Test
    fun `error state shows message and retry invokes callback`() {
        var retries = 0
        setContent(
            SyncUiState.from(
                syncState = SyncState.Error("Watch disconnected"),
                isPaired = true,
                hasFolders = true,
                lastSummary = null,
            ),
            onRetryClicked = { retries++ },
        )

        composeRule.onNodeWithText("Watch disconnected").assertExists()
        composeRule.onNodeWithText("Retry").assertIsEnabled().performClick()
        assertEquals(1, retries)
    }

    // --- Completed state: summary (Req 6.3) ------------------------------------------------

    @Test
    fun `completed state shows summary counts`() {
        val summary = SyncSummary(
            syncedCount = 5,
            skippedCount = 2,
            removedCount = 1,
            lastSyncAt = 1_700_000_000_000L,
            error = null,
        )
        setContent(
            SyncUiState.from(
                syncState = SyncState.Completed(summary),
                isPaired = true,
                hasFolders = true,
                lastSummary = summary,
            ),
        )

        composeRule.onNodeWithText("Sync complete").assertExists()
        composeRule.onNodeWithText("5 synced · 2 skipped · 1 removed").assertExists()
    }

    // --- Watch stats: unknown / known + Refresh gating (Req 7.1, 7.3) ----------------------

    @Test
    fun `watch stats render unknown when null and refresh disabled when unpaired`() {
        setContent(idle(isPaired = false, hasFolders = true, watchStats = null))

        composeRule.onNodeWithText("On your watch").assertExists()
        composeRule.onNodeWithText("Files: unknown").assertExists()
        composeRule.onNodeWithText("Folders: unknown").assertExists()
        composeRule.onNodeWithText("Last sync: unknown").assertExists()
        composeRule.onNodeWithText("Refresh stats").assertIsNotEnabled()
    }

    @Test
    fun `watch stats render known values and refresh invokes callback when paired`() {
        var refreshes = 0
        setContent(
            idle(
                isPaired = true,
                hasFolders = true,
                watchStats = WatchStats(fileCount = 42, folderCount = 3, lastSyncAt = null),
            ),
            onRefreshStatsClicked = { refreshes++ },
        )

        composeRule.onNodeWithText("Files: 42").assertExists()
        composeRule.onNodeWithText("Folders: 3").assertExists()
        // lastSyncAt == null renders "never" (distinct from the whole model being unknown).
        composeRule.onNodeWithText("Last sync: never").assertExists()

        composeRule.onNodeWithText("Refresh stats").assertIsEnabled().performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun `idle state renders previous run summary when present`() {
        val summary = SyncSummary(
            syncedCount = 7,
            skippedCount = 0,
            removedCount = 0,
            lastSyncAt = 1_700_000_000_000L,
            error = null,
        )
        setContent(idle(isPaired = true, hasFolders = true, lastSummary = summary))

        // The idle heading and the carried-over last-run summary are both shown.
        composeRule.onNodeWithText("Sync your music").assertExists()
        composeRule.onNodeWithText("7 synced · 0 skipped · 0 removed").assertExists()
    }
}
