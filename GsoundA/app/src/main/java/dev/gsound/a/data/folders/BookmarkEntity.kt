package dev.gsound.a.data.folders

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phone-side audiobook bookmark (spec gsound-player PL-3, design §4.1), one row per folder.
 *
 * Keyed by [folderId] (= `Folder.id`, design R2) rather than the folder name, which may collide.
 * Deleted together with the folder (PL-3.6).
 *
 * @property folderName display name; the watch's `bm:<folder>` key.
 * @property positionSec absolute position within the chapter, seconds.
 * @property updatedAt epoch seconds; the merge key ("at").
 * @property source last writer: "phone" or "watch".
 * @property lastSentAt last `updatedAt` exchanged with the watch (PL-6.4).
 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val folderId: String,
    val folderName: String,
    val trackId: String,
    val chapterIndex: Int,
    val positionSec: Int,
    val finished: Boolean,
    val updatedAt: Long,
    val source: String,
    val lastSentAt: Long = 0
)
