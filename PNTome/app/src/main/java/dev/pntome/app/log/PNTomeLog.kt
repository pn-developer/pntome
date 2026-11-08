package dev.pntome.app.log

import android.util.Log

/**
 * The app's logging seam (Req 8.4, design §5.7 "a Redacting logger wraps sensitive fields").
 *
 * ## Policy
 * Secrets MUST NOT be written to logs. Specifically, callers MUST pass the following through
 * [Redact.redact] before including them in any log message:
 * - the pairing `sharedSecret` and the `X-PNTome-Secret` header value,
 * - the pairing `secret` and the 6-digit confirmation code.
 *
 * Non-secret identifiers used for diagnostics — the `pairingId` (opaque UUID) and a message
 * `type` — are safe to log verbatim. The guidance is: log *type + pairingId*, never the secret.
 *
 * ## Why an interface
 * [android.util.Log] is a static Android API that no-ops (and warns) under plain JVM unit tests.
 * Hiding it behind [Logger] lets production code depend on a tiny tag+level surface, lets tests
 * substitute [NoopLogger] (or a capturing fake) without Robolectric, and gives us one choke point
 * where the redaction policy is documented. The default production instance is [PNTomeLog].
 *
 * This type adds **no** new runtime logging on its own; it exists so that any logging added later
 * routes secrets through [Redact]. Current call sites deliberately avoid logging secret-bearing
 * values at all (see `ConnectIqManager`, `SecretAuth`, `SecretProvider`, `PNTomeRouting`).
 */
interface Logger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    /**
     * Convenience that redacts [secret] via [Redact.redact] before logging it at debug level,
     * so a developer can confirm presence/length of a credential without ever emitting it.
     * The raw value is discarded; only the placeholder is logged.
     */
    fun dRedacted(tag: String, label: String, secret: String?) {
        d(tag, "$label=${Redact.redact(secret)}")
    }
}

/**
 * Production [Logger] backed by [android.util.Log]. A thin pass-through — the only behaviour it
 * guarantees beyond the platform call is the [Logger.dRedacted] helper from the interface, which
 * runs values through [Redact] (Req 8.4).
 */
object PNTomeLog : Logger {
    override fun d(tag: String, message: String) { Log.d(tag, message) }
    override fun i(tag: String, message: String) { Log.i(tag, message) }
    override fun w(tag: String, message: String, throwable: Throwable?) { Log.w(tag, message, throwable) }
    override fun e(tag: String, message: String, throwable: Throwable?) { Log.e(tag, message, throwable) }
}

/**
 * A [Logger] that discards everything. Useful as a default in JVM unit tests where
 * [android.util.Log] is unavailable, and as an explicit "logging intentionally off" choice.
 */
object NoopLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}
