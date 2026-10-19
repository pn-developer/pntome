package dev.gsound.a.net.server

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, process-wide holder of the currently active [SyncSession] (design §5.5).
 *
 * ## Why a holder
 * The HTTP server is a long-lived `@Singleton`, but *what it serves* changes per sync: the sync
 * orchestration (task 9) builds a fresh [SyncSession], installs it here with [set] when a sync
 * starts, and clears it with [clear] when the sync completes or is cancelled (Req 5.6). The
 * routes read the current session through this holder (via [MediaSource]) on every request, so
 * they always reflect the latest manifest without the server being restarted or re-wired.
 *
 * ## Concurrency
 * The server handles requests on Ktor's coroutine dispatcher while the orchestrator mutates the
 * session from elsewhere; an [AtomicReference] gives lock-free, consistent reads/writes of the
 * whole (immutable) session reference. Because [SyncSession] is immutable, a reader always sees
 * a coherent snapshot.
 *
 * ## No active session
 * Before the first sync and after a sync ends [current] is `null`. The [MediaSource] built on
 * top of this (via [ContentResolverMediaSource]) turns that into an empty `/manifest` and `404`
 * on the media endpoint (see [MediaSource]).
 */
@Singleton
class SyncSessionHolder @Inject constructor() {

    private val ref = AtomicReference<SyncSession?>(null)

    /** The active session, or `null` when no sync is in progress. */
    val current: SyncSession?
        get() = ref.get()

    /** Install [session] as the active sync session (called by the orchestrator on sync start). */
    fun set(session: SyncSession) {
        ref.set(session)
    }

    /** Clear the active session so the server stops serving its tracks (Req 5.6). */
    fun clear() {
        ref.set(null)
    }
}
