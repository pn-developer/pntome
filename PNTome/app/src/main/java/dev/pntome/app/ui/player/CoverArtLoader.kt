package dev.pntome.app.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import dev.pntome.app.data.folders.FolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Supplies the cover picture of a folder (album / book); null when there is none. */
fun interface CoverArtSource {
    suspend fun load(folderId: String): Bitmap?
}

/**
 * Finds cover art for a folder: first an image file in the folder root (`cover.jpg`,
 * `folder.png`, ... or, failing that, any image), then the picture embedded in its first track.
 * Results (including "none") are cached per folder. All I/O runs on [Dispatchers.IO]; any
 * failure just means "no cover".
 */
class CoverArtLoader(
    private val context: Context,
    private val folders: FolderRepository,
) : CoverArtSource {

    private class Result(val bitmap: Bitmap?)

    private val cache = LruCache<String, Result>(CACHE_SIZE)

    override suspend fun load(folderId: String): Bitmap? {
        cache.get(folderId)?.let { return it.bitmap }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { fromImageFile(folderId) ?: fromEmbeddedPicture(folderId) }.getOrNull()
        }
        cache.put(folderId, Result(bitmap))
        return bitmap
    }

    private suspend fun fromImageFile(folderId: String): Bitmap? {
        val folder = folders.findFolder(folderId) ?: return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return null
        val images = root.listFiles().filter { f ->
            f.isFile && (f.type?.startsWith("image/") == true ||
                f.name?.substringAfterLast('.', "")?.lowercase() in IMAGE_EXTENSIONS)
        }
        val pick = images.firstOrNull { f ->
            f.name?.substringBeforeLast('.')?.lowercase() in PREFERRED_NAMES
        } ?: images.firstOrNull() ?: return null
        return decode { context.contentResolver.openInputStream(pick.uri)?.use { it.readBytes() } }
    }

    private suspend fun fromEmbeddedPicture(folderId: String): Bitmap? {
        val track = folders.getTracks(folderId).firstOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(track.documentUri))
            return decode { retriever.embeddedPicture }
        } finally {
            try {
                retriever.release()
            } catch (_: IOException) {
            }
        }
    }

    /** Decodes [bytes] downsampled so the longer edge is about [MAX_EDGE] px. */
    private fun decode(bytes: () -> ByteArray?): Bitmap? {
        val data = bytes() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private companion object {
        const val CACHE_SIZE = 8
        const val MAX_EDGE = 1024
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val PREFERRED_NAMES = setOf("cover", "folder", "front", "albumart", "album", "artwork")
    }
}
