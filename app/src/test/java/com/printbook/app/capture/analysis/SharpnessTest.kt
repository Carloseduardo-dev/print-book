package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharpnessTest {

    /** Listras pretas e brancas de 1 px (texto nítido) × degradê suave (versão borrada). */
    private val sharp = { x: Int, _: Int -> if (x % 2 == 0) 0 else 255 }
    private val blurred = { x: Int, _: Int -> 128 + ((x % 8) - 4) * 4 }

    @Test
    fun `pagina nitida mede bem mais que a borrada`() {
        val s = Sharpness.measure(64, 16, sharp)
        val b = Sharpness.measure(64, 16, blurred)
        assertThat(s).isGreaterThan(b * 10)
    }

    @Test
    fun `tela uniforme tem nitidez zero`() {
        assertThat(Sharpness.measure(32, 8) { _, _ -> 200 }).isEqualTo(0.0)
    }

    @Test
    fun `gate espera a nitidez parar de subir e ficar estavel pelo tempo minimo`() {
        val g = SharpnessGate(minStableMs = 300)
        assertThat(g.onSample(10.0, 0)).isFalse() // ainda borrada
        assertThat(g.onSample(40.0, 150)).isFalse() // subindo
        assertThat(g.onSample(80.0, 300)).isFalse() // subindo
        assertThat(g.onSample(80.0, 450)).isFalse() // estável há só 150 ms
        assertThat(g.onSample(80.0, 600)).isTrue() // estável há 300 ms
    }

    @Test
    fun `versao borrada parada nao libera antes do tempo minimo`() {
        val g = SharpnessGate(minStableMs = 300)
        // Tela parada na versão borrada: medições iguais, mas ainda dentro da janela mínima.
        assertThat(g.onSample(12.0, 0)).isFalse()
        assertThat(g.onSample(12.0, 150)).isFalse()
        // A versão nítida chega: a contagem recomeça a partir dela.
        assertThat(g.onSample(60.0, 250)).isFalse()
        assertThat(g.onSample(60.0, 400)).isFalse()
        assertThat(g.onSample(60.0, 560)).isTrue()
    }

    @Test
    fun `gate libera pagina em branco`() {
        val g = SharpnessGate()
        assertThat(g.onSample(0.1, 0)).isTrue()
    }

    @Test
    fun `reset volta a esperar`() {
        val g = SharpnessGate(minStableMs = 100)
        g.onSample(50.0, 0); g.onSample(50.0, 200)
        g.reset()
        assertThat(g.onSample(50.0, 300)).isFalse()
    }
}
