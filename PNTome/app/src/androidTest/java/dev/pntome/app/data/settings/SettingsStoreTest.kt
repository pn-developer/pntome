package dev.pntome.app.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pntome.app.data.DataStoreTestContext
import dev.pntome.app.domain.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented round-trip tests for [SettingsStore] (Req 8.1 persist settings, 8.2 restore).
 *
 * Verifies the mp3-only / port-8080 defaults (design §5.2, §5.5), full save/get round-trips, and
 * transform-based [SettingsStore.update]. Each test uses an isolated DataStore file.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    private lateinit var filesDir: File
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        filesDir = File(base.cacheDir, "settings-${System.nanoTime()}")
        store = SettingsStore(DataStoreTestContext(base, filesDir))
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun defaultsAreMp3OnlyOnPort8080() = runTest {
        val settings = store.get()

        assertFalse(settings.allowM4aAac)
        assertFalse(settings.allowWav)
        assertFalse(settings.allowFlac)
        assertEquals(AppSettings.DEFAULT_SERVER_PORT, settings.serverPort)
        assertEquals(8080, settings.serverPort)
    }

    @Test
    fun saveThenGetRoundTripsAllFields() = runTest {
        val settings = AppSettings(
            allowM4aAac = true,
            allowWav = true,
            allowFlac = true,
            serverPort = 9191
        )

        store.save(settings)

        assertEquals(settings, store.get())
        assertEquals(settings, store.settings.first())
    }

    @Test
    fun updateAppliesTransformToPersistedValue() = runTest {
        store.update { it.copy(allowFlac = true) }
        store.update { it.copy(serverPort = 8181) }

        val result = store.get()
        assertEquals(true, result.allowFlac)
        assertEquals(8181, result.serverPort)
        // Untouched flags keep their defaults.
        assertFalse(result.allowM4aAac)
        assertFalse(result.allowWav)
    }
}
