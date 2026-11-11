package com.printbook.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.printbook.app.data.db.PrintBookDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PrintBookDatabase =
        Room.databaseBuilder(context, PrintBookDatabase::class.java, "printbook.db").build()

    @Provides fun provideBookDao(db: PrintBookDatabase) = db.bookDao()
    @Provides fun providePageDao(db: PrintBookDatabase) = db.pageDao()
    @Provides fun provideCollectionDao(db: PrintBookDatabase) = db.collectionDao()
    @Provides fun provideBookmarkDao(db: PrintBookDatabase) = db.bookmarkDao()
    @Provides fun provideEditorTransactions(db: PrintBookDatabase) = db.editorTransactions()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
