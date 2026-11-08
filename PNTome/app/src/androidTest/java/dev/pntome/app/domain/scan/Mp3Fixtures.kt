package dev.pntome.app.domain.scan

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Generates small but genuinely decodable MP3 files for the scanner instrumented test (task 5.4),
 * so [android.media.MediaMetadataRetriever] has real audio to parse rather than a stub.
 *
 * Each file is a run of valid MPEG-1 Audio Layer III frames (constant 128 kbps, 44.1 kHz,
 * mono, silent) optionally preceded by an ID3v2.3 tag carrying title/artist/album. This is
 * enough for the retriever to report a non-zero duration and to read the embedded tags, which
 * lets the test distinguish the metadata path (Req 3.4) from the filename-fallback path.
 *
 * Keeping the bytes synthetic (no binary asset checked in) keeps the test hermetic and small.
 */
object Mp3Fixtures {

    /**
     * Write an MP3 at [file] with [frameCount] silent MPEG frames. When any of [title]/[artist]/
     * [album] is non-null an ID3v2.3 tag is prepended so the retriever reports that metadata.
     */
    fun write(
        file: File,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        frameCount: Int = 40
    ) {
        file.parentFile?.mkdirs()
        val out = ByteArrayOutputStream()
        if (title != null || artist != null || album != null) {
            out.write(id3v2Tag(title, artist, album))
        }
        val frame = silentFrame()
        repeat(frameCount) { out.write(frame) }
        file.writeBytes(out.toByteArray())
    }

    // --- MPEG audio frame ---------------------------------------------------------------------

    /**
     * One MPEG-1 Layer III frame: header `0xFF 0xFB 0x90 0x00` (MPEG-1, Layer III, no CRC,
     * 128 kbps, 44.1 kHz, no padding, mono) followed by zeroed frame data. Frame length for
     * these parameters is `floor(144 * 128000 / 44100) = 417` bytes.
     */
    private fun silentFrame(): ByteArray {
        val frameLen = 144 * BITRATE_BPS / SAMPLE_RATE_HZ // 417
        val frame = ByteArray(frameLen)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte() // 1111 1011: MPEG-1, Layer III, no CRC protection
        frame[2] = 0x90.toByte() // 1001 0000: 128 kbps, 44.1 kHz, no padding, private=0
        frame[3] = 0x00.toByte() // 0000 0000: stereo bits -> treated as mono-ish; no emphasis
        // Remaining bytes stay zero (silence); decoders accept this.
        return frame
    }

    // --- ID3v2.3 tag --------------------------------------------------------------------------

    private fun id3v2Tag(title: String?, artist: String?, album: String?): ByteArray {
        val frames = ByteArrayOutputStream()
        title?.let { frames.write(textFrame("TIT2", it)) }
        artist?.let { frames.write(textFrame("TPE1", it)) }
        album?.let { frames.write(textFrame("TALB", it)) }
        val frameBytes = frames.toByteArray()

        val header = ByteArrayOutputStream()
        header.write("ID3".toByteArray(Charsets.US_ASCII))
        header.write(0x03) // version major (v2.3)
        header.write(0x00) // version revision
        header.write(0x00) // flags
        header.write(synchsafe(frameBytes.size)) // tag size (excluding the 10-byte header)
        header.write(frameBytes)
        return header.toByteArray()
    }

    /** An ID3v2.3 text information frame with ISO-8859-1 encoding ($00). */
    private fun textFrame(id: String, value: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0x00) // text encoding: ISO-8859-1
        payload.write(value.toByteArray(Charsets.ISO_8859_1))
        val payloadBytes = payload.toByteArray()

        val frame = ByteArrayOutputStream()
        frame.write(id.toByteArray(Charsets.US_ASCII)) // 4-char frame id
        frame.write(size32(payloadBytes.size))         // v2.3 size is a plain 32-bit big-endian int
        frame.write(0x00); frame.write(0x00)           // flags
        frame.write(payloadBytes)
        return frame.toByteArray()
    }

    /** Plain big-endian 32-bit size used by ID3v2.3 frame headers. */
    private fun size32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24 and 0xFF).toByte(),
        (value ushr 16 and 0xFF).toByte(),
        (value ushr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    /** Synchsafe 28-bit integer used by the ID3v2 tag-size field (7 bits per byte). */
    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        (value ushr 21 and 0x7F).toByte(),
        (value ushr 14 and 0x7F).toByte(),
        (value ushr 7 and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private const val BITRATE_BPS = 128_000
    private const val SAMPLE_RATE_HZ = 44_100
}
