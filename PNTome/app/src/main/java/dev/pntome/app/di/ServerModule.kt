package dev.pntome.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pntome.app.net.server.ConnectivityWifiAddressResolver
import dev.pntome.app.net.server.ContentResolverMediaSource
import dev.pntome.app.net.server.DefaultSecretProvider
import dev.pntome.app.net.server.MediaSource
import dev.pntome.app.net.server.SecretProvider
import dev.pntome.app.net.server.WifiAddressResolver
import dev.pntome.app.net.server.installPNTomeRouting
import io.ktor.server.application.Application
import javax.inject.Singleton

/**
 * Hilt bindings for the local HTTP server seam (task 8.1).
 *
 * - Binds the Android-coupled [ConnectivityWifiAddressResolver] to the [WifiAddressResolver]
 *   interface so [dev.pntome.app.net.server.PNTomeHttpServer] depends on the interface and can be
 *   driven by a fake resolver in tests (matching the [ControlChannelModule]/[ScanModule]
 *   interface-binding pattern).
 * - Declares an (initially empty) multibound `Set<Application.() -> Unit>` of Ktor routing
 *   modules via [Multibinds]. Tasks 8.2/8.3 contribute their routes + auth interceptor into this
 *   set with `@IntoSet` providers, so the server's constructor receives them without this task
 *   knowing the route details. `PNTomeHttpServer` is `@Singleton @Inject` but is started/stopped
 *   explicitly by the sync orchestration (task 9), never at construction.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServerModule {

    @Binds
    @Singleton
    abstract fun bindWifiAddressResolver(impl: ConnectivityWifiAddressResolver): WifiAddressResolver

    /**
     * Bind the SAF/ContentResolver-backed [ContentResolverMediaSource] (task 8.2) to the
     * [MediaSource] interface the routes depend on, so route code stays testable with a fake
     * media source (task 8.4) and free of Android types.
     */
    @Binds
    @Singleton
    abstract fun bindMediaSource(impl: ContentResolverMediaSource): MediaSource

    /**
     * Bind the session/pairing-backed [DefaultSecretProvider] (task 8.3) to the [SecretProvider]
     * interface the auth interceptor depends on, so the `X-PNTome-Secret` check stays testable
     * with a fake provider (task 8.4) and free of Android/DataStore types.
     */
    @Binds
    @Singleton
    abstract fun bindSecretProvider(impl: DefaultSecretProvider): SecretProvider

    /**
     * Multibinding seam for Ktor application modules (routes/auth), filled by tasks 8.2/8.3.
     * Declaring it here lets `PNTomeHttpServer` inject the set even while it is empty.
     */
    @Multibinds
    abstract fun ktorModules(): Set<@JvmSuppressWildcards Application.() -> Unit>

    companion object {

        /**
         * Contribute the routing module (`/health`, `/manifest`, `/media/{trackId}` plus
         * ContentNegotiation/PartialContent/AutoHeadResponse) and the task 8.3 secret-auth
         * interceptor into the multibound set the [dev.pntome.app.net.server.PNTomeHttpServer]
         * applies at start. The lambda captures the injected [MediaSource] and [SecretProvider];
         * [installPNTomeRouting] installs the `X-PNTome-Secret` gate (Req 5.3) ahead of the
         * routes, exempting only `/health`.
         */
        @Provides
        @IntoSet
        @Singleton
        fun provideRoutingModule(
            mediaSource: MediaSource,
            secretProvider: SecretProvider
        ): @JvmSuppressWildcards Application.() -> Unit = {
            installPNTomeRouting(mediaSource, secretProvider)
        }
    }
}
