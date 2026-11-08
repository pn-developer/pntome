package dev.pntome.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.pntome.app.data.folders.BookmarkDao
import dev.pntome.app.data.folders.FolderDao
import dev.pntome.app.data.folders.PNTomeDatabase
import dev.pntome.app.data.folders.TrackDao
import javax.inject.Singleton

/**
 * Hilt bindings for the Room persistence layer (design §5.7).
 *
 * Provides the application-scoped [PNTomeDatabase] and its DAOs so repositories can be
 * constructor-injected. The `FolderRepository` itself is `@Inject`-constructed and needs no
 * explicit binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PNTomeDatabase =
        Room.databaseBuilder(context, PNTomeDatabase::class.java, PNTomeDatabase.NAME)
            .addMigrations(PNTomeDatabase.MIGRATION_1_2, PNTomeDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun provideFolderDao(db: PNTomeDatabase): FolderDao = db.folderDao()

    @Provides
    fun provideTrackDao(db: PNTomeDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideBookmarkDao(db: PNTomeDatabase): BookmarkDao = db.bookmarkDao()
}
