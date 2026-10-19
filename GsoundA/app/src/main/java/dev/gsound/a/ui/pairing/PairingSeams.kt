package dev.gsound.a.ui.pairing

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the current wall-clock time in epoch millis for stamping [PairingRecord.createdAt]
 * (Req 4.5). Behind an interface so tests can inject a fixed clock and assert the persisted
 * timestamp deterministically (task 7.3).
 */
fun interface PairingClock {
    fun nowMillis(): Long
}

/** Production [PairingClock] backed by the system wall clock. */
@Singleton
class SystemPairingClock @Inject constructor() : PairingClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Supplies the watch peer's display name for the [PairingRecord] (Req 4.5).
 *
 * Deferred note: the SDK-free [dev.gsound.a.net.garmin.ControlChannel] / `ConnectionState`
 * deliberately expose no device identity, so the resolved Garmin device friendly name is not
 * available here yet. v1 uses a stable placeholder; wiring the real device name (surfaced from
 * `ConnectIqManager`'s resolved `IQDevice`, or carried on a future `PAIR_CONFIRM`) is left to a
 * follow-up. Kept behind an interface so that swap is local and testable.
 */
fun interface PeerNameProvider {
    fun peerName(): String
}

/** Default [PeerNameProvider] returning a generic placeholder until the device name is wired. */
@Singleton
class DefaultPeerNameProvider @Inject constructor() : PeerNameProvider {
    override fun peerName(): String = "Garmin watch"
}
