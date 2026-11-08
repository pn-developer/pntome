package dev.pntome.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pntome.app.domain.folders.LibraryRefresher
import dev.pntome.app.domain.folders.ScanLibraryRefresher
import dev.pntome.app.domain.scan.FolderScanner
import dev.pntome.app.domain.scan.TrackScanner
import javax.inject.Singleton

/**
 * Hilt bindings for the folder-scanning seam (task 5.3).
 *
 * Binds the [TrackScanner] interface to the Android-coupled [FolderScanner] so
 * [dev.pntome.app.domain.folders.ScanAndCacheUseCase] can depend on the interface (and be tested
 * with a fake scanner on the JVM) while production code gets the real SAF-backed scanner.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScanModule {

    @Binds
    @Singleton
    abstract fun bindTrackScanner(impl: FolderScanner): TrackScanner

    /** Rescan-before-sync seam used by SyncOrchestrator. */
    @Binds
    @Singleton
    abstract fun bindLibraryRefresher(impl: ScanLibraryRefresher): LibraryRefresher
}
