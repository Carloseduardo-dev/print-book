package com.printbook.app.capture.analysis

/**
 * Máquina de estados do modo semiautomático. Recebe assinaturas da tela ao vivo (com a bolha
 * visível) em intervalos regulares e diz quando capturar:
 *
 *  REFERENCE  -> espera a tela ficar estável e guarda essa assinatura como "página atual"
 *  ARMED      -> espera a tela mudar em relação à referência (usuário virou a página)
 *  SETTLING   -> espera a nova tela estabilizar (fim da animação) e dispara a captura
 *
 * A referência é sempre tirada da tela ao vivo, então a bolha entra igual nas duas assinaturas
 * e não causa falso disparo.
 */
class AutoCaptureTrigger(
    private val changeThreshold: Double = 3.0,
    private val stableThreshold: Double = 0.6,
    private val stableSamples: Int = 3,
) {
    enum class State { REFERENCE, ARMED, SETTLING }

    var state: State = State.REFERENCE
        private set

    private var reference: FrameSignature? = null
    private var previous: FrameSignature? = null
    private var stableCount = 0

    /** Chamado após cada captura (ou ao iniciar): a próxima tela estável vira a referência. */
    fun resetReference() {
        state = State.REFERENCE
        reference = null
        previous = null
        stableCount = 0
    }

    /** @return true quando a página nova estabilizou e deve ser capturada. */
    fun onSample(sample: FrameSignature): Boolean {
        val prev = previous
        previous = sample
        stableCount = if (prev != null && sample.meanAbsDiff(prev) < stableThreshold) stableCount + 1 else 0
        val stable = stableCount >= stableSamples

        when (state) {
            State.REFERENCE -> if (stable) {
                reference = sample
                state = State.ARMED
            }
            State.ARMED -> {
                val ref = reference ?: return false.also { state = State.REFERENCE }
                if (sample.meanAbsDiff(ref) > changeThreshold) state = State.SETTLING
            }
            State.SETTLING -> if (stable) {
                val ref = reference
                if (ref != null && sample.meanAbsDiff(ref) <= changeThreshold) {
                    // Voltou para a mesma página (ex.: toque abriu e fechou o menu): não captura.
                    state = State.ARMED
                } else {
                    state = State.REFERENCE
                    return true
                }
            }
        }
        return false
    }
}
