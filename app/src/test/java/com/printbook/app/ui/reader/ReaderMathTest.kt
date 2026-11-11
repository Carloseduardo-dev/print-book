package com.printbook.app.ui.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderMathTest {

    @Test
    fun `posicao do slider vira a pagina mais proxima`() {
        assertThat(ReaderMath.scrubPage(0f, 99)).isEqualTo(0)
        assertThat(ReaderMath.scrubPage(36.4f, 99)).isEqualTo(36)
        assertThat(ReaderMath.scrubPage(36.6f, 99)).isEqualTo(37)
        assertThat(ReaderMath.scrubPage(99f, 99)).isEqualTo(99)
    }

    @Test
    fun `valores fora da faixa ficam nos limites`() {
        assertThat(ReaderMath.scrubPage(-3f, 10)).isEqualTo(0)
        assertThat(ReaderMath.scrubPage(15f, 10)).isEqualTo(10)
    }

    @Test
    fun `livro de uma pagina`() {
        assertThat(ReaderMath.scrubPage(0.7f, 0)).isEqualTo(0)
        assertThat(ReaderMath.fraction(0, 0)).isEqualTo(0f)
    }

    @Test
    fun `fracao para posicionar o balao`() {
        assertThat(ReaderMath.fraction(50, 100)).isWithin(1e-6f).of(0.5f)
        assertThat(ReaderMath.fraction(100, 100)).isEqualTo(1f)
    }
}
