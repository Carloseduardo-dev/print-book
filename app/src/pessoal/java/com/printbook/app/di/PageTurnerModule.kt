package com.printbook.app.di

import com.printbook.app.automation.AccessibilityPageTurner
import com.printbook.app.capture.PageTurner
import com.printbook.app.export.BookExporter
import com.printbook.app.export.PdfBookExporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Flavor "pessoal": virada de página por Acessibilidade e exportação em PDF. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PageTurnerModule {
    @Binds
    abstract fun bindPageTurner(impl: AccessibilityPageTurner): PageTurner

    @Binds
    abstract fun bindExporter(impl: PdfBookExporter): BookExporter
}
