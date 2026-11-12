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
    /** Início da sequência estável atual: a tela não pode "escorregar" devagar a partir dele. */
    private var anchor: FrameSignature? = null
    private var stableCount = 0

    /** Chamado após cada captura (ou ao iniciar): a próxima tela estável vira a referência. */
    fun resetReference() {
        state = State.REFERENCE
        reference = null
        previous = null
        anchor = null
        stableCount = 0
    }

    /**
     * O quadro "estável" era a virada travada no meio: volta a esperar a página nova estabilizar,
     * mantendo a referência.
     */
    fun rejectSettled() {
        state = State.SETTLING
        stableCount = 0
        anchor = null
    }

    /** Última amostra recebida (quando [onSample] devolve true, é a página estável que disparou). */
    val lastSample: FrameSignature? get() = previous

    /**
     * Usa [current] (a tela estável que acabou de ser capturada) como referência e já arma a
     * espera pela próxima virada, sem esperar a tela estabilizar de novo.
     */
    fun armWith(current: FrameSignature) {
        reference = current
        previous = current
        anchor = current
        stableCount = stableSamples
        state = State.ARMED
    }

    /** @return true quando a página nova estabilizou e deve ser capturada. */
    fun onSample(sample: FrameSignature): Boolean {
        val prev = previous
        previous = sample
        // Estável = cada amostra perto da anterior E perto do início da sequência. A segunda regra
        // pega o fim lento da animação de virada (a página ainda deslizando poucos px por quadro).
        val a = anchor
        if (prev != null && a != null && sample.meanAbsDiff(prev) < stableThreshold && sample.meanAbsDiff(a) < stableThreshold) {
            stableCount++
        } else {
            stableCount = 0
            anchor = sample
        }
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
