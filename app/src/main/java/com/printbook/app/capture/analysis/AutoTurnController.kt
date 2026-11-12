package com.printbook.app.capture.analysis

/**
 * Decide, a cada ciclo do modo automático (virar → esperar → capturar), se continua, para ou pausa.
 * Lógica pura, sem Android, para testes em JVM.
 *
 *  - Fim do livro: [duplicateLimit] duplicatas seguidas ou [noChangeLimit] viradas sem a tela mudar.
 *  - Limite de páginas por sessão ([maxPages], null = sem limite).
 *  - Quadro preto: pausa (app bloqueia captura; nunca contornar).
 *  - Falhas seguidas de gesto/captura: pausa.
 */
class AutoTurnController(
    private val maxPages: Int? = 500,
    private val duplicateLimit: Int = 3,
    private val noChangeLimit: Int = 2,
    private val failureLimit: Int = 3,
) {
    enum class Result { SAVED, DUPLICATE, NO_CHANGE, BLACK, FAILED }

    enum class Reason(val message: String) {
        END_OF_BOOK("Fim do livro"),
        PAGE_LIMIT("Limite de páginas da sessão atingido"),
        BLOCKED("Tela preta: este app bloqueia captura"),
        LEFT_READER("Captura pausada: você saiu do app que estava sendo capturado"),
        FAILURES("Captura pausada: falhas seguidas ao virar a página"),
    }

    sealed interface Decision {
        data object Continue : Decision
        data class Stop(val reason: Reason) : Decision
        data class Pause(val reason: Reason) : Decision
    }

    var savedPages = 0
        private set
    private var duplicates = 0
    private var noChanges = 0
    private var failures = 0

    /** Página que o leitor tinha pulado e foi recuperada (conta no total da sessão). */
    fun onRecovered() {
        savedPages++
    }

    fun onResult(result: Result): Decision {
        when (result) {
            Result.SAVED -> {
                savedPages++
                duplicates = 0; noChanges = 0; failures = 0
                if (maxPages != null && savedPages >= maxPages) return Decision.Stop(Reason.PAGE_LIMIT)
            }
            Result.DUPLICATE -> {
                duplicates++; failures = 0
                if (duplicates >= duplicateLimit) return Decision.Stop(Reason.END_OF_BOOK)
            }
            Result.NO_CHANGE -> {
                noChanges++; failures = 0
                if (noChanges >= noChangeLimit) return Decision.Stop(Reason.END_OF_BOOK)
            }
            Result.BLACK -> return Decision.Pause(Reason.BLOCKED)
            Result.FAILED -> {
                failures++
                if (failures >= failureLimit) return Decision.Pause(Reason.FAILURES)
            }
        }
        return Decision.Continue
    }
}
