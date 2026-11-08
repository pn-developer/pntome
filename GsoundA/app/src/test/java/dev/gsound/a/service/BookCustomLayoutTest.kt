package dev.gsound.a.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 6.4: ±30 s custom layout for book queues (PL-2.4). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BookCustomLayoutTest {
    private fun item(id: String, book: Boolean) = MediaItem.Builder()
        .setMediaId(id).setUri(Uri.parse("content://x/$id"))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(id)
                .setExtras(Bundle().apply { putBoolean(QueueBuilder.EXTRA_AUDIOBOOK, book) })
                .build()
        ).build()

    private fun exo() = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()

    @Test
    fun book_hasTwoButtons_music_hasNone() {
        val book = BookCustomLayout.layoutFor(item("b", true))
        assertEquals(listOf("Back 30 s", "Forward 30 s"), book.map { it.displayName.toString() })
        assertEquals(
            listOf(BookCustomLayout.ACTION_SKIP_BACK_30, BookCustomLayout.ACTION_SKIP_FWD_30),
            book.map { it.sessionCommand?.customAction }
        )
        assertTrue(BookCustomLayout.layoutFor(item("m", false)).isEmpty())
        assertTrue(BookCustomLayout.layoutFor(null).isEmpty())
    }

    @Test
    fun updater_followsCurrentItem() {
        val player = exo()
        val calls = mutableListOf<ImmutableList<CommandButton>>()
        val updater = BookLayoutUpdater(player) { calls += it }
        player.addListener(updater)
        player.setMediaItems(listOf(item("b", true), item("m", false)))
        updater.apply()
        assertEquals(2, calls.last().size)
        player.seekToNextMediaItem()
        updater.onMediaItemTransition(player.currentMediaItem, 0)
        assertTrue(calls.last().isEmpty())
        val n = calls.size
        updater.apply() // unchanged -> no redundant push
        assertEquals(n, calls.size)
        player.release()
    }

    @Test
    fun commands_seekByThirtySecondsOnBooks() {
        val player = exo()
        player.setMediaItem(item("b", true))
        val wrapped = BookSeekPlayer(player)
        player.seekTo(100_000)
        val r1 = BookCustomLayout.execute(BookCustomLayout.skipBack, wrapped)
        assertEquals(SessionResult.RESULT_SUCCESS, r1!!.resultCode)
        assertEquals(70_000L, player.currentPosition)
        BookCustomLayout.execute(BookCustomLayout.skipForward, wrapped)
        assertEquals(100_000L, player.currentPosition)
        assertNull(BookCustomLayout.execute(androidx.media3.session.SessionCommand("other", Bundle.EMPTY), wrapped))
        assertNotNull(BookCustomLayout.commands)
        player.release()
    }
}
