package dev.gsound.a.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gsound.a.data.folders.BookmarkDao
import dev.gsound.a.data.folders.BookmarkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Task 5.1: triggers, throttling and book-only filtering, with a fake player and a fake DAO. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BookmarkRecorderTest {

    /** Mutable state behind a [Player] proxy; only what the recorder reads is implemented. */
    private class FakePlayerState {
        var item: MediaItem? = null
        var index = 0
        var positionMs = 0L
        var playing = false
        var state = Player.STATE_READY
        var hasNext = true
        val listeners = mutableListOf<Player.Listener>()

        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java)
        ) { _, m, args ->
            when (m.name) {
                "addListener" -> { listeners += args[0] as Player.Listener; null }
                "removeListener" -> { listeners -= args[0] as Player.Listener; null }
                "getCurrentMediaItem" -> item
                "getCurrentMediaItemIndex" -> index
                "getCurrentPosition" -> positionMs
                "isPlaying" -> playing
                "getPlaybackState" -> state
                "hasNextMediaItem" -> hasNext
                else -> throw UnsupportedOperationException(m.name)
            }
        } as Player
    }

    private class FakeDao : BookmarkDao {
        val rows = mutableMapOf<String, BookmarkEntity>()
        var upserts = 0
        override suspend fun get(folderId: String) = rows[folderId]
        override suspend fun getAllByRecency() = rows.values.sortedByDescending { it.updatedAt }
        override fun observeAll(): Flow<List<BookmarkEntity>> = MutableStateFlow(rows.values.toList())
        var failNext = 0
        override suspend fun upsert(bookmark: BookmarkEntity) {
            if (failNext > 0) { failNext--; throw IllegalStateException("disk full") }
            upserts++
            rows[bookmark.folderId] = bookmark
        }
        override suspend fun delete(folderId: String) { rows.remove(folderId) }
        override suspend fun markSent(folderId: String, lastSentAt: Long) = Unit
    }

    private fun item(id: String, book: Boolean = true, folderId: String? = "f1"): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setAlbumTitle("Book").setExtras(Bundle().apply {
                putBoolean(QueueBuilder.EXTRA_AUDIOBOOK, book)
                if (folderId != null) putString(QueueBuilder.EXTRA_FOLDER_ID, folderId)
            }).build()
        ).build()

    private class Env(scheduler: TestCoroutineScheduler, alreadyPlaying: Boolean = false) {
        val fake = FakePlayerState().also { it.playing = alreadyPlaying }
        val dao = FakeDao()
        var nowMs = 1_000_000L
        val scope = CoroutineScope(StandardTestDispatcher(scheduler))
        val recorder = BookmarkRecorder(fake.player, dao, scope, scope, { nowMs })
        val listener get() = fake.listeners.single()

        /**
         * The ticker is an endless delay loop on the test scheduler; runTest's final
         * advanceUntilIdle would never finish while it runs, so tests that leave it running
         * must stop it before returning.
         */
        fun stopTicker() {
            fake.playing = false
            recorder.release()
        }
    }

    @Test
    fun pause_writesCurrentChapterAndPosition() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t2"); e.fake.index = 1; e.fake.positionMs = 61_900
        e.listener.onIsPlayingChanged(false)
        runCurrent()
        val b = e.dao.rows["f1"]!!
        assertEquals("t2", b.trackId)
        assertEquals(1, b.chapterIndex)
        assertEquals(61, b.positionSec)
        assertEquals("Book", b.folderName)
        assertEquals("phone", b.source)
        assertEquals(1000L, b.updatedAt)
        assertFalse(b.finished)
    }

    @Test
    fun write_preservesStoredLastSentAt() = runTest {
        val e = Env(testScheduler)
        e.dao.rows["f1"] = BookmarkEntity("f1", "Book", "t0", 0, 3, false, 500L, "watch", 500L)
        e.fake.item = item("t1"); e.fake.positionMs = 10_000
        e.listener.onIsPlayingChanged(false); runCurrent()
        val b = e.dao.rows["f1"]!!
        assertEquals("t1", b.trackId)
        assertEquals(500L, b.lastSentAt)
        assertEquals("phone", b.source)
    }

    @Test
    fun ticker_writesEvery15sWhilePlayingAndStopsOnPause() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.playing = true
        e.listener.onIsPlayingChanged(true)
        e.fake.positionMs = 15_000; advanceTimeBy(15_001)
        assertEquals(15, e.dao.rows["f1"]!!.positionSec)
        e.fake.positionMs = 30_000; advanceTimeBy(15_000)
        assertEquals(30, e.dao.rows["f1"]!!.positionSec)
        e.fake.playing = false
        e.listener.onIsPlayingChanged(false)
        runCurrent()
        val writes = e.dao.upserts
        e.fake.positionMs = 99_000; advanceTimeBy(60_000)
        assertEquals(writes, e.dao.upserts)
    }

    @Test
    fun unchangedValues_areNotWrittenAgain() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 10_000
        e.listener.onIsPlayingChanged(false); runCurrent()
        e.nowMs += 5_000
        e.listener.onIsPlayingChanged(false); runCurrent()
        assertEquals(1, e.dao.upserts)
        e.fake.positionMs = 11_000
        e.listener.onIsPlayingChanged(false); runCurrent()
        assertEquals(2, e.dao.upserts)
    }

    @Test
    fun transition_writesNewChapter_butNotPlaylistChange() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1")
        e.listener.onMediaItemTransition(e.fake.item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        runCurrent()
        assertNull(e.dao.rows["f1"])
        e.fake.item = item("t2"); e.fake.index = 1; e.fake.positionMs = 0
        e.listener.onMediaItemTransition(e.fake.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        runCurrent()
        assertEquals("t2", e.dao.rows["f1"]!!.trackId)
        assertEquals(0, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun seek_writesNewPosition() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 120_000
        val info = Player.PositionInfo(null, 0, null, null, 0, 0, 0, -1, -1)
        e.listener.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK)
        runCurrent()
        assertEquals(120, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun endedOnLastChapter_setsFinished_endedElsewhereDoesNot() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t3"); e.fake.index = 2; e.fake.positionMs = 500_000
        e.fake.state = Player.STATE_ENDED; e.fake.hasNext = true
        e.listener.onPlaybackStateChanged(Player.STATE_ENDED); runCurrent()
        assertFalse(e.dao.rows["f1"]!!.finished)
        e.fake.hasNext = false
        e.listener.onPlaybackStateChanged(Player.STATE_ENDED); runCurrent()
        assertTrue(e.dao.rows["f1"]!!.finished)
    }

    @Test
    fun release_doesFinalWriteThenDetaches() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 42_000
        e.recorder.release()
        runCurrent()
        assertEquals(42, e.dao.rows["f1"]!!.positionSec)
        assertTrue(e.fake.listeners.isEmpty())
        e.fake.positionMs = 50_000
        e.recorder.flush(); runCurrent()
        assertEquals(42, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun conflation_keepsLatestSnapshotPerFolder() = runTest {
        val e = Env(testScheduler)
        // Several snapshots of A, then B, all before the writer runs.
        e.fake.item = item("a1", folderId = "A"); e.fake.positionMs = 10_000
        e.listener.onIsPlayingChanged(false)
        e.fake.positionMs = 20_000
        e.listener.onIsPlayingChanged(false)
        e.fake.item = item("b1", folderId = "B"); e.fake.positionMs = 5_000
        e.listener.onIsPlayingChanged(false)
        runCurrent()
        assertEquals(20, e.dao.rows["A"]!!.positionSec)
        assertEquals(5, e.dao.rows["B"]!!.positionSec)
        assertEquals(2, e.dao.upserts)
    }

    @Test
    fun writerSurvivesDaoException_andRetriesOnNextSnapshot() = runTest {
        val e = Env(testScheduler)
        e.dao.failNext = 1
        e.fake.item = item("t1"); e.fake.positionMs = 10_000
        e.listener.onIsPlayingChanged(false); runCurrent()
        assertNull(e.dao.rows["f1"])
        // Same values again: the failed write was not recorded as written, so it is retried.
        e.listener.onIsPlayingChanged(false); runCurrent()
        assertEquals(10, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun flush_writesCurrentPosition() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 7_000
        e.recorder.flush(); runCurrent()
        assertEquals(7, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun nonBooks_andItemsWithoutFolderId_areIgnored() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("m1", book = false)
        e.listener.onIsPlayingChanged(false)
        e.fake.item = item("b1", book = true, folderId = null)
        e.listener.onIsPlayingChanged(false)
        e.fake.item = null
        e.recorder.flush()
        runCurrent()
        assertEquals(0, e.dao.upserts)
    }

    @Test
    fun autoTransition_writesNextChapterAtZero_evenIfPlayerReportsOffset() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t2"); e.fake.index = 1; e.fake.positionMs = 3_000
        e.listener.onMediaItemTransition(e.fake.item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        runCurrent()
        val b = e.dao.rows["f1"]!!
        assertEquals("t2", b.trackId); assertEquals(1, b.chapterIndex)
        assertEquals(0, b.positionSec); assertFalse(b.finished)
    }

    @Test
    fun manualTransition_keepsActualPosition() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t2"); e.fake.index = 1; e.fake.positionMs = 9_000
        e.listener.onMediaItemTransition(e.fake.item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        runCurrent()
        assertEquals(9, e.dao.rows["f1"]!!.positionSec)
    }

    // --- Task 5.4 additions ---

    @Test
    fun ticker_isThrottled_noWriteBetweenTicks_oneWritePerInterval() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.playing = true; e.fake.positionMs = 1_000
        e.listener.onIsPlayingChanged(true)
        advanceTimeBy(14_999); runCurrent()
        assertEquals(0, e.dao.upserts)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, e.dao.upserts)
        e.fake.positionMs = 2_000
        advanceTimeBy(14_999); runCurrent()
        assertEquals(1, e.dao.upserts)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, e.dao.upserts)
        e.stopTicker()
    }

    @Test
    fun ticker_doesNotWriteMusic() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("m1", book = false); e.fake.playing = true; e.fake.positionMs = 5_000
        e.listener.onIsPlayingChanged(true)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, e.dao.upserts)
        e.stopTicker()
    }

    @Test
    fun construction_whileAlreadyPlaying_startsTicker() = runTest {
        val e = Env(testScheduler, alreadyPlaying = true)
        e.fake.item = item("t1"); e.fake.positionMs = 20_000
        advanceTimeBy(15_000); runCurrent()
        assertEquals(20, e.dao.rows["f1"]!!.positionSec)
        e.stopTicker()
    }

    @Test
    fun ticker_skipsTickWhenPlayerIsNotPlaying() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 5_000
        e.listener.onIsPlayingChanged(true)
        e.fake.playing = false // paused without the listener event yet
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, e.dao.upserts)
        e.stopTicker()
    }

    @Test
    fun ticker_stopsAfterRelease() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.playing = true; e.fake.positionMs = 1_000
        e.listener.onIsPlayingChanged(true)
        e.recorder.release(); runCurrent()
        val writes = e.dao.upserts
        e.fake.positionMs = 90_000
        advanceTimeBy(60_000); runCurrent()
        assertEquals(writes, e.dao.upserts)
        assertEquals(1, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun release_isIdempotent() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 42_000
        e.recorder.release()
        e.recorder.release()
        runCurrent()
        assertEquals(1, e.dao.upserts)
        assertTrue(e.fake.listeners.isEmpty())
    }

    @Test
    fun flush_onTaskRemoved_writesWhilePlayingAndKeepsListening() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.playing = true; e.fake.positionMs = 33_000
        e.recorder.flush(); runCurrent()
        assertEquals(33, e.dao.rows["f1"]!!.positionSec)
        assertEquals(1, e.fake.listeners.size)
    }

    @Test
    fun position_isTruncatedToWholeSeconds() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 59_999
        e.listener.onIsPlayingChanged(false); runCurrent()
        assertEquals(59, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun negativePosition_isClampedToZero() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = -500
        e.listener.onIsPlayingChanged(false); runCurrent()
        assertEquals(0, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun seekAdjustment_writesPosition() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 77_000
        val info = Player.PositionInfo(null, 0, null, null, 0, 0, 0, -1, -1)
        e.listener.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        runCurrent()
        assertEquals(77, e.dao.rows["f1"]!!.positionSec)
    }

    @Test
    fun otherDiscontinuityReasons_doNotWrite() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 77_000
        val info = Player.PositionInfo(null, 0, null, null, 0, 0, 0, -1, -1)
        e.listener.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        runCurrent()
        assertEquals(0, e.dao.upserts)
    }

    @Test
    fun ended_writesPositionOfCurrentChapter() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t2"); e.fake.index = 1; e.fake.positionMs = 123_456
        e.fake.state = Player.STATE_ENDED; e.fake.hasNext = true
        e.listener.onPlaybackStateChanged(Player.STATE_ENDED); runCurrent()
        val b = e.dao.rows["f1"]!!
        assertEquals("t2", b.trackId); assertEquals(1, b.chapterIndex)
        assertEquals(123, b.positionSec)
    }

    @Test
    fun nonEndedStateChanges_doNotWrite() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t1"); e.fake.positionMs = 5_000
        e.listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
        e.listener.onPlaybackStateChanged(Player.STATE_READY)
        runCurrent()
        assertEquals(0, e.dao.upserts)
    }

    @Test
    fun finished_survivesStopAfterEnded_butNotRestart() = runTest {
        val e = Env(testScheduler)
        e.fake.item = item("t3"); e.fake.index = 2; e.fake.positionMs = 500_000
        e.fake.state = Player.STATE_ENDED; e.fake.hasNext = false
        e.listener.onPlaybackStateChanged(Player.STATE_ENDED); runCurrent()
        assertTrue(e.dao.rows["f1"]!!.finished)
        assertEquals(500, e.dao.rows["f1"]!!.positionSec)
        // stop() -> IDLE, then pause event and destroy flush must not revert it
        e.fake.state = Player.STATE_IDLE
        e.listener.onIsPlayingChanged(false); runCurrent()
        e.recorder.flush(); runCurrent()
        assertTrue(e.dao.rows["f1"]!!.finished)
        // restart: seek to 0 clears finished
        e.fake.state = Player.STATE_READY; e.fake.positionMs = 0
        val info = Player.PositionInfo(null, 0, null, null, 0, 0, 0, -1, -1)
        e.listener.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_SEEK)
        runCurrent()
        assertFalse(e.dao.rows["f1"]!!.finished)
        assertEquals(0, e.dao.rows["f1"]!!.positionSec)
    }
}
