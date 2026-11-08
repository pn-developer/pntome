package dev.pntome.app.ui.sync

import dev.pntome.app.data.folders.BookmarkEntity
import dev.pntome.app.domain.model.Folder

/**
 * One line of the Refresh stats bookmark list (PL-4.5, PL-4.6): which book, where it stopped and
 * which device wrote the bookmark last.
 *
 * @property position "Ch N · mm:ss" (h:mm:ss from one hour) or "Finished".
 * @property device "Phone" or "Watch".
 */
data class BookmarkRow(
    val folderId: String,
    val title: String,
    val position: String,
    val device: String,
) {
    /** Single sentence for screen readers. */
    val description: String
        get() = "$title, $position, from $device"
}

/** Pure mapping from stored bookmarks to display rows. */
object BookmarkRows {

    /**
     * Rows for bookmarks whose folder still exists and is an audiobook; stale rows are skipped.
     * Most recently updated first. Works from the phone's own data only, so it does not depend on
     * the watch being reachable or sending STATS (PL-4.6).
     */
    fun from(bookmarks: List<BookmarkEntity>, folders: List<Folder>): List<BookmarkRow> {
        val books = folders.filter { it.isAudiobook }.associateBy { it.id }
        return bookmarks
            .sortedWith(compareByDescending<BookmarkEntity> { it.updatedAt }.thenBy { it.folderId })
            .mapNotNull { b ->
                val folder = books[b.folderId] ?: return@mapNotNull null
                BookmarkRow(
                    folderId = b.folderId,
                    title = folder.displayName,
                    position = positionLabel(b),
                    device = deviceLabel(b.source),
                )
            }
    }

    internal fun positionLabel(b: BookmarkEntity): String =
        if (b.finished) "Finished"
        else "Ch ${b.chapterIndex.coerceAtLeast(0) + 1} · ${formatPosition(b.positionSec)}"

    internal fun deviceLabel(source: String): String =
        if (source == "watch") "Watch" else "Phone"

    /** mm:ss, or h:mm:ss when at least one hour. Negative values clamp to zero. */
    internal fun formatPosition(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
    }
}
