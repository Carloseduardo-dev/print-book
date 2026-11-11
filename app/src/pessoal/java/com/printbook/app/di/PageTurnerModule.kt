package com.printbook.app.di

import com.printbook.app.capture.NoOpPageTurner
import com.printbook.app.capture.PageTurner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Flavor "pessoal": a implementação por Acessibilidade entra na Fase 3. */
@Module
@InstallIn(SingletonComponent::class)
object PageTurnerModule {
    @Provides
    @Singleton
    fun providePageTurner(): PageTurner = NoOpPageTurner()
}
