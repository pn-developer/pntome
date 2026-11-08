package dev.gsound.a.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.gsound.a.data.pairing.DataStorePairingStore
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.ui.pairing.DefaultPeerNameProvider
import dev.gsound.a.ui.pairing.PairingClock
import dev.gsound.a.ui.pairing.PairingCredentialGenerator
import dev.gsound.a.ui.pairing.PairingViewModel
import dev.gsound.a.ui.pairing.PeerNameProvider
import dev.gsound.a.ui.pairing.SecureRandomPairingCredentialGenerator
import dev.gsound.a.ui.pairing.SystemPairingClock
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt bindings for the pairing testability seams (task 7.1).
 *
 * Binds the interfaces [PairingViewModel][dev.gsound.a.ui.pairing.PairingViewModel] depends on
 * to their production implementations, so unit tests (task 7.3) can substitute deterministic
 * fakes (fixed credentials, fixed clock) while production uses secure randomness and the system
 * clock. Mirrors the existing [ScanModule]/[ControlChannelModule] pattern.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PairingModule {

    @Binds
    @Singleton
    abstract fun bindPairingCredentialGenerator(
        impl: SecureRandomPairingCredentialGenerator,
    ): PairingCredentialGenerator

    @Binds
    @Singleton
    abstract fun bindPairingClock(impl: SystemPairingClock): PairingClock

    @Binds
    @Singleton
    abstract fun bindPeerNameProvider(impl: DefaultPeerNameProvider): PeerNameProvider

    @Binds
    @Singleton
    abstract fun bindPairingStore(impl: DataStorePairingStore): PairingStore

    companion object {

        /**
         * Provide the pairing timeout (Req 4.6) under the qualifier the view model injects.
         * Factored as a provider so a test Hilt module (or direct constructor call) can supply
         * a short timeout driven by a `TestDispatcher` in task 7.3.
         */
        @Provides
        @Named(PairingViewModel.PAIRING_TIMEOUT_MILLIS)
        fun providePairingTimeoutMillis(): Long = PairingViewModel.DEFAULT_TIMEOUT_MILLIS
    }
}
