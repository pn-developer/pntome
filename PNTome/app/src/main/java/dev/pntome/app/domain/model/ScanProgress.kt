package dev.pntome.app.domain.model

/**
 * Progress of an in-flight folder scan, emitted as a `Flow<ScanProgress>` by the
 * `FolderScanner` so the UI stays responsive and can show live feedback (Req 3.6).
 *
 * Pure domain model — framework-free.
 *
 * @property filesScanned total number of files examined so far (supported + skipped).
 * @property tracksFound number of eligible, supported tracks discovered so far (Req 3.2).
 * @property skippedCount number of files skipped because they are unsupported (Req 3.3).
 * @property currentFile display name of the file currently being processed, or null when idle
 *   or finished.
 * @property done true on the terminal emission once enumeration and metadata extraction complete.
 */
data class ScanProgress(
    val filesScanned: Int = 0,
    val tracksFound: Int = 0,
    val skippedCount: Int = 0,
    val currentFile: String? = null,
    val done: Boolean = false
)
