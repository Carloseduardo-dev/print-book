package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PageIndicatorTest {

    @Test
    fun `minha biblioteca com barra`() {
        val p = PageIndicator.parse("13 / 553")!!
        assertThat(p.current).isEqualTo(13)
        assertThat(p.total).isEqualTo(553)
        assertThat(p.pagesToCapture).isEqualTo(541)
    }

    @Test
    fun `play livros com paginas restantes e porcentagem`() {
        val p = PageIndicator.parse("212 páginas restantes · 37%")!!
        assertThat(p.remaining).isEqualTo(212)
        assertThat(p.percent).isEqualTo(37)
        assertThat(p.pagesToCapture).isEqualTo(213)
    }

    @Test
    fun `paginas restantes no capitulo nao servem de total`() {
        val p = PageIndicator.parse("5 páginas restantes no capítulo · 12%")!!
        assertThat(p.pagesToCapture).isNull()
        assertThat(p.percent).isEqualTo(12)
    }

    @Test
    fun `kindle localizacao usa so a proporcao`() {
        val p = PageIndicator.parse("Localização 120 de 5400 • 2%")!!
        assertThat(p.isLocation).isTrue()
        assertThat(p.pagesToCapture).isNull()
        assertThat(p.percent).isEqualTo(2)
    }

    @Test
    fun `pagina x de y`() {
        assertThat(PageIndicator.parse("Página 30 de 200")!!.pagesToCapture).isEqualTo(171)
    }

    @Test
    fun `texto sem indicador`() {
        assertThat(PageIndicator.parse("Capítulo 1 · Números reais")).isNull()
    }

    @Test
    fun `ignora fracao invalida`() {
        assertThat(PageIndicator.parse("600 / 553")).isNull()
    }

    @Test
    fun `capa e paginas iniciais guardam so o total`() {
        val capa = PageIndicator.parse("Capa / 553")!!
        assertThat(capa.total).isEqualTo(553)
        assertThat(capa.current).isNull()
        assertThat(capa.pagesToCapture).isNull()
        assertThat(PageIndicator.parse("iv / 553")!!.total).isEqualTo(553)
    }

    @Test
    fun `data no rodape nao vira pagina`() {
        // Revisão: "1/11/2026" virava "página 1 de 11" no início do livro todo.
        assertThat(PageIndicator.parse("Atualizado em 1/11/2026")).isNull()
        assertThat(PageIndicator.parse("1/11/2026   13 / 553")!!.current).isEqualTo(13)
    }

    @Test
    fun `pag abreviado`() {
        assertThat(PageIndicator.parse("Pág. 7 de 120")!!.current).isEqualTo(7)
    }

    @Test
    fun `os dois leitores do rodape concordam`() {
        val text = "Cap. 3/12   41 / 553   12/11/2026"
        assertThat(PageIndicator.pageCandidates(text)).containsExactly(3 to 12, 41 to 553)
        assertThat(IndicatorTracker.candidates(text).map { it.current to it.total }).isEqualTo(PageIndicator.pageCandidates(text))
    }
}
