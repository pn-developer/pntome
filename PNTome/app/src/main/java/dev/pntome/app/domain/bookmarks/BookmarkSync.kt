package dev.pntome.app.domain.bookmarks

import dev.pntome.app.data.folders.BookmarkDao
import dev.pntome.app.data.folders.FolderRepository
import dev.pntome.app.data.pairing.PairingStore
import dev.pntome.app.log.PNTomeLog
import dev.pntome.app.net.garmin.ControlChannel
import dev.pntome.app.net.garmin.messages.Bookmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-lifetime collector of watch `BOOKMARK` messages (spec gsound-player PL-4.3, 4.4, PL-6,
 * PL-7.3; design §6, §8, R2).
 *
 * For each incoming [Bookmark]: the `pairingId` must equal the current pairing; the folder is
 * resolved by `fid` == `Folder.id` first, then by a unique audiobook folder whose display name
 * equals `folder`; it must be an audiobook folder. The bookmark is then merged by
 * [BookmarkMerge] and stored with `source = "watch"`. Idempotent: a repeat is "equal" and ignored.
 * Logs never include the pairing id or secrets.
 */
@Singleton
class BookmarkSync internal constructor(
    private val controlChannel: ControlChannel,
    private val pairingStore: PairingStore,
    private val folders: FolderRepository,
    private val bookmarkDao: BookmarkDao,
    private val nowSec: () -> Long,
) {
    @Inject
    constructor(
        controlChannel: ControlChannel,
        pairingStore: PairingStore,
        folders: FolderRepository,
        bookmarkDao: BookmarkDao,
    ) : this(controlChannel, pairingStore, folders, bookmarkDao, { System.currentTimeMillis() / 1000 })

    private val tag = "PNTome.Bookmark"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** Begin collecting. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            controlChannel.incoming.filterIsInstance<Bookmark>().collect { msg ->
                try {
                    handle(msg)
                } catch (e: Exception) {
                    PNTomeLog.w(tag, "BOOKMARK handling failed", e)
                }
            }
        }
    }

    /** Process one incoming bookmark. Returns true when it was stored. */
    suspend fun handle(msg: Bookmark): Boolean {
        val current = pairingStore.get()?.pairingId
        if (current == null || msg.pairingId != current) {
            PNTomeLog.w(tag, "BOOKMARK ignored: not for the current pairing")
            return false
        }
        val folder = resolveFolder(msg)
        if (folder == null) {
            PNTomeLog.w(tag, "BOOKMARK ignored: no matching audiobook folder for '${msg.folder}'")
            return false
        }
        val merged = BookmarkMerge.merge(
            local = bookmarkDao.get(folder.id),
            incoming = msg,
            folderId = folder.id,
            folderName = folder.displayName,
            now = nowSec(),
        )
        if (merged == null) {
            PNTomeLog.d(tag, "BOOKMARK for '${folder.displayName}' not newer; kept local")
            return false
        }
        bookmarkDao.upsert(merged)
        PNTomeLog.i(tag, "BOOKMARK for '${folder.displayName}' taken from watch")
        return true
    }

    private suspend fun resolveFolder(msg: Bookmark): dev.pntome.app.domain.model.Folder? {
        val fid = msg.fid
        if (!fid.isNullOrEmpty()) {
            val byId = folders.findFolder(fid)
            if (byId != null) return byId.takeIf { it.isAudiobook }
        }
        return folders.getFolders()
            .filter { it.isAudiobook && it.displayName == msg.folder }
            .singleOrNull()
    }
}
