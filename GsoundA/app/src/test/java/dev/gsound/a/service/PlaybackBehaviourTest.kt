package dev.gsound.a.service

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 2.3 JVM checks: per-item seek increments and unreadable-file skipping. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaybackBehaviourTest {

    private fun item(id: String, book: Boolean) = MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse("content://x/$id"))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(id)
                .setExtras(Bundle().apply { putBoolean(QueueBuilder.EXTRA_AUDIOBOOK, book) })
                .build()
        ).build()

    private fun player() = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()

    @Test
    fun seekIncrement_is30sForBooks_15sForMusic() {
        val exo = player()
        val wrapped = BookSeekPlayer(exo)
        exo.setMediaItem(item("b", true))
        assertEquals(30_000L, wrapped.seekBackIncrement)
        assertEquals(30_000L, wrapped.seekForwardIncrement)
        exo.setMediaItem(item("m", false))
        assertEquals(15_000L, wrapped.seekForwardIncrement)
        exo.release()
    }

    @Test
    fun playerError_skipsToNextAndReportsMessage() {
        val exo = player()
        exo.setMediaItems(listOf(item("a", true), item("b", true)))
        val messages = mutableListOf<String>()
        UnreadableFileSkipper(exo) { messages += it }.onPlayerError(
            PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        )
        assertEquals(1, exo.currentMediaItemIndex)
        assertEquals("${UnreadableFileSkipper.MESSAGE}: a", messages.single())
        exo.release()
    }

    @Test
    fun playerError_onLastItem_doesNotCrash() {
        val exo = player()
        exo.setMediaItem(item("a", false))
        val messages = mutableListOf<String>()
        UnreadableFileSkipper(exo) { messages += it }
            .onPlayerError(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(0, exo.currentMediaItemIndex)
        assertEquals(1, messages.size)
        exo.release()
    }
}
