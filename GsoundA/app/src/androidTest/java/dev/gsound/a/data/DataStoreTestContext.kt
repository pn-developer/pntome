package dev.gsound.a.data

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * A [Context] whose [getFilesDir] points at a caller-supplied directory.
 *
 * The Proto DataStore stores ([dev.gsound.a.data.pairing.PairingStore],
 * [dev.gsound.a.data.settings.SettingsStore], [dev.gsound.a.data.sync.SyncSummaryStore]) resolve
 * their backing file via `context.dataStoreFile(name)`, i.e. under `filesDir/datastore/<name>`.
 * Giving each test a unique files directory yields a unique DataStore file, which both isolates
 * tests from one another and avoids the "multiple DataStores active for the same file" error that
 * occurs when two instances point at the same path within a single process.
 *
 * `dataStoreFile` resolves its path from `this.applicationContext.filesDir`, so this wrapper also
 * returns itself from [getApplicationContext] to ensure the [getFilesDir] override is honored.
 */
class DataStoreTestContext(
    base: Context,
    private val filesDirOverride: File
) : ContextWrapper(base) {

    init {
        filesDirOverride.mkdirs()
    }

    override fun getFilesDir(): File = filesDirOverride

    override fun getApplicationContext(): Context = this
}
