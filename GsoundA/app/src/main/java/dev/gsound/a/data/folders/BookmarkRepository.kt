package dev.gsound.a.data.folders

import dev.gsound.a.service.BookmarkResolver
import dev.gsound.a.service.QueueBuilder
import dev.gsound.a.service.ResumePoint
import javax.inject.Inject
import javax.inject.Singleton

/** Bookmark access for playback: resolves the resume point of a book (design §4.3). */
@Singleton
class BookmarkRepository @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val folderRepository: FolderRepository
) {
    /** Every bookmark, most recently updated first. */
    suspend fun recent(): List<BookmarkEntity> = bookmarkDao.getAllByRecency()

    /** Resume point for [folderId], or null when the book should start at chapter 1, 0 ms. */
    suspend fun resolve(folderId: String): ResumePoint? {
        val bookmark = bookmarkDao.get(folderId) ?: return null
        val folder = folderRepository.findFolder(folderId) ?: return null
        if (!folder.isAudiobook) return null
        val ids = QueueBuilder().order(folder, folderRepository.getTracks(folderId)).map { it.id }
        return BookmarkResolver.resolve(bookmark, ids)
    }
}
