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

    // --- manifest track seq/book (spec gsound-audiobooks AB-3.1, 3.2) --------------------------

    private fun manifestTrack(seq: Int? = null, book: Boolean? = null) = ManifestTrack(
        id = "t1",
        folder = "Book",
        title = "01 Intro",
        durationMs = 1_800_000L,
        sizeBytes = 42L,
        mime = "audio/mpeg",
        path = "/media/t1",
        seq = seq,
        book = book,
    )

    @Test
    fun `SYNC_MANIFEST round-trips seq and book`() {
        val original = SyncManifest(
            pairingId = pairingId,
            serverUrl = "http://192.168.1.2:8080",
            tracks = listOf(manifestTrack(seq = 3, book = true)),
        )

        val result = MessageCodec.decodeAndValidate(MessageCodec.encode(original), pairingId)

        assertEquals(original, (result as DecodeResult.Valid).message)
    }

    @Test
    fun `SYNC_MANIFEST map sent to the watch carries seq as a number and book as a boolean`() {
        val map = MessageCodec.encodeToMap(
            SyncManifest(
                pairingId = pairingId,
                serverUrl = "http://h",
                tracks = listOf(manifestTrack(seq = 0, book = true)),
            )
        )

        @Suppress("UNCHECKED_CAST")
        val track = (map["tracks"] as List<Map<String, Any?>>).single()
        assertEquals(0L, track["seq"])     // same numeric representation as "v"
        assertEquals(true, track["book"])
    }

    @Test
    fun `a track without seq and book (older peer) still decodes`() {
        val json = """{"type":"SYNC_MANIFEST","v":1,"pairingId":"$pairingId","serverUrl":"http://h",""" +
            """"tracks":[{"id":"t1","folder":"F","title":"T","durationMs":1,"sizeBytes":2,""" +
            """"mime":"audio/mpeg","path":"/media/t1"}]}"""

        val result = MessageCodec.decodeAndValidate(json, pairingId)

        val track = ((result as DecodeResult.Valid).message as SyncManifest).tracks.single()
        assertEquals(null, track.seq)
        assertEquals(null, track.book)
    }

    // --- watch diagnostic LOG line (G→A) ----------------------------------------------------

    @Test
    fun `watch LOG line sent as a flat Map decodes to WatchLogLine`() {
        // The Connect IQ SDK delivers a watch Dictionary as a Map.
        val payload = mapOf<String, Any?>(
            "v" to 1,
            "type" to "LOG",
            "pairingId" to pairingId,
            "line" to "#7 t=48210 PB: getContentIterator folder=Rock start=0",
        )

        val result = MessageCodec.decodeAndValidateFromAny(payload, expectedPairingId = pairingId)

        assertTrue("expected Valid, got $result", result is DecodeResult.Valid)
        val log = (result as DecodeResult.Valid).message as WatchLogLine
        assertEquals("#7 t=48210 PB: getContentIterator folder=Rock start=0", log.line)
    }

    @Test
    fun `watch LOG line from a foreign pairing is dropped`() {
        val payload = mapOf<String, Any?>(
            "v" to 1, "type" to "LOG", "pairingId" to otherPairingId, "line" to "#1 x",
        )

        val result = MessageCodec.decodeAndValidateFromAny(payload, expectedPairingId = pairingId)

        assertTrue("expected UnpairedPeer, got $result", result is DecodeResult.UnpairedPeer)
    }

    // --- map round-trip (the pairing-wire-format fix) ----------------------------------------

    @Test
    fun `encodeToMap then decodeAndValidateFromAny round-trips a valid message`() {
        // This mirrors the real wire path: the SDK marshals the Map to a watch Dictionary and
        // back to a Map, so encode-to-map + decode-from-map must reproduce the message.
        val original = PairRequest(
            code = "123456",
            pairingId = pairingId,
            secret = "shared-secret-value",
            peerName = "Pixel",
        )

        val map = MessageCodec.encodeToMap(original)
        // The map must carry the exact contract keys GsoundG reads, with v as a number.
        assertEquals("PAIR_REQUEST", map["type"])
        assertEquals(1, (map["v"] as Number).toInt())
        assertEquals(pairingId, map["pairingId"])

        val result = MessageCodec.decodeAndValidateFromAny(map, expectedPairingId = pairingId)
        assertTrue("expected Valid, got $result", result is DecodeResult.Valid)
        assertEquals(original, (result as DecodeResult.Valid).message)
    }

    @Test
    fun `decodeAndValidateFromAny still accepts a legacy JSON String payload`() {
        val json = MessageCodec.encode(PairConfirm(pairingId = pairingId))
        val result = MessageCodec.decodeAndValidateFromAny(json, expectedPairingId = pairingId)
        assertTrue("expected Valid, got $result", result is DecodeResult.Valid)
    }

    @Test
    fun `decodeAndValidateFromAny rejects an unsupported payload type`() {
        val result = MessageCodec.decodeAndValidateFromAny(42, expectedPairingId = null)
        assertTrue(result is DecodeResult.Invalid)
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

    // --- BOOKMARK (spec gsound-player PL-4.2, PL-7.5, design §5) -------------------------------

    private fun decode(json: String) = MessageCodec.decodeAndValidate(json, pairingId)

    private fun bookmarkJson(extra: String = "", fid: String = """"fid":"f1",""", fin: String = """"fin":true,""") =
        """{"type":"BOOKMARK","v":1,"pairingId":"$pairingId","folder":"Dune",$fid"trk":"t1",""" +
            """"idx":2,"pos":95,$fin$extra"at":1700000000}"""

    @Test
    fun `BOOKMARK round-trips with fid and fin`() {
        val original = Bookmark(pairingId, "Dune", "f1", "t1", 2, 95, true, 1_700_000_000L)

        val json = MessageCodec.encode(original)
        val result = decode(json)

        assertTrue(json.contains("\"type\":\"BOOKMARK\""))
        assertEquals(original, (result as DecodeResult.Valid).message)
    }

    @Test
    fun `BOOKMARK round-trips without fid`() {
        val original = Bookmark(pairingId, "Dune", null, "t1", 0, 0, false, 5L)

        val result = decode(MessageCodec.encode(original))

        assertEquals(original, (result as DecodeResult.Valid).message)
    }

    @Test
    fun `BOOKMARK fin defaults to false when absent and fid to null`() {
        val json = bookmarkJson(fid = "", fin = "")

        val msg = (decode(json) as DecodeResult.Valid).message as Bookmark

        assertEquals(false, msg.fin)
        assertEquals(null, msg.fid)
    }

    @Test
    fun `BOOKMARK with explicit null fid decodes`() {
        val msg = (decode(bookmarkJson(fid = """"fid":null,""")) as DecodeResult.Valid).message as Bookmark

        assertEquals(null, msg.fid)
        assertEquals("t1", msg.trk)
        assertEquals(95, msg.pos)
    }

    @Test
    fun `BOOKMARK ignores unknown extra fields`() {
        val result = decode(bookmarkJson(extra = """"future":"x","n":{"a":1},"""))

        assertTrue("expected Valid, got $result", result is DecodeResult.Valid)
    }

    @Test
    fun `BOOKMARK with wrong field types is Invalid`() {
        assertTrue(decode(bookmarkJson().replace("\"idx\":2", "\"idx\":\"two\"")) is DecodeResult.Invalid)
        assertTrue(decode(bookmarkJson().replace("\"fin\":true", "\"fin\":\"yes\"")) is DecodeResult.Invalid)
        assertTrue(decode(bookmarkJson().replace("\"pos\":95", "\"pos\":1.5")) is DecodeResult.Invalid)
        assertTrue(decode(bookmarkJson().replace("\"trk\":\"t1\"", "\"trk\":7")) is DecodeResult.Invalid)
    }

    @Test
    fun `BOOKMARK missing a required field is Invalid`() {
        for (key in listOf("folder", "trk", "idx", "pos", "at")) {
            val json = bookmarkJson().replace(Regex(""""$key":("[^"]*"|\d+),?"""), "").replace(",}", "}")
            assertTrue("$key: expected Invalid for $json", decode(json) is DecodeResult.Invalid)
        }
    }

    @Test
    fun `BOOKMARK with mismatched pairingId is dropped as UnpairedPeer`() {
        val json = MessageCodec.encode(Bookmark(otherPairingId, "Dune", null, "t1", 0, 0, false, 5L))

        assertTrue(decode(json) is DecodeResult.UnpairedPeer)
    }

    @Test
    fun `STATS from an old watch is unaffected by the BOOKMARK type`() {
        val original = Stats(pairingId = pairingId, fileCount = 4, folderCount = 2)

        val result = decode(MessageCodec.encode(original))

        assertEquals(original, (result as DecodeResult.Valid).message)
    }

    @Test
    fun `unknown type next to BOOKMARK is still Invalid`() {
        val json = bookmarkJson().replace("\"BOOKMARK\"", "\"BOOKMARKS\"")

        assertTrue(decode(json) is DecodeResult.Invalid)
    }
}
