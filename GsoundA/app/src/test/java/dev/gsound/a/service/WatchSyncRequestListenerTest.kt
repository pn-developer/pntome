package dev.gsound.a.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.FakeControlChannel
import dev.gsound.a.net.garmin.messages.SyncRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric JVM tests for [WatchSyncRequestListener]: a watch-initiated `SYNC_REQUEST` starts
 * the sync foreground service, and foreign/unpaired requests are ignored.
 *
 * The real [SyncForegroundService] is never run — we assert only that the correct start intent
 * was dispatched, via Robolectric's shadow application (the deterministic way to verify
 * "SYNC_REQUEST → service started" without a live service, notifications, or permissions).
 */
@RunWith(RobolectricTestRunner::class)
class WatchSyncRequestListenerTest {

    private lateinit var context: Context
    private lateinit var channel: FakeControlChannel
    private lateinit var store: FakePairingStore
    private lateinit var listener: WatchSyncRequestListener

    private val pairingId = "11111111-2222-3333-4444-555555555555"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        channel = FakeControlChannel(initialState = ConnectionState.Connected)
        store = FakePairingStore()
        listener = WatchSyncRequestListener(context, channel, store)
    }

    @Test
    fun `matching SYNC_REQUEST starts the sync service`() = runTest {
        store.save(PairingRecord(pairingId, "fenix", "secret", 1L))
        listener.start()
        awaitSubscribed()

        channel.emitIncoming(SyncRequest(pairingId = pairingId))
        val started = awaitServiceStarted()

        assertTrue("expected a service start intent", started != null)
        assertEquals(SyncForegroundService::class.java.name, started!!.component?.className)
        assertEquals(SyncForegroundService.ACTION_START, started.action)
    }

    @Test
    fun `SYNC_REQUEST with a foreign pairingId is ignored`() = runTest {
        store.save(PairingRecord(pairingId, "fenix", "secret", 1L))
        listener.start()
        awaitSubscribed()

        channel.emitIncoming(SyncRequest(pairingId = "some-other-pairing"))
        // Give the collector a chance to process (and reject) the message.
        repeat(5) { kotlinx.coroutines.yield() }

        assertNull(
            "no service should start for a foreign pairingId",
            shadowOf(context as android.app.Application).nextStartedService,
        )
    }

    @Test
    fun `SYNC_REQUEST is ignored when not paired`() = runTest {
        // store left empty → unpaired
        listener.start()
        awaitSubscribed()

        channel.emitIncoming(SyncRequest(pairingId = pairingId))
        repeat(5) { kotlinx.coroutines.yield() }

        assertNull(
            "no service should start when unpaired",
            shadowOf(context as android.app.Application).nextStartedService,
        )
    }

    // The listener collects on its own Dispatchers.Default scope; poll for the subscription and
    // for the resulting service start on real time with a short bound.
    private suspend fun awaitSubscribed() {
        var waited = 0
        while (channel.subscriptionCount.value == 0 && waited < 2_000) {
            kotlinx.coroutines.delay(10)
            waited += 10
        }
    }

    private suspend fun awaitServiceStarted(): android.content.Intent? {
        var waited = 0
        while (waited < 2_000) {
            val intent = shadowOf(context as android.app.Application).nextStartedService
            if (intent != null) return intent
            kotlinx.coroutines.delay(10)
            waited += 10
        }
        return null
    }

    /** Minimal in-memory [PairingStore]. */
    private class FakePairingStore : PairingStore {
        private val state = MutableStateFlow<PairingRecord?>(null)
        override val pairing: Flow<PairingRecord?> = state.asStateFlow()
        override suspend fun get(): PairingRecord? = state.value
        override suspend fun save(record: PairingRecord) { state.value = record }
        override suspend fun clear() { state.value = null }
    }
}
