package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaceMeterTest {

    @Test
    fun `sem ritmo com uma pagina so`() {
        val p = PaceMeter()
        p.onPage(1_000)
        assertThat(p.etaMs(10)).isNull()
    }

    @Test
    fun `estima pelo ritmo das ultimas paginas`() {
        val p = PaceMeter(window = 3)
        listOf(0L, 3_000, 6_000, 7_000, 8_000, 9_000).forEach(p::onPage) // ficou mais rápido
        assertThat(p.msPerPage).isWithin(0.01).of(1_000.0)
        assertThat(p.etaMs(120)).isEqualTo(120_000)
    }

    @Test
    fun `formata o tempo restante`() {
        assertThat(PaceMeter.format(40_000)).isEqualTo("~40 s")
        assertThat(PaceMeter.format(14 * 60_000L)).isEqualTo("~14 min")
        assertThat(PaceMeter.format(65 * 60_000L)).isEqualTo("~1 h 05 min")
    }
}
