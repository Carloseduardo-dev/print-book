package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeamDetectorTest {

    private val w = 968
    private val h = 1282

    /** Página branca com linhas verticais escuras de altura inteira nas colunas [lines]. */
    private fun page(vararg lines: Int): (Int, Int) -> Int = { x, _ -> if (lines.any { x in it..it + 1 }) 120 else 250 }

    @Test
    fun `duas bordas de folha com o vao entre elas e virada`() {
        assertThat(SeamDetector.hasSheetSeam(w, h, page(438, 576))).isTrue()
        assertThat(SeamDetector.hasSheetSeam(w, h, page(104, 242))).isTrue()
    }

    @Test
    fun `linha unica da coluna de notas nao e virada`() {
        assertThat(SeamDetector.hasSheetSeam(w, h, page(150))).isFalse()
    }

    @Test
    fun `pagina sem linhas nao e virada`() {
        assertThat(SeamDetector.hasSheetSeam(w, h, page())).isFalse()
    }

    @Test
    fun `linhas longe demais nao formam vao de folha`() {
        assertThat(SeamDetector.hasSheetSeam(w, h, page(150, 700))).isFalse()
    }

    @Test
    fun `bordas na lateral da imagem sao ignoradas`() {
        assertThat(SeamDetector.hasSheetSeam(w, h, page(10, 40))).isFalse()
    }

    @Test
    fun `pagina escura como a contracapa nao e virada`() {
        // Fundo escuro com faixas claras (letras) que deixam pedaços finos escuros entre elas.
        val dark: (Int, Int) -> Int = { x, _ -> if (x % 160 in 0..40) 240 else 30 }
        assertThat(SeamDetector.hasSheetSeam(w, h, dark)).isFalse()
    }
}
