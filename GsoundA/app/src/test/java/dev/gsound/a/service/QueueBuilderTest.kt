package dev.gsound.a.service

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gsound.a.data.folders.FolderRepository
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.domain.model.Folder
import dev.gsound.a.domain.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 2.2: queue order, ids and metadata (PL-1.6). Uses Robolectric only for `Uri`. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueBuilderTest {
    private val art = Uri.parse("android.resource://dev.gsound.a/drawable/ic_launcher_foreground")
    private val builder = QueueBuilder(art)

    private fun folder(book: Boolean) = Folder(
        id = "f1", treeUri = "content://t", displayName = "My Folder",
        trackCount = 0, totalBytes = 0, addedAt = 0, isAudiobook = book
    )

    private fun track(id: String, file: String, title: String = file, rel: String = "My Folder") = Track(
        id = id, folderId = "f1", documentUri = "content://doc/$id", relativeFolder = rel,
        title = title, artist = "Art", album = "Alb", durationMs = 1000, sizeBytes = 1,
        mime = "audio/mpeg", fileName = file
    )

    @Test
    fun audiobook_usesNaturalOrder_andFileNameTitles() {
        val tracks = listOf(
            track("c", "10 Last.mp3", title = "ID3 c"),
            track("a", "2 Second.mp3", title = "ID3 a"),
            track("b", "1 First.mp3", title = "ID3 b")
        )
        val items = builder.build(folder(book = true), tracks)
        assertEquals(listOf("b", "a", "c"), items.map { it.mediaId })
        assertEquals(listOf("1 First", "2 Second", "10 Last"), items.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun music_keepsStoredOrder_andTagTitles() {
        val tracks = listOf(track("c", "10.mp3", "Z"), track("a", "2.mp3", "Y"), track("b", "1.mp3", "X"))
        val items = builder.build(folder(book = false), tracks)
        assertEquals(listOf("c", "a", "b"), items.map { it.mediaId })
        assertEquals(listOf("Z", "Y", "X"), items.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun metadata_hasFolderArtistArtworkAndUri() {
        val item = builder.build(folder(book = false), listOf(track("a", "a.mp3"))).single()
        assertEquals("a", item.mediaId)
        assertEquals(Uri.parse("content://doc/a"), item.localConfiguration!!.uri)
        assertEquals("My Folder", item.mediaMetadata.albumTitle.toString())
        assertEquals("Art", item.mediaMetadata.artist.toString())
        assertEquals(art, item.mediaMetadata.artworkUri)
        assertTrue(item.mediaMetadata.isPlayable == true)
    }

    @Test
    fun audiobook_groupsBySubfolderInFirstAppearanceOrder() {
        val tracks = listOf(
            track("x2", "2.mp3", rel = "B/CD2"),
            track("y1", "1.mp3", rel = "B/CD1"),
            track("x1", "1.mp3", rel = "B/CD2")
        )
        val ids = builder.build(folder(book = true), tracks).map { it.mediaId }
        assertEquals(listOf("x1", "x2", "y1"), ids)
    }

    @Test
    fun emptyTrackList_givesEmptyQueue_forBooksAndMusic() {
        assertTrue(builder.build(folder(book = true), emptyList()).isEmpty())
        assertTrue(builder.build(folder(book = false), emptyList()).isEmpty())
    }

    @Test
    fun mediaIds_areTrackIds_uniqueAndStableAcrossBuilds() {
        val tracks = listOf(track("c", "3.mp3"), track("a", "1.mp3"), track("b", "2.mp3"))
        val first = builder.build(folder(book = true), tracks).map { it.mediaId }
        val second = builder.build(folder(book = true), tracks.reversed()).map { it.mediaId }
        assertEquals(first.toSet().size, first.size)
        assertEquals(listOf("a", "b", "c"), first)
        assertEquals(first, second) // book order does not depend on input order
    }

    @Test
    fun audiobook_equalSortNames_keepInputOrder() {
        val tracks = listOf(track("t1", "same.mp3"), track("t2", "same.mp3"), track("t3", "same.mp3"))
        val ids = builder.build(folder(book = true), tracks).map { it.mediaId }
        assertEquals(listOf("t1", "t2", "t3"), ids)
    }

    @Test
    fun audiobook_blankFileName_sortsAndLabelsByTitle() {
        val tracks = listOf(
            track("b", "", title = "10 Ten"),
            track("a", "", title = "2 Two")
        )
        val items = builder.build(folder(book = true), tracks)
        assertEquals(listOf("a", "b"), items.map { it.mediaId })
        assertEquals(listOf("2 Two", "10 Ten"), items.map { it.mediaMetadata.title.toString() })
    }

    @Test
    fun audiobook_fileNameWithoutExtension_isUsedAsIs() {
        val item = builder.build(folder(book = true), listOf(track("a", "Chapter 1", title = "T"))).single()
        assertEquals("Chapter 1", item.mediaMetadata.title.toString())
    }

    @Test
    fun audiobook_multiFolderGroups_sortNaturallyWithinEachGroup() {
        val tracks = listOf(
            track("b10", "10.mp3", rel = "B/CD2"),
            track("a2", "2.mp3", rel = "B/CD1"),
            track("b2", "2.mp3", rel = "B/CD2"),
            track("a10", "10.mp3", rel = "B/CD1")
        )
        val ids = builder.build(folder(book = true), tracks).map { it.mediaId }
        assertEquals(listOf("b2", "b10", "a2", "a10"), ids)
    }

    @Test
    fun audiobookFlag_isInExtras_andNoArtworkWhenNull() {
        val book = QueueBuilder().build(folder(book = true), listOf(track("a", "a.mp3"))).single()
        val music = QueueBuilder().build(folder(book = false), listOf(track("a", "a.mp3"))).single()
        assertEquals(true, book.mediaMetadata.extras!!.getBoolean(QueueBuilder.EXTRA_AUDIOBOOK))
        assertEquals(false, music.mediaMetadata.extras!!.getBoolean(QueueBuilder.EXTRA_AUDIOBOOK))
        assertNull(book.mediaMetadata.artworkUri)
    }

    @Test
    fun repositoryBuild_unknownFolder_givesEmptyQueue() = withRepository { repo, _ ->
        assertTrue(builder.build(repo, "missing").isEmpty())
    }

    @Test
    fun repositoryBuild_knownFolder_ordersFolderTracks() = withRepository { repo, _ ->
        val f = folder(book = true)
        repo.upsertFolder(f)
        repo.upsertTracks(listOf(track("c", "10.mp3"), track("a", "2.mp3"), track("b", "1.mp3")))
        assertEquals(listOf("b", "a", "c"), builder.build(repo, f.id).map { it.mediaId })
    }

    private fun withRepository(block: suspend (FolderRepository, GsoundDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), GsoundDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            runBlocking { block(FolderRepository(db.folderDao(), db.trackDao()), db) }
        } finally {
            db.close()
        }
    }
}
