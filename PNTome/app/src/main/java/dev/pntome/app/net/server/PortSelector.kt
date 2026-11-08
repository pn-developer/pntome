package dev.pntome.app.net.server

/**
 * Pure, JVM-testable selection of an available local HTTP server port with fallback
 * (design §5.5, Req 5.1 "on an available port").
 *
 * ## Why a dedicated object
 * The Wi-Fi server (task 8.1) prefers a stable, user-visible port (default 8080, see
 * [dev.pntome.app.domain.model.AppSettings.serverPort]) so the LAN address shown to the user
 * (Req 5.8) is predictable across syncs. But that port can already be taken by another app, so
 * the server must gracefully fall back to another port rather than fail the whole sync. The
 * "which port do we actually bind" decision is therefore a small policy worth isolating from
 * the Ktor/socket machinery so it can be unit-tested exhaustively without opening real sockets.
 *
 * ## Purity / testability
 * [select] takes the preferred port plus an injectable [isBindable] predicate and returns a
 * [Selection]. No sockets, no Ktor, no Android — tests drive it with a fake predicate. The real
 * server supplies a predicate backed by an actual bind probe (see [PNTomeHttpServer]).
 *
 * ## Strategy
 * 1. Try the [preferredPort] first (the predictable default).
 * 2. On failure, walk a small contiguous range above it ([FALLBACK_RANGE] ports) so the chosen
 *    port stays close to the advertised default and still human-recognizable.
 * 3. If the whole range is busy, fall back to the ephemeral port [EPHEMERAL_PORT] (`0`), letting
 *    the OS assign any free port. The caller must then read back the actually-bound port.
 *
 * The ephemeral fallback means [select] does not fail for a busy-port reason; it always yields a
 * usable [Selection]. (A truly unbindable `0` would surface later as a bind error from the
 * server itself, not from this pure selector.)
 */
object PortSelector {

    /**
     * Sentinel port value meaning "let the operating system pick any free ephemeral port".
     *
     * Binding to `0` is the standard JVM/socket convention for an OS-assigned port; the actual
     * port is only known after binding, so the server reads it back for the LAN address (Req 5.8).
     */
    const val EPHEMERAL_PORT: Int = 0

    /**
     * Number of consecutive ports (starting at [preferredPort]) tried before giving up on a
     * fixed port and falling back to the ephemeral port.
     *
     * Kept small so the chosen port stays close to the advertised default; a wide scan would
     * provide little benefit over just asking the OS for an ephemeral port.
     */
    const val FALLBACK_RANGE: Int = 10

    /** Highest valid TCP port; the fixed-port scan never probes beyond this. */
    private const val MAX_PORT: Int = 65_535

    /**
     * How the selected port was chosen — surfaced so the caller/UI can distinguish the happy
     * path (predictable default) from a fallback, and so tests can assert the branch taken.
     */
    enum class Strategy {
        /** The [preferredPort] itself was bindable. */
        PREFERRED,

        /** The preferred port was busy; a nearby fixed port in the fallback range was chosen. */
        FALLBACK_FIXED,

        /** The whole fixed range was busy; the OS will assign an ephemeral port. */
        EPHEMERAL
    }

    /**
     * The outcome of [select].
     *
     * @property port the port to bind to. For [Strategy.EPHEMERAL] this is [EPHEMERAL_PORT]
     *   (`0`) and the real bound port is only known after the server binds.
     * @property strategy how [port] was chosen.
     */
    data class Selection(
        val port: Int,
        val strategy: Strategy
    )

    /**
     * Pick a port to bind, preferring [preferredPort] and falling back as described above.
     *
     * @param preferredPort the port to try first (e.g. [dev.pntome.app.domain.model.AppSettings.serverPort],
     *   default 8080). Values outside the valid `1..65535` fixed-port space skip straight to the
     *   ephemeral fallback.
     * @param isBindable predicate answering "can the server bind this port right now?" — injected
     *   so this function stays pure and socket-free for unit tests. Never called with
     *   [EPHEMERAL_PORT].
     * @return the chosen [Selection]; never fails for a busy-port reason (ephemeral is the floor).
     */
    fun select(
        preferredPort: Int,
        isBindable: (Int) -> Boolean
    ): Selection {
        if (preferredPort in 1..MAX_PORT && isBindable(preferredPort)) {
            return Selection(preferredPort, Strategy.PREFERRED)
        }

        // Walk a small contiguous range above the preferred port, clamped to valid ports.
        if (preferredPort in 1..MAX_PORT) {
            val end = minOf(preferredPort + FALLBACK_RANGE, MAX_PORT)
            for (candidate in (preferredPort + 1)..end) {
                if (isBindable(candidate)) {
                    return Selection(candidate, Strategy.FALLBACK_FIXED)
                }
            }
        }

        return Selection(EPHEMERAL_PORT, Strategy.EPHEMERAL)
    }
}
