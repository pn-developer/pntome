package dev.pntome.app.data.folders

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.pntome.app.domain.model.Track

/**
 * Room persistence representation of a [Track].
 *
 * Tracks are owned by a [FolderEntity]; removing a folder cascades to its cached tracks so the
 * `tracks` table never references a folder that no longer exists.
 *
 * Entity concerns are kept separate from the pure domain model (see [Track]). Mapping is done
 * via [toDomain]/[toEntity].
 *
 * @property id stable content/path hash (overview §5.1); primary key, stable across scans (Req 3.5).
 * @property folderId id of the owning folder (foreign key).
 */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId")]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val documentUri: String,
    val relativeFolder: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mime: String,
    /** File display name (DB v2); default matches the migration's `DEFAULT ''`. */
    @ColumnInfo(defaultValue = "") val fileName: String = ""
)

/** Maps this persistence entity to its pure domain model. */
fun TrackEntity.toDomain(): Track = Track(
    id = id,
    folderId = folderId,
    documentUri = documentUri,
    relativeFolder = relativeFolder,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    mime = mime,
    fileName = fileName
)

/** Maps a domain [Track] to its persistence entity. */
fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    folderId = folderId,
    documentUri = documentUri,
    relativeFolder = relativeFolder,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    mime = mime,
    fileName = fileName
)
