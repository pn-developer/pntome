package dev.pntome.app.ui.pairing

import android.util.Base64
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The three freshly-minted, temporary credentials a pairing attempt starts with (Req 4.3).
 *
 * These are *temporary* until pairing completes: if the attempt times out or is cancelled they
 * are discarded and never persisted (Req 4.6). Only on success do [pairingId] and [sharedSecret]
 * (plus the peer name) become a [dev.pntome.app.domain.model.PairingRecord].
 *
 * @property code the 6-digit confirmation code the user compares against the watch (Req 4.4).
 * @property pairingId the UUID that binds every subsequent control message to this pairing
 *   (Req 4.3, Req 10.3).
 * @property sharedSecret the secret later used to authenticate HTTP data-channel requests
 *   (Req 4.3, Req 5.3), 32 random bytes encoded Base64Url.
 */
data class PairingCredentials(
    val code: String,
    val pairingId: String,
    val sharedSecret: String,
)

/**
 * Mints the temporary [PairingCredentials] for a pairing attempt (design §5.4, Req 4.3).
 *
 * Extracted behind an interface so the pairing state machine can be unit-tested with a
 * deterministic generator (task 7.3) instead of real randomness, while production uses
 * [SecureRandomPairingCredentialGenerator]. This is one of the testability "seams" of
 * [PairingViewModel].
 */
interface PairingCredentialGenerator {
    /** Generate a fresh code / pairingId / sharedSecret triple for one pairing attempt. */
    fun generate(): PairingCredentials
}

/**
 * Production [PairingCredentialGenerator] (design §5.4):
 * - code: a uniformly random value in `[0, 1_000_000)` zero-padded to 6 digits,
 * - pairingId: [UUID.randomUUID],
 * - sharedSecret: 32 cryptographically-random bytes, Base64Url (no padding/wrap).
 *
 * Uses [SecureRandom] so the code and secret are not predictable.
 */
@Singleton
class SecureRandomPairingCredentialGenerator @Inject constructor() : PairingCredentialGenerator {

    private val random = SecureRandom()

    override fun generate(): PairingCredentials {
        val code = "%06d".format(random.nextInt(1_000_000))
        val pairingId = UUID.randomUUID().toString()
        val secretBytes = ByteArray(SECRET_BYTES).also(random::nextBytes)
        val sharedSecret = Base64.encodeToString(
            secretBytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return PairingCredentials(code = code, pairingId = pairingId, sharedSecret = sharedSecret)
    }

    private companion object {
        /** 32 bytes → 256 bits of entropy for the shared secret. */
        const val SECRET_BYTES = 32
    }
}
