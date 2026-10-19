package dev.gsound.a.data.pairing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gsound.a.data.DataStoreTestContext
import dev.gsound.a.domain.model.PairingRecord
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented round-trip tests for [PairingStore] (Req 8.1 persist pairing, 8.2 restore,
 * 4.5 save on confirm, 4.7 clear on unpair).
 *
 * Each test gets a unique files directory so its DataStore file is isolated (see
 * [DataStoreTestContext]).
 */
@RunWith(AndroidJUnit4::class)
class PairingStoreTest {

    private lateinit var filesDir: File
    private lateinit var store: DataStorePairingStore

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<android.content.Context>()
        filesDir = File(base.cacheDir, "pairing-${System.nanoTime()}")
        store = DataStorePairingStore(DataStoreTestContext(base, filesDir))
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun defaultIsNotPaired() = runTest {
        assertNull(store.get())
        assertNull(store.pairing.first())
        assertFalse(store.isPaired())
    }

    @Test
    fun saveThenGetReturnsSameRecord() = runTest {
        val record = PairingRecord(
            pairingId = "11111111-2222-3333-4444-555555555555",
            peerName = "fenix 7",
            sharedSecret = "c2VjcmV0LXZhbHVl",
            createdAt = 1_700_000_000_000L
        )

        store.save(record)

        assertEquals(record, store.get())
        assertEquals(record, store.pairing.first())
        assertTrue(store.isPaired())
    }

    @Test
    fun saveReplacesPreviousPairing() = runTest {
        store.save(
            PairingRecord("old-id", "Old Watch", "old-secret", 1L)
        )
        val replacement = PairingRecord("new-id", "New Watch", "new-secret", 2L)

        store.save(replacement)

        assertEquals(replacement, store.get())
    }

    @Test
    fun clearResetsToNotPaired() = runTest {
        store.save(PairingRecord("id", "Watch", "secret", 1L))
        assertTrue(store.isPaired())

        store.clear()

        assertNull(store.get())
        assertFalse(store.isPaired())
    }
}
