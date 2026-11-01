package dev.gsound.a.domain.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.log.NoopLogger
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.data.sync.SyncSummaryStore
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.model.Track
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.FakeControlChannel
import dev.gsound.a.net.garmin.messages.SyncManifest
import dev.gsound.a.net.garmin.messages.SyncStatus
import dev.gsound.a.net.server.GsoundHttpServer
import dev.gsound.a.net.server.ServerTransferMonitor
import dev.gsound.a.net.server.SyncSessionHolder
import dev.gsound.a.net.server.WifiAddressResolver
import dev.gsound.a.net.server.WifiGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM/Robolectric tests for [SyncOrchestrator] (task 9.4, Req 5.6, 5.7, 6.1–6.6, 8.3).
 *
 * ## Test harness
 * The orchestrator is wired against real collaborators wherever that stays deterministic on the
 * JVM, and a tiny fake only where a real one would need hardware/the SDK:
 *
 * - **ControlChannel** → the shared [FakeControlChannel]: records every [SyncManifest] sent and
 *   replays scripted [SyncStatus] replies via [FakeControlChannel.emitIncoming].
 * - **FolderRepository** → the real repository over an in-memory [GsoundDatabase] (Robolectric),
 *   following the precedent in `ScanAndCacheUseCaseTest`. Tracks are seeded with
 *   `upsertFolder` + `upsertTracks` so `getAllTracks()` drives a real manifest.
 * - **GsoundHttpServer** → the **real** server, constructed with a fake [WifiAddressResolver]
 *   returning `127.0.0.1` and no routing modules, so `start()` binds a real Ktor CIO socket on
 *   loopback (the "in-memory server" for this unit test) and `stop()` releases it. This genuinely
 *   exercises the start/stop lifecycle and the `WifiUnavailable` path (fake resolver → null).
 * - **SyncSummaryStore** → the real Proto DataStore-backed store pointed at the Robolectric app's
 *   temp files dir. The authoritative completion assertion is the orchestrator's own
 *   `SyncState.Completed(summary)`; the store write is additionally asserted via `get()` after the
 *   (awaited) sync, which is reliable because the whole `startSync()` call is joined before reading.
 * - **SyncSessionHolder** / **WifiGuard** → the real (trivial) implementations.
 *
 * ## Coroutine model
 * The orchestrator owns no `Dispatchers.Main` scope (no `viewModelScope`) and has **no internal
 * `delay`**, so virtual time buys nothing here; instead the real Ktor CIO socket bind, the Room
 * query, and the DataStore write genuinely suspend on their own dispatchers. These tests therefore
 * drive it with a `runBlocking` scope and real dispatchers:
 * - guard / send-failure paths call `startSync()` inline (it returns without ever blocking on a
 *   status), and
 * - the status-driven paths launch `startSync()` on [Dispatchers.Default] (it parks in its
 *   status-collection loop), then [awaitSent]/[awaitUntil] poll for the real work to settle before
 *   emitting [SyncStatus] replies through the fake channel and awaiting the terminal state.
 *
 * This keeps the whole lifecycle (bind → serve → tear down) real while staying deterministic via
 * short bounded polling rather than virtual-time stepping.
 */
@RunWith(RobolectricTestRunner::class)
class SyncOrchestratorTest {

    private lateinit var db: GsoundDatabase
    private lateinit var repository: FolderRepository
    private lateinit var pairingStore: FakePairingStore
    private lateinit var summaryStore: SyncSummaryStore
    private lateinit var channel: FakeControlChannel
    private lateinit var resolver: MutableWifiAddressResolver
    private lateinit var httpServer: GsoundHttpServer
    private lateinit var sessionHolder: SyncSessionHolder
    private lateinit var transferMonitor: ServerTransferMonitor
    private lateinit var wifiGuard: WifiGuard
    private lateinit var orchestrator: SyncOrchestrator

    private val pairing = PairingRecord(
        pairingId = "11111111-2222-3333-4444-555555555555",
        peerName = "fenix 7",
        sharedSecret = "c2hhcmVkLXNlY3JldA",
        createdAt = 1_700_000_000_000L
    )

    private val folderId = "folder-1"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()

        db = Room.inMemoryDatabaseBuilder(app, GsoundDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FolderRepository(db.folderDao(), db.trackDao())

        pairingStore = FakePairingStore()
        summaryStore = SyncSummaryStore(app)
        channel = FakeControlChannel(initialState = ConnectionState.Connected)
        resolver = MutableWifiAddressResolver("127.0.0.1")
        httpServer = GsoundHttpServer(resolver, emptySet())
        sessionHolder = SyncSessionHolder()
        transferMonitor = ServerTransferMonitor()
        wifiGuard = WifiGuard(resolver)

        orchestrator = SyncOrchestrator(
            folderRepository = repository,
            pairingStore = pairingStore,
            summaryStore = summaryStore,
            controlChannel = channel,
            httpServer = httpServer,
            sessionHolder = sessionHolder,
            transferMonitor = transferMonitor,
            wifiGuard = wifiGuard,
            logger = NoopLogger,
        )
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { httpServer.stop() } }
        db.close()
    }

    // --- Pre-flight guards (server must NOT start) -----------------------------------------------

    @Test
    fun `no pairing yields Error and does not start the server`() = runBlocking {
        val result = orchestrator.startSync()

        assertTrue("expected Error but was $result", result is SyncState.Error)
        assertEquals(SyncState.Error("Pair with your watch before syncing."), result)
        assertServerNotStarted()
    }

    @Test
    fun `empty track cache yields Error and does not start the server`() = runBlocking {
        pairingStore.save(pairing)
        // No tracks seeded.

        val result = orchestrator.startSync()

        assertTrue(result is SyncState.Error)
        assertEquals(SyncState.Error("Add a folder with music before syncing."), result)
        assertServerNotStarted()
    }

    @Test
    fun `wifi unavailable yields guidance Error and does not start the server`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))
        resolver.ipv4 = null // Wi-Fi down.

        val result = orchestrator.startSync()

        assertEquals(SyncState.Error(WifiGuard.GUIDANCE_MESSAGE), result)
        assertServerNotStarted()
        // Nothing was sent to the watch and no session was installed (Req 5.7).
        assertTrue(channel.sent.isEmpty())
        assertNull(sessionHolder.current)
    }

    // --- Happy path -----------------------------------------------------------------------------

    @Test
    fun `happy path sends SYNC_MANIFEST, installs session, completes and tears down`() =
        runBlocking {
            pairingStore.save(pairing)
            seedTracks(track("a"), track("b"))

            val run = startSyncAsync()
            // Wait for prepare/start/send to finish (server bound, manifest sent, session set).
            val manifestMsg = awaitSent() as SyncManifest

            // SYNC_MANIFEST was sent with the server URL, pairingId, and the two tracks (Req 6.1).
            assertEquals(pairing.pairingId, manifestMsg.pairingId)
            assertTrue(
                "serverUrl should be the bound loopback URL, was ${manifestMsg.serverUrl}",
                manifestMsg.serverUrl.startsWith("http://127.0.0.1:")
            )
            assertEquals(listOf("a", "b"), manifestMsg.tracks.map { it.id })

            // Session installed with the pairing secret and mapped tracks (Req 8.3).
            val session = sessionHolder.current
            assertNotNull("session should be installed while syncing", session)
            assertEquals(pairing.sharedSecret, session!!.secret)
            assertEquals(setOf("a", "b"), session.tracks.keys)
            assertEquals("content://docs/a", session.tracks["a"]!!.documentUri)

            // Server is actually bound.
            assertTrue(httpServer.isRunning)

            // Emit a terminal status (all done) → Completed(summary).
            channel.emitIncoming(terminalStatus(done = 2, total = 2, bytes = 2_048L))
            val terminal = awaitTerminal(run)

            val completed = terminal as SyncState.Completed
            assertEquals(2, completed.summary.syncedCount)
            assertEquals(0, completed.summary.skippedCount)
            assertEquals(completed, orchestrator.state.value)

            // Teardown: server stopped and session cleared (Req 5.6).
            assertFalse("server should be stopped after completion", httpServer.isRunning)
            assertNull("session should be cleared after completion", sessionHolder.current)

            // The summary was additionally persisted (Req 6.3, 8.3).
            val persisted = summaryStore.get()
            assertNotNull(persisted)
            assertEquals(2, persisted!!.syncedCount)
        }

    // --- Progress -------------------------------------------------------------------------------

    @Test
    fun `intermediate status projects Running with progress and serverUrl`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"), track("b"), track("c"))

        val run = startSyncAsync()
        val serverUrl = (awaitSent() as SyncManifest).serverUrl

        // Partial progress.
        channel.emitIncoming(
            SyncStatus(
                pairingId = pairing.pairingId,
                state = "transferring",
                done = 1,
                total = 3,
                bytesDone = 512L,
                error = null
            )
        )
        awaitUntil("state becomes Running") { orchestrator.state.value is SyncState.Running }

        val running = orchestrator.state.value as SyncState.Running
        assertEquals(1, running.tracksDone)
        assertEquals(3, running.tracksTotal)
        assertEquals(512L, running.bytesSent)
        assertEquals(serverUrl, running.serverUrl)

        // Finish so the launched coroutine completes cleanly.
        channel.emitIncoming(terminalStatus(done = 3, total = 3, bytes = 1_500L))
        awaitTerminal(run)
        Unit
    }

    // --- Watch-reported error -------------------------------------------------------------------

    @Test
    fun `watch error status yields Error and tears down`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))

        val run = startSyncAsync()
        awaitSent()
        assertTrue(httpServer.isRunning)

        channel.emitIncoming(
            SyncStatus(
                pairingId = pairing.pairingId,
                state = "error",
                done = 0,
                total = 1,
                bytesDone = 0L,
                error = "watch out of space"
            )
        )
        val terminal = awaitTerminal(run)

        assertEquals(SyncState.Error("watch out of space"), terminal)
        assertFalse(httpServer.isRunning)
        assertNull(sessionHolder.current)
    }

    // --- Send failure ---------------------------------------------------------------------------

    @Test
    fun `send failure yields Error and tears down`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))
        channel.sendResults.addLast(Result.failure(RuntimeException("transport down")))

        val result = orchestrator.startSync()

        assertTrue("expected Error but was $result", result is SyncState.Error)
        assertEquals("transport down", (result as SyncState.Error).message)
        assertFalse(httpServer.isRunning)
        assertNull(sessionHolder.current)
    }

    // --- Single-sync mutex (Req 6.6) ------------------------------------------------------------

    @Test
    fun `second startSync while active is refused without disturbing the first`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))

        val run = startSyncAsync()
        awaitSent()
        // First sync is active: manifest sent once, server up.
        assertEquals(1, channel.sent.size)
        assertTrue(httpServer.isRunning)

        // Concurrent trigger returns immediately (current state) without a second manifest.
        val rejected = orchestrator.startSync()
        assertEquals(1, channel.sent.size)
        assertTrue(
            "rejected call should reflect the active sync's state, was $rejected",
            rejected is SyncState.Running || rejected is SyncState.Preparing
        )
        assertTrue(httpServer.isRunning)

        // Let the first finish.
        channel.emitIncoming(terminalStatus(done = 1, total = 1, bytes = 10L))
        awaitTerminal(run)
        assertFalse(httpServer.isRunning)
    }

    // --- Cancel (Req 6.5) -----------------------------------------------------------------------

    @Test
    fun `cancelSync stops the server, clears the session and sets Cancelled`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))

        val run = startSyncAsync()
        awaitSent()
        assertTrue(httpServer.isRunning)
        assertNotNull(sessionHolder.current)

        orchestrator.cancelSync()

        awaitUntil("state becomes Cancelled") { orchestrator.state.value is SyncState.Cancelled }
        assertEquals(SyncState.Cancelled, orchestrator.state.value)
        assertFalse(httpServer.isRunning)
        assertNull(sessionHolder.current)

        // The still-collecting startSync is unblocked by a terminal status so the launched
        // coroutine ends and the test completes cleanly (its own finally re-asserts teardown).
        channel.emitIncoming(terminalStatus(done = 1, total = 1, bytes = 10L))
        awaitTerminal(run)
        Unit
    }

    // --- Cancellation is propagated, not swallowed as Error -------------------------------------

    @Test
    fun `coroutine cancellation propagates and is not mapped to Error`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))

        // Launch a sync and let it park in status collection (server bound, manifest sent).
        val run = startSyncAsync()
        awaitSent()
        assertTrue(httpServer.isRunning)

        // Cancel the coroutine running startSync() — this models the foreground service's
        // lifecycleScope being torn down. The orchestrator must NOT catch this and turn it into
        // SyncState.Error (the old "sync threw: JobCancellationException" bug); it must propagate.
        run.cancel()

        var cancelled = false
        try {
            run.await()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation should propagate out of startSync()", cancelled)

        // State was never flipped to Error by the cancellation.
        assertFalse(
            "cancellation must not be mapped to SyncState.Error",
            orchestrator.state.value is SyncState.Error
        )

        // Teardown still ran via finally: server stopped, session cleared (Req 5.6).
        awaitUntil("server stopped after cancellation teardown") { !httpServer.isRunning }
        assertNull("session should be cleared after cancellation", sessionHolder.current)
    }

    // --- beginSync clears a retained terminal state ---------------------------------------------

    @Test
    fun `beginSync clears a retained terminal state to Preparing`() = runBlocking {
        pairingStore.save(pairing)
        seedTracks(track("a"))

        // Drive one sync to a terminal Error so the singleton StateFlow retains it.
        channel.sendResults.addLast(Result.failure(RuntimeException("transport down")))
        val first = orchestrator.startSync()
        assertTrue(first is SyncState.Error)
        assertTrue(orchestrator.state.value is SyncState.Error)

        // beginSync() must move the retained terminal value to Preparing BEFORE a new run — this
        // is what stops a fresh state observer from replaying the stale terminal and killing the
        // next sync.
        orchestrator.beginSync()
        assertEquals(SyncState.Preparing, orchestrator.state.value)
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Launch [SyncOrchestrator.startSync] on a real dispatcher so it can park on status collection. */
    private fun CoroutineScope.startSyncAsync(): Deferred<SyncState> =
        async(Dispatchers.Default) { orchestrator.startSync() }

    /**
     * Await (with a wall-clock bound) until the first control message is recorded, then until the
     * orchestrator has actually subscribed to `incoming`, and return the recorded message.
     *
     * The subscription wait is essential: `startSync` records the `SYNC_MANIFEST` send *before*
     * `collectStatuses` subscribes to the incoming status flow, which has `replay = 0`. Waiting
     * only on `sent` and then emitting a status races the collector's subscription — under load
     * the status is emitted first and dropped, so the collector parks forever and `run.await()`
     * times out (this is what intermittently failed the cancel test). Gating on
     * [FakeControlChannel.subscriptionCount] makes every status-driven test deterministic.
     */
    private suspend fun awaitSent(): dev.gsound.a.net.garmin.messages.ControlMessage {
        awaitUntil("a control message to be sent") { channel.sent.isNotEmpty() }
        awaitUntil("the orchestrator to subscribe to incoming statuses") {
            channel.subscriptionCount.value > 0
        }
        return channel.sent.first()
    }

    /** Await a launched sync's terminal [SyncState] with a wall-clock bound. */
    private suspend fun awaitTerminal(run: Deferred<SyncState>): SyncState =
        withTimeout(POLL_TIMEOUT_MS) { run.await() }

    /** Poll [condition] on real time until true, failing after [POLL_TIMEOUT_MS]. */
    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        withTimeout(POLL_TIMEOUT_MS) {
            while (!condition()) delay(POLL_STEP_MS)
        }
    }

    private fun terminalStatus(done: Int, total: Int, bytes: Long): SyncStatus =
        SyncStatus(
            pairingId = pairing.pairingId,
            state = "done",
            done = done,
            total = total,
            bytesDone = bytes,
            error = null
        )

    private suspend fun seedTracks(vararg tracks: Track) {
        repository.upsertFolder(
            Folder(
                id = folderId,
                treeUri = "content://tree/$folderId",
                displayName = "Music",
                trackCount = tracks.size,
                totalBytes = tracks.sumOf { it.sizeBytes },
                addedAt = 1_700_000_000_000L
            )
        )
        repository.upsertTracks(tracks.toList())
    }

    private fun track(id: String, size: Long = 1_000L): Track = Track(
        id = id,
        folderId = folderId,
        documentUri = "content://docs/$id",
        relativeFolder = "Rock",
        title = "Title $id",
        artist = null,
        album = null,
        durationMs = 0L,
        sizeBytes = size,
        mime = "audio/mpeg"
    )

    private fun assertServerNotStarted() {
        assertFalse("server must not be running", httpServer.isRunning)
        assertNull("no session should be installed", sessionHolder.current)
    }

    /** Minimal in-memory [PairingStore] (mirrors the one in PairingViewModelTest). */
    private class FakePairingStore : PairingStore {
        private val state = MutableStateFlow<PairingRecord?>(null)
        override val pairing: Flow<PairingRecord?> = state.asStateFlow()
        override suspend fun get(): PairingRecord? = state.value
        override suspend fun save(record: PairingRecord) { state.value = record }
        override suspend fun clear() { state.value = null }
    }

    /** Fake [WifiAddressResolver] whose address can be toggled per test (null = Wi-Fi down). */
    private class MutableWifiAddressResolver(var ipv4: String?) : WifiAddressResolver {
        override fun resolveWifiIpv4(): String? = ipv4
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 10_000L
        const val POLL_STEP_MS = 10L
    }
}
