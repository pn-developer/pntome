package dev.pntome.app.domain.model

/**
 * User-configurable application settings (design §5.5, Req 8.1).
 *
 * Pure domain model — no framework/persistence annotations (Proto DataStore schema lives in
 * the data layer, see task 3).
 *
 * Audio format selection defaults to mp3-only for safe watch compatibility (design §5.2);
 * mp3 is always required and therefore has no flag. The optional formats are opt-in.
 *
 * @property allowM4aAac include AAC/M4A files when scanning.
 * @property allowWav include WAV files when scanning.
 * @property allowFlac include FLAC files when scanning.
 * @property serverPort preferred local HTTP server port (default 8080, design §5.5).
 */
data class AppSettings(
    val allowM4aAac: Boolean = false,
    val allowWav: Boolean = false,
    val allowFlac: Boolean = false,
    val serverPort: Int = DEFAULT_SERVER_PORT
) {
    companion object {
        /** Default local HTTP server port (design §5.5). */
        const val DEFAULT_SERVER_PORT: Int = 8080
    }
}
