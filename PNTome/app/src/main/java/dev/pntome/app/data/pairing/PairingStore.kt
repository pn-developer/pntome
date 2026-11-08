package dev.gsound.a.data.pairing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gsound.a.domain.model.PairingRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Store for the single active [PairingRecord] (design §5.4, §5.7; Req 4.5, 4.7, 4.8, 8.1, 8.2).
 *
 * Exposes a reactive [pairing] read so the UI restores the paired peer on launch (Req 8.2), and
 * `suspend` writes for pairing/unpairing. v1 supports a single active pairing (Req 4.8); a null
 * value represents "not paired".
 *
 * Behind an interface (mirroring [dev.gsound.a.net.garmin.ControlChannel] and the pairing seams)
 * so collaborators like [dev.gsound.a.ui.pairing.PairingViewModel] and
 * [dev.gsound.a.net.garmin.ConnectIqManager] depend on the abstraction. Production uses the Proto
 * DataStore-backed [DataStorePairingStore]; unit tests substitute a deterministic in-memory fake
 * (task 7.3) without an Android `Context` or DataStore IO.
 */
interface PairingStore {

    /** Observe the persisted pairing, or null when not paired. */
    val pairing: Flow<PairingRecord?>

    /** One-shot snapshot of the current pairing, or null when not paired. */
    suspend fun get(): PairingRecord?

    /** True when a pairing is currently persisted. */
    suspend fun isPaired(): Boolean = get() != null

    /** Persist [record] as the active pairing, replacing any previous one (Req 4.5). */
    suspend fun save(record: PairingRecord)

    /** Clear the stored pairing back to the "not paired" default (Unpair, Req 4.7). */
    suspend fun clear()
}

/**
 * Proto DataStore-backed [PairingStore] (design §5.4, §5.7).
 *
 * Translates between [PairingRecordProto] and the framework-free domain model so callers never
 * see persistence types.
 *
 * The backing [DataStore] is created here (rather than injected) because Hilt/KSP cannot
 * resolve the generated proto type argument of an injected `DataStore<PairingRecordProto>`.
 */
@Singleton
class DataStorePairingStore @Inject constructor(
    @ApplicationContext context: Context,
) : PairingStore {

    private val dataStore: DataStore<PairingRecordProto> =
        DataStoreFactory.create(serializer = PairingRecordSerializer) {
            context.dataStoreFile(FILE_NAME)
        }

    override val pairing: Flow<PairingRecord?> = dataStore.data.map { it.toDomainOrNull() }

    override suspend fun get(): PairingRecord? = dataStore.data.first().toDomainOrNull()

    override suspend fun isPaired(): Boolean = get() != null

    override suspend fun save(record: PairingRecord) {
        dataStore.updateData { record.toProto() }
    }

    override suspend fun clear() {
        dataStore.updateData { PairingRecordProto.getDefaultInstance() }
    }

    private companion object {
        const val FILE_NAME = "pairing.pb"
    }
}

/** A proto with no pairingId is the "not paired" default; map it to null. */
private fun PairingRecordProto.toDomainOrNull(): PairingRecord? =
    if (pairingId.isEmpty()) {
        null
    } else {
        PairingRecord(
            pairingId = pairingId,
            peerName = peerName,
            sharedSecret = sharedSecret,
            createdAt = createdAt
        )
    }

private fun PairingRecord.toProto(): PairingRecordProto =
    PairingRecordProto.newBuilder()
        .setPairingId(pairingId)
        .setPeerName(peerName)
        .setSharedSecret(sharedSecret)
        .setCreatedAt(createdAt)
        .build()
