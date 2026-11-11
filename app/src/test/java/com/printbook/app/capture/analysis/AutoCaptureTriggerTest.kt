package com.printbook.app.capture.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoCaptureTriggerTest {

    private fun sig(value: Int, w: Int = 8, h: Int = 8) = FrameSignature(w, h, ByteArray(w * h) { value.toByte() })

    private fun AutoCaptureTrigger.feed(vararg samples: FrameSignature): List<Boolean> = samples.map { onSample(it) }

    private val pageA = sig(200)
    private val pageB = sig(150)
    private val anim = sig(175)

    @Test
    fun `nao captura enquanto a pagina nao muda`() {
        val t = AutoCaptureTrigger()
        val fired = t.feed(pageA, pageA, pageA, pageA, pageA, pageA, pageA, pageA)
        assertThat(fired).doesNotContain(true)
        assertThat(t.state).isEqualTo(AutoCaptureTrigger.State.ARMED)
    }

    @Test
    fun `captura uma vez quando a nova pagina estabiliza`() {
        val t = AutoCaptureTrigger()
        t.feed(pageA, pageA, pageA, pageA) // referência
        val fired = t.feed(anim, anim, pageB, pageB, pageB, pageB, pageB, pageB)
        assertThat(fired.count { it }).isEqualTo(1)
    }

    @Test
    fun `nao captura durante a animacao de virada`() {
        val t = AutoCaptureTrigger()
        t.feed(pageA, pageA, pageA, pageA)
        val fired = t.feed(sig(190), sig(180), sig(170), sig(160))
        assertThat(fired).doesNotContain(true)
    }

    @Test
    fun `voltar para a mesma pagina nao dispara`() {
        val t = AutoCaptureTrigger()
        t.feed(pageA, pageA, pageA, pageA)
        val fired = t.feed(pageB, pageA, pageA, pageA, pageA, pageA)
        assertThat(fired).doesNotContain(true)
        assertThat(t.state).isEqualTo(AutoCaptureTrigger.State.ARMED)
    }

    @Test
    fun `apos reset a proxima tela estavel vira referencia`() {
        val t = AutoCaptureTrigger()
        t.feed(pageA, pageA, pageA, pageA, pageB, pageB, pageB, pageB)
        t.resetReference()
        assertThat(t.state).isEqualTo(AutoCaptureTrigger.State.REFERENCE)
        val fired = t.feed(pageB, pageB, pageB, pageB)
        assertThat(fired).doesNotContain(true)
        assertThat(t.state).isEqualTo(AutoCaptureTrigger.State.ARMED)
    }

    @Test
    fun `diferenca media absoluta`() {
        assertThat(sig(10).meanAbsDiff(sig(30))).isWithin(1e-9).of(20.0)
        assertThat(pageA.meanAbsDiff(pageA)).isEqualTo(0.0)
    }
}
