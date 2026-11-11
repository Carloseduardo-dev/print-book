package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlackFrameDetectorTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val darkThemeText = 0xFFCCCCCC.toInt() // texto claro do tema escuro do Kindle

    @Test
    fun `quadro todo preto e detectado`() {
        assertThat(BlackFrameDetector.isBlack(IntArray(4096) { black })).isTrue()
    }

    @Test
    fun `pagina branca nao e preta`() {
        assertThat(BlackFrameDetector.isBlack(IntArray(4096) { white })).isFalse()
    }

    @Test
    fun `pagina em tema escuro com texto nao e considerada preta`() {
        // ~3% de pixels de texto claro sobre fundo preto, como uma página do Kindle no modo escuro.
        val samples = IntArray(4096) { i -> if (i % 32 == 0) darkThemeText else black }
        assertThat(BlackFrameDetector.isBlack(samples)).isFalse()
    }

    @Test
    fun `amostra vazia conta como preta`() {
        assertThat(BlackFrameDetector.isBlack(IntArray(0))).isTrue()
    }

    @Test
    fun `luminancia segue pesos ITU-R 601`() {
        assertThat(BlackFrameDetector.luma(white)).isEqualTo(255)
        assertThat(BlackFrameDetector.luma(black)).isEqualTo(0)
    }
}
