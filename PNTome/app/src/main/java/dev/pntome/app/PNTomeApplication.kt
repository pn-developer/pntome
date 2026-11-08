package dev.pntome.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.pntome.app.service.WatchSyncRequestListener
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to generate the application-level Hilt component that
 * serves as the parent for all other Hilt components in the app.
 */
@HiltAndroidApp
class PNTomeApplication : Application() {

    /**
     * App-lifetime listener that lets the watch initiate a sync (`SYNC_REQUEST`). Field-injected
     * and started here because a watch-initiated sync must work even when no screen is open — it
     * cannot live in a ViewModel's transient scope.
     */
    @Inject
    lateinit var watchSyncRequestListener: WatchSyncRequestListener

    /** App-lifetime collector of watch `BOOKMARK` messages (gsound-player PL-4.3). */
    @Inject
    lateinit var bookmarkSync: dev.pntome.app.domain.bookmarks.BookmarkSync

    override fun onCreate() {
        super.onCreate()
        bookmarkSync.start()
        watchSyncRequestListener.start()
    }
}
