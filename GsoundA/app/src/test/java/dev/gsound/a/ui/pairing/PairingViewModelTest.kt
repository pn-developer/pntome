package dev.gsound.a.ui.pairing

import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.FakeControlChannel
import dev.gsound.a.net.garmin.messages.PairConfirm
import dev.gsound.a.net.garmin.messages.PairRequest
import dev.gsound.a.net.garmin.messages.Unpair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests for the [PairingViewModel] state machine (task 7.3, Req 4.3–4.8).
 *
 * ## Test approach
 * - **Dispatcher**: a single [StandardTestDispatcher] is installed as [Dispatchers.Main] (the
 *   dispatcher [androidx.lifecycle.viewModelScope] uses) and each `runTest` shares that
 *   dispatcher's scheduler, so `runCurrent` / `advanceTimeBy` drive the view model's
 *   `viewModelScope` coroutines deterministically. `runCurrent` runs everything scheduled *now*
 *   without advancing past the pending timeout `delay`, so the watchdog only fires when a test
 *   explicitly advances time. It is reset in [tearDown].
 * - **Seams**: the three production seams are faked deterministically — a fixed
 *   [PairingCredentialGenerator] (constant code / pairingId / secret), a fixed [PairingClock], and
 *   a fixed [PeerNameProvider] — so every persisted field is asserted exactly.
 * - **Control channel**: the shared [FakeControlChannel] records sent messages, scripts send
 *   results, drives connection state, and pushes incoming `PAIR_CONFIRM`s.
 * - **PairingStore**: `PairingStore` is an interface (production is the DataStore-backed
 *   `DataStorePairingStore`). The view model is exercised against [FakePairingStore], a trivial
 *   in-memory implementation, so there is no Android `Context` or real DataStore IO — the whole
 *   state machine runs on the test dispatcher's virtual time with no flakiness. This keeps the
 *   test a plain JVM unit test (no Robolectric needed).
 *
 * A short injected timeout keeps the timeout test fast and lets `advanceTimeBy` trip the watchdog.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var store: FakePairingStore
    private lateinit var channel: FakeControlChannel

    private val code = "123456"
    private val pairingId = "11111111-2222-3333-4444-555555555555"
    private val secret = "c2hhcmVkLXNlY3JldA"
    private val peerName = "fenix 7"
    private val nowMillis = 1_700_000_000_000L
    private val timeoutMillis = 10_000L

    // PairingCredentialGenerator is a plain interface (not a fun interface), so use an explicit
    // object rather than a lambda SAM conversion.
    private val generator = object : PairingCredentialGenerator {
        override fun generate() =
            PairingCredentials(code = code, pairingId = pairingId, sharedSecret = secret)
    }
    private val clock = PairingClock { nowMillis }
    private val peerNames = PeerNameProvider { peerName }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = FakePairingStore()
        channel = FakeControlChannel(initialState = ConnectionState.Connected)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(timeout: Long = timeoutMillis): PairingViewModel =
        PairingViewModel(
            controlChannel = channel,
            pairingStore = store,
            credentialGenerator = generator,
            peerNameProvider = peerNames,
            clock = clock,
            timeoutMillis = timeout,
        )

    // --- Happy path -----------------------------------------------------------------------------

    @Test
    fun `startPairing on connected channel awaits confirm and sends PAIR_REQUEST`() =
        runTest(dispatcher.scheduler) {
            val vm = newViewModel()

            vm.startPairing()
            runCurrent()

            val state = vm.uiState.value
            assertTrue(
                "expected AwaitingConfirm but was $state",
                state is PairingUiState.AwaitingConfirm,
            )
            assertEquals(code, (state as PairingUiState.AwaitingConfirm).code)

            // A single PAIR_REQUEST carrying the generated credentials + peer name (Req 4.3, 4.4).
            val request = channel.sent.single()
            assertTrue(request is PairRequest)
            request as PairRequest
            assertEquals(code, request.code)
            assertEquals(pairingId, request.pairingId)
            assertEquals(secret, request.secret)
            assertEquals(peerName, request.peerName)
        }

    @Test
    fun `local confirm then matching PAIR_CONFIRM pairs and persists the record`() =
        runTest(dispatcher.scheduler) {
            val vm = newViewModel()
            vm.startPairing()
            runCurrent()

            vm.confirm()
            runCurrent()
            channel.emitIncoming(PairConfirm(pairingId = pairingId))
            runCurrent()

            assertPaired(vm)
        }

    // --- Order independence (Req 4.5) -----------------------------------------------------------

    @Test
    fun `PAIR_CONFIRM arriving before local confirm still pairs`() = runTest(dispatcher.scheduler) {
        val vm = newViewModel()
        vm.startPairing()
        runCurrent()

        channel.emitIncoming(PairConfirm(pairingId = pairingId))
        runCurrent()
        // Remote half in; not yet paired until the user confirms locally.
        assertTrue(vm.uiState.value is PairingUiState.AwaitingConfirm)

        vm.confirm()
        runCurrent()

        assertPaired(vm)
    }

    // --- Confirm requires BOTH signals (Req 4.5) ------------------------------------------------

    @Test
    fun `only local confirm without PAIR_CONFIRM stays AwaitingConfirm`() =
        runTest(dispatcher.scheduler) {
            val vm = newViewModel()
            vm.startPairing()
            runCurrent()

            vm.confirm()
            runCurrent()

            assertTrue(vm.uiState.value is PairingUiState.AwaitingConfirm)
            assertNull("must not persist without PAIR_CONFIRM", store.get())
        }

    @Test
    fun `only PAIR_CONFIRM without local confirm stays AwaitingConfirm`() =
        runTest(dispatcher.scheduler) {
            val vm = newViewModel()
            vm.startPairing()
            runCurrent()

            channel.emitIncoming(PairConfirm(pairingId = pairingId))
            runCurrent()

            assertTrue(vm.uiState.value is PairingUiState.AwaitingConfirm)
            assertNull("must not persist without local confirm", store.get())
        }

    // --- Mismatched pairingId -------------------------------------------------------------------

    @Test
    fun `PAIR_CONFIRM with a different pairingId does not complete pairing`() =
        runTest(dispatcher.scheduler) {
            val vm = newViewModel()
            vm.startPairing()
            runCurrent()

            vm.confirm()
            channel.emitIncoming(PairConfirm(pairingId = "99999999-0000-0000-0000-000000000000"))
            runCurrent()

            assertTrue(
                "mismatched PAIR_CONFIRM must be ignored",
                vm.uiState.value is PairingUiState.AwaitingConfirm,
            )
            assertNull(store.get())
        }

    // --- Timeout (Req 4.6) ----------------------------------------------------------------------

    @Test
    fun `timeout aborts to Error and discards temp credentials`() = runTest(dispatcher.scheduler) {
        val vm = newViewModel(timeout = timeoutMillis)
        vm.startPairing()
        runCurrent()
        assertTrue(vm.uiState.value is PairingUiState.AwaitingConfirm)

        advanceTimeBy(timeoutMillis + 1)
        runCurrent()

        assertTrue("expected Error after timeout", vm.uiState.value is PairingUiState.Error)
        assertNull(store.get())

        // Credentials were discarded: a late (valid) confirm must not silently pair.
        vm.confirm()
        channel.emitIncoming(PairConfirm(pairingId = pairingId))
        runCurrent()
        assertTrue(vm.uiState.value is PairingUiState.Error)
        assertNull(store.get())
    }

    // --- Connection error (Req 4.2) -------------------------------------------------------------

    @Test
    fun `startPairing surfaces an actionable Error when the channel is in error`() =
        runTest(dispatcher.scheduler) {
            channel.setConnectionState(
                ConnectionState.Error(ConnectionState.Error.Reason.APP_NOT_INSTALLED),
            )
            val vm = newViewModel()

            vm.startPairing()
            runCurrent()

            val state = vm.uiState.value
            assertTrue(state is PairingUiState.Error)
            assertTrue((state as PairingUiState.Error).message.isNotBlank())
            assertTrue("no PAIR_REQUEST should be sent on connection error", channel.sent.isEmpty())
        }

    // --- Send failure ---------------------------------------------------------------------------

    @Test
    fun `a failed PAIR_REQUEST send transitions to Error`() = runTest(dispatcher.scheduler) {
        channel.sendResults.addLast(Result.failure(RuntimeException("transport down")))
        val vm = newViewModel()

        vm.startPairing()
        runCurrent()

        assertTrue(vm.uiState.value is PairingUiState.Error)
        assertNull(store.get())
    }

    // --- Single active pairing (Req 4.8) --------------------------------------------------------

    @Test
    fun `with a pre-saved pairing Idle reflects the peer and startPairing refuses`() =
        runTest(dispatcher.scheduler) {
            val existing = PairingRecord(
                pairingId = "pre-paired-id",
                peerName = "Old Watch",
                sharedSecret = "old-secret",
                createdAt = 42L,
            )
            store.save(existing)

            val vm = newViewModel()
            runCurrent()

            // Idle reflects the restored peer (Req 4.8, 8.2).
            val idle = vm.uiState.value
            assertTrue("expected Idle but was $idle", idle is PairingUiState.Idle)
            assertEquals(existing, (idle as PairingUiState.Idle).paired)

            vm.startPairing()
            runCurrent()

            assertTrue(
                "starting while paired must raise an Error asking to unpair first",
                vm.uiState.value is PairingUiState.Error,
            )
            assertTrue("no PAIR_REQUEST should be sent while already paired", channel.sent.isEmpty())
        }

    // --- Unpair (Req 4.7) -----------------------------------------------------------------------

    @Test
    fun `unpair sends UNPAIR, clears the store, and returns to Idle unpaired`() =
        runTest(dispatcher.scheduler) {
            val existing = PairingRecord(
                pairingId = "pre-paired-id",
                peerName = "Old Watch",
                sharedSecret = "old-secret",
                createdAt = 42L,
            )
            store.save(existing)
            val vm = newViewModel()
            runCurrent()

            vm.unpair()
            runCurrent()

            // UNPAIR carrying the existing pairingId was sent over the channel.
            val unpair = channel.sent.single()
            assertTrue(unpair is Unpair)
            assertEquals(existing.pairingId, (unpair as Unpair).pairingId)

            // Store cleared and state is unpaired Idle.
            assertNull(store.get())
            val state = vm.uiState.value
            assertTrue(state is PairingUiState.Idle)
            assertNull((state as PairingUiState.Idle).paired)
        }

    // --- helpers --------------------------------------------------------------------------------

    private suspend fun assertPaired(vm: PairingViewModel) {
        val state = vm.uiState.value
        assertTrue("expected Paired but was $state", state is PairingUiState.Paired)
        val expected = PairingRecord(
            pairingId = pairingId,
            peerName = peerName,
            sharedSecret = secret,
            createdAt = nowMillis,
        )
        assertEquals(expected, (state as PairingUiState.Paired).peer)
        // Persisted with exactly the generated fields + clock timestamp (Req 4.5).
        assertEquals(expected, store.get())
    }

    /**
     * Minimal in-memory [PairingStore] for the state-machine tests: a single nullable record
     * exposed as a hot [MutableStateFlow] so the view model's restore collector observes writes.
     */
    private class FakePairingStore : PairingStore {
        private val state = MutableStateFlow<PairingRecord?>(null)
        override val pairing: Flow<PairingRecord?> = state.asStateFlow()
        override suspend fun get(): PairingRecord? = state.value
        override suspend fun save(record: PairingRecord) { state.value = record }
        override suspend fun clear() { state.value = null }
    }
}
