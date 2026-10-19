package dev.gsound.a.data.folders

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.gsound.a.domain.model.Folder

/**
 * Room persistence representation of a [Folder].
 *
 * Entity concerns are kept separate from the pure domain model (see [Folder]) and the
 * serialization/wire types (see `net/garmin/messages`). Mapping between this entity and the
 * domain model is done via [toDomain]/[toEntity].
 *
 * @property id stable hash of [treeUri]; primary key and foreign key target for tracks.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val treeUri: String,
    val displayName: String,
    val trackCount: Int,
    val totalBytes: Long,
    val addedAt: Long
)

/** Maps this persistence entity to its pure domain model. */
fun FolderEntity.toDomain(): Folder = Folder(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    trackCount = trackCount,
    totalBytes = totalBytes,
    addedAt = addedAt
)

/** Maps a domain [Folder] to its persistence entity. */
fun Folder.toEntity(): FolderEntity = FolderEntity(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    trackCount = trackCount,
    totalBytes = totalBytes,
    addedAt = addedAt
)
