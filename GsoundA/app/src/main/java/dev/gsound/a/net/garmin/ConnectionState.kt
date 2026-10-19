package dev.gsound.a.net.garmin

/**
 * Hardware-independent view of the Garmin control-channel connection (design §5.3, §10).
 *
 * This is the public, SDK-free state that [ControlChannel] exposes to the rest of the app
 * (pairing/sync view models). It deliberately contains **no** Connect IQ Mobile SDK types so
 * that a fake channel can drive the same state transitions in unit tests (task 6.3) while the
 * real `ConnectIqManager` (task 6.2) maps SDK device/app states into these values (task 6.4).
 *
 * Modeled as a sealed interface so callers get exhaustive `when` handling at compile time.
 */
sealed interface ConnectionState {

    /** Not connected to a device/app; initial and post-[ControlChannel.close] resting state. */
    data object Disconnected : ConnectionState

    /**
     * A connection attempt is in progress: initializing the SDK, resolving the Garmin device,
     * and locating the GsoundG app (Req 4.1).
     */
    data object Connecting : ConnectionState

    /**
     * The GsoundG app on a connected Garmin device is reachable and control messages can be
     * exchanged (Req 4.1).
     */
    data object Connected : ConnectionState

    /**
     * The channel is unavailable or failed. Carries a mapped, actionable [reason] so the UI can
     * guide the user without touching SDK internals (Req 4.2, Req 10.2).
     *
     * The concrete SDK → [Reason] mapping is implemented in task 6.4.
     *
     * @property reason the mapped cause of the error.
     * @property detail optional human-readable detail for logging/diagnostics (never secrets).
     */
    data class Error(
        val reason: Reason,
        val detail: String? = null
    ) : ConnectionState {

        /**
         * Actionable, SDK-independent error causes surfaced to the user (Req 4.2).
         *
         * Kept intentionally small; task 6.4 is responsible for collapsing the various SDK
         * states/exceptions into exactly one of these.
         */
        enum class Reason {
            /** No paired Garmin device is available (e.g., Garmin Connect not running). */
            NO_DEVICE,

            /** A device is present but the GsoundG Connect IQ app is not installed on it. */
            APP_NOT_INSTALLED,

            /** Bluetooth is turned off on the phone. */
            BLUETOOTH_OFF,

            /** The device was reachable but has since disconnected (Req 10.2). */
            DEVICE_DISCONNECTED,

            /** The Connect IQ SDK failed to initialize or returned an unexpected failure. */
            SDK_FAILURE
        }
    }
}
