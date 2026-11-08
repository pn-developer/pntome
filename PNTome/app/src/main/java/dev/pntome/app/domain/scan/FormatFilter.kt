package dev.pntome.app.domain.scan

import dev.pntome.app.domain.model.AppSettings

/**
 * Decides whether a scanned file is a Garmin-supported, currently-enabled audio format
 * (Req 3.2, Req 3.3).
 *
 * The watch (PNTomeGo) always supports mp3; AAC/M4A, WAV, and FLAC are optional and depend
 * on per-model codec support, so they are gated behind [AppSettings] flags that default to
 * off (mp3-only) for safe watch compatibility (design §5.2). A file the filter rejects is
 * counted as skipped by the scanner (Req 3.3).
 *
 * Eligibility is decided from two independent signals — the filename extension and the
 * declared MIME type — because SAF document providers do not always report both reliably:
 *
 * - Both signals are mapped to a canonical [AudioFormat] (extension via [SUPPORTED_EXTENSIONS],
 *   MIME via [SUPPORTED_MIME_TYPES]); unknown values map to `null`.
 * - A file is eligible when **either** signal resolves to a format that is enabled for the
 *   given [AppSettings]. This "either" (OR) rule is deliberately lenient: a correct extension
 *   rescues a missing/`application/octet-stream` MIME, and a correct MIME rescues a missing or
 *   misspelled extension. The gating flags still exclude disabled formats regardless of signal,
 *   so leniency never widens the format whitelist — it only tolerates one weak signal.
 * - Matching is case-insensitive for both extension and MIME; a null/blank MIME simply falls
 *   back to the extension (and vice versa).
 *
 * Pure Kotlin, no Android dependencies, so it is unit-testable on the JVM — consistent with
 * [TrackIdHasher] and [FolderIdHasher].
 */
object FormatFilter {

    /** Canonical Garmin-relevant audio formats recognised by the filter. */
    enum class AudioFormat {
        MP3,
        M4A_AAC,
        WAV,
        FLAC
    }

    /**
     * Lowercase file extensions (without the leading dot) mapped to their canonical format.
     * mp3 is always present; the optional formats are only *eligible* when their flag is set,
     * but they are always *recognised* here so a disabled file is skipped rather than treated
     * as an unknown type.
     */
    val SUPPORTED_EXTENSIONS: Map<String, AudioFormat> = mapOf(
        "mp3" to AudioFormat.MP3,
        "m4a" to AudioFormat.M4A_AAC,
        "aac" to AudioFormat.M4A_AAC,
        "wav" to AudioFormat.WAV,
        "flac" to AudioFormat.FLAC
    )

    /**
     * Lowercase MIME types mapped to their canonical format. Several providers report
     * synonymous MIME types for the same container (e.g. `audio/wav` vs `audio/x-wav`), so all
     * common spellings are included.
     */
    val SUPPORTED_MIME_TYPES: Map<String, AudioFormat> = mapOf(
        "audio/mpeg" to AudioFormat.MP3,
        "audio/mp3" to AudioFormat.MP3,
        "audio/mp4" to AudioFormat.M4A_AAC,
        "audio/aac" to AudioFormat.M4A_AAC,
        "audio/x-m4a" to AudioFormat.M4A_AAC,
        "audio/wav" to AudioFormat.WAV,
        "audio/x-wav" to AudioFormat.WAV,
        "audio/wave" to AudioFormat.WAV,
        "audio/flac" to AudioFormat.FLAC,
        "audio/x-flac" to AudioFormat.FLAC
    )

    /**
     * Whether a file is an eligible, currently-enabled audio format.
     *
     * @param fileName the file's display name or path; the extension after the last `.` is
     *   used (case-insensitive). May be blank or extension-less, in which case only the MIME
     *   contributes.
     * @param mimeType the declared MIME type, or `null`/blank if the provider did not report
     *   one; in that case the decision falls back to the extension.
     * @param settings the current settings whose flags gate the optional formats.
     * @return `true` if the file should be included for sync, `false` if it must be skipped
     *   (Req 3.3).
     */
    fun isEligible(fileName: String?, mimeType: String?, settings: AppSettings): Boolean =
        isEligible(
            fileName = fileName,
            mimeType = mimeType,
            allowM4aAac = settings.allowM4aAac,
            allowWav = settings.allowWav,
            allowFlac = settings.allowFlac
        )

    /**
     * Flag-level overload for callers that do not hold a full [AppSettings] (and for exhaustive
     * unit testing of the gating logic). mp3 is always allowed and has no flag.
     *
     * @param fileName see [isEligible].
     * @param mimeType see [isEligible].
     * @param allowM4aAac include AAC/M4A files.
     * @param allowWav include WAV files.
     * @param allowFlac include FLAC files.
     */
    fun isEligible(
        fileName: String?,
        mimeType: String?,
        allowM4aAac: Boolean = false,
        allowWav: Boolean = false,
        allowFlac: Boolean = false
    ): Boolean {
        val byExtension = formatFromExtension(fileName)
        val byMime = formatFromMime(mimeType)
        if (byExtension == null && byMime == null) return false

        // Lenient OR: eligible if either recognised signal maps to an enabled format.
        return isEnabled(byExtension, allowM4aAac, allowWav, allowFlac) ||
            isEnabled(byMime, allowM4aAac, allowWav, allowFlac)
    }

    /** Resolve the canonical format from a filename's extension, or `null` if unrecognised. */
    fun formatFromExtension(fileName: String?): AudioFormat? {
        if (fileName.isNullOrBlank()) return null
        val name = fileName.trim()
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        val ext = name.substring(dot + 1).lowercase()
        return SUPPORTED_EXTENSIONS[ext]
    }

    /** Resolve the canonical format from a MIME type, or `null` if unrecognised/blank. */
    fun formatFromMime(mimeType: String?): AudioFormat? {
        if (mimeType.isNullOrBlank()) return null
        // Drop any parameters (e.g. "audio/mpeg; charset=...") and normalise case.
        val mime = mimeType.substringBefore(';').trim().lowercase()
        return SUPPORTED_MIME_TYPES[mime]
    }

    private fun isEnabled(
        format: AudioFormat?,
        allowM4aAac: Boolean,
        allowWav: Boolean,
        allowFlac: Boolean
    ): Boolean = when (format) {
        AudioFormat.MP3 -> true
        AudioFormat.M4A_AAC -> allowM4aAac
        AudioFormat.WAV -> allowWav
        AudioFormat.FLAC -> allowFlac
        null -> false
    }
}
