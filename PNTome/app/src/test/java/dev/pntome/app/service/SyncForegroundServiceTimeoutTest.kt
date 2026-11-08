package dev.pntome.app.service

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pntome.app.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Task 5.2 (Req 6.5): the dataSync timeout cancels the sync, stops the service, tells the user. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SyncForegroundServiceTimeoutTest {

    @Test
    fun onTimeout_cancelsSyncStopsServiceAndPostsMessage() {
        val controller = Robolectric.buildService(SyncForegroundService::class.java).create()
        val service = controller.get()
        service.orchestrator.beginSync()
        assertEquals(SyncState.Preparing, service.orchestrator.state.value)

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(SyncState.Cancelled, service.orchestrator.state.value)
        assertTrue(shadowOf(service).isStoppedBySelf)

        val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(manager).allNotifications
        assertNotNull(posted.firstOrNull { it.extras.getCharSequence("android.title") == "Sync stopped" })
    }
}
