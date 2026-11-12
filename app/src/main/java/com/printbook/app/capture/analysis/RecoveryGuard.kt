package com.printbook.app.capture.analysis

/**
 * Protege contra o rodapé lido errado do mesmo jeito em várias páginas: cada leitura errada
 * dispara uma recuperação que termina em "não faltou nada" (alarme falso). Nada errado é
 * gravado, mas a captura fica lenta. Dois alarmes falsos próximos (a até [window] páginas um do
 * outro) desligam a recuperação na sessão. Lógica pura.
 *
 * Alarmes isolados (um erro de leitura de vez em quando) não desligam nada.
 */
class RecoveryGuard(private val window: Int = 10) {

    var enabled = true
        private set

    private var lastFalseAlarmAt: Int? = null

    /**
     * Registra um alarme falso na página [position] da sessão.
     * @return true se a recuperação acabou de ser desligada por causa dele.
     */
    fun onFalseAlarm(position: Int): Boolean {
        val last = lastFalseAlarmAt
        lastFalseAlarmAt = position
        if (enabled && last != null && position - last in 0..window) {
            enabled = false
            return true
        }
        return false
    }

    /** Uma página pulada de verdade foi recuperada: o rodapé está sendo lido certo. */
    fun onRecovered() {
        lastFalseAlarmAt = null
    }
}
