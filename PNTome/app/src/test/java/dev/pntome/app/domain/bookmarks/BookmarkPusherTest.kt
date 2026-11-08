package dev.pntome.app.domain.bookmarks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.pntome.app.data.folders.BookmarkEntity
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.data.folders.PNTomeDatabase
import dev.pntome.app.data.pairing.PairingStore
import dev.pntome.app.domain.model.Folder
import dev.pntome.app.domain.model.PairingRecord
import dev.pntome.app.net.garmin.ConnectionState
import dev.pntome.app.net.garmin.FakeControlChannel
import dev.pntome.app.net.garmin.messages.Bookmark
import dev.pntome.app.net.garmin.messages.Stats
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Task 10.1: phone → watch bookmark push. */
@RunWith(RobolectricTestRunner::class)
class BookmarkPusherTest {
    private val pairingId = "11111111-2222-3333-4444-555555555555"
    private lateinit var db: PNTomeDatabase
    private lateinit var repo: FolderRepository
    private lateinit var channel: FakeControlChannel

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
        db = Room.inMemoryDatabaseBuilder(app, PNTomeDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = FolderRepository(db.folderDao(), db.trackDao())
        channel = FakeControlChannel(initialState = ConnectionState.Connected)
    }

    @After
    fun tearDown() = db.close()

    private fun pusher(paired: Boolean = true) = BookmarkPusher(
        channel,
        FakePairingStore(if (paired) PairingRecord(pairingId, "fenix", "secret", 1L) else null),
        repo, db.bookmarkDao(),
        statsTimeoutMs = 500, quietPeriodMs = 100, maxWindowMs = 1_000,
    )

    private suspend fun folder(id: String, name: String, book: Boolean = true) =
        repo.upsertFolder(Folder(id, "content://tree/$id", name, 1, 10L, 1L, isAudiobook = book))

    private suspend fun bm(id: String, name: String, at: Long, sent: Long = 0, pos: Int = 40) =
        db.bookmarkDao().upsert(BookmarkEntity(id, name, "t$id", 2, pos, true, at, "phone", sent))

    @Test
    fun `sends only pending in order and marks sent`() = runBlocking {
        folder("a", "A"); folder("b", "B"); folder("c", "C")
        bm("a", "A", 10); bm("b", "B", 30); bm("c", "C", 20, sent = 20)
        assertEquals(2, pusher().pushPending())
        val sent = channel.sent.map { it as Bookmark }
        assertEquals(listOf("b", "a"), sent.map { it.fid }) // most recent first
        val b = sent.first()
        assertEquals(Bookmark(pairingId, "B", "b", "tb", 2, 40, true, 30), b)
        assertEquals(30L, db.bookmarkDao().get("b")!!.lastSentAt)
        assertEquals(10L, db.bookmarkDao().get("a")!!.lastSentAt)
        assertEquals(0, pusher().pushPending())
        assertEquals(2, channel.sent.size)
    }

    @Test
    fun `failure stops the chain and leaves the rest pending`() = runBlocking {
        folder("a", "A"); folder("b", "B")
        bm("a", "A", 10); bm("b", "B", 30)
        channel.sendResults += Result.success(Unit)
        channel.sendResults += Result.failure(RuntimeException("x"))
        channel.defaultSendResult = Result.failure(RuntimeException("x"))
        val p = pusher()
        assertEquals(1, p.pushPending())
        assertEquals(2, channel.sent.size)
        assertEquals(30L, db.bookmarkDao().get("b")!!.lastSentAt)
        assertEquals(0L, db.bookmarkDao().get("a")!!.lastSentAt)
        channel.defaultSendResult = Result.success(Unit)
        assertEquals(1, p.pushPending())
        assertEquals(10L, db.bookmarkDao().get("a")!!.lastSentAt)
    }

    @Test
    fun `skips non-audiobook and missing folders`() = runBlocking {
        folder("m", "Music", book = false); folder("a", "A")
        bm("m", "Music", 5); bm("gone", "Gone", 6); bm("a", "A", 7)
        assertEquals(1, pusher().pushPending())
        assertEquals(listOf("a"), channel.sent.map { (it as Bookmark).fid })
    }

    @Test
    fun `no pairing or unreachable watch sends nothing`() = runBlocking {
        folder("a", "A"); bm("a", "A", 7)
        assertEquals(0, pusher(paired = false).pushPending())
        channel.setConnectionState(ConnectionState.Disconnected)
        assertEquals(0, pusher().pushPending())
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `bookmark taken from watch is not echoed`() = runBlocking {
        folder("a", "A"); bm("a", "A", 50, sent = 50)
        assertEquals(0, pusher().pushPending())
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `concurrent call is guarded`() = runBlocking {
        folder("a", "A"); bm("a", "A", 7)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : dev.pntome.app.net.garmin.ControlChannel by channel {
            override suspend fun send(message: dev.pntome.app.net.garmin.messages.ControlMessage): Result<Unit> {
                gate.await()
                return channel.send(message)
            }
        }
        val p = BookmarkPusher(
            slow, FakePairingStore(PairingRecord(pairingId, "f", "s", 1L)), repo, db.bookmarkDao(),
            500, 100, 1_000,
        )
        val first = async(kotlinx.coroutines.Dispatchers.Default) { p.pushPending() }
        delay(200)
        assertEquals(0, p.pushPending())
        gate.complete(Unit)
        assertEquals(1, first.await())
        assertEquals(1, channel.sent.size)
    }

    @Test
    fun `pushAfterStats waits for stats then pushes`() = runBlocking {
        folder("a", "A"); bm("a", "A", 7)
        val p = pusher()
        val job = async(kotlinx.coroutines.Dispatchers.Default) {
            p.pushAfterStats {
                withTimeout(2_000) { channel.subscriptionCount.first { it > 0 } }
                channel.emitIncoming(Stats(pairingId, 1, 1, 0L))
            }
        }
        assertEquals(1, job.await())
    }

    @Test
    fun `pushAfterStats without stats pushes nothing`() = runBlocking {
        folder("a", "A"); bm("a", "A", 7)
        assertEquals(0, pusher().pushAfterStats { })
        assertTrue(channel.sent.isEmpty())
    }
}
