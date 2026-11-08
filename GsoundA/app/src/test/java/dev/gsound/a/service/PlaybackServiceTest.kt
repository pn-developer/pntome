package dev.gsound.a.service

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Task 2.1 spike, JVM part: the service builds its player and queues a SAF `content://` URI from
 * the play intent. Actual audio output, the notification and background behaviour need a device
 * and are NOT covered here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaybackServiceTest {

    @Test
    fun playIntent_queuesSafUriWithTitle() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.mp3"
        )
        val service = Robolectric.buildService(PlaybackService::class.java).create().get()
        assertNotNull(service.player)
        // Hilt wiring (task 2.4): dependencies are injected before onCreate returns.
        assertNotNull(service.folderRepository)
        assertNotNull(service.queueBuilder)

        val intent = Intent(service, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_PLAY_URI)
            .setData(uri)
            .putExtra(PlaybackService.EXTRA_TITLE, "Track A")
        service.onStartCommand(intent, 0, 1)

        val item = service.player!!.currentMediaItem
        assertNotNull(item)
        assertEquals(uri, item!!.localConfiguration!!.uri)
        assertEquals("Track A", item.mediaMetadata.title.toString())
    }
}
