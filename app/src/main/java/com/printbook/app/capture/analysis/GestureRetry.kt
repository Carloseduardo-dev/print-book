package com.printbook.app.capture.analysis

import kotlinx.coroutines.delay

/**
 * Gesto de virar a página com nova tentativa quando o leitor o ignora (ex.: o deslize caiu
 * durante uma animação). Lógica sem Android: quem chama fornece o gesto e as esperas.
 *
 * A cada tentativa: faz o gesto e espera a tela COMEÇAR a mudar por pouco tempo. Se ela nem
 * começou, o gesto foi ignorado: espera um pouco e tenta de novo. Se começou, espera parar (sem
 * novas tentativas: repetir aí viraria uma página a mais).
 */
object GestureRetry {

    enum class Result {
        /** A tela mudou e parou. */
        SETTLED,
        /** A tela mudou e não parou (leitor carregando): não dá para saber onde ela está. */
        UNSETTLED,
        /** Nenhuma tentativa mudou a tela: continua onde estava. */
        IGNORED,
    }

    /**
     * @param beforeAttempt prepara a tentativa (ex.: arma o gatilho com a tela de antes).
     * @param gesture faz o gesto; false se não pôde ser executado (conta como ignorado).
     * @param awaitStart true = a tela já mudou e parou; false = começou a mudar; null = não mudou no prazo.
     * @param awaitSettle espera a tela que começou a mudar parar (true = parou).
     */
    suspend fun turn(
        attempts: Int,
        retryDelayMs: Long,
        beforeAttempt: suspend () -> Unit,
        gesture: suspend () -> Boolean,
        awaitStart: suspend () -> Boolean?,
        awaitSettle: suspend () -> Boolean,
        onRetry: (attempt: Int) -> Unit = {},
    ): Result {
        for (attempt in 1..attempts.coerceAtLeast(1)) {
            if (attempt > 1) {
                onRetry(attempt)
                delay(retryDelayMs)
            }
            beforeAttempt()
            if (!gesture()) continue
            when (awaitStart()) {
                true -> return Result.SETTLED
                false -> return if (awaitSettle()) Result.SETTLED else Result.UNSETTLED
                null -> Unit // ignorado: tenta de novo
            }
        }
        return Result.IGNORED
    }
}
