package dev.gsound.a.net.garmin

import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import dev.gsound.a.net.garmin.ConnectionState.Error.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SdkStateMapper] (task 6.4, Req 4.2 / Req 10.3).
 *
 * The mapper is pure, so these are plain JVM tests exercising the full mapping table: every
 * [ConnectIQ.IQSdkErrorStatus], every [IQDevice.IQDeviceStatus], every [IQApp.IQAppStatus], both
 * mapped SDK exceptions plus a generic throwable, and both Bluetooth-flag branches. Each case
 * also asserts the error carries a non-blank, secret-free [ConnectionState.Error.detail] so the
 * UI always has an actionable default message (Req 4.2).
 */
class SdkStateMapperTest {

    private fun assertActionableDetail(error: ConnectionState.Error) {
        assertNotNull("expected a default detail message", error.detail)
        assertTrue("detail should not be blank", error.detail!!.isNotBlank())
    }

    // --- init errors ---------------------------------------------------------------------------

    @Test
    fun `GCM not installed maps to NO_DEVICE with install guidance`() {
        val error = SdkStateMapper.mapInitError(ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED)
        assertEquals(Reason.NO_DEVICE, error.reason)
        assertActionableDetail(error)
        assertTrue(error.detail!!.contains("Install", ignoreCase = true))
    }

    @Test
    fun `GCM upgrade needed maps to NO_DEVICE with update guidance`() {
        val error = SdkStateMapper.mapInitError(ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED)
        assertEquals(Reason.NO_DEVICE, error.reason)
        assertActionableDetail(error)
        assertTrue(error.detail!!.contains("Update", ignoreCase = true))
    }

    @Test
    fun `service error maps to SDK_FAILURE`() {
        val error = SdkStateMapper.mapInitError(ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR)
        assertEquals(Reason.SDK_FAILURE, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `every init error status is mapped`() {
        for (status in ConnectIQ.IQSdkErrorStatus.entries) {
            val error = SdkStateMapper.mapInitError(status)
            assertActionableDetail(error)
        }
    }

    // --- device status -------------------------------------------------------------------------

    @Test
    fun `connected device yields Connected outcome`() {
        val outcome = SdkStateMapper.mapDeviceStatus(IQDevice.IQDeviceStatus.CONNECTED)
        assertEquals(SdkStateMapper.DeviceOutcome.Connected, outcome)
    }

    @Test
    fun `not connected device maps to DEVICE_DISCONNECTED`() {
        val outcome = SdkStateMapper.mapDeviceStatus(IQDevice.IQDeviceStatus.NOT_CONNECTED)
        val error = (outcome as SdkStateMapper.DeviceOutcome.Error).error
        assertEquals(Reason.DEVICE_DISCONNECTED, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `not paired device maps to NO_DEVICE`() {
        val outcome = SdkStateMapper.mapDeviceStatus(IQDevice.IQDeviceStatus.NOT_PAIRED)
        val error = (outcome as SdkStateMapper.DeviceOutcome.Error).error
        assertEquals(Reason.NO_DEVICE, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `unknown device status maps to DEVICE_DISCONNECTED`() {
        val outcome = SdkStateMapper.mapDeviceStatus(IQDevice.IQDeviceStatus.UNKNOWN)
        val error = (outcome as SdkStateMapper.DeviceOutcome.Error).error
        assertEquals(Reason.DEVICE_DISCONNECTED, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `every device status is mapped`() {
        for (status in IQDevice.IQDeviceStatus.entries) {
            when (val outcome = SdkStateMapper.mapDeviceStatus(status)) {
                is SdkStateMapper.DeviceOutcome.Connected -> Unit
                is SdkStateMapper.DeviceOutcome.Error -> assertActionableDetail(outcome.error)
            }
        }
    }

    // --- app status ----------------------------------------------------------------------------

    @Test
    fun `installed app yields Installed outcome`() {
        val outcome = SdkStateMapper.mapAppStatus(IQApp.IQAppStatus.INSTALLED)
        assertEquals(SdkStateMapper.AppOutcome.Installed, outcome)
    }

    @Test
    fun `not installed app maps to APP_NOT_INSTALLED`() {
        val outcome = SdkStateMapper.mapAppStatus(IQApp.IQAppStatus.NOT_INSTALLED)
        val error = (outcome as SdkStateMapper.AppOutcome.Error).error
        assertEquals(Reason.APP_NOT_INSTALLED, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `not supported app maps to APP_NOT_INSTALLED`() {
        val outcome = SdkStateMapper.mapAppStatus(IQApp.IQAppStatus.NOT_SUPPORTED)
        val error = (outcome as SdkStateMapper.AppOutcome.Error).error
        assertEquals(Reason.APP_NOT_INSTALLED, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `unknown app status is treated conservatively as APP_NOT_INSTALLED`() {
        val outcome = SdkStateMapper.mapAppStatus(IQApp.IQAppStatus.UNKNOWN)
        val error = (outcome as SdkStateMapper.AppOutcome.Error).error
        assertEquals(Reason.APP_NOT_INSTALLED, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `every app status is mapped`() {
        for (status in IQApp.IQAppStatus.entries) {
            when (val outcome = SdkStateMapper.mapAppStatus(status)) {
                is SdkStateMapper.AppOutcome.Installed -> Unit
                is SdkStateMapper.AppOutcome.Error -> assertActionableDetail(outcome.error)
            }
        }
    }

    // --- thrown exceptions ---------------------------------------------------------------------

    @Test
    fun `service unavailable exception maps to DEVICE_DISCONNECTED`() {
        val error = SdkStateMapper.mapThrowable(ServiceUnavailableException("gcm down"))
        assertEquals(Reason.DEVICE_DISCONNECTED, error.reason)
        assertEquals("gcm down", error.detail)
    }

    @Test
    fun `invalid state exception maps to SDK_FAILURE`() {
        val error = SdkStateMapper.mapThrowable(InvalidStateException("not initialized"))
        assertEquals(Reason.SDK_FAILURE, error.reason)
        assertEquals("not initialized", error.detail)
    }

    @Test
    fun `generic throwable maps to SDK_FAILURE`() {
        val error = SdkStateMapper.mapThrowable(RuntimeException("boom"))
        assertEquals(Reason.SDK_FAILURE, error.reason)
        assertEquals("boom", error.detail)
    }

    @Test
    fun `null exception message falls back to a default detail`() {
        val error = SdkStateMapper.mapThrowable(RuntimeException())
        assertEquals(Reason.SDK_FAILURE, error.reason)
        assertActionableDetail(error)
    }

    @Test
    fun `blank exception message falls back to a default detail`() {
        val error = SdkStateMapper.mapThrowable(ServiceUnavailableException("   "))
        assertEquals(Reason.DEVICE_DISCONNECTED, error.reason)
        assertActionableDetail(error)
    }

    // --- bluetooth-aware connectivity ----------------------------------------------------------

    @Test
    fun `bluetooth off is preferred over no device when BT disabled`() {
        val error = SdkStateMapper.resolveConnectivityError(bluetoothEnabled = false)
        assertEquals(Reason.BLUETOOTH_OFF, error.reason)
        assertActionableDetail(error)
        assertTrue(error.detail!!.contains("Bluetooth", ignoreCase = true))
    }

    @Test
    fun `no device when bluetooth enabled`() {
        val error = SdkStateMapper.resolveConnectivityError(bluetoothEnabled = true)
        assertEquals(Reason.NO_DEVICE, error.reason)
        assertActionableDetail(error)
    }

    // --- detail strings never leak secrets -----------------------------------------------------

    @Test
    fun `details are plain guidance without secret-looking tokens`() {
        val errors = buildList {
            ConnectIQ.IQSdkErrorStatus.entries.forEach { add(SdkStateMapper.mapInitError(it)) }
            IQDevice.IQDeviceStatus.entries.forEach {
                (SdkStateMapper.mapDeviceStatus(it) as? SdkStateMapper.DeviceOutcome.Error)
                    ?.let { e -> add(e.error) }
            }
            IQApp.IQAppStatus.entries.forEach {
                (SdkStateMapper.mapAppStatus(it) as? SdkStateMapper.AppOutcome.Error)
                    ?.let { e -> add(e.error) }
            }
            add(SdkStateMapper.resolveConnectivityError(true))
            add(SdkStateMapper.resolveConnectivityError(false))
        }
        for (error in errors) {
            assertFalse(
                "detail should not mention a secret: ${error.detail}",
                error.detail!!.contains("secret", ignoreCase = true),
            )
        }
    }
}
