package dev.pntome.app.net.server

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Pure secret-authentication helpers for the HTTP data channel (Req 5.3, 8.4).
 *
 * Kept framework-free (no Ktor, no Android) so the exact comparison rule can be unit-tested on
 * the JVM independently of the Ktor test host (task 8.4 covers the end-to-end `401`).
 *
 * The HTTP header carrying the credential.
 */
const val SECRET_HEADER = "X-PNTome-Secret"

/**
 * Whether a request bearing [presented] (the `X-PNTome-Secret` header value, or `null`/empty when
 * absent) is authorized given the [expected] secret.
 *
 * Rules (fail closed):
 * - `expected == null` → no secret can be authenticated right now → **never** authorized
 *   (returns `false`), regardless of what the client sent.
 * - `presented == null` (header missing) → not authorized.
 * - otherwise authorized iff the two byte sequences are equal.
 *
 * The comparison uses [MessageDigest.isEqual] over the UTF-8 bytes, which is **constant-time** in
 * the length of the inputs (it does not early-return on the first differing byte). This avoids a
 * timing side channel that could let an attacker recover the secret byte-by-byte. Neither
 * argument is logged (Req 8.4).
 *
 * @param presented the value of the `X-PNTome-Secret` header, or `null` when the header is absent.
 * @param expected the secret the request must match, or `null` when nothing can be authenticated.
 */
fun isSecretAuthorized(presented: String?, expected: String?): Boolean {
    if (expected == null || presented == null) return false
    val presentedBytes = presented.toByteArray(StandardCharsets.UTF_8)
    val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
    // MessageDigest.isEqual compares all bytes without short-circuiting (constant-time), and
    // already returns false for a length mismatch — safe for the empty-string cases too.
    return MessageDigest.isEqual(presentedBytes, expectedBytes)
}
