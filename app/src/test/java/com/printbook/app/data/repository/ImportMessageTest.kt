package com.printbook.app.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportMessageTest {

    private fun msg(added: Int, failed: Int) = importMessage(BookRepository.ImportResult(added, failed))

    @Test
    fun `singular e plural`() {
        assertThat(msg(1, 0)).isEqualTo("1 imagem adicionada.")
        assertThat(msg(3, 0)).isEqualTo("3 imagens adicionadas.")
    }

    @Test
    fun `avisa as imagens que nao abriram`() {
        assertThat(msg(2, 1)).isEqualTo("2 imagens adicionadas · 1 não pôde ser adicionada.")
        assertThat(msg(1, 4)).isEqualTo("1 imagem adicionada · 4 não puderam ser adicionadas.")
    }

    @Test
    fun `nenhuma imagem adicionada`() {
        assertThat(msg(0, 2)).isEqualTo("Nenhuma imagem adicionada · 2 não puderam ser adicionadas.")
    }
}
