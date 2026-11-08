package dev.pntome.app.log

/**
 * Pure, framework-free redaction helpers for log output (Req 8.4: PNTome SHALL NOT retain
 * unencrypted secrets in logs; design §5.7 "No secrets written to logs; a Redacting logger wraps
 * sensitive fields").
 *
 * ## What counts as a secret
 * The values that MUST never reach a log sink in cleartext are:
 * - [dev.pntome.app.domain.model.PairingRecord.sharedSecret] (the HTTP data-channel credential),
 * - the `X-PNTome-Secret` header value (same secret, as presented by the watch),
 * - the pairing `secret`/6-digit confirmation code generated during pairing.
 *
 * The `pairingId` (an opaque UUID) and message `type` are **not** secrets and may be logged as-is
 * for diagnostics — they identify a pairing/message without exposing the credential that gates
 * data access.
 *
 * ## Why pure (no Android)
 * [redact] contains the only logic worth testing (that the output never contains the raw secret
 * and has a stable shape); keeping it off `android.util.Log` lets it be pinned by JVM unit tests
 * (see `RedactTest`). The thin Android sink lives in [PNTomeLog].
 *
 * ## Contract
 * [redact] returns a short, **non-reversible** placeholder describing a value's presence and
 * length — never any portion of the value itself. A caller that is unsure whether a string holds
 * a secret should pass it through [redact] before logging.
 */
object Redact {

    /** The fixed marker used for present-but-redacted values. */
    const val MARKER: String = "***"

    /**
     * Render [secret] as a safe, non-reversible placeholder for logging.
     *
     * The result never contains any character of [secret]. It encodes only two facts a developer
     * might need while debugging — whether a value was present and (for a non-empty value) how
     * long it was:
     * - `null`  → `"<null>"`
     * - `""`    → `"<empty>"`
     * - else    → `"***(len=N)"` where `N` is [String.length] (e.g. `"***(len=43)"`).
     *
     * The length is intentionally coarse: it leaks only the size, which for a fixed-scheme secret
     * (32 random bytes Base64Url ⇒ constant length) carries no useful information, while helping
     * confirm a value was non-blank. It is stable (same input ⇒ same output) so log lines are
     * greppable.
     *
     * @param secret the sensitive value, or `null` when absent.
     * @return a placeholder that is safe to write to any log sink (Req 8.4).
     */
    fun redact(secret: String?): String = when {
        secret == null -> "<null>"
        secret.isEmpty() -> "<empty>"
        else -> "$MARKER(len=${secret.length})"
    }
}
