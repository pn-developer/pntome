package dev.pntome.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pntome.app.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proto DataStore-backed store for [AppSettings] (design §5.5, §5.7).
 *
 * Exposes a reactive [Flow] read so settings restore on launch (Req 8.2) and `suspend`
 * writes for mutations, mapping between [AppSettingsProto] and the domain model.
 *
 * The backing [DataStore] is created here (rather than injected) because Hilt/KSP cannot
 * resolve the generated proto type argument of an injected `DataStore<AppSettingsProto>`.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val dataStore: DataStore<AppSettingsProto> =
        DataStoreFactory.create(serializer = AppSettingsSerializer) {
            context.dataStoreFile(FILE_NAME)
        }

    /** Observe the persisted settings. */
    val settings: Flow<AppSettings> = dataStore.data.map { it.toDomain() }

    /** One-shot snapshot of the current settings. */
    suspend fun get(): AppSettings = dataStore.data.first().toDomain()

    /** Replace the full settings value. */
    suspend fun save(settings: AppSettings) {
        dataStore.updateData { settings.toProto() }
    }

    /** Apply a transform to the current settings and persist the result. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.updateData { transform(it.toDomain()).toProto() }
    }

    private companion object {
        const val FILE_NAME = "settings.pb"
    }
}

private fun AppSettingsProto.toDomain(): AppSettings =
    AppSettings(
        allowM4aAac = allowM4AAac,
        allowWav = allowWav,
        allowFlac = allowFlac,
        // A stored 0 (e.g. legacy value) falls back to the default port.
        serverPort = if (serverPort == 0) AppSettings.DEFAULT_SERVER_PORT else serverPort
    )

private fun AppSettings.toProto(): AppSettingsProto =
    AppSettingsProto.newBuilder()
        .setAllowM4AAac(allowM4aAac)
        .setAllowWav(allowWav)
        .setAllowFlac(allowFlac)
        .setServerPort(serverPort)
        .build()
