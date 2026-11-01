package dev.gsound.a.domain.sync

import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.data.sync.SyncSummaryStore
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.model.SyncSummary
import dev.gsound.a.domain.model.Track
import dev.gsound.a.log.GsoundLog
import dev.gsound.a.log.Logger
import dev.gsound.a.net.garmin.ControlChannel
import dev.gsound.a.net.garmin.messages.SyncManifest
import dev.gsound.a.net.garmin.messages.SyncStatus
import dev.gsound.a.net.server.GsoundHttpServer
import dev.gsound.a.net.server.ServerTransferMonitor
import dev.gsound.a.net.server.SyncSession
import dev.gsound.a.net.server.SyncSessionHolder
import dev.gsound.a.net.server.WifiGuard
import dev.gsound.a.net.server.WifiStatus
import kotlinx.coroutines.CancellationException
import dev.gsound.a.net.server.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestration core of a sync (design §5.6; Req 5.6, 5.7, 6.1–6.6, 8.3).
 *
 * Wires together the pieces built in earlier tasks into a single, drivable sync flow:
 * 1. build the wire [dev.gsound.a.net.garmin.messages.Manifest] from the Room track cache
 *    (pure [ManifestBuilder]),
 * 2. start the local [GsoundHttpServer] bound to the Wi-Fi LAN,
 * 3. install a [SyncSession] (manifest + per-track SAF `documentUri` + `secret`) in the
 *    [SyncSessionHolder] so `/manifest` and `/media/{trackId}` serve this sync,
 * 4. send `SYNC_MANIFEST { serverUrl, tracks }` to the watch over the [ControlChannel],
 * 5. collect `SYNC_STATUS` replies and project them onto [state] as [SyncState.Running],
 * 6. on a terminal status write a [SyncSummary] and transition to [SyncState.Completed],
 * 7. always stop the server and clear the session on completion/cancel/error (Req 5.6).
 *
 * ## State
 * The current sync state is exposed as a hot [state] `StateFlow<SyncState>` the `SyncViewModel`
 * (task 10) collects. It starts [SyncState.Idle] and moves through
 * `Preparing → Running(…) → Completed/Error/Cancelled` and back toward a resting state.
 *
 * ## Single-sync mutex (Req 6.6)
 * [startSync] refuses to begin a second sync while one is active. A non-suspending guard
 * ([Mutex.tryLock]) is used so a concurrent trigger is *rejected* (returns without disturbing
 * the in-flight sync) rather than queued — concurrent syncs are a user error, not work to defer.
 *
 * ## Manifest / secret decision
 * The [SyncManifest] wire type carries `pairingId`, `serverUrl`, and `tracks` only — it has **no
 * `secret` field** (see `ControlMessage.kt`). The shared secret is established at pairing and
 * authenticates HTTP requests; it travels on the [SyncSession] (`SyncSession.secret`, consumed by
 * `DefaultSecretProvider` for the `X-Gsound-Secret` gate), **not** on the control message. This
 * matches the actual message type where it diverges from design §5.6's `SYNC_MANIFEST { …, secret }`
 * sketch.
 *
 * ## Cancel semantics
 * There is no `CANCEL` message in the control protocol. [cancelSync] therefore stops the local
 * server and clears the session (the watch observes the data channel going away and stops
 * fetching) and sets [SyncState.Cancelled]. No new wire type is invented (that would require a
 * coordinated change to the shared [dev.gsound.a.net.garmin.messages.ControlMessage] contract).
 *
 * ## Scope / testability
 * Collaborators are injected; [ControlChannel] is already an interface (fakeable). The server
 * and stores are concrete `@Singleton`s today — task 9.4 can wrap/relocate them behind
 * interfaces if needed, but the orchestration logic here depends only on their small public
 * surface. The incoming-status collection runs on the caller's coroutine inside [startSync] so
 * no long-lived background scope is owned here; the foreground service (task 9.2) provides the
 * scope that keeps a sync alive when backgrounded.
 */
@Singleton
class SyncOrchestrator @Inject constructor(
    private val folderRepository: FolderRepository,
    private val pairingStore: PairingStore,
    private val summaryStore: SyncSummaryStore,
    private val controlChannel: ControlChannel,
    private val httpServer: GsoundHttpServer,
    private val sessionHolder: SyncSessionHolder,
    private val transferMonitor: ServerTransferMonitor,
    private val wifiGuard: WifiGuard,
    private val logger: Logger = GsoundLog,
) {

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)

    /** Logcat tag for sync-orchestration diagnostics (filter: `adb logcat -s Gsound.Sync`). */
    private val tag = "Gsound.Sync"

    /** Live sync state for the UI (task 10) to collect. Starts [SyncState.Idle]. */
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Guards against a second concurrent sync (Req 6.6). Held for the whole [startSync] body and
     * released in its `finally`; a concurrent caller fails [Mutex.tryLock] and returns.
     */
    private val syncMutex = Mutex()

    /**
     * Run a sync end-to-end, suspending until it reaches a terminal state
     * ([SyncState.Completed], [SyncState.Error], or [SyncState.Cancelled]). Progress is published
     * on [state] as it proceeds.
     *
     * A second call while a sync is active is refused immediately (Req 6.6) and leaves the
     * in-flight sync untouched.
     *
     * The local server is always stopped and the session always cleared before returning,
     * regardless of outcome (Req 5.6), via a `finally` block.
     *
     * @return the terminal [SyncState] this call resolved to (also observable via [state]).
     */
    /**
     * Synchronously move [state] out of any retained terminal value to [SyncState.Preparing]
     * **before** a sync coroutine is launched.
     *
     * [state] is a hot singleton [StateFlow], so it keeps the last terminal value
     * (Completed/Error/Cancelled) between runs. A fresh collector — notably
     * [dev.gsound.a.service.SyncForegroundService]'s state observer — replays that retained value
     * the instant it subscribes. If that replayed value is terminal, the service calls
     * `stopSelf()` immediately, which cancels the just-launched [startSync] coroutine and surfaces
     * as a spurious `JobCancellationException`. Calling this first makes the replayed value
     * [SyncState.Preparing], so the observer sees a correct in-progress state instead.
     */
    fun beginSync() {
        _state.value = SyncState.Preparing
    }

    suspend fun startSync(): SyncState {
        // Req 6.6: reject a concurrent sync without disturbing the active one.
        if (!syncMutex.tryLock()) {
            logger.w(tag, "startSync rejected: a sync is already active (Req 6.6)")
            return _state.value
        }

        try {
            logger.i(tag, "startSync: state=Preparing")
            // Clear any terminal value retained by this singleton StateFlow from a *previous*
            // sync run. Observers that subscribe before this coroutine runs must not see a stale
            // Completed/Error/Cancelled (which would make the foreground service stopSelf() and
            // cancel this very sync). beginSync() already sets Preparing synchronously for that
            // reason; this keeps it true on the direct startSync() path too.
            _state.value = SyncState.Preparing

            // Req 6.1: a sync requires an active pairing (pairingId + sharedSecret).
            val pairing = pairingStore.get()
                ?: run {
                    logger.w(tag, "startSync abort: no pairing")
                    return transition(SyncState.Error("Pair with your watch before syncing."))
                }
            logger.i(tag, "startSync: pairing ok (pairingId=${pairing.pairingId})")

            // Build the manifest from the current Room track cache (pure mapping). A "fresh scan
            // if stale" is intentionally driven elsewhere (ScanAndCacheUseCase / the Folders UI);
            // this uses the latest cached snapshot to stay minimal.
            val tracks = folderRepository.getAllTracks()
            if (tracks.isEmpty()) {
                logger.w(tag, "startSync abort: no cached tracks (add a folder/scan first)")
                return transition(SyncState.Error("Add a folder with music before syncing."))
            }
            val manifest = ManifestBuilder.build(tracks)
            logger.i(tag, "manifest built: ${manifest.tracks.size} track(s) from ${tracks.size} cached")

            // Req 5.7: Wi-Fi precondition — check BEFORE touching the server and refuse to start
            // it when Wi-Fi is down, carrying the centralized "same Wi-Fi network" guidance
            // (WifiGuard.GUIDANCE_MESSAGE). This makes "do not start the server" explicit here
            // rather than relying only on the server's own WifiUnavailable fail-fast (which
            // remains as a backstop below).
            if (wifiGuard.check() is WifiStatus.Unavailable) {
                logger.w(tag, "startSync abort: Wi-Fi unavailable before server start (Req 5.7)")
                return transition(SyncState.Error(WifiGuard.GUIDANCE_MESSAGE))
            }

            // Req 5.1 / 5.8: start the LAN server. Map each outcome to a clear state. The
            // WifiUnavailable branch is a backstop — the guard above normally catches Wi-Fi loss
            // first, but a race (Wi-Fi dropping between the check and the bind) still resolves to
            // the same canonical guidance.
            val serverUrl = when (val result = httpServer.start()) {
                is GsoundHttpServer.StartResult.Started -> {
                    logger.i(tag, "HTTP server started at ${result.url}")
                    result.url
                }
                is GsoundHttpServer.StartResult.WifiUnavailable -> {
                    logger.w(tag, "HTTP server start: Wi-Fi unavailable (race backstop)")
                    return transition(SyncState.Error(WifiGuard.GUIDANCE_MESSAGE))
                }
                is GsoundHttpServer.StartResult.Error -> {
                    logger.e(tag, "HTTP server start error: ${result.detail}")
                    return transition(SyncState.Error(result.detail))
                }
            }

            // Install the session the server serves: manifest + per-track SAF documentUri (from
            // the Room cache) + the pairing secret for HTTP auth (Req 8.3). The secret travels
            // here, not on SYNC_MANIFEST (see class doc).
            val documentUriByTrackId: Map<String, String> = tracks.associate { it.id to it.documentUri }
            val session = SyncSession.from(
                manifest = manifest,
                secret = pairing.sharedSecret,
                documentUriFor = { trackId -> documentUriByTrackId[trackId].orEmpty() }
            )
            sessionHolder.set(session)
            // Start watching real HTTP byte activity so the UI shows download progress/speed even
            // if the watch never sends SYNC_STATUS (observed in the field).
            transferMonitor.begin(session)
            logger.i(tag, "sync session installed; sending SYNC_MANIFEST (serverUrl=$serverUrl, tracks=${manifest.tracks.size})")

            // Req 6.1: tell the watch where to fetch and what to fetch. The ControlChannel impl
            // (ConnectIqManager) applies the retry/backoff (Req 10.2).
            val sendResult = controlChannel.send(
                SyncManifest(
                    pairingId = pairing.pairingId,
                    serverUrl = serverUrl,
                    tracks = manifest.tracks
                )
            )
            if (sendResult.isFailure) {
                logger.w(tag, "SYNC_MANIFEST send failed: ${sendResult.exceptionOrNull()?.javaClass?.simpleName}")
                return transition(
                    SyncState.Error(
                        sendResult.exceptionOrNull()?.message
                            ?: "Could not reach the watch. Check the connection and try again."
                    )
                )
            }
            logger.i(tag, "SYNC_MANIFEST sent; awaiting SYNC_STATUS updates")

            // Req 6.2 / 6.3: collect SYNC_STATUS updates until a terminal one, projecting
            // progress onto `state`. collectStatuses returns the terminal SyncState.
            val terminal = collectStatuses(
                pairing,
                totalTracks = manifest.tracks.size,
                serverUrl = serverUrl
            )
            logger.i(tag, "sync terminal: ${terminal::class.simpleName}")
            return transition(terminal)
        } catch (c: CancellationException) {
            // Structured-concurrency cancellation (e.g. the foreground service's lifecycleScope
            // being torn down) is NOT a sync failure. Re-throw so cancellation propagates and is
            // not mislabeled as SyncState.Error (this also stops the misleading
            // "sync threw: JobCancellationException" error log). The `finally` below still runs
            // teardown, so the server is stopped and the session cleared regardless.
            logger.i(tag, "sync cancelled; propagating cancellation")
            throw c
        } catch (t: Throwable) {
            logger.e(tag, "sync threw: ${t.javaClass.simpleName}", t)
            return transition(
                SyncState.Error(t.message ?: "Sync failed. Please try again.")
            )
        } finally {
            // Req 5.6: never leave the server running or a session installed.
            logger.i(tag, "sync teardown: stopping server + clearing session")
            stopServerAndClearSession()
            syncMutex.unlock()
        }
    }

    /**
     * Cancel the active sync (Req 6.5). Stops the local server and clears the session so the
     * watch stops fetching, and transitions [state] to [SyncState.Cancelled].
     *
     * Because there is no `CANCEL` control message (see class doc), cancellation is signalled
     * by tearing down the data channel rather than sending a wire message. Safe to call when no
     * sync is active (it still ensures the server is stopped).
     */
    suspend fun cancelSync() {
        logger.i(tag, "cancelSync: tearing down data channel -> Cancelled")
        stopServerAndClearSession()
        _state.value = SyncState.Cancelled
    }

    /**
     * Drive [state] from two merged progress sources and return the terminal [SyncState].
     *
     * 1. **Watch control-channel** `SYNC_STATUS` messages (the original source): authoritative for
     *    watch-reported errors and watch-reported completion.
     * 2. **Server-observed transfer** ([ServerTransferMonitor]): the actual bytes the watch pulls
     *    from the local HTTP server. This makes progress and speed visible **even when the watch
     *    never sends `SYNC_STATUS`** — the real-world failure mode where the UI was stuck on
     *    "Preparing".
     *
     * Precedence: a watch error is terminal immediately. Otherwise whichever source shows more
     * completed tracks wins the progress projection, and completion resolves when **either** the
     * watch reports all tracks done **or** the server has fully served every track. Completion
     * persists a [SyncSummary] (Req 6.3, 8.3).
     *
     * @param serverUrl the bound LAN `http://ip:port`, forwarded onto each [SyncState.Running]
     *   so the UI can show it (Req 5.8).
     */
    private suspend fun collectStatuses(
        pairing: PairingRecord,
        totalTracks: Int,
        serverUrl: String
    ): SyncState {
        var terminal: SyncState? = null
        // Last values seen from each source, so a progress emission from one doesn't erase the
        // richer picture from the other.
        var watchDone = 0
        var watchBytes = 0L
        var serverProgress: TransferProgress? = null

        val watchEvents = controlChannel.incoming
            .filterIsInstance<SyncStatus>()
            .map<SyncStatus, ProgressEvent> { ProgressEvent.Watch(it) }
        val serverEvents = transferMonitor.progress
            .filterNotNull()
            .map<TransferProgress, ProgressEvent> { ProgressEvent.Server(it) }

        merge(watchEvents, serverEvents).collectUntil({ terminal != null }) { event ->
            when (event) {
                is ProgressEvent.Watch -> {
                    val status = event.status
                    if (status.pairingId != pairing.pairingId) {
                        logger.w(tag, "SYNC_STATUS ignored: pairingId mismatch (got=${status.pairingId})")
                        return@collectUntil
                    }
                    val total = if (status.total > 0) status.total else totalTracks
                    logger.i(
                        tag,
                        "SYNC_STATUS: done=${status.done}/${total} bytes=${status.bytesDone}" +
                            (status.error?.let { " error=$it" } ?: "")
                    )
                    if (status.error != null) {
                        logger.w(tag, "watch reported error -> terminal Error")
                        terminal = SyncState.Error(status.error!!)
                        return@collectUntil
                    }
                    watchDone = status.done
                    watchBytes = status.bytesDone
                    if (total > 0 && status.done >= total) {
                        terminal = complete(done = status.done, total = total)
                        return@collectUntil
                    }
                }

                is ProgressEvent.Server -> {
                    serverProgress = event.progress
                    logger.i(
                        tag,
                        "server transfer: complete=${event.progress.tracksComplete}/${event.progress.tracksTotal} " +
                            "bytes=${event.progress.bytesServed}" +
                            (event.progress.speedBytesPerSec?.let { " speed=${it}B/s" } ?: "")
                    )
                    val p = event.progress
                    if (p.tracksTotal > 0 && p.tracksComplete >= p.tracksTotal) {
                        terminal = complete(done = p.tracksComplete, total = p.tracksTotal)
                        return@collectUntil
                    }
                }
            }

            // Project merged progress: take whichever source shows more completed tracks / more
            // bytes, and enrich with server-observed total/speed/current-track when available.
            val sp = serverProgress
            val tracksDone = maxOf(watchDone, sp?.tracksComplete ?: 0)
            val bytesSent = maxOf(watchBytes, sp?.bytesServed ?: 0L)
            _state.value = SyncState.Running(
                tracksDone = tracksDone,
                tracksTotal = sp?.tracksTotal?.takeIf { it > 0 } ?: totalTracks,
                bytesSent = bytesSent,
                bytesTotal = sp?.bytesTotal,
                currentTrackTitle = sp?.currentTrackTitle,
                speedBytesPerSec = sp?.speedBytesPerSec,
                serverUrl = serverUrl // Req 5.8: surface the LAN address to the UI.
            )
        }

        if (terminal == null) {
            logger.w(tag, "progress stream ended with no terminal status")
        }
        return terminal ?: SyncState.Error("The watch stopped reporting progress.")
    }

    /** Persist a success [SyncSummary] and build the terminal [SyncState.Completed] (Req 6.3, 8.3). */
    private suspend fun complete(done: Int, total: Int): SyncState {
        val summary = SyncSummary(
            syncedCount = done,
            skippedCount = (total - done).coerceAtLeast(0),
            removedCount = 0,
            lastSyncAt = System.currentTimeMillis(),
            error = null
        )
        summaryStore.save(summary)
        logger.i(tag, "sync COMPLETE: synced=${summary.syncedCount} skipped=${summary.skippedCount}")
        return SyncState.Completed(summary)
    }

    /** Apply a terminal/intermediate [next] state and return it, for `return transition(...)`. */
    private fun transition(next: SyncState): SyncState {
        _state.value = next
        return next
    }

    /** Stop the server and clear the active session (Req 5.6); best-effort, never throws. */
    private suspend fun stopServerAndClearSession() {
        runCatching { httpServer.stop() }
        sessionHolder.clear()
        transferMonitor.reset()
    }
}

/**
 * Collect this flow, invoking [action] for each value, stopping once [stop] returns true
 * (checked after each [action]). A small helper so [SyncOrchestrator] can terminate collection
 * on a terminal status without throwing to break the `collect`.
 */
private suspend inline fun <T> Flow<T>.collectUntil(
    crossinline stop: () -> Boolean,
    crossinline action: suspend (T) -> Unit
) {
    try {
        collect { value ->
            action(value)
            if (stop()) throw StopCollection
        }
    } catch (_: StopCollection) {
        // normal termination
    }
}

/**
 * A unified progress event merged from the two sources [SyncOrchestrator.collectStatuses]
 * listens to: watch-reported [SyncStatus] messages and server-observed [TransferProgress].
 */
private sealed interface ProgressEvent {
    data class Watch(val status: SyncStatus) : ProgressEvent
    data class Server(val progress: TransferProgress) : ProgressEvent
}

/** Sentinel used by [collectUntil] to break out of a `collect` without surfacing an error. */
private object StopCollection : Throwable() {
    private fun readResolve(): Any = StopCollection
    override fun fillInStackTrace(): Throwable = this
}
