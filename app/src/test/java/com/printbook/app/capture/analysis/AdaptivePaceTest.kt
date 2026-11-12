package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptivePaceTest {

    @Test
    fun `comeca no minimo do Turbo`() {
        val p = AdaptivePace()
        assertThat(p.intervalMs).isEqualTo(900)
        assertThat(p.stableSamples).isEqualTo(3)
        assertThat(p.sharpStableMs).isEqualTo(150)
    }

    @Test
    fun `leitor rapido continua no minimo`() {
        val p = AdaptivePace()
        repeat(12) { p.onSettled(350); p.onCleanPage() }
        assertThat(p.intervalMs).isEqualTo(900)
    }

    @Test
    fun `leitor lento aumenta o intervalo pelos 10 por cento mais lentos`() {
        val p = AdaptivePace()
        repeat(10) { p.onSettled(400) }
        p.onSettled(1_600) // uma página lenta entre poucas amostras ainda pesa
        p.onSettled(1_800)
        // p90 de 12 amostras = posição ceil(10,8) = 11ª ordenada = 1600 → 900 + (1600 - 500)
        assertThat(p.intervalMs).isEqualTo(2_000)
    }

    @Test
    fun `intervalo nunca passa do teto`() {
        val p = AdaptivePace()
        repeat(12) { p.onSettled(9_000) }
        repeat(5) { p.onSlowPage() }
        assertThat(p.intervalMs).isEqualTo(2_500)
    }

    @Test
    fun `quadro no meio da virada pede mais amostras e mais tempo`() {
        val p = AdaptivePace()
        p.onMidTurn()
        assertThat(p.stableSamples).isEqualTo(5)
        assertThat(p.intervalMs).isEqualTo(1_300)
        assertThat(p.sharpStableMs).isEqualTo(250)
    }

    @Test
    fun `volta ao ritmo rapido depois de paginas limpas`() {
        val p = AdaptivePace()
        p.onMidTurn()
        repeat(AdaptivePace.CAUTIOUS_PAGES) { p.onCleanPage() }
        assertThat(p.stableSamples).isEqualTo(3)
        repeat(30) { p.onCleanPage() }
        assertThat(p.intervalMs).isEqualTo(900)
        assertThat(p.sharpStableMs).isEqualTo(150)
    }

    @Test
    fun `pagina lenta deixa o ritmo mais cauteloso por um tempo`() {
        val p = AdaptivePace()
        p.onSlowPage()
        assertThat(p.intervalMs).isEqualTo(1_500)
        repeat(5) { p.onCleanPage() }
        assertThat(p.intervalMs).isLessThan(1_500)
        assertThat(p.intervalMs).isGreaterThan(900)
    }

    @Test
    fun `so as ultimas paginas contam`() {
        val p = AdaptivePace(window = 12)
        repeat(12) { p.onSettled(3_000) }
        repeat(12) { p.onSettled(300) }
        assertThat(p.intervalMs).isEqualTo(900)
    }
}
