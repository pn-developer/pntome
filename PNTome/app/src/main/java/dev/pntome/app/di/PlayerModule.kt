package dev.pntome.app.di

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pntome.app.R
import dev.pntome.app.service.QueueBuilder
import dev.pntome.app.ui.player.MediaControllerConnector
import dev.pntome.app.ui.player.PlayerConnector

/**
 * Hilt wiring for the phone player (gsound-player design §2, task 2.4).
 *
 * [QueueBuilder] takes a plain [Uri] so JVM tests can construct it directly; here production gets
 * the bundled launcher icon as the default artwork for every queue item.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    fun provideQueueBuilder(@ApplicationContext context: Context): QueueBuilder =
        QueueBuilder(defaultArtworkUri(context))

    @Provides
    fun providePlayerConnector(@ApplicationContext context: Context): PlayerConnector =
        MediaControllerConnector(context)

    internal fun defaultArtworkUri(context: Context): Uri =
        Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(R.drawable.ic_launcher_foreground))
            .appendPath(context.resources.getResourceEntryName(R.drawable.ic_launcher_foreground))
            .build()
}
