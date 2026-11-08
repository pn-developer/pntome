package dev.gsound.a.ui.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.data.folders.BookmarkDao
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.data.sync.SyncSummaryStore
import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.model.SyncSummary
import dev.gsound.a.domain.sync.SyncOrchestrator
import dev.gsound.a.log.GsoundLog
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.ControlChannel
import dev.gsound.a.net.garmin.messages.Ping
import dev.gsound.a.net.garmin.messages.Stats
import dev.gsound.a.service.SyncForegroundService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Immutable UI state for the Sync screen, driven as a [StateFlow] (design §6, Req 6).
 *
 * A flat, render-ready projection of three reactive inputs — the live [SyncState] from the
 * [SyncOrchestrator], the gating preconditions (paired + folders non-empty), and the persisted
 * last-sync [SyncSummary] — combined by [SyncViewModel]. Keeping it flat (rather than nesting the
 * domain [SyncState]) lets the stateless screen render each piece directly and lets this mapping
 * be unit-tested on the JVM via [from] without Android or Hilt.
 *
 * Gating (Req 6.1, 2.4): a sync may be started only when a pairing exists AND at least one folder
 * is configured. [canStartSync] additionally requires that no sync is currently active.
 *
 * The paired `sharedSecret` is never surfaced here (Req 8.4) — only the LAN [serverUrl] is shown,
 * for connectivity verification (Req 5.8).
 *
 * @property syncState the live orchestrator state being rendered.
 * @property isPaired whether an active pairing exists (one gating precondition, Req 6.1).
 * @property hasFolders whether at least one folder is configured (the other precondition,
 *   Req 2.4/6.1).
 * @property lastSummary the persisted last-sync summary shown while idle (Req 6.3), or null.
 * @property watchStats the latest watch-reported stats for the current pairing (Req 7.1), or null
 *   when **unknown** — no `STATS` received yet or not paired (Req 7.3). The screen renders "unknown"
 *   when null rather than showing stale numbers; see [WatchStats] for the staleness rule.
 */
data class SyncUiState(
    val syncState: SyncState = SyncState.Idle,
    val isPaired: Boolean = false,
    val hasFolders: Boolean = false,
    val lastSummary: SyncSummary? = null,
    val watchStats: WatchStats? = null,
    val bookmarks: List<BookmarkRow> = emptyList(),
) {

    /** True while a sync is in a non-terminal, active phase (Preparing or Running). */
    val isSyncing: Boolean
        get() = syncState is SyncState.Preparing || syncState is SyncState.Running

    /**
     * Whether both gating preconditions are met: paired AND folders non-empty (Req 6.1, 2.4).
     * This is the "could a sync ever start" condition, independent of a sync being in flight.
     */
    val canSync: Boolean
        get() = isPaired && hasFolders

    /**
     * Whether the Sync button should be enabled: gating satisfied AND no sync currently active
     * (Req 6.1, 6.6). While a sync runs the button is replaced by Cancel.
     */
    val canStartSync: Boolean
        get() = canSync && !isSyncing

    /** Show a Cancel affordance while a sync is active (Req 6.5). */
    val showCancel: Boolean
        get() = isSyncing

    /** Show a Retry affordance when the last attempt failed (Req 6.4). */
    val showRetry: Boolean
        get() = syncState is SyncState.Error

    /**
     * The LAN `http://ip:port` the music server is bound to while running, surfaced so the user
     * can verify connectivity (Req 5.8); null when not running or not yet known.
     */
    val serverUrl: String?
        get() = (syncState as? SyncState.Running)?.serverUrl

    /**
     * A human-readable reason the Sync button is disabled, or null when it is enabled or when the
     * disabled-ness is due to a sync already running (where the Cancel affordance explains state).
     * Drives the "tell the user why" hint under a disabled button (Req 6.1, 2.4).
     */
    val disabledReason: String?
        get() = when {
            isSyncing -> null
            !isPaired && !hasFolders -> "Pair your watch and add a music folder to sync."
            !isPaired -> "Pair your watch to sync."
            !hasFolders -> "Add a music folder to sync."
            else -> null
        }

    companion object {
        /**
         * Pure projection of the three reactive inputs into a [SyncUiState]. Extracted so the
         * combine mapping in [SyncViewModel] is a one-liner and so the gating/derived flags can
         * be unit-tested on the JVM (task 10.3 covers the screen; this covers the mapping).
         */
        fun from(
            syncState: SyncState,
            isPaired: Boolean,
            hasFolders: Boolean,
            lastSummary: SyncSummary?,
            watchStats: WatchStats? = null,
            bookmarks: List<BookmarkRow> = emptyList(),
        ): SyncUiState = SyncUiState(
            syncState = syncState,
            isPaired = isPaired,
            hasFolders = hasFolders,
            lastSummary = lastSummary,
            // Req 7.3: watch stats are only meaningful while paired. When unpaired, force
            // "unknown" (null) so a previous watch's counts can never be shown as if current.
            watchStats = if (isPaired) watchStats else null,
            // PL-4.6: the phone's own bookmarks do not depend on pairing or STATS.
            bookmarks = bookmarks,
        )
    }
}

/**
 * Backs [SyncScreen]: projects sync state for display and starts/cancels syncs (Req 6).
 *
 * ## Observe vs. drive — the service/orchestrator split
 * This view model **observes** [SyncOrchestrator.state] for the live UI, but **starts and cancels**
 * through [SyncForegroundService] rather than calling [SyncOrchestrator.startSync] directly. A sync
 * drives a local HTTP server the watch pulls from; if the user backgrounds the app, work started on
 * [viewModelScope] could be torn down mid-transfer. Routing start/cancel through the foreground
 * service keeps the sync alive when backgrounded (Req 8.3), while the orchestrator's hot
 * [StateFlow][SyncOrchestrator.state] remains the single source of truth both the service and this
 * view model read. The injected [@ApplicationContext][ApplicationContext] [Context] is used only to
 * send the service start/cancel intents.
 *
 * ## Gating (Req 6.1, 2.4)
 * Sync is enabled only when a pairing exists ([PairingStore.pairing]) AND at least one folder is
 * configured ([FolderRepository.observeFolders]). Both are observed reactively and combined into
 * [SyncUiState]; the screen disables the button and explains why when the preconditions aren't met.
 *
 * ## Last-sync summary (Req 6.3)
 * The persisted [SyncSummaryStore.summary] is combined in so the idle screen can show the previous
 * run's synced/skipped/removed counts and timestamp.
 *
 * ## Watch stats (Req 7.1–7.3)
 * `STATS` messages from the watch are observed on [ControlChannel.incoming]: a collector launched
 * in [init] keeps the latest [Stats] whose `pairingId` matches the current pairing in a private
 * [MutableStateFlow], which is combined into [SyncUiState.watchStats]. A `null` means **unknown**
 * (nothing received yet, or unpaired — [SyncUiState.from] forces unknown when not paired), and the
 * screen renders "unknown" rather than stale numbers (Req 7.3). Fresh stats are requested via
 * [onRefreshStatsClicked], which sends a `PING` only when paired **and** the watch is reachable
 * ([ConnectionState.Connected]); the watch answers with a `STATS` the collector picks up (Req 7.2).
 *
 * ## Deferred
 * UI gating/rendering tests are task 10.3.
 */
@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    orchestrator: SyncOrchestrator,
    private val pairingStore: PairingStore,
    folderRepository: FolderRepository,
    summaryStore: SyncSummaryStore,
    private val controlChannel: ControlChannel,
    bookmarkDao: BookmarkDao,
    private val bookmarkPusher: dev.gsound.a.domain.bookmarks.BookmarkPusher,
) : ViewModel() {

    /**
     * Latest watch-reported stats for the current pairing, or null when **unknown** (Req 7.1, 7.3).
     * Fed by the [ControlChannel.incoming] collector launched in [init]; combined into [uiState].
     */
    private val watchStats = MutableStateFlow<WatchStats?>(null)

    /** Logcat tag for sync diagnostics (filter: `adb logcat -s Gsound.Sync`). */
    private val syncTag = "Gsound.Sync"

    init {
        // Req 7.1: observe STATS from the watch and keep the latest for the current pairing.
        // Req 7.3: accept only a STATS whose pairingId matches the active pairing so an old/other
        // pairing's counts never become the displayed value. Unpaired → force unknown (null), which
        // also discards any previously retained counts.
        viewModelScope.launch {
            controlChannel.incoming
                .filterIsInstance<Stats>()
                .collect { stats ->
                    val currentPairingId = pairingStore.get()?.pairingId
                    if (currentPairingId != null && stats.pairingId == currentPairingId) {
                        GsoundLog.i(syncTag, "STATS received: files=${stats.fileCount} folders=${stats.folderCount} lastSyncAt=${stats.lastSyncAt}")
                        watchStats.value = WatchStats.from(stats)
                    } else {
                        GsoundLog.w(syncTag, "STATS ignored: pairingId mismatch (got=${stats.pairingId}, current=$currentPairingId)")
                        watchStats.value = null
                    }
                }
        }
    }

    /** Phone bookmarks joined with folder names; independent of the watch (PL-4.6). */
    private val bookmarkRows: Flow<List<BookmarkRow>> =
        combine(bookmarkDao.observeAll(), folderRepository.observeFolders(), BookmarkRows::from)

    val uiState: StateFlow<SyncUiState> =
        combine(
            orchestrator.state,
            pairingStore.pairing,
            folderRepository.observeFolders(),
            summaryStore.summary,
            combine(watchStats, bookmarkRows) { stats, rows -> stats to rows },
        ) { syncState, pairing, folders, summary, (stats, rows) ->
            SyncUiState.from(
                syncState = syncState,
                isPaired = pairing != null,
                hasFolders = folders.isNotEmpty(),
                lastSummary = summary,
                watchStats = stats,
                bookmarks = rows,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SyncUiState(),
        )

    /**
     * Start a sync via the foreground service (Req 6.1, 8.3). No-op guard against double-starts
     * lives in the orchestrator's single-sync mutex and the service's start guard; the screen also
     * only enables this when [SyncUiState.canStartSync] is true.
     */
    fun onSyncClicked() {
        GsoundLog.i(syncTag, "onSyncClicked: starting sync foreground service")
        SyncForegroundService.start(context)
    }

    /** Cancel the active sync via the foreground service (Req 6.5). */
    fun onCancelClicked() {
        GsoundLog.i(syncTag, "onCancelClicked: cancelling sync")
        SyncForegroundService.cancel(context)
    }

    /** Retry after a failed sync by starting again (Req 6.4). Same path as [onSyncClicked]. */
    fun onRetryClicked() {
        GsoundLog.i(syncTag, "onRetryClicked: restarting sync")
        SyncForegroundService.start(context)
    }

    /**
     * Request fresh watch stats (Req 7.2).
     *
     * There is no dedicated `STATS_REQUEST` wire type in the control protocol, so a `PING` carrying
     * the current `pairingId` is used as the stats request (the task's "via PING/stats request") —
     * no new wire type is invented. The watch answers with a `STATS` message, which the
     * [ControlChannel.incoming] collector in [init] picks up and projects into [uiState].
     *
     * Guarded by the "paired AND watch reachable" precondition (Req 7.2): this is a no-op unless a
     * pairing exists **and** [ControlChannel.connectionState] is [ConnectionState.Connected]. When
     * not reachable there is nothing to ask, so stats simply stay at their last value (or
     * "unknown"); nothing stale is fabricated.
     */
    fun onRefreshStatsClicked() {
        viewModelScope.launch {
            val pairing = pairingStore.get() ?: run {
                GsoundLog.d(syncTag, "refreshStats: no pairing -> skip")
                return@launch
            }
            val state = controlChannel.connectionState.value
            if (state !is ConnectionState.Connected) {
                GsoundLog.d(syncTag, "refreshStats: channel not connected (state=${state::class.simpleName}) -> skip")
                return@launch
            }
            GsoundLog.i(syncTag, "refreshStats: sending PING to request STATS (pairingId=${pairing.pairingId})")
            // PL-5.1: after STATS and the watch's BOOKMARKs have landed, push newer phone bookmarks.
            bookmarkPusher.pushAfterStats { controlChannel.send(Ping(pairingId = pairing.pairingId)) }
        }
    }
}
