package dev.gsound.a.net.garmin.messages

import dev.gsound.a.net.garmin.messages.MessageCodec.DecodeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MessageCodec] (design §5.3, Req 10.3).
 *
 * Covers: round-trip encode/decode of a valid message; malformed JSON → [DecodeResult.Invalid];
 * unknown `type` → [DecodeResult.Invalid]; protocol-version mismatch handling; `pairingId`
 * mismatch → [DecodeResult.UnpairedPeer]; matching `pairingId` → [DecodeResult.Valid]; and the
 * null-`pairingId` liveness rule for `PING`/`PONG`.
 */
class MessageCodecTest {

    private val pairingId = "11111111-1111-1111-1111-111111111111"
    private val otherPairingId = "22222222-2222-2222-2222-222222222222"

    // --- round-trip --------------------------------------------------------------------------

    @Test
    fun `encode then decodeAndValidate round-trips a valid message`() {
        val original = SyncStatus(
            pairingId = pairingId,
            state = "transferring",
            done = 3,
            total = 10,
            bytesDone = 1_234L,
        )

        val json = MessageCodec.encode(original)
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)

        assertTrue("expected Valid, got $result", result is DecodeResult.Valid)
        assertEquals(original, (result as DecodeResult.Valid).message)
    }

    @Test
    fun `encode emits the type discriminator and version`() {
        val json = MessageCodec.encode(PairConfirm(pairingId = pairingId))
        assertTrue(json.contains("\"type\":\"PAIR_CONFIRM\""))
        assertTrue(json.contains("\"v\":1"))
    }

    // --- schema / JSON validation ------------------------------------------------------------

    @Test
    fun `malformed JSON is Invalid`() {
        val result = MessageCodec.decodeAndValidate("{not valid json", expectedPairingId = null)
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun `empty payload is Invalid`() {
        val result = MessageCodec.decodeAndValidate("", expectedPairingId = null)
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun `unknown type discriminator is Invalid`() {
        val json = """{"type":"NOT_A_REAL_TYPE","v":1,"pairingId":"$pairingId"}"""
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue(result is DecodeResult.Invalid)
    }

    @Test
    fun `missing required field is Invalid`() {
        // SYNC_STATUS requires state/done/total/bytesDone; omit them.
        val json = """{"type":"SYNC_STATUS","v":1,"pairingId":"$pairingId"}"""
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue(result is DecodeResult.Invalid)
    }

    // --- protocol version --------------------------------------------------------------------

    @Test
    fun `version mismatch is Invalid`() {
        val json = """{"type":"PAIR_CONFIRM","v":2,"pairingId":"$pairingId"}"""
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue("expected Invalid, got $result", result is DecodeResult.Invalid)
        assertTrue((result as DecodeResult.Invalid).reason.contains("version"))
    }

    @Test
    fun `matching protocol version is accepted`() {
        val json = """{"type":"PAIR_CONFIRM","v":1,"pairingId":"$pairingId"}"""
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue(result is DecodeResult.Valid)
    }

    // --- pairingId binding (Req 10.3) --------------------------------------------------------

    @Test
    fun `matching pairingId is Valid`() {
        val json = MessageCodec.encode(Unpair(pairingId = pairingId))
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue(result is DecodeResult.Valid)
    }

    @Test
    fun `mismatched pairingId is dropped as UnpairedPeer`() {
        val json = MessageCodec.encode(Unpair(pairingId = otherPairingId))
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)

        assertTrue("expected UnpairedPeer, got $result", result is DecodeResult.UnpairedPeer)
        assertEquals(otherPairingId, (result as DecodeResult.UnpairedPeer).pairingId)
    }

    @Test
    fun `non-null pairingId is accepted when not yet paired`() {
        // expectedPairingId null → nothing to match against; a well-formed message passes.
        val json = MessageCodec.encode(PairRequest("123456", pairingId, "secret", "fenix"))
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = null)
        assertTrue(result is DecodeResult.Valid)
    }

    // --- null pairingId liveness rule --------------------------------------------------------

    @Test
    fun `ping with null pairingId is always Valid even when paired`() {
        val json = MessageCodec.encode(Ping())
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue("expected Valid, got $result", result is DecodeResult.Valid)
        assertTrue((result as DecodeResult.Valid).message is Ping)
    }

    @Test
    fun `pong with null pairingId is Valid when not paired`() {
        val json = MessageCodec.encode(Pong())
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = null)
        assertTrue(result is DecodeResult.Valid)
    }

    @Test
    fun `ping carrying a mismatched pairingId is still dropped as UnpairedPeer`() {
        // When liveness carries an explicit non-null pairingId, strict matching still applies.
        val json = MessageCodec.encode(Ping(pairingId = otherPairingId))
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue(result is DecodeResult.UnpairedPeer)
    }

    @Test
    fun `ping carrying a matching pairingId is Valid`() {
        val json = MessageCodec.encode(Ping(pairingId = pairingId))
        val result = MessageCodec.decodeAndValidate(json, expectedPairingId = pairingId)
        assertTrue(result is DecodeResult.Valid)
    }
}
