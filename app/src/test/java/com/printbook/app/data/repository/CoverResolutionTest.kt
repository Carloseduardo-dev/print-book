package com.printbook.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoverResolutionTest {

    @Test
    fun `capa propria tem prioridade`() {
        assertThat(BookRepository.resolveCover("cover-1.webp", "p2.webp", "p1.webp")).isEqualTo("cover-1.webp")
    }

    @Test
    fun `sem capa propria usa a pagina escolhida`() {
        assertThat(BookRepository.resolveCover(null, "p2.webp", "p1.webp")).isEqualTo("p2.webp")
    }

    @Test
    fun `sem escolha usa a primeira pagina`() {
        assertThat(BookRepository.resolveCover(null, null, "p1.webp")).isEqualTo("p1.webp")
    }

    @Test
    fun `livro vazio nao tem capa`() {
        assertThat(BookRepository.resolveCover(null, null, null)).isNull()
    }
}
