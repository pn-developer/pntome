package dev.gsound.a.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.gsound.a.MainActivity
import dev.gsound.a.R
import dev.gsound.a.domain.model.SyncState
import dev.gsound.a.domain.sync.SyncOrchestrator
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps a sync alive while the UI is backgrounded (design §5.6; Req 8.3).
 *
 * ## Why a foreground service
 * A sync drives the local [dev.gsound.a.net.server.GsoundHttpServer] (served from
 * [SyncOrchestrator]) that the watch pulls audio from. If the user leaves the app, Android would
 * otherwise be free to stop background work and tear down the process, killing the server
 * mid-transfer. Running the orchestration from a foreground service (with a visible notification)
 * keeps the server + control coordination running until the sync reaches a terminal state
 * (Req 8.3), subject to platform limits.
 *
 * ## Foreground service type
 * The design names the type "mediaSync/dataSync". The Android platform has **no `mediaSync`
 * foreground service type** — the correct, available constant for "transfer data over the
 * network to/from a device" is
 * [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] (`dataSync` in the
 * manifest). We therefore use `dataSync`. On Android 14+ (targetSdk 34+) the type must be
 * declared in the manifest **and** passed to [ServiceCompat.startForeground]; we do both.
 *
 * ## Wi-Fi lock (Req 8.3)
 * A [WifiManager.WifiLock] is acquired for the lifetime of the service so Wi-Fi is not powered
 * down (which would drop the LAN the watch connects to). We request a high-performance mode
 * ([WifiManager.WIFI_MODE_FULL_LOW_LATENCY] on API 29+, else the deprecated
 * [WifiManager.WIFI_MODE_FULL_HIGH_PERF]) and release it in [onDestroy].
 *
 * ## Lifecycle & API
 * The service is driven by intent actions, built via the [Companion] helpers the `SyncViewModel`
 * (task 10) calls:
 * - [start] → [ACTION_START]: acquire the Wi-Fi lock, go foreground, launch
 *   [SyncOrchestrator.startSync] once (double-start guarded by [syncLaunched]).
 * - [cancel] → [ACTION_CANCEL]: call [SyncOrchestrator.cancelSync] then [stopSelf].
 *   The notification's Cancel action fires this same action.
 *
 * It observes [SyncOrchestrator.state] on [lifecycleScope]: intermediate states update the
 * notification (Preparing → indeterminate; Running → determinate progress); any terminal state
 * (Completed/Error/Cancelled) stops the foreground service via [stopSelf].
 *
 * [LifecycleService] is used so collection is scoped to [lifecycleScope] and automatically
 * cancelled when the service is destroyed — no hand-rolled [kotlinx.coroutines.CoroutineScope].
 */
@AndroidEntryPoint
class SyncForegroundService : LifecycleService() {

    @Inject
    lateinit var orchestrator: SyncOrchestrator

    private var wifiLock: WifiManager.WifiLock? = null

    /** Guards against launching the orchestrator twice if [ACTION_START] is delivered again. */
    private var syncLaunched = false

    /** Guards against observing [SyncOrchestrator.state] more than once. */
    private var stateObserved = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder? {
        // Not a bound service, but LifecycleService requires a super call.
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LifecycleService forwards the start command to its lifecycle; keep the super call.
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_CANCEL -> {
                lifecycleScope.launch { orchestrator.cancelSync() }
                stopSelf()
                return START_NOT_STICKY
            }

            else -> startSync()
        }
        return START_NOT_STICKY
    }

    private fun startSync() {
        // Promote to foreground immediately with an initial notification so the system does not
        // kill us during startup (Android requires startForeground shortly after start).
        acquireWifiLock()
        goForeground(buildNotification(SyncState.Preparing))
        observeState()

        if (!syncLaunched) {
            syncLaunched = true
            lifecycleScope.launch {
                // startSync suspends until terminal; the state observer drives notification +
                // stopSelf, so we don't need its return value here.
                orchestrator.startSync()
            }
        }
    }

    private fun observeState() {
        if (stateObserved) return
        stateObserved = true
        lifecycleScope.launch {
            orchestrator.state.collect { state ->
                when (state) {
                    is SyncState.Preparing,
                    is SyncState.Running -> updateNotification(buildNotification(state))

                    is SyncState.Completed,
                    is SyncState.Error,
                    is SyncState.Cancelled -> {
                        // Terminal: drop the ongoing notification and stop the service.
                        stopForegroundCompat()
                        stopSelf()
                    }

                    is SyncState.Idle -> {
                        // Resting state before a sync begins; keep the startup notification.
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        releaseWifiLock()
        super.onDestroy()
    }

    // --- Wi-Fi lock (Req 8.3) ---------------------------------------------------------------

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, WIFI_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // --- Notification -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sync_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.sync_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: SyncState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.sync_action_cancel),
                cancelPendingIntent()
            )

        when (state) {
            is SyncState.Running -> {
                builder.setContentText(
                    getString(
                        R.string.sync_notification_progress,
                        state.tracksDone,
                        state.tracksTotal
                    )
                )
                if (state.tracksTotal > 0) {
                    builder.setProgress(state.tracksTotal, state.tracksDone, false)
                } else {
                    builder.setProgress(0, 0, true)
                }
            }

            else -> {
                // Preparing (and any transient non-Running state we notify for): indeterminate.
                builder.setContentText(getString(R.string.sync_notification_preparing))
                builder.setProgress(0, 0, true)
            }
        }
        return builder.build()
    }

    private fun goForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags())
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, SyncForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        return PendingIntent.getService(this, 1, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    companion object {
        private const val CHANNEL_ID = "gsound_sync"
        private const val NOTIFICATION_ID = 1001
        private const val WIFI_LOCK_TAG = "gsound:sync"

        /** Begin a sync in the foreground service. Idempotent at the service (double-start guarded). */
        const val ACTION_START = "dev.gsound.a.action.SYNC_START"

        /** Cancel the active sync and stop the service. Also fired by the notification action. */
        const val ACTION_CANCEL = "dev.gsound.a.action.SYNC_CANCEL"

        /** Build the [Intent] that starts a sync. */
        fun startIntent(context: Context): Intent =
            Intent(context, SyncForegroundService::class.java).apply { action = ACTION_START }

        /** Build the [Intent] that cancels a sync. */
        fun cancelIntent(context: Context): Intent =
            Intent(context, SyncForegroundService::class.java).apply { action = ACTION_CANCEL }

        /** Start the sync foreground service (the `SyncViewModel`, task 10, calls this). */
        fun start(context: Context) {
            context.startForegroundService(startIntent(context))
        }

        /** Request cancellation of the active sync. */
        fun cancel(context: Context) {
            context.startService(cancelIntent(context))
        }
    }
}
