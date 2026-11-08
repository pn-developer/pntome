package dev.pntome.app.net.server

import dev.pntome.app.domain.model.AppSettings
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ServerSocket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The local HTTP data-channel server (Ktor CIO, embedded) that serves selected music to the
 * watch over Wi-Fi (design §5.5, Req 5.1, 5.6, 5.7, 5.8).
 *
 * This task (8.1) owns the server **lifecycle, LAN binding, and address/port resolution** only.
 * The routes (`/health`, `/manifest`, `/media/{trackId}`) and the secret-auth interceptor are
 * tasks 8.2/8.3 — they plug in through the [modules] routing seam (see below) without touching
 * this lifecycle code.
 *
 * ## Lifecycle
 * - [start] resolves the Wi-Fi address, selects a port, binds Ktor CIO, and returns a
 *   [StartResult]. It is idempotent-safe under a [Mutex]: a second [start] while running returns
 *   the existing [StartResult.Started].
 * - [stop] shuts the engine down and clears state; safe to call when not running.
 * - [serverUrl] exposes the bound `http://ip:port` for Req 5.8 (UI display) and for the
 *   `SYNC_MANIFEST { serverUrl }` message sent later (Req 6.1); `null` when not running.
 *
 * ## Wi-Fi fail-fast (Req 5.7)
 * [start] first asks [wifiAddressResolver] for the LAN IPv4 address. If it is `null` the server
 * does **not** start and returns [StartResult.WifiUnavailable]; the caller turns that into the
 * "both devices must be on the same Wi-Fi network" guidance. The server never binds to
 * `0.0.0.0`/all interfaces — only the resolved Wi-Fi address — keeping it off other networks
 * (design §8 security).
 *
 * ## Port selection (Req 5.1)
 * The preferred port comes from the caller (typically [AppSettings.serverPort], default
 * [AppSettings.DEFAULT_SERVER_PORT]). [PortSelector] picks it or a nearby/ephemeral fallback
 * using the real [isPortBindable] probe. For an ephemeral selection the actually-bound port is
 * read back from the engine so [serverUrl] is always concrete.
 *
 * ## Routing seam for tasks 8.2 / 8.3
 * The constructor takes [modules]: a list of `Application.() -> Unit` configuration blocks
 * applied to the Ktor application at [start]. Task 8.2/8.3 provide a module that installs the
 * routes and the auth interceptor (and whatever plugins they need, e.g. ContentNegotiation /
 * PartialContent). This task installs no routes of its own, keeping the seam clean; a server
 * started with the default (empty) [modules] still binds and serves 404s, which is enough to
 * verify binding/lifecycle (and task 8.4's `/health` test will pass once 8.2 supplies the
 * module). Hilt provides the production [modules] list (see `ServerModule`).
 *
 * @property wifiAddressResolver resolves the Wi-Fi LAN address (and signals unavailability).
 * @property modules Ktor application configuration blocks (routes/auth) injected by 8.2/8.3.
 * @property isPortBindable probe used by [PortSelector]; injectable so lifecycle tests need no
 *   real sockets (defaults to a real TCP bind probe on the resolved host).
 */
@Singleton
class PNTomeHttpServer @Inject constructor(
    private val wifiAddressResolver: WifiAddressResolver,
    private val modules: Set<@JvmSuppressWildcards Application.() -> Unit>,
) {

    /** Serializes [start]/[stop] so concurrent sync triggers cannot double-bind. */
    private val lifecycleMutex = Mutex()

    @Volatile
    private var engine: ApplicationEngine? = null

    @Volatile
    private var running: Running? = null

    /** The bound server URL (`http://ip:port`) while running, else `null` (Req 5.8, 6.1). */
    val serverUrl: String?
        get() = running?.url

    /** Whether the server is currently bound and serving. */
    val isRunning: Boolean
        get() = running != null

    /**
     * Outcome of [start].
     *
     * Modeled as a sealed result (rather than throwing) so the caller can turn the
     * Wi-Fi-unavailable case directly into Req 5.7 user guidance and the error case into a
     * retryable failure, with exhaustive handling.
     */
    sealed interface StartResult {
        /**
         * The server bound successfully.
         *
         * @property url the `http://ip:port` the server is listening on (Req 5.8).
         * @property host the resolved Wi-Fi LAN address bound to.
         * @property port the actual bound port (concrete even for an ephemeral selection).
         */
        data class Started(
            val url: String,
            val host: String,
            val port: Int,
            val kind: LanKind = LanKind.WIFI,
        ) : StartResult

        /**
         * Wi-Fi is unavailable, so the server was not started (Req 5.7). The caller should tell
         * the user both devices must be on the same Wi-Fi network.
         */
        data object WifiUnavailable : StartResult

        /**
         * Binding failed for an unexpected reason (e.g. the engine could not start). Retryable.
         *
         * @property detail short, secret-free diagnostic suitable for logging/UI.
         */
        data class Error(val detail: String) : StartResult
    }

    /** Internal snapshot of a running server. */
    private data class Running(val host: String, val port: Int, val kind: LanKind) {
        val url: String get() = "http://$host:$port"
    }

    /**
     * Start the server bound to the Wi-Fi LAN interface.
     *
     * @param preferredPort the port to try first (default [AppSettings.DEFAULT_SERVER_PORT]).
     * @return [StartResult.Started] with the bound URL, [StartResult.WifiUnavailable] (Req 5.7),
     *   or [StartResult.Error] on an unexpected bind failure.
     */
    suspend fun start(preferredPort: Int = AppSettings.DEFAULT_SERVER_PORT): StartResult =
        lifecycleMutex.withLock {
            running?.let { return@withLock StartResult.Started(it.url, it.host, it.port, it.kind) }

            // Req 5.7 / HS-2.3: no Wi-Fi and no hotspot → do not start. Bind only to the
            // single resolved LAN address (never 0.0.0.0).
            val lan = wifiAddressResolver.resolveLanAddress()
                ?: return@withLock StartResult.WifiUnavailable
            val host = lan.ipv4

            val selection = PortSelector.select(preferredPort) { candidate ->
                isPortBindable(host, candidate)
            }

            try {
                val startedEngine = embeddedServer(
                    factory = CIO,
                    host = host,
                    port = selection.port,
                ) {
                    modules.forEach { module -> module() }
                }.start(wait = false)

                // For an ephemeral (0) selection the real port is only known after binding.
                val boundPort = startedEngine.resolvedPort(fallback = selection.port)

                engine = startedEngine
                val snapshot = Running(host = host, port = boundPort, kind = lan.kind)
                running = snapshot
                StartResult.Started(
                    url = snapshot.url, host = host, port = boundPort, kind = lan.kind,
                )
            } catch (t: Throwable) {
                // Leave no half-started engine behind on failure.
                runCatching { engine?.stop(gracePeriodMillis = 0, timeoutMillis = 0) }
                engine = null
                running = null
                StartResult.Error(detail = t.message?.takeIf { it.isNotBlank() }
                    ?: "The music server could not start. Try again.")
            }
        }

    /**
     * Stop the server and release the port (Req 5.6). Safe to call when not running.
     */
    suspend fun stop() = lifecycleMutex.withLock {
        val current = engine
        engine = null
        running = null
        if (current != null) {
            runCatching { current.stop(gracePeriodMillis = GRACE_MS, timeoutMillis = TIMEOUT_MS) }
        }
        Unit
    }

    private companion object {
        const val GRACE_MS = 100L
        const val TIMEOUT_MS = 500L

        /**
         * Real "can we bind this port on this host?" probe backing [PortSelector]. Opens and
         * immediately closes a [ServerSocket] bound to the resolved Wi-Fi host; returns `false`
         * on any bind failure (port busy / address unusable).
         */
        fun isPortBindable(host: String, port: Int): Boolean = try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(java.net.InetSocketAddress(host, port))
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

        /**
         * Read back the actually-bound port from the Ktor engine's resolved connectors, falling
         * back to [fallback] (the requested port) when it cannot be resolved.
         */
        fun ApplicationEngine.resolvedPort(fallback: Int): Int =
            runCatching {
                runCatching {
                    // Ktor 2.x: resolvedConnectors() is a suspend fn; the engine resolves ports at
                    // bind time, so a blocking read here is safe immediately after start().
                    kotlinx.coroutines.runBlocking { resolvedConnectors() }
                        .firstOrNull()?.port
                }.getOrNull() ?: fallback
            }.getOrDefault(fallback)
                .let { if (it == PortSelector.EPHEMERAL_PORT) fallback else it }
    }
}
