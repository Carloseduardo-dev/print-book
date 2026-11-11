package com.printbook.app.di

import com.printbook.app.capture.NoOpPageTurner
import com.printbook.app.capture.PageTurner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Flavor "loja": nunca terá automação de páginas. */
@Module
@InstallIn(SingletonComponent::class)
object PageTurnerModule {
    @Provides
    @Singleton
    fun providePageTurner(): PageTurner = NoOpPageTurner()
}
