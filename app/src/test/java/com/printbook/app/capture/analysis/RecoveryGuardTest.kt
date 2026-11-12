package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecoveryGuardTest {

    @Test
    fun `comeca ligada`() {
        assertThat(RecoveryGuard().enabled).isTrue()
    }

    @Test
    fun `um alarme falso isolado nao desliga`() {
        val g = RecoveryGuard()
        assertThat(g.onFalseAlarm(40)).isFalse()
        assertThat(g.enabled).isTrue()
    }

    @Test
    fun `dois alarmes falsos proximos desligam`() {
        // Rodapé lido errado do mesmo jeito: alarme falso em páginas seguidas.
        val g = RecoveryGuard(window = 10)
        g.onFalseAlarm(40)
        assertThat(g.onFalseAlarm(41)).isTrue()
        assertThat(g.enabled).isFalse()
    }

    @Test
    fun `dois alarmes falsos distantes nao desligam`() {
        // Num livro longo, dois erros de leitura separados por centenas de páginas.
        val g = RecoveryGuard(window = 10)
        g.onFalseAlarm(40)
        assertThat(g.onFalseAlarm(300)).isFalse()
        assertThat(g.enabled).isTrue()
    }

    @Test
    fun `no limite da janela ainda desliga`() {
        val g = RecoveryGuard(window = 10)
        g.onFalseAlarm(40)
        assertThat(g.onFalseAlarm(50)).isTrue()
    }

    @Test
    fun `recuperacao de verdade entre os alarmes zera a contagem`() {
        val g = RecoveryGuard(window = 10)
        g.onFalseAlarm(40)
        g.onRecovered()
        assertThat(g.onFalseAlarm(42)).isFalse()
        assertThat(g.enabled).isTrue()
    }

    @Test
    fun `avisa que desligou so uma vez`() {
        val g = RecoveryGuard(window = 10)
        g.onFalseAlarm(1)
        assertThat(g.onFalseAlarm(2)).isTrue()
        assertThat(g.onFalseAlarm(3)).isFalse()
        assertThat(g.enabled).isFalse()
    }
}
