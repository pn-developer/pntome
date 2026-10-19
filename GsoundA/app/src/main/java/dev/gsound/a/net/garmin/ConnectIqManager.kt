package dev.gsound.a.net.garmin

import android.bluetooth.BluetoothManager
import android.content.Context
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.net.garmin.messages.ControlMessage
import dev.gsound.a.net.garmin.messages.MessageCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
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
        for (payload in messageData) {
            val json = payload as? String ?: continue
            when (val result = MessageCodec.decodeAndValidate(json, expectedPairingId)) {
                is MessageCodec.DecodeResult.Valid ->
                    // Non-blocking emit; the SharedFlow buffer absorbs bursts.
                    _incoming.tryEmit(result.message)

                // Invalid envelope/schema or an unpaired peer: ignore (Req 10.3).
                is MessageCodec.DecodeResult.Invalid,
                is MessageCodec.DecodeResult.UnpairedPeer,
                -> Unit
            }
        }
    }

    /**
     * Watches device-level status changes. A status other than CONNECTED after we reached
     * [ConnectionState.Connected] is mapped to an actionable error via [SdkStateMapper]
     * (typically [ConnectionState.Error.Reason.DEVICE_DISCONNECTED]); a CONNECTED status restores
     * [ConnectionState.Connected] (task 6.4).
     */
    private val deviceEventListener = ConnectIQ.IQDeviceEventListener { _, status ->
        _connectionState.value = when (val outcome = SdkStateMapper.mapDeviceStatus(status)) {
            is SdkStateMapper.DeviceOutcome.Connected -> ConnectionState.Connected
            is SdkStateMapper.DeviceOutcome.Error -> outcome.error
        }
    }

    override suspend fun connect() {
        _connectionState.value = ConnectionState.Connecting

        val iq = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
        connectIq = iq

        // Await onSdkReady / onInitializeError. On ready we proceed to resolve device + app;
        // on error we map to a ConnectionState.Error and stop.
        val ready = CompletableDeferred<Boolean>()
        val listener = object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                if (!ready.isCompleted) ready.complete(true)
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                _connectionState.value = SdkStateMapper.mapInitError(status)
                if (!ready.isCompleted) ready.complete(false)
            }

            override fun onSdkShutDown() {
                _connectionState.value = ConnectionState.Disconnected
            }
        }

        try {
            iq.initialize(context, /* autoUI = */ false, listener)
        } catch (t: Throwable) {
            _connectionState.value = SdkStateMapper.mapThrowable(t)
            return
        }

        if (!ready.await()) {
            // onInitializeError already set the error state.
            return
        }

        resolveDeviceAndApp(iq)
    }

    /**
     * After the SDK is ready: pick the first CONNECTED device, confirm the GsoundG app is
     * installed, register listeners, and move to [ConnectionState.Connected]. SDK throws are
     * mapped via [mapThrowable]; missing device/app map to the matching reasons.
     */
    private suspend fun resolveDeviceAndApp(iq: ConnectIQ) {
        val connected = try {
            iq.connectedDevices.orEmpty().firstOrNull {
                it.status == IQDevice.IQDeviceStatus.CONNECTED
            }
        } catch (t: Throwable) {
            _connectionState.value = SdkStateMapper.mapThrowable(t)
            return
        }

        if (connected == null) {
            // No reachable device. Let the mapper decide between "Bluetooth is off" and
            // "no device" using the phone's current adapter state (task 6.4, Req 4.2).
            _connectionState.value = SdkStateMapper.resolveConnectivityError(isBluetoothEnabled())
            return
        }
        device = connected

        // Confirm the GsoundG app is installed, then register for events (Req 4.1).
        val appInstalled = suspendCancellableCoroutine { cont ->
            iq.getApplicationInfo(
                GsoundAppId.VALUE,
                connected,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(iqApp: IQApp) {
                        app = iqApp
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onApplicationNotInstalled(applicationId: String) {
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
            _connectionState.value = SdkStateMapper.mapThrowable(t)
            return
        }

        _connectionState.value = ConnectionState.Connected
    }

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
        val payload = MessageCodec.encode(message)
        return OutgoingRetry.retryWithBackoff(policy = retryPolicy) {
            sendOnce(payload)
        }
    }

    /**
     * A single send attempt. Resolves the current device/app and hands [payload] to the SDK,
     * returning [Result.success] on [ConnectIQ.IQMessageStatus.SUCCESS] and [Result.failure]
     * (never throwing for transport problems) otherwise, so [OutgoingRetry] can retry.
     */
    private suspend fun sendOnce(payload: String): Result<Unit> {
        val iq = connectIq
        val targetDevice = device
        val targetApp = app ?: gsoundApp
        if (iq == null || targetDevice == null) {
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
