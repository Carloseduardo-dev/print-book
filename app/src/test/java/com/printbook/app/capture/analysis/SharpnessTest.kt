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
    fun `gate espera a nitidez parar de subir`() {
        val g = SharpnessGate()
        assertThat(g.onSample(10.0)).isFalse() // ainda borrada
        assertThat(g.onSample(40.0)).isFalse() // subindo
        assertThat(g.onSample(80.0)).isFalse() // subindo
        assertThat(g.onSample(80.0)).isTrue() // estável no máximo
    }

    @Test
    fun `gate libera pagina em branco`() {
        val g = SharpnessGate()
        assertThat(g.onSample(0.1)).isTrue()
    }

    @Test
    fun `reset volta a esperar`() {
        val g = SharpnessGate()
        g.onSample(50.0); g.onSample(50.0)
        g.reset()
        assertThat(g.onSample(50.0)).isFalse()
    }
}
