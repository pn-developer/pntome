package dev.gsound.a.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.domain.model.SyncSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proto DataStore-backed store for the most recent [SyncSummary] (design §5.6, §5.7).
 *
 * Exposes a reactive [Flow] read so the last-sync summary restores on launch (Req 6.3, 8.2)
 * and a `suspend` write recorded on sync completion, mapping between [SyncSummaryProto] and
 * the domain model. A missing summary (no sync yet) reads as null.
 *
 * The backing [DataStore] is created here (rather than injected) because Hilt/KSP cannot
 * resolve the generated proto type argument of an injected `DataStore<SyncSummaryProto>`.
 */
@Singleton
class SyncSummaryStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val dataStore: DataStore<SyncSummaryProto> =
        DataStoreFactory.create(serializer = SyncSummarySerializer) {
            context.dataStoreFile(FILE_NAME)
        }

    /** Observe the last-sync summary, or null when no sync has completed. */
    val summary: Flow<SyncSummary?> = dataStore.data.map { it.toDomainOrNull() }

    /** One-shot snapshot of the last-sync summary, or null when none exists. */
    suspend fun get(): SyncSummary? = dataStore.data.first().toDomainOrNull()

    /** Persist the summary of a completed sync. */
    suspend fun save(summary: SyncSummary) {
        dataStore.updateData { summary.toProto() }
    }

    private companion object {
        const val FILE_NAME = "sync_summary.pb"
    }
}

/** last_sync_at == 0 is the "no sync yet" default; map it to null. */
private fun SyncSummaryProto.toDomainOrNull(): SyncSummary? =
    if (lastSyncAt == 0L) {
        null
    } else {
        SyncSummary(
            syncedCount = syncedCount,
            skippedCount = skippedCount,
            removedCount = removedCount,
            lastSyncAt = lastSyncAt,
            error = if (hasError) error else null
        )
    }

private fun SyncSummary.toProto(): SyncSummaryProto =
    SyncSummaryProto.newBuilder()
        .setSyncedCount(syncedCount)
        .setSkippedCount(skippedCount)
        .setRemovedCount(removedCount)
        .setLastSyncAt(lastSyncAt)
        .setHasError(error != null)
        .setError(error ?: "")
        .build()
