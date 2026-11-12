package com.printbook.app.data.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluralsTest {

    @Test
    fun `um print-book no singular`() {
        assertThat(Plurals.books(1)).isEqualTo("1 print-book")
        assertThat(Plurals.books(0)).isEqualTo("0 print-books")
        assertThat(Plurals.books(7)).isEqualTo("7 print-books")
    }

    @Test
    fun `paginas`() {
        assertThat(Plurals.pages(1)).isEqualTo("1 página")
        assertThat(Plurals.pages(558)).isEqualTo("558 páginas")
        assertThat(Plurals.pages(1L)).isEqualTo("1 página")
    }

    @Test
    fun `frases com participio concordam`() {
        assertThat(Plurals.pagesCaptured(1)).isEqualTo("1 página capturada")
        assertThat(Plurals.pagesCaptured(3)).isEqualTo("3 páginas capturadas")
        assertThat(Plurals.pagesSaved(1)).isEqualTo("1 página salva")
        assertThat(Plurals.count(2, "repetida ignorada", "repetidas ignoradas")).isEqualTo("2 repetidas ignoradas")
    }
}
