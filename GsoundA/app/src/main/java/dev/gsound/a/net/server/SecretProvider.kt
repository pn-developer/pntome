package dev.gsound.a.net.server

import dev.gsound.a.data.pairing.PairingStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the secret the HTTP data-channel auth interceptor expects in the `X-Gsound-Secret`
 * header (Req 5.3, design §5.5/§8).
 *
 * ## Why an interface
 * The auth check (see [installGsoundRouting]) must be driven by a fake in the Ktor test-host
 * tests (task 8.4) without an Android `Context`, DataStore, or a running sync. Hiding "what is
 * the current expected secret?" behind this one small, suspendable method keeps the interceptor
 * free of those dependencies and makes `401`/success paths trivially scriptable.
 *
 * ## Contract
 * [expectedSecret] returns the secret a request must match, or `null` when there is **no**
 * expected secret right now (no active sync session and no persisted pairing). A `null` result
 * means *every* authenticated request is rejected with `401`: without a known secret the server
 * cannot authenticate anyone, so it fails closed rather than open.
 *
 * It is a `suspend` function because the production source may read the persisted pairing
 * (DataStore IO); the interceptor runs inside Ktor's request coroutine so it can await it.
 *
 * The returned value is a credential and MUST NOT be logged (Req 8.4).
 */
interface SecretProvider {

    /**
     * The secret the current request must present, or `null` when nothing can be authenticated
     * (→ interceptor replies `401`). See the class doc for the fail-closed rationale.
     */
    suspend fun expectedSecret(): String?
}

/**
 * Production [SecretProvider] that prefers the **active sync session's** secret and falls back to
 * the **persisted pairing's** secret (design §5.6).
 *
 * ## Source of truth, in order
 * 1. [SyncSessionHolder.current]`?.secret` — while a sync is in progress the orchestrator installs
 *    a [SyncSession] carrying the exact `secret` it also sent to the watch in
 *    `SYNC_MANIFEST { secret }`. Authenticating against the session guarantees the server and the
 *    watch agree even if the persisted pairing were rotated mid-sync.
 * 2. [PairingStore.get]`()?.sharedSecret` — when no session is active (e.g. a bare `/manifest`
 *    probe between syncs) the persisted pairing's secret is used, matching the design's
 *    "`X-Gsound-Secret == pairing.sharedSecret`".
 *
 * When neither exists (unpaired, no session) the result is `null` and the interceptor fails
 * closed with `401`.
 */
@Singleton
class DefaultSecretProvider @Inject constructor(
    private val sessionHolder: SyncSessionHolder,
    private val pairingStore: PairingStore,
) : SecretProvider {

    override suspend fun expectedSecret(): String? =
        sessionHolder.current?.secret ?: pairingStore.get()?.sharedSecret
}
