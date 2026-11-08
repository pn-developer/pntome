package dev.gsound.a.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gsound.a.net.garmin.ConnectIqManager
import dev.gsound.a.net.garmin.ControlChannel
import javax.inject.Singleton

/**
 * Hilt bindings for the Garmin control-channel seam (task 6.2).
 *
 * Binds the hardware-independent [ControlChannel] interface to the Connect IQ Mobile SDK-backed
 * [ConnectIqManager] so pairing/sync view models depend on the interface (and can be driven by
 * a fake channel in unit tests, task 6.3) while production code gets the real SDK manager.
 *
 * Kept as a single [Singleton] [Binds] matching the existing [ScanModule] pattern.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ControlChannelModule {

    @Binds
    @Singleton
    abstract fun bindControlChannel(impl: ConnectIqManager): ControlChannel
}
