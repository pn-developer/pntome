package dev.gsound.a.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gsound.a.domain.scan.FolderScanner
import dev.gsound.a.domain.scan.TrackScanner
import javax.inject.Singleton

/**
 * Hilt bindings for the folder-scanning seam (task 5.3).
 *
 * Binds the [TrackScanner] interface to the Android-coupled [FolderScanner] so
 * [dev.gsound.a.domain.folders.ScanAndCacheUseCase] can depend on the interface (and be tested
 * with a fake scanner on the JVM) while production code gets the real SAF-backed scanner.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScanModule {

    @Binds
    @Singleton
    abstract fun bindTrackScanner(impl: FolderScanner): TrackScanner
}
