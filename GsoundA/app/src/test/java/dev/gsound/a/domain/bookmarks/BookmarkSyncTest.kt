package dev.gsound.a.domain.bookmarks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gsound.a.data.folders.BookmarkDao
import dev.gsound.a.data.folders.BookmarkEntity
import dev.gsound.a.net.garmin.messages.Stats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.PairingRecord
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.FakeControlChannel
import dev.gsound.a.net.garmin.messages.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 8.2: watch BOOKMARK validation, folder resolution (fid / name) and merge-on-store. */
@RunWith(RobolectricTestRunner::class)
class BookmarkSyncTest {
    private val pairingId = "11111111-2222-3333-4444-555555555555"
    private val now = 10_000L

    private lateinit var db: GsoundDatabase
    private lateinit var repo: FolderRepository
    private lateinit var sync: BookmarkSync

    private class FakePairingStore(initial: PairingRecord?) : PairingStore {
        private val state = MutableStateFlow(initial)
        override val pairing: Flow<PairingRecord?> = state.asStateFlow()
        override suspend fun get(): PairingRecord? = state.value
        override suspend fun save(record: PairingRecord) { state.value = record }
        override suspend fun clear() { state.value = null }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(app, GsoundDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = FolderRepository(db.folderDao(), db.trackDao())
        sync = BookmarkSync(
            FakeControlChannel(initialState = ConnectionState.Connected),
            FakePairingStore(PairingRecord(pairingId, "fenix", "secret", 1L)),
            repo, db.bookmarkDao(), { now },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun folder(id: String, name: String, book: Boolean = true) =
        repo.upsertFolder(Folder(id, "content://tree/$id", name, 1, 10L, 1L, isAudiobook = book))

    private fun msg(
        folder: String = "Dune", fid: String? = "f1", at: Long = 5_000L,
        pairing: String = pairingId, pos: Int = 90, fin: Boolean = false,
    ) = Bookmark(pairing, folder, fid, "t2", 1, pos, fin, at)

    @Test
    fun `valid bookmark is stored as watch with lastSentAt equal at`() = runBlocking {
        folder("f1", "Dune")
        assertTrue(sync.handle(msg()))
        assertEquals(
            BookmarkEntity("f1", "Dune", "t2", 1, 90, false, 5_000L, "watch", 5_000L),
            db.bookmarkDao().get("f1"),
        )
    }

    @Test
    fun `older or equal incoming is ignored and repeat is idempotent`() = runBlocking {
        folder("f1", "Dune")
        val local = BookmarkEntity("f1", "Dune", "t1", 0, 30, false, 6_000L, "phone")
        db.bookmarkDao().upsert(local)
        assertFalse(sync.handle(msg(at = 5_000L)))
        assertFalse(sync.handle(msg(at = 6_000L)))
        assertEquals(local, db.bookmarkDao().get("f1"))

        assertTrue(sync.handle(msg(at = 7_000L)))
        val taken = db.bookmarkDao().get("f1")
        assertFalse(sync.handle(msg(at = 7_000L)))
        assertEquals(taken, db.bookmarkDao().get("f1"))
    }

    @Test
    fun `future timestamp is clamped to now plus 300`() = runBlocking {
        folder("f1", "Dune")
        assertTrue(sync.handle(msg(at = 999_999L)))
        assertEquals(now + 300, db.bookmarkDao().get("f1")!!.updatedAt)
    }

    @Test
    fun `wrong pairing is ignored`() = runBlocking {
        folder("f1", "Dune")
        assertFalse(sync.handle(msg(pairing = "other")))
        assertNull(db.bookmarkDao().get("f1"))
    }

    @Test
    fun `unknown folder is ignored`() = runBlocking {
        folder("f1", "Dune")
        assertFalse(sync.handle(msg(folder = "Nope", fid = "zzz")))
        assertNull(db.bookmarkDao().get("f1"))
        assertNull(db.bookmarkDao().get("zzz"))
    }

    @Test
    fun `non book folder is ignored`() = runBlocking {
        folder("f1", "Dune", book = false)
        assertFalse(sync.handle(msg()))
        assertFalse(sync.handle(msg(fid = null)))
        assertNull(db.bookmarkDao().get("f1"))
    }

    @Test
    fun `fid wins over name and name is the fallback when fid is absent or unknown`() = runBlocking {
        folder("f1", "Dune")
        folder("f2", "Emma")
        // fid points to f2 even though the name says Dune.
        assertTrue(sync.handle(msg(folder = "Dune", fid = "f2")))
        assertNull(db.bookmarkDao().get("f1"))
        assertEquals("Emma", db.bookmarkDao().get("f2")!!.folderName)

        // No fid, and unknown fid: fall back to the unique name match.
        assertTrue(sync.handle(msg(folder = "Dune", fid = null)))
        assertTrue(sync.handle(msg(folder = "Dune", fid = "gone", at = 6_000L)))
        assertEquals(6_000L, db.bookmarkDao().get("f1")!!.updatedAt)
    }

    // --- Task 8.4: end-to-end through the real collector (start()) ---------------------------

    private fun newSync(channel: FakeControlChannel, dao: BookmarkDao = db.bookmarkDao()) =
        BookmarkSync(channel, FakePairingStore(PairingRecord(pairingId, "fenix", "secret", 1L)), repo, dao, { now })

    private suspend fun awaitSubscribers(channel: FakeControlChannel, n: Int) =
        withTimeout(5_000) { channel.subscriptionCount.first { it >= n } }

    private suspend fun awaitStored(folderId: String, at: Long) = withTimeout(5_000) {
        while (db.bookmarkDao().get(folderId)?.updatedAt != at) delay(10)
    }

    @Test
    fun `bookmark emitted on the channel after start is stored`() = runBlocking {
        folder("f1", "Dune")
        val channel = FakeControlChannel(initialState = ConnectionState.Connected)
        newSync(channel).start()
        awaitSubscribers(channel, 1)
        assertTrue(channel.emitIncoming(msg(at = 5_000L)))
        awaitStored("f1", 5_000L)
        assertEquals("watch", db.bookmarkDao().get("f1")!!.source)
    }

    @Test
    fun `start is idempotent - two starts give one collector`() = runBlocking {
        val channel = FakeControlChannel(initialState = ConnectionState.Connected)
        val s = newSync(channel)
        s.start()
        s.start()
        awaitSubscribers(channel, 1)
        delay(200)
        assertEquals(1, channel.subscriptionCount.value)
    }

    @Test
    fun `a failing store does not kill the collector`() = runBlocking {
        folder("f1", "Dune")
        val real = db.bookmarkDao()
        var calls = 0
        val flaky = object : BookmarkDao by real {
            override suspend fun upsert(bookmark: BookmarkEntity) {
                if (calls++ == 0) throw IllegalStateException("boom")
                real.upsert(bookmark)
            }
        }
        val channel = FakeControlChannel(initialState = ConnectionState.Connected)
        newSync(channel, flaky).start()
        awaitSubscribers(channel, 1)
        channel.emitIncoming(msg(at = 5_000L))   // handler throws
        channel.emitIncoming(msg(at = 6_000L))   // must still be processed
        awaitStored("f1", 6_000L)
        assertEquals(2, calls)
    }

    @Test
    fun `non bookmark messages are ignored`() = runBlocking {
        folder("f1", "Dune")
        val channel = FakeControlChannel(initialState = ConnectionState.Connected)
        newSync(channel).start()
        awaitSubscribers(channel, 1)
        channel.emitIncoming(Stats(pairingId, 3, 1, 1_700_000_000L))
        channel.emitIncoming(msg(at = 5_000L))   // marker: processed after the Stats
        awaitStored("f1", 5_000L)
        assertEquals(1, db.bookmarkDao().getAllByRecency().size)
    }

    @Test
    fun `ambiguous name without fid is ignored`() = runBlocking {
        folder("f1", "Dune")
        folder("f2", "Dune")
        assertFalse(sync.handle(msg(fid = null)))
        assertNull(db.bookmarkDao().get("f1"))
        assertNull(db.bookmarkDao().get("f2"))
    }
}
