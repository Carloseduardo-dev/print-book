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

    @Test
    fun `armWith arma direto com a pagina capturada e dispara na proxima virada`() {
        val t = AutoCaptureTrigger()
        t.armWith(pageA)
        assertThat(t.state).isEqualTo(AutoCaptureTrigger.State.ARMED)
        // A mesma página continua na tela: nada dispara.
        assertThat(t.feed(pageA, pageA, pageA)).doesNotContain(true)
        // Virou: dispara uma vez quando a nova estabiliza.
        val fired = t.feed(anim, pageB, pageB, pageB, pageB)
        assertThat(fired.count { it }).isEqualTo(1)
        assertThat(t.lastSample).isEqualTo(pageB)
    }

    @Test
    fun `turbo com duas amostras estaveis dispara mais cedo`() {
        val t = AutoCaptureTrigger(stableSamples = 2)
        t.armWith(pageA)
        val fired = t.feed(anim, pageB, pageB, pageB)
        assertThat(fired).containsExactly(false, false, false, true).inOrder()
    }

    @Test
    fun `nao captura no fim lento da animacao (pagina ainda deslizando)`() {
        val t = AutoCaptureTrigger(stableSamples = 3)
        t.armWith(pageA)
        // Passos de 0,5 (abaixo do limiar 0,6 entre amostras), mas a tela continua andando:
        // a cada passo mais metade dos pixels escurece 1 nível.
        fun step(n: Int) = FrameSignature(8, 8, ByteArray(64) { i -> (160 - (n + (i % 2)) / 2).toByte() })
        val slide = (0 until 10).map { step(it) }
        assertThat(t.feed(anim, *slide.toTypedArray())).doesNotContain(true)
        // Parou de verdade: agora dispara.
        assertThat(t.feed(pageB, pageB, pageB, pageB)).contains(true)
    }

    @Test
    fun `passo de 0,5 seria aceito so pela regra entre amostras`() {
        fun step(n: Int) = FrameSignature(8, 8, ByteArray(64) { i -> (160 - (n + (i % 2)) / 2).toByte() })
        assertThat(step(1).meanAbsDiff(step(0))).isLessThan(0.6)
        assertThat(step(4).meanAbsDiff(step(0))).isGreaterThan(0.6)
    }

    @Test
    fun `quadro travado no meio da virada so dispara quando a tela muda de verdade`() {
        // Revisão: com a referência antiga, o mesmo quadro travado disparava de novo em ~240 ms.
        val t = AutoCaptureTrigger()
        t.feed(pageA, pageA, pageA, pageA) // referência = página antes da virada
        val midTurn = anim
        assertThat(t.feed(midTurn, midTurn, midTurn, midTurn).count { it }).isEqualTo(1) // "estável" no meio
        t.rejectSettled(midTurn)
        // Continua travado: não dispara.
        assertThat(t.feed(midTurn, midTurn, midTurn, midTurn, midTurn, midTurn)).doesNotContain(true)
        // A virada termina: dispara uma vez na página nova.
        assertThat(t.feed(pageB, pageB, pageB, pageB, pageB).count { it }).isEqualTo(1)
    }
}
