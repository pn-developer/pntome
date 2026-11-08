package dev.gsound.a.domain.bookmarks

import dev.gsound.a.data.folders.BookmarkDao
import dev.gsound.a.data.folders.BookmarkEntity

/**
 * `lastSentAt` bookkeeping (spec gsound-player PL-6.4, design §8). Pure helpers.
 *
 * `lastSentAt` is the last `at` exchanged with the watch in either direction: [BookmarkMerge]
 * sets it when a watch bookmark is taken, and [markSent] sets it after a phone bookmark was
 * delivered. A bookmark is sent only when it is newer than that, so a taken bookmark is never
 * echoed back.
 */
object BookmarkSyncState {
    /** True when [entity] has changed since it was last exchanged with the watch. */
    fun needsSending(entity: BookmarkEntity): Boolean = entity.updatedAt > entity.lastSentAt

    /** The bookmarks (in input order) that still have to go to the watch. */
    fun pending(all: List<BookmarkEntity>): List<BookmarkEntity> = all.filter(::needsSending)

    /** Record that [sent] reached the watch. Never lowers `lastSentAt`. */
    suspend fun markSent(dao: BookmarkDao, sent: BookmarkEntity) {
        // Keyed on the sent snapshot's `updatedAt`: a newer recorder write in between stays pending.
        val current = dao.get(sent.folderId) ?: return
        if (sent.updatedAt > current.lastSentAt) dao.markSent(sent.folderId, sent.updatedAt)
    }
}
