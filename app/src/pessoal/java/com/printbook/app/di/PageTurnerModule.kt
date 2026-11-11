package com.printbook.app.di

import com.printbook.app.automation.AccessibilityPageTurner
import com.printbook.app.capture.PageTurner
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Flavor "pessoal": virada de página automática pelo serviço de Acessibilidade. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PageTurnerModule {
    @Binds
    abstract fun bindPageTurner(impl: AccessibilityPageTurner): PageTurner
}
