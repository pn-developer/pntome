package dev.gsound.a

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.gsound.a.service.WatchSyncRequestListener
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to generate the application-level Hilt component that
 * serves as the parent for all other Hilt components in the app.
 */
@HiltAndroidApp
class GsoundApplication : Application() {

    /**
     * App-lifetime listener that lets the watch initiate a sync (`SYNC_REQUEST`). Field-injected
     * and started here because a watch-initiated sync must work even when no screen is open — it
     * cannot live in a ViewModel's transient scope.
     */
    @Inject
    lateinit var watchSyncRequestListener: WatchSyncRequestListener

    override fun onCreate() {
        super.onCreate()
        watchSyncRequestListener.start()
    }
}
