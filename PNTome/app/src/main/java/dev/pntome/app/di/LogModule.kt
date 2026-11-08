package dev.gsound.a.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gsound.a.log.GsoundLog
import dev.gsound.a.log.Logger
import javax.inject.Singleton

/**
 * Hilt binding for the app's logging seam ([Logger]).
 *
 * Production code injects [Logger] and gets the [android.util.Log]-backed [GsoundLog]; JVM unit
 * tests construct their subject with [dev.gsound.a.log.NoopLogger] instead, so logging never
 * reaches the unmocked `android.util.Log` (which throws "not mocked" under plain JUnit). This is
 * why logging is injected rather than referencing the [GsoundLog] object directly in classes that
 * have JVM unit tests (PairingViewModel, SyncOrchestrator).
 *
 * Provided (not @Binds) because the production implementation is a Kotlin `object`, not an
 * @Inject-constructed class.
 */
@Module
@InstallIn(SingletonComponent::class)
object LogModule {

    @Provides
    @Singleton
    fun provideLogger(): Logger = GsoundLog
}
