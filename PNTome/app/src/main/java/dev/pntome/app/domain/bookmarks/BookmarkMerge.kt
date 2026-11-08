package dev.pntome.app.domain.bookmarks

import dev.pntome.app.data.folders.BookmarkEntity
import dev.pntome.app.net.garmin.messages.Bookmark

/**
 * Bookmark merge rule (spec gsound-player PL-6, design §8). Pure.
 *
 * The newer `at` wins; equal or older keeps the local bookmark. `finished` is not special: it only
 * wins by being newer. Incoming timestamps are clamped to `now + 300 s` against bad future values.
 *
 * Shared decision table (identical in PNTomeGo/source/sync/BookmarkMerge.mc); all times are epoch
 * seconds, inAt = min(incomingAt, now + 300):
 *
 *   | local at | incoming at       | finished flags   | result                          |
 *   |----------|-------------------|------------------|---------------------------------|
 *   | none     | any               | any              | take, effective at = inAt       |
 *   | L        | > L               | any              | take (newer wins)               |
 *   | L        | == L              | any              | keep local (equal keeps local)  |
 *   | L        | < L               | any              | keep local                      |
 *   | L        | far future        | any              | clamp to now+300, then as above |
 *   | L        | newer, local fin  | incoming not fin | take (finished is not special)  |
 *   | L        | older, inc. fin   | local not fin    | keep local (fin only wins newer)|
 *   | L        | repeat of a taken | any              | keep (idempotent: taken at = L) |
 */
object BookmarkMerge {
    /** Maximum tolerated lead of an incoming `at` over the local clock, seconds. */
    const val FUTURE_CLAMP_SEC = 300L

    /** Source tag stored on a bookmark taken from the watch. */
    const val SOURCE_WATCH = "watch"

    /**
     * Data-level core of the rule. Returns the effective (clamped) `at` to store when the incoming
     * bookmark is taken, or null to keep the local one. [localAt] is null when there is no local.
     */
    fun decide(localAt: Long?, incomingAt: Long, now: Long): Long? {
        val inAt = minOf(incomingAt, now + FUTURE_CLAMP_SEC)
        return if (localAt == null || inAt > localAt) inAt else null
    }

    /**
     * Merge [incoming] (for the folder [folderId] / [folderName]) into [local].
     *
     * @param now current epoch seconds.
     * @return the bookmark to store when the incoming one is taken, or null to keep [local].
     *   A taken bookmark has `source = "watch"` and `lastSentAt = at` so it is not echoed back
     *   (PL-6.4).
     */
    fun merge(
        local: BookmarkEntity?,
        incoming: Bookmark,
        folderId: String,
        folderName: String,
        now: Long,
    ): BookmarkEntity? {
        val inAt = decide(local?.updatedAt, incoming.at, now) ?: return null
        return BookmarkEntity(
            folderId = folderId,
            folderName = folderName,
            trackId = incoming.trk,
            chapterIndex = incoming.idx,
            positionSec = incoming.pos,
            finished = incoming.fin,
            updatedAt = inAt,
            source = SOURCE_WATCH,
            lastSentAt = inAt,
        )
    }
}
