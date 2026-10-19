package dev.gsound.a.domain.model

/**
 * A user-selected music folder, backed by a persisted Storage Access Framework (SAF) tree URI.
 *
 * Pure domain model — no framework/persistence annotations (Room entities live in the data
 * layer, see task 3).
 *
 * @property id stable hash derived from [treeUri], used for dedupe and as a foreign key for tracks.
 * @property treeUri persisted SAF `content://` tree URI granting read access to the subtree.
 * @property displayName human-readable folder name shown in the UI.
 * @property trackCount number of eligible (supported) tracks found in this folder.
 * @property totalBytes total size in bytes of the eligible tracks.
 * @property addedAt epoch millis when the folder was added.
 */
data class Folder(
    val id: String,
    val treeUri: String,
    val displayName: String,
    val trackCount: Int,
    val totalBytes: Long,
    val addedAt: Long
)
