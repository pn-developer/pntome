package dev.gsound.a.domain.scan

/**
 * Pure path/name helpers used by [FolderScanner] (task 5.2), extracted so the string logic is
 * unit-testable on the JVM without Android (the Android-coupled enumeration/metadata lives in
 * the scanner; the instrumented coverage is task 5.4).
 */
object ScanPaths {

    private const val SEPARATOR = "/"

    /**
     * Build the slash-joined path of a file relative to the scanned tree root, used both as the
     * stable-id input (Req 3.5, via [TrackIdHasher]) and to derive [relativeFolder].
     *
     * @param segments the directory names from the tree root down to (but not including) the
     *   file, in order. The root itself contributes no segment, so a file directly under the
     *   root has an empty [segments] list.
     * @param fileName the file's display name.
     * @return e.g. `Rock/Live/song.mp3`, or just `song.mp3` when the file sits at the root.
     *   Blank segments (which SAF should never produce) are dropped so the path never contains
     *   `//` or a leading slash.
     */
    fun relativePath(segments: List<String>, fileName: String): String {
        val parts = segments.filter { it.isNotBlank() } + fileName
        return parts.joinToString(SEPARATOR)
    }

    /**
     * Derive the playlist/folder name a track is grouped under on the watch
     * ([dev.gsound.a.domain.model.Track.relativeFolder]).
     *
     * This is the slash-joined subfolder path relative to the tree root. A file directly under
     * the root has no subfolder, in which case [rootName] (the scanned folder's display name)
     * is used so the track still belongs to a named group rather than an empty string.
     *
     * @param segments the directory names from the tree root down to the file's parent.
     * @param rootName the scanned folder's display name, used when the file sits at the root.
     */
    fun relativeFolder(segments: List<String>, rootName: String): String {
        val parts = segments.filter { it.isNotBlank() }
        return if (parts.isEmpty()) rootName else parts.joinToString(SEPARATOR)
    }

    /**
     * The filename-fallback title used when metadata carries no title (Req 3.4): the display
     * name with its final extension stripped.
     *
     * A leading-dot name (e.g. `.hidden`) or an extension-less name is returned unchanged; only
     * a genuine trailing `.ext` is removed.
     *
     * @param fileName the file's display name, e.g. `01 - Song.mp3`.
     * @return e.g. `01 - Song`.
     */
    fun titleFromFileName(fileName: String): String {
        val name = fileName.trim()
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
