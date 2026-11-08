package dev.gsound.a.service

import dev.gsound.a.data.folders.BookmarkEntity

/** Where playback should start: chapter index in the queue and position within it (ms). */
data class ResumePoint(val index: Int, val positionMs: Long)

/**
 * Pure resolver of where "resume" starts for a book (gsound-player PL-3.7, PL-2.3, PL-6.6,
 * design §4.3). Mirrors the watch's `Bookmarks.resolve`:
 *
 * - no bookmark, finished, or no chapters -> null (callers start chapter 1 at 0);
 * - bookmarked track id missing from the queue -> the chapter now at the old index, from 0
 *   (null if that index is out of range);
 * - otherwise the bookmarked chapter, rewound by [REWIND_SEC] and clamped at 0.
 */
object BookmarkResolver {
    const val REWIND_SEC = 5

    fun resolve(bookmark: BookmarkEntity?, chapterIds: List<String>): ResumePoint? {
        if (bookmark == null || bookmark.finished || chapterIds.isEmpty()) return null
        var index = chapterIds.indexOf(bookmark.trackId)
        var posSec = bookmark.positionSec
        if (index < 0) {
            index = bookmark.chapterIndex.coerceAtLeast(0)
            if (index >= chapterIds.size) return null
            posSec = 0
        }
        val rewound = (posSec - REWIND_SEC).coerceAtLeast(0)
        return ResumePoint(index, rewound * 1000L)
    }
}
