package dev.gsound.a.net.garmin

import android.bluetooth.BluetoothManager
import android.content.Context
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.log.GsoundLog
import dev.gsound.a.net.garmin.messages.ControlMessage
import dev.gsound.a.net.garmin.messages.MessageCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Connect IQ Mobile SDK-backed implementation of [ControlChannel] (design §5.3, task 6.2).
 *
 * Bridges the SDK's callback-style API into coroutines/[kotlinx.coroutines.flow.Flow]:
 * - [connect] initializes the SDK, resolves a connected [IQDevice], locates the GsoundG
 *   [IQApp] by its shared [GsoundAppId], and registers message/device listeners (Req 4.1).
 * - [incoming] re-emits decoded [ControlMessage]s received from the watch.
 * - [send] encodes a message to JSON and hands it to [ConnectIQ.sendMessage].
 * - [close] unregisters listeners and shuts the SDK down.
 *
 * ## Scope / seams for later tasks
 * - **Task 6.3** (this file) hardens [incoming] via [MessageCodec.decodeAndValidate]: envelope
 *   validation (`v`, `type`, `pairingId`) and dropping messages from unpaired peers using the
 *   persisted pairing read from [pairingStore] (Req 10.3); and wraps [send] with
 *   [OutgoingRetry.retryWithBackoff] so transient transport failures are retried before the
 *   caller is told the channel is down (Req 10.2).
 * - **Task 6.4** moves the authoritative SDK-state → [ConnectionState.Error.Reason] mapping
 *   (not installed / Bluetooth off / device disconnected) into the pure, JVM-testable
 *   [SdkStateMapper]. This class only performs the one Android-specific read it cannot delegate
 *   — the phone's [android.bluetooth.BluetoothAdapter] state via [isBluetoothEnabled] — and
 *   hands everything else to the mapper.
 *
 * Not safe to drive from device I/O here beyond what the SDK requires; all heavy transfer is
 * on the HTTP channel.
 */
@Singleton
class ConnectIqManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pairingStore: PairingStore,
) : ControlChannel {

    /** Backoff policy applied to outgoing sends (Req 10.2). */
    private val retryPolicy = OutgoingRetry.RetryPolicy()

    /** Logcat tag for control-channel diagnostics (filter: `adb logcat -s Gsound.CIQ`). */
    private val tag = "Gsound.CIQ"

    /**
     * Device-enumeration poll window used by [resolveDeviceAndApp].
     *
     * With the WIRELESS transport the SDK reaches the watch THROUGH Garmin Connect Mobile over
     * an IPC binding. `connectedDevices` is frequently EMPTY for a short moment right after
     * [ConnectIQ.ConnectIQListener.onSdkReady] because that binding has not finished enumerating
     * paired devices yet — even when the watch is connected in Garmin Connect. Reading it exactly
     * once therefore produced a spurious [ConnectionState.Error.Reason.NO_DEVICE] ("No Garmin
     * watch found") on a perfectly healthy setup. We instead poll it a few times over a short
     * window and only conclude NO_DEVICE if nothing CONNECTED appears within [devicePollTotalMs].
     */
    private val devicePollIntervalMs = 300L
    private val devicePollTotalMs = 5_000L

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Hot, broadcast-style flow: multiple collectors (pairing, sync) observe the same messages;
    // no replay (messages before subscription are not redelivered), with a small buffer so a
    // momentarily-slow collector doesn't block the SDK callback thread.
    private val _incoming = MutableSharedFlow<ControlMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val incoming: SharedFlow<ControlMessage> = _incoming.asSharedFlow()

    /** The SDK handle, created lazily in [connect] and retained until [close]. */
    private var connectIq: ConnectIQ? = null

    /**
     * True once [ConnectIQ.ConnectIQListener.onSdkReady] has fired for the current [connectIq]
     * handle. The Connect IQ SDK invokes that callback only on the FIRST [ConnectIQ.initialize]
     * for a handle; calling `initialize()` again on an already-initialized SDK silently never
     * calls back, which hung [connect] on every call after the first (observed: repeated
     * "initializing Connect IQ SDK" with no "onSdkReady"). We track readiness so a subsequent
     * [connect]/refresh skips re-initialization and re-resolves the device directly.
     */
    private var sdkInitialized: Boolean = false

    /** Serializes [connect] so overlapping taps (refresh) don't stack concurrent resolves. */
    private val connectMutex = kotlinx.coroutines.sync.Mutex()

    /** The resolved device/app pair, set once [ConnectionState.Connected] is reached. */
    private var device: IQDevice? = null
    private var app: IQApp? = null

    /** The GsoundG app identity used for info lookup, event registration, and sends. */
    private val gsoundApp = IQApp(GsoundAppId.VALUE)

    /**
     * Receives incoming SDK message batches, validates each payload via [MessageCodec], and
     * re-emits only the surviving (schema-valid, version-matched, paired-peer) messages.
     * Invalid payloads and messages from an unpaired peer are dropped here so they never reach
     * [incoming] (Req 10.3).
     */
    private val appEventListener = ConnectIQ.IQApplicationEventListener { _, _, messageData, _ ->
        // Snapshot the active pairing once per batch; the SDK delivers on its own thread, so a
        // short blocking read of the persisted record is acceptable (control payloads are tiny).
        val expectedPairingId = runBlocking { pairingStore.get()?.pairingId }
        GsoundLog.d(tag, "incoming batch: ${messageData.size} payload(s)")
        for (payload in messageData) {
            // The SDK delivers structured values: a watch-side Dictionary arrives as a Map.
            // decodeAndValidateFromAny handles that (and tolerates a legacy JSON String).
            when (val result = MessageCodec.decodeAndValidateFromAny(payload, expectedPairingId)) {
                is MessageCodec.DecodeResult.Valid -> {
                    // type is a non-secret discriminator; never log the payload (Req 8.4).
                    GsoundLog.i(tag, "incoming accepted: type=${result.message::class.simpleName}")
                    // Non-blocking emit; the SharedFlow buffer absorbs bursts.
                    _incoming.tryEmit(result.message)
                }

                // Invalid envelope/schema or an unpaired peer: ignore (Req 10.3).
                is MessageCodec.DecodeResult.Invalid -> {
                    GsoundLog.w(tag, "incoming dropped: invalid envelope/schema")
                }
                is MessageCodec.DecodeResult.UnpairedPeer -> {
                    GsoundLog.w(tag, "incoming dropped: unpaired/foreign peer (pairingId mismatch)")
                }
            }
        }
    }

    /**
     * Watches device-level status changes. A status other than CONNECTED after we reached
     * [ConnectionState.Connected] is mapped to an actionable error via [SdkStateMapper]
     * (typically [ConnectionState.Error.Reason.DEVICE_DISCONNECTED]); a CONNECTED status restores
     * [ConnectionState.Connected] (task 6.4).
     */
    private val deviceEventListener = ConnectIQ.IQDeviceEventListener { eventDevice, status ->
        GsoundLog.i(tag, "deviceEvent: device=${eventDevice?.friendlyName} id=${eventDevice?.deviceIdentifier} status=$status")
        when (val outcome = SdkStateMapper.mapDeviceStatus(status)) {
            is SdkStateMapper.DeviceOutcome.Connected -> {
                // Capture the device from the event so a send has a target even if this CONNECTED
                // arrived outside a full connect()/resolveDeviceAndApp pass (e.g. a BT reconnect
                // after unpair/reboot). Without this the state could be Connected while `device`
                // stayed null, and sends failed with "not connected (device=false)". `app` is
                // still bound by resolveDeviceAndApp / falls back to gsoundApp in sendOnce.
                if (eventDevice != null) {
                    device = eventDevice
                }
                _connectionState.value = ConnectionState.Connected
            }
            is SdkStateMapper.DeviceOutcome.Error -> {
                _connectionState.value = outcome.error
            }
        }
    }

    override suspend fun connect() = connectMutex.withLock {
        // Idempotent: the SDK's ConnectIQListener fires onSdkReady only on the first initialize()
        // for a handle. A second initialize() on an already-initialized SDK never calls back, so
        // re-initializing hung every refresh after the first. When already initialized, skip
        // initialization and just RE-RESOLVE the device/app — that is the actual "is it online"
        // re-check a refresh wants.
        val existing = connectIq
        if (sdkInitialized && existing != null) {
            GsoundLog.i(tag, "connect(): SDK already initialized; re-resolving device (refresh)")
            _connectionState.value = ConnectionState.Connecting
            resolveDeviceAndApp(existing)
            return@withLock
        }

        GsoundLog.i(tag, "connect(): initializing Connect IQ SDK (transport=WIRELESS, via Garmin Connect)")
        _connectionState.value = ConnectionState.Connecting

        val iq = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        connectIq = iq

        // Await onSdkReady / onInitializeError. On ready we proceed to resolve device + app;
        // on error we map to a ConnectionState.Error and stop.
        val ready = CompletableDeferred<Boolean>()
        val listener = object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                GsoundLog.i(tag, "onSdkReady: SDK bound to Garmin Connect; resolving device")
                sdkInitialized = true
                if (!ready.isCompleted) ready.complete(true)
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                GsoundLog.e(tag, "onInitializeError: status=$status (is Garmin Connect installed/updated?)")
                sdkInitialized = false
                _connectionState.value = SdkStateMapper.mapInitError(status)
                if (!ready.isCompleted) ready.complete(false)
            }

            override fun onSdkShutDown() {
                GsoundLog.i(tag, "onSdkShutDown")
                sdkInitialized = false
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        try {
            iq.initialize(context, /* autoUI = */ false, listener)
        } catch (t: Throwable) {
            GsoundLog.e(tag, "initialize() threw: ${t.javaClass.simpleName}", t)
            _connectionState.value = SdkStateMapper.mapThrowable(t)
            return@withLock
        }

        if (!ready.await()) {
            // onInitializeError already set the error state.
            return@withLock
        }

        resolveDeviceAndApp(iq)
    }

    /**
     * After the SDK is ready: pick the first CONNECTED device, confirm the GsoundG app is
     * installed, register listeners, and move to [ConnectionState.Connected]. SDK throws are
     * mapped via [mapThrowable]; missing device/app map to the matching reasons.
     */
    private suspend fun resolveDeviceAndApp(iq: ConnectIQ) {
        // Resolve a reachable device over a short window. The candidate device is found by
        // enumeration, but its LIVE connection status is resolved via the device-event listener
        // + iq.getDeviceStatus(...), NOT the synchronous IQDevice.status field: with the WIRELESS
        // transport that field is a stale placeholder that stays UNKNOWN even for a healthy,
        // connected watch (observed on a fenix 6S Pro via GCM), so filtering enumeration on
        // status == CONNECTED never matched and timed out to a false NO_DEVICE. Any SDK throw is
        // mapped and aborts the wait.
        val connected = try {
            awaitConnectedDevice(iq)
        } catch (t: Throwable) {
            _connectionState.value = SdkStateMapper.mapThrowable(t)
            return
        }

        if (connected == null) {
            // No reachable device after the full poll window. Let the mapper decide between
            // "Bluetooth is off" and "no device" using the phone's current adapter state
            // (task 6.4, Req 4.2).
            GsoundLog.w(tag, "no CONNECTED device after poll window (btEnabled=${isBluetoothEnabled()}) -> NO_DEVICE/BLUETOOTH_OFF")
            _connectionState.value = SdkStateMapper.resolveConnectivityError(isBluetoothEnabled())
            return
        }
        device = connected
        GsoundLog.i(tag, "resolved device: name=${connected.friendlyName} id=${connected.deviceIdentifier} status=${connected.status}")

        // Confirm the GsoundG app is installed, then register for events (Req 4.1).
        val appInstalled = suspendCancellableCoroutine { cont ->
            iq.getApplicationInfo(
                GsoundAppId.VALUE,
                connected,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(iqApp: IQApp) {
                        GsoundLog.i(tag, "GsoundG app found on watch (appId=${GsoundAppId.VALUE})")
                        app = iqApp
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onApplicationNotInstalled(applicationId: String) {
                        GsoundLog.w(tag, "GsoundG app NOT installed on watch (appId=$applicationId)")
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }

        if (!appInstalled) {
            // Route through the mapper so the NOT_INSTALLED → APP_NOT_INSTALLED decision and its
            // actionable wording live in one place (task 6.4, Req 4.2).
            _connectionState.value =
                when (val outcome = SdkStateMapper.mapAppStatus(IQApp.IQAppStatus.NOT_INSTALLED)) {
                    is SdkStateMapper.AppOutcome.Installed -> ConnectionState.Connected
                    is SdkStateMapper.AppOutcome.Error -> outcome.error
                }
            return
        }

        val resolvedApp = app ?: gsoundApp
        try {
            iq.registerForAppEvents(connected, resolvedApp, appEventListener)
            iq.registerForDeviceEvents(connected, deviceEventListener)
        } catch (t: Throwable) {
            GsoundLog.e(tag, "registerForEvents threw: ${t.javaClass.simpleName}", t)
            _connectionState.value = SdkStateMapper.mapThrowable(t)
            return
        }

        GsoundLog.i(tag, "control channel CONNECTED (device + GsoundG app ready)")
        _connectionState.value = ConnectionState.Connected
    }

    /**
     * Resolve a reachable Garmin device within the poll window, returning it once its LIVE status
     * is [IQDevice.IQDeviceStatus.CONNECTED], or null if none becomes connected in time.
     *
     * ## Why not the synchronous `IQDevice.status`
     * With the WIRELESS transport (via Garmin Connect Mobile) the device objects returned by
     * [ConnectIQ.getConnectedDevices]/[ConnectIQ.getKnownDevices] carry a STALE status that
     * stays [IQDevice.IQDeviceStatus.UNKNOWN] even for a healthy, connected watch — observed on
     * a fenix 6S Pro: `connectedDevices=1 [fenix 6S Pro=UNKNOWN]` on every poll. Filtering
     * enumeration on `status == CONNECTED` therefore never matched and always timed out to a
     * false NO_DEVICE. The authoritative live status comes from [ConnectIQ.getDeviceStatus] and
     * from the async [ConnectIQ.IQDeviceEventListener] — not that field.
     *
     * ## Strategy
     * 1. Enumerate a CANDIDATE device (first of `connectedDevices`, else `knownDevices`) — WITHOUT
     *    filtering on the placeholder status. The watch is present even while reporting UNKNOWN.
     * 2. Register that candidate for device events ([ConnectIQ.registerForDeviceEvents]) so GCM
     *    pushes its real status, and also actively query [ConnectIQ.getDeviceStatus].
     * 3. Accept the device as soon as either signal reports CONNECTED; keep re-checking (and
     *    re-enumerating, since the candidate list itself may start empty right after
     *    onSdkReady) every [devicePollIntervalMs] until the [devicePollTotalMs] budget is spent.
     *
     * The event listener registered here is the SAME [deviceEventListener] used for the lifetime
     * of the connection, so registration is not torn down on success — [resolveDeviceAndApp]
     * re-registers it idempotently after the app check, and [close] unregisters it.
     *
     * A thrown SDK exception propagates to the caller (mapped to an error state) rather than
     * being swallowed, so a hard failure is not mistaken for "still enumerating".
     */
    private suspend fun awaitConnectedDevice(iq: ConnectIQ): IQDevice? {
        var registeredFor: IQDevice? = null
        var elapsed = 0L
        while (true) {
            val candidate = firstCandidate(iq)

            if (candidate != null) {
                // Register for this candidate's events once so GCM delivers its live status.
                if (registeredFor?.deviceIdentifier != candidate.deviceIdentifier) {
                    try {
                        iq.registerForDeviceEvents(candidate, deviceEventListener)
                        registeredFor = candidate
                    } catch (t: Throwable) {
                        // Registration failure is not fatal to resolution: fall back to polling
                        // getDeviceStatus below. A genuinely broken SDK surfaces via that call.
                        GsoundLog.w(tag, "registerForDeviceEvents(candidate) failed: ${t.javaClass.simpleName}")
                    }
                }

                // Authoritative live status (not the stale IQDevice.status placeholder).
                val liveStatus = runCatching { iq.getDeviceStatus(candidate) }.getOrNull()
                GsoundLog.d(tag, "candidate=${candidate.friendlyName} liveStatus=$liveStatus (field=${candidate.status})")
                if (liveStatus == IQDevice.IQDeviceStatus.CONNECTED) {
                    return candidate
                }
            }

            if (elapsed >= devicePollTotalMs) {
                return null
            }
            GsoundLog.d(tag, "device not CONNECTED yet; waiting (elapsed=${elapsed}ms/${devicePollTotalMs}ms)")
            delay(devicePollIntervalMs)
            elapsed += devicePollIntervalMs
        }
    }

    /**
     * One enumeration pass returning a CANDIDATE device to resolve — the first entry of
     * `connectedDevices`, falling back to the first of `knownDevices` — WITHOUT filtering on the
     * synchronous [IQDevice.status], which is an unreliable placeholder under the WIRELESS
     * transport (see [awaitConnectedDevice]). Returns null only when GCM reports no devices at
     * all. SDK throws propagate to the caller rather than being treated as "no device".
     */
    private fun firstCandidate(iq: ConnectIQ): IQDevice? {
        val connectedList = iq.connectedDevices.orEmpty()
        val knownList = iq.knownDevices.orEmpty()
        // Diagnostic (non-secret): friendlyName + status are not sensitive.
        GsoundLog.d(
            tag,
            "enumerate: connectedDevices=${connectedList.size} ${connectedList.describe()} | " +
                "knownDevices=${knownList.size} ${knownList.describe()}",
        )
        return connectedList.firstOrNull() ?: knownList.firstOrNull()
    }

    /** Compact, non-secret rendering of a device list for diagnostics: `[name=status, ...]`. */
    private fun List<IQDevice>.describe(): String =
        joinToString(prefix = "[", postfix = "]") { "${it.friendlyName}=${it.status}" }

    /**
     * Encode and send [message], retrying transient transport failures with exponential backoff
     * (Req 10.2). Only the final outcome is returned: [Result.success] once the transport
     * accepts the payload, or the last [Result.failure] once the retry budget is exhausted so
     * the caller can inform the user that the channel is down.
     */
    override suspend fun send(message: ControlMessage): Result<Unit> {
        // Redaction policy (Req 8.4, design §5.7): the encoded [payload] / [message] may carry
        // sensitive pairing values (the 6-digit code / secret in a PAIR_REQUEST). Do NOT log the
        // payload or message contents. If diagnostics are needed, log only the non-secret
        // discriminator and pairingId (e.g. via dev.gsound.a.log.GsoundLog), and route any secret
        // through dev.gsound.a.log.Redact.redact first.
        // Log only the non-secret message type — never the encoded payload, which may carry the
        // PAIR_REQUEST code/secret (Req 8.4).
        GsoundLog.i(tag, "send: type=${message::class.simpleName}")
        // Send a STRUCTURED Map, not a JSON String: the Connect IQ SDK marshals a Map to a
        // Monkey C Dictionary on the watch, which is what GsoundG's Protocol.validate requires.
        // A JSON String would arrive as a watch String and be dropped as INVALID_NOT_DICT.
        val payload = MessageCodec.encodeToMap(message)
        val result = OutgoingRetry.retryWithBackoff(policy = retryPolicy) {
            sendOnce(payload)
        }
        if (result.isSuccess) {
            GsoundLog.i(tag, "send ok: type=${message::class.simpleName}")
        } else {
            GsoundLog.w(tag, "send FAILED: type=${message::class.simpleName} cause=${result.exceptionOrNull()?.javaClass?.simpleName}")
        }
        return result
    }

    /**
     * A single send attempt. Resolves the current device/app and hands [payload] to the SDK,
     * returning [Result.success] on [ConnectIQ.IQMessageStatus.SUCCESS] and [Result.failure]
     * (never throwing for transport problems) otherwise, so [OutgoingRetry] can retry.
     */
    private suspend fun sendOnce(payload: Map<String, Any?>): Result<Unit> {
        var iq = connectIq
        // Self-heal: the device-event listener can flip connectionState to Connected (a BT
        // reconnect after unpair/reboot) WITHOUT binding `device`/`app` — those are only set by
        // resolveDeviceAndApp. If we have no resolved device yet, resolve it on demand via
        // connect() (idempotent) rather than failing, so a send never dies with "not connected"
        // while the watch is actually reachable. This was the cause of PAIR_REQUEST failing with
        // device=false right after the channel reported Connected.
        if (device == null) {
            GsoundLog.i(tag, "sendOnce: no resolved device yet; resolving on demand before send")
            runCatching { connect() }
                .onFailure { GsoundLog.w(tag, "sendOnce: on-demand connect() threw: ${it.javaClass.simpleName}") }
            iq = connectIq
        }
        val targetDevice = device
        val targetApp = app ?: gsoundApp
        if (iq == null || targetDevice == null) {
            GsoundLog.w(tag, "sendOnce: not connected (iq=${iq != null}, device=${targetDevice != null})")
            return Result.failure(
                IllegalStateException("Control channel not connected; call connect() first"),
            )
        }

        return try {
            suspendCancellableCoroutine { cont ->
                iq.sendMessage(
                    targetDevice,
                    targetApp,
                    payload,
                    ConnectIQ.IQSendMessageListener { _, _, status ->
                        if (!cont.isActive) return@IQSendMessageListener
                        if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                            cont.resume(Result.success(Unit))
                        } else {
                            GsoundLog.w(tag, "sendMessage transport status=$status (will retry if budget remains)")
                            cont.resume(
                                Result.failure(
                                    SendFailedException(status),
                                ),
                            )
                        }
                    },
                )
            }
        } catch (t: Throwable) {
            // InvalidStateException / ServiceUnavailableException and any other throw.
            Result.failure(t)
        }
    }

    override suspend fun close() {
        val iq = connectIq
        val currentDevice = device
        // Unregister listeners defensively; the SDK may be in any state here.
        if (iq != null) {
            try {
                if (currentDevice != null) {
                    iq.unregisterForDeviceEvents(currentDevice)
                    val currentApp = app
                    if (currentApp != null) {
                        iq.unregisterForEvents(currentDevice)
                    }
                }
            } catch (_: Throwable) {
                // Best-effort cleanup; ignore.
            }
            try {
                iq.shutdown(context)
            } catch (_: Throwable) {
                // Best-effort cleanup; ignore.
            }
        }
        connectIq = null
        device = null
        app = null
        sdkInitialized = false
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Defensive read of the phone's Bluetooth adapter state — the only Android-specific input to
     * the otherwise pure [SdkStateMapper] (task 6.4). The SDK channel runs over Bluetooth, so
     * when no device resolves this tells the mapper whether to prefer
     * [ConnectionState.Error.Reason.BLUETOOTH_OFF] (BT disabled) over
     * [ConnectionState.Error.Reason.NO_DEVICE].
     *
     * On a device with no Bluetooth adapter (null [android.bluetooth.BluetoothAdapter]) or if the
     * system service is unavailable, we report `true` ("not off") so the user is pointed at the
     * real cause (no device) rather than a misleading "turn Bluetooth on".
     */
    private fun isBluetoothEnabled(): Boolean = try {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        // Null adapter → no BT hardware → treat as "not off".
        manager?.adapter?.isEnabled ?: true
    } catch (_: Throwable) {
        // Never let a platform read break connection resolution; assume BT is not the problem.
        true
    }

    /**
     * Carries the failing [ConnectIQ.IQMessageStatus] when [send] does not succeed, so callers
     * (and task 6.3's retry logic) can inspect the transport outcome.
     */
    class SendFailedException(
        val status: ConnectIQ.IQMessageStatus,
    ) : Exception("Connect IQ sendMessage failed: $status")
}
