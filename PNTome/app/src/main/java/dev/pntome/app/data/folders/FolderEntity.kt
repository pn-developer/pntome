package dev.pntome.app.data.folders

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.pntome.app.domain.model.Folder

/**
 * Room persistence representation of a [Folder].
 *
 * Entity concerns are kept separate from the pure domain model (see [Folder]) and the
 * serialization/wire types (see `net/garmin/messages`). Mapping between this entity and the
 * domain model is done via [toDomain]/[toEntity].
 *
 * @property id stable hash of [treeUri]; primary key and foreign key target for tracks.
 * @property isAudiobook audiobook flag (DB v2, [PNTomeDatabase.MIGRATION_1_2]); the column
 *   default matches the migration's `DEFAULT 0`.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val treeUri: String,
    val displayName: String,
    val trackCount: Int,
    val totalBytes: Long,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val isAudiobook: Boolean = false
)

/** Maps this persistence entity to its pure domain model. */
fun FolderEntity.toDomain(): Folder = Folder(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    trackCount = trackCount,
    totalBytes = totalBytes,
    addedAt = addedAt,
    isAudiobook = isAudiobook
)

/** Maps a domain [Folder] to its persistence entity. */
fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    trackCount = trackCount,
    totalBytes = totalBytes,
    addedAt = addedAt,
    isAudiobook = isAudiobook
)
