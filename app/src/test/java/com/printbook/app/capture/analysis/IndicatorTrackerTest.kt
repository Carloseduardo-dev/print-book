package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import com.printbook.app.capture.analysis.IndicatorTracker.Event
import com.printbook.app.capture.analysis.IndicatorTracker.Reading
import org.junit.Test

class IndicatorTrackerTest {

    private fun r(c: Int, t: Int) = Reading(c, t)

    @Test
    fun `confirma depois de subir +1 por pagina`() {
        val t = IndicatorTracker()
        assertThat(t.onReading(0, listOf(r(13, 553)))).isEqualTo(Event.None)
        assertThat(t.onReading(1, listOf(r(14, 553)))).isEqualTo(Event.Confirmed(14, 553))
        assertThat(t.expectedAt(5)).isEqualTo(18)
    }

    @Test
    fun `numero de capitulo ou data nao confirmam`() {
        val t = IndicatorTracker()
        // "Cap. 3/12" fica parado; a página sobe.
        t.onReading(0, listOf(r(3, 12), r(40, 553)))
        assertThat(t.onReading(1, listOf(r(3, 12), r(41, 553)))).isEqualTo(Event.Confirmed(41, 553))
        assertThat(t.total).isEqualTo(553)
    }

    @Test
    fun `confirma tambem quando uma leitura falhou no meio`() {
        val t = IndicatorTracker()
        t.onReading(0, listOf(r(100, 300)))
        t.onReading(1, emptyList()) // OCR não achou nada
        assertThat(t.onReading(2, listOf(r(102, 300)))).isEqualTo(Event.Confirmed(102, 300))
    }

    @Test
    fun `sequencia normal nao gera evento`() {
        val t = confirmedAt(10)
        for (i in 2..20) assertThat(t.onReading(i, listOf(r(10 + i, 553)))).isEqualTo(Event.None)
    }

    @Test
    fun `pagina pulada aparece com o numero que faltou`() {
        val t = confirmedAt(10)
        t.onReading(2, listOf(r(12, 553)))
        // Posição 3 deveria ser 13; o leitor mostrou 14.
        assertThat(t.onReading(3, listOf(r(14, 553)))).isEqualTo(Event.Skipped(listOf(13), 14))
    }

    @Test
    fun `depois de aceitar a pagina pulada a contagem segue dela`() {
        val t = confirmedAt(10)
        val e = t.onReading(2, listOf(r(13, 553))) as Event.Skipped
        assertThat(e.missing).containsExactly(12)
        t.accept(2, 13)
        assertThat(t.onReading(3, listOf(r(14, 553)))).isEqualTo(Event.None)
    }

    @Test
    fun `erro do OCR nao aceito mantem a contagem original`() {
        val t = confirmedAt(10)
        // Leu 13 em vez de 12: a recuperação mostra que não faltou nada e não aceita.
        assertThat(t.onReading(2, listOf(r(13, 553)))).isInstanceOf(Event.Skipped::class.java)
        assertThat(t.onReading(3, listOf(r(13, 553)))).isEqualTo(Event.None)
    }

    @Test
    fun `salto grande so vale com duas leituras iguais`() {
        val t = confirmedAt(10)
        assertThat(t.onReading(2, listOf(r(40, 553)))).isEqualTo(Event.None)
        assertThat(t.onReading(3, listOf(r(41, 553)))).isEqualTo(Event.Jumped(41, 13))
    }

    @Test
    fun `volta do leitor so vale com duas leituras iguais`() {
        val t = confirmedAt(10)
        assertThat(t.onReading(2, listOf(r(5, 553)))).isEqualTo(Event.None)
        assertThat(t.onReading(3, listOf(r(6, 553)))).isEqualTo(Event.WentBack(6, 13))
    }

    @Test
    fun `erro do OCR com salto nao pausa nem muda a contagem`() {
        // Revisão: "173" lido como "178" movia a base e a leitura certa seguinte parecia "voltou".
        val t = confirmedAt(171) // posição 1 = 172
        assertThat(t.onReading(2, listOf(r(178, 553)))).isEqualTo(Event.None)
        assertThat(t.onReading(3, listOf(r(174, 553)))).isEqualTo(Event.None)
        assertThat(t.expectedAt(4)).isEqualTo(175)
    }

    @Test
    fun `erro do OCR de mais duas paginas nao acusa paginas faltando`() {
        val t = confirmedAt(171)
        assertThat(t.onReading(2, listOf(r(175, 553)))).isEqualTo(Event.None) // 173 lido como 175
        assertThat(t.onReading(3, listOf(r(174, 553)))).isEqualTo(Event.None)
    }

    @Test
    fun `duas paginas puladas confirmadas trazem as paginas da primeira leitura`() {
        val t = confirmedAt(10) // posição 1 = 11
        assertThat(t.onReading(2, listOf(r(14, 553)))).isEqualTo(Event.None) // esperado 12: faltam 12 e 13
        assertThat(t.onReading(3, listOf(r(15, 553)))).isEqualTo(Event.Skipped(listOf(12, 13), 15))
    }

    @Test
    fun `pequena diferenca para tras que se repete corrige a base`() {
        // Revisão: com a base 1 à frente, os pulos de uma página ficavam escondidos.
        val t = confirmedAt(10)
        assertThat(t.onReading(2, listOf(r(11, 553)))).isEqualTo(Event.None)
        assertThat(t.onReading(3, listOf(r(12, 553)))).isEqualTo(Event.None) // mesma diferença: corrige
        // Agora um pulo real de uma página aparece.
        assertThat(t.onReading(4, listOf(r(14, 553)))).isEqualTo(Event.Skipped(listOf(13), 14))
    }

    @Test
    fun `pagina que falhou aparece como pulada na leitura seguinte`() {
        // A posição só avança com a página salva: a 12 falhou (não salva), a 13 é salva na posição 2.
        val t = confirmedAt(10)
        assertThat(t.onReading(2, listOf(r(13, 553)))).isEqualTo(Event.Skipped(listOf(12), 13))
    }

    @Test
    fun `pequena diferenca para tras e ignorada`() {
        val t = confirmedAt(10)
        assertThat(t.onReading(2, listOf(r(11, 553)))).isEqualTo(Event.None)
    }

    @Test
    fun `leitura de outro total depois de confirmar e ignorada`() {
        val t = confirmedAt(10)
        assertThat(t.onReading(2, listOf(r(3, 12)))).isEqualTo(Event.None)
    }

    @Test
    fun `candidatos do rodape`() {
        assertThat(IndicatorTracker.candidates("13 / 553")).containsExactly(r(13, 553))
        assertThat(IndicatorTracker.candidates("Página 7 de 120 · 6%")).containsExactly(r(7, 120))
        assertThat(IndicatorTracker.candidates("Cap. 3/12   41 / 553")).containsExactly(r(3, 12), r(41, 553))
        // Data e número maior que o total não são página.
        assertThat(IndicatorTracker.candidates("12/11/2026")).isEmpty()
        assertThat(IndicatorTracker.candidates("600 / 553")).isEmpty()
        // Localização do Kindle não é página.
        assertThat(IndicatorTracker.candidates("Localização 120 de 5400")).isEmpty()
    }

    private fun confirmedAt(firstPage: Int): IndicatorTracker {
        val t = IndicatorTracker()
        t.onReading(0, listOf(r(firstPage, 553)))
        t.onReading(1, listOf(r(firstPage + 1, 553)))
        return t
    }
}
