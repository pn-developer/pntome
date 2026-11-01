package dev.gsound.a.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.log.GsoundLog
import dev.gsound.a.net.garmin.ControlChannel
import dev.gsound.a.net.garmin.messages.SyncRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifetime listener that lets the **watch** start a sync (Req: initiate sync from the watch).
 *
 * ## Why this is app-scoped, not screen-scoped
 * A watch-initiated sync must work even when the Sync screen is not open, so this cannot live in
 * a ViewModel (those only collect while their screen is alive). Every other inbound-message
 * observer in the app is transient; this is the one always-on collector, started once from
 * [dev.gsound.a.GsoundApplication.onCreate] and never torn down for the process lifetime.
 *
 * ## What it does
 * Collects [SyncRequest] (`SYNC_REQUEST`, G→A) from [ControlChannel.incoming], verifies the
 * `pairingId` matches the stored pairing (defence in depth — the channel already drops foreign
 * peers), and starts the sync via the same [SyncForegroundService] path the on-screen Sync
 * button uses. The orchestrator's single-sync mutex and the service's start guard make a
 * duplicate/concurrent trigger safe, so a stray repeat `SYNC_REQUEST` cannot start two syncs.
 */
@Singleton
class WatchSyncRequestListener @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlChannel: ControlChannel,
    private val pairingStore: PairingStore,
) {

    private val tag = "Gsound.Sync"

    /** App-lifetime scope; a failed collection of one message must not kill the listener. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** Begin listening. Idempotent: safe to call once from Application.onCreate. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            controlChannel.incoming
                .filterIsInstance<SyncRequest>()
                .collect { request ->
                    val currentPairingId = pairingStore.get()?.pairingId
                    if (currentPairingId == null) {
                        GsoundLog.w(tag, "SYNC_REQUEST ignored: not paired")
                        return@collect
                    }
                    if (request.pairingId != currentPairingId) {
                        GsoundLog.w(
                            tag,
                            "SYNC_REQUEST ignored: pairingId mismatch (got=${request.pairingId})",
                        )
                        return@collect
                    }
                    GsoundLog.i(tag, "SYNC_REQUEST from watch -> starting sync service")
                    SyncForegroundService.start(context)
                }
        }
    }
}
