package dev.gsound.a.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.gsound.a.data.folders.FolderDao
import dev.gsound.a.data.folders.GsoundDatabase
import dev.gsound.a.data.folders.TrackDao
import javax.inject.Singleton

/**
 * Hilt bindings for the Room persistence layer (design §5.7).
 *
 * Provides the application-scoped [GsoundDatabase] and its DAOs so repositories can be
 * constructor-injected. The `FolderRepository` itself is `@Inject`-constructed and needs no
 * explicit binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GsoundDatabase =
        Room.databaseBuilder(context, GsoundDatabase::class.java, GsoundDatabase.NAME)
            .addMigrations(GsoundDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideFolderDao(db: GsoundDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideTrackDao(db: GsoundDatabase): TrackDao = db.trackDao()
}
