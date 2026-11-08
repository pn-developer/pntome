package dev.gsound.a.net.garmin

import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import dev.gsound.a.net.garmin.ConnectionState.Error.Reason

/**
 * Pure, JVM-testable mapping of Connect IQ Mobile SDK states/exceptions to the app's
 * hardware-independent [ConnectionState] (design §5.3 note, Req 4.2, Req 10.3) — task 6.4.
 *
 * ## Why a dedicated object
 * The SDK surfaces connection problems through several unrelated channels — an init
 * [ConnectIQ.IQSdkErrorStatus], a per-device [IQDevice.IQDeviceStatus], a per-app
 * [IQApp.IQAppStatus], or thrown [InvalidStateException]/[ServiceUnavailableException]. Spreading
 * the `when`s across [ConnectIqManager] made the "which SDK state means which user-facing
 * message" decision hard to see and impossible to unit-test without Android. Centralizing the
 * decision here keeps every mapping in one table, lets the manager stay thin, and makes the
 * whole policy exercisable from a plain JVM test (every enum value + both exceptions + the
 * Bluetooth flag).
 *
 * ## Purity
 * Every function takes only SDK enums / exceptions / a plain [Boolean] and returns a value — no
 * Android framework access, no SDK calls, no I/O. The single Android touch required by the
 * design note (reading [android.bluetooth.BluetoothAdapter] to tell "Bluetooth off" apart from
 * "no device") stays in [ConnectIqManager], which passes the resolved `bluetoothEnabled`
 * boolean into [resolveConnectivityError].
 *
 * ## Bluetooth-off handling
 * The SDK has no dedicated "Bluetooth is off" state. When BT is off the symptom is simply that
 * no device resolves (or a transport/service error). So the manager reads the adapter and, when
 * BT is disabled, [resolveConnectivityError] prefers [ConnectionState.Error.Reason.BLUETOOTH_OFF]
 * over the generic [ConnectionState.Error.Reason.NO_DEVICE] because turning Bluetooth on is the
 * actionable first step for the user (Req 4.2).
 *
 * ## Detail strings
 * Each [ConnectionState.Error] carries a short, actionable, secret-free [detail] suitable for a
 * default UI message (Req 4.2). The UI layer (task 10) may override wording per
 * [ConnectionState.Error.Reason]; these defaults are safe to show as-is.
 */
object SdkStateMapper {

    // --- SDK initialization --------------------------------------------------------------------

    /**
     * Map an SDK initialization failure ([ConnectIQ.ConnectIQListener.onInitializeError]).
     *
     * - [ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED] / [ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED]:
     *   the SDK channel is routed through Garmin Connect Mobile, so without a usable GCM there is
     *   effectively no reachable device → [Reason.NO_DEVICE] with install/update guidance.
     * - [ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR]: the Connect IQ service itself failed →
     *   [Reason.SDK_FAILURE].
     */
    fun mapInitError(status: ConnectIQ.IQSdkErrorStatus): ConnectionState.Error = when (status) {
        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> ConnectionState.Error(
            reason = Reason.NO_DEVICE,
            detail = "Garmin Connect app is not installed. Install Garmin Connect to reach your watch.",
        )

        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> ConnectionState.Error(
            reason = Reason.NO_DEVICE,
            detail = "Garmin Connect app needs updating. Update Garmin Connect to reach your watch.",
        )

        ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> ConnectionState.Error(
            reason = Reason.SDK_FAILURE,
            detail = "The Connect IQ service reported an error. Try again.",
        )
    }

    // --- Device status -------------------------------------------------------------------------

    /**
     * Outcome of inspecting a resolved device's [IQDevice.IQDeviceStatus].
     *
     * Modeled as a tiny sealed result (rather than returning a nullable [ConnectionState.Error])
     * so callers get an explicit [Connected] signal and exhaustive handling.
     */
    sealed interface DeviceOutcome {
        /** The device is connected and usable; proceed to resolve the GsoundG app. */
        data object Connected : DeviceOutcome

        /** The device is not usable; carries the mapped actionable error. */
        data class Error(val error: ConnectionState.Error) : DeviceOutcome
    }

    /**
     * Map a device's [IQDevice.IQDeviceStatus] to a [DeviceOutcome].
     *
     * - [IQDevice.IQDeviceStatus.CONNECTED] → [DeviceOutcome.Connected].
     * - [IQDevice.IQDeviceStatus.NOT_CONNECTED] → [Reason.DEVICE_DISCONNECTED] (paired but the
     *   phone cannot currently reach it — turn the watch on / bring it in range).
     * - [IQDevice.IQDeviceStatus.NOT_PAIRED] → [Reason.NO_DEVICE] (no watch is paired in Garmin
     *   Connect yet — pair one there first).
     * - [IQDevice.IQDeviceStatus.UNKNOWN] → [Reason.DEVICE_DISCONNECTED] (chosen over
     *   SDK_FAILURE: an indeterminate device status is almost always a transient reachability
     *   problem, and "reconnect your watch" is the actionable guidance; it is also retry-able,
     *   whereas SDK_FAILURE reads as a hard fault).
     */
    fun mapDeviceStatus(status: IQDevice.IQDeviceStatus): DeviceOutcome = when (status) {
        IQDevice.IQDeviceStatus.CONNECTED -> DeviceOutcome.Connected

        IQDevice.IQDeviceStatus.NOT_CONNECTED -> DeviceOutcome.Error(
            ConnectionState.Error(
                reason = Reason.DEVICE_DISCONNECTED,
                detail = "Your watch is not connected. Make sure it is on and in range.",
            ),
        )

        IQDevice.IQDeviceStatus.NOT_PAIRED -> DeviceOutcome.Error(
            ConnectionState.Error(
                reason = Reason.NO_DEVICE,
                detail = "No paired Garmin watch found. Pair your watch in Garmin Connect first.",
            ),
        )

        IQDevice.IQDeviceStatus.UNKNOWN -> DeviceOutcome.Error(
            ConnectionState.Error(
                reason = Reason.DEVICE_DISCONNECTED,
                detail = "Watch status is unknown. Make sure it is on and in range.",
            ),
        )
    }

    // --- App status ----------------------------------------------------------------------------

    /**
     * Outcome of inspecting the GsoundG app's [IQApp.IQAppStatus] on the device.
     */
    sealed interface AppOutcome {
        /** The GsoundG app is installed and usable; the channel can proceed to Connected. */
        data object Installed : AppOutcome

        /** The app is not usable; carries the mapped actionable error. */
        data class Error(val error: ConnectionState.Error) : AppOutcome
    }

    /**
     * Map the GsoundG app's [IQApp.IQAppStatus] to an [AppOutcome].
     *
     * - [IQApp.IQAppStatus.INSTALLED] → [AppOutcome.Installed].
     * - [IQApp.IQAppStatus.NOT_INSTALLED] / [IQApp.IQAppStatus.NOT_SUPPORTED] →
     *   [Reason.APP_NOT_INSTALLED] (the user must install GsoundG on the watch; a watch that
     *   does not support the app is, from the user's point of view, the same actionable
     *   "install/get a compatible build" step).
     * - [IQApp.IQAppStatus.UNKNOWN] → treated conservatively as [Reason.APP_NOT_INSTALLED]: when
     *   the SDK cannot confirm the app is present we must not claim the channel is ready, and the
     *   safe, actionable guidance is to check that GsoundG is installed on the watch.
     */
    fun mapAppStatus(status: IQApp.IQAppStatus): AppOutcome = when (status) {
        IQApp.IQAppStatus.INSTALLED -> AppOutcome.Installed

        IQApp.IQAppStatus.NOT_INSTALLED -> AppOutcome.Error(
            ConnectionState.Error(
                reason = Reason.APP_NOT_INSTALLED,
                detail = "GsoundG is not installed on the watch. Install it from the Connect IQ store.",
            ),
        )

        IQApp.IQAppStatus.NOT_SUPPORTED -> AppOutcome.Error(
            ConnectionState.Error(
                reason = Reason.APP_NOT_INSTALLED,
                detail = "GsoundG is not supported on this watch. Install a compatible build on the watch.",
            ),
        )

        IQApp.IQAppStatus.UNKNOWN -> AppOutcome.Error(
            ConnectionState.Error(
                reason = Reason.APP_NOT_INSTALLED,
                detail = "Could not confirm GsoundG on the watch. Make sure it is installed on the watch.",
            ),
        )
    }

    // --- Thrown exceptions ---------------------------------------------------------------------

    /**
     * Map a thrown SDK exception to a [ConnectionState.Error].
     *
     * - [ServiceUnavailableException] → [Reason.DEVICE_DISCONNECTED]: the Connect IQ service /
     *   device is momentarily unreachable; reconnecting the watch is the actionable step.
     * - [InvalidStateException] → [Reason.SDK_FAILURE]: the SDK was used in an invalid state
     *   (e.g. not initialized); not something the user can fix by reconnecting.
     * - any other throwable → [Reason.SDK_FAILURE] as a safe default.
     *
     * The exception's own message is preserved in [detail] for diagnostics (never a secret), with
     * a sensible fallback when it is null/blank.
     */
    fun mapThrowable(t: Throwable): ConnectionState.Error = when (t) {
        is ServiceUnavailableException -> ConnectionState.Error(
            reason = Reason.DEVICE_DISCONNECTED,
            detail = t.message?.takeIf { it.isNotBlank() }
                ?: "The watch connection is unavailable. Reconnect your watch.",
        )

        is InvalidStateException -> ConnectionState.Error(
            reason = Reason.SDK_FAILURE,
            detail = t.message?.takeIf { it.isNotBlank() }
                ?: "The Connect IQ SDK is in an invalid state. Try again.",
        )

        else -> ConnectionState.Error(
            reason = Reason.SDK_FAILURE,
            detail = t.message?.takeIf { it.isNotBlank() }
                ?: "An unexpected Connect IQ error occurred. Try again.",
        )
    }

    // --- Bluetooth-aware "no device" resolution ------------------------------------------------

    /**
     * Resolve the error to surface when no connected device could be found, taking the phone's
     * Bluetooth state into account.
     *
     * The SDK channel runs over Bluetooth (via Garmin Connect Mobile), so when no device resolves
     * the first thing to check is whether Bluetooth is even on. When [bluetoothEnabled] is
     * `false`, the actionable guidance is "turn Bluetooth on" ([Reason.BLUETOOTH_OFF]); otherwise
     * the problem is genuinely that there is no reachable device ([Reason.NO_DEVICE]).
     *
     * `ConnectIqManager` resolves [bluetoothEnabled] defensively from the system
     * [android.bluetooth.BluetoothAdapter]; on a device without a Bluetooth adapter the manager
     * passes `true` here (BT is "not off"), so the user is pointed at the real cause
     * ([Reason.NO_DEVICE]) rather than a misleading "turn Bluetooth on".
     *
     * @param bluetoothEnabled whether the phone's Bluetooth is currently enabled.
     */
    fun resolveConnectivityError(bluetoothEnabled: Boolean): ConnectionState.Error =
        if (!bluetoothEnabled) {
            ConnectionState.Error(
                reason = Reason.BLUETOOTH_OFF,
                detail = "Bluetooth is off. Turn Bluetooth on to reach your watch.",
            )
        } else {
            ConnectionState.Error(
                reason = Reason.NO_DEVICE,
                detail = "No connected Garmin watch found. Open Garmin Connect and connect your watch.",
            )
        }
}
