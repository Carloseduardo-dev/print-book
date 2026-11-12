package com.printbook.app.capture.analysis

/**
 * Confere o número da página lido no rodapé a cada captura, sem regra por app. Lógica pura.
 *
 * O rodapé pode ter vários números no formato "N / total" (página, capítulo, data, erro do OCR).
 * Um padrão só passa a ser confiável quando sobe exatamente +1 por página avançada em duas
 * leituras seguidas. Depois disso, cada leitura é comparada com o esperado:
 *  - igual: tudo certo;
 *  - 1 página à frente: página pulada ([Event.Skipped]), na hora;
 *  - 2 a [maxSkip] à frente: páginas puladas; mais que isso: o leitor saltou ([Event.Jumped]);
 *  - atrás além da tolerância: o leitor voltou sozinho ([Event.WentBack]);
 *  - pouco atrás: corrige a base.
 * Fora a página única, toda diferença precisa aparecer igual em duas leituras seguidas.
 *
 * [index] é a posição da página na sessão: 0 na primeira página e +1 a cada virada que mudou a
 * tela (mesmo se a captura falhou: aí a página foi perdida e aparece como pulada depois).
 */
class IndicatorTracker(
    private val maxSkip: Int = 3,
    private val backTolerance: Int = 2,
) {
    data class Reading(val current: Int, val total: Int)

    sealed interface Event {
        data object None : Event
        data class Confirmed(val page: Int, val total: Int) : Event
        data class Skipped(val missing: List<Int>, val page: Int) : Event
        data class Jumped(val page: Int, val expected: Int) : Event
        data class WentBack(val page: Int, val expected: Int) : Event
    }

    /** Leituras antes de confiar no indicador, por total. */
    private val unconfirmed = mutableMapOf<Int, ArrayDeque<Pair<Int, Int>>>()

    var total: Int? = null
        private set
    private var baseIndex = 0
    private var basePage = 0

    val confirmed: Boolean get() = total != null

    /** Página esperada na posição [index] (null antes de confirmar). */
    fun expectedAt(index: Int): Int? = total?.let { basePage + (index - baseIndex) }

    /** Diferença ainda não confirmada: posição, diferença e páginas que faltariam (da 1ª leitura). */
    private var pending: Triple<Int, Int, List<Int>>? = null

    fun onReading(index: Int, candidates: List<Reading>): Event {
        val t = total
        if (t == null) {
            for (c in candidates) {
                val list = unconfirmed.getOrPut(c.total) { ArrayDeque() }
                val prev = list.lastOrNull()
                list.addLast(index to c.current)
                while (list.size > 3) list.removeFirst()
                if (prev != null && index > prev.first && c.current - prev.second == index - prev.first) {
                    total = c.total
                    accept(index, c.current)
                    unconfirmed.clear()
                    return Event.Confirmed(c.current, c.total)
                }
            }
            return Event.None
        }
        val r = candidates.firstOrNull { it.total == t } ?: return Event.None
        val expected = basePage + (index - baseIndex)
        val diff = r.current - expected
        if (diff == 0) {
            accept(index, r.current)
            pending = null
            return Event.None
        }
        // Uma página à frente: vale na hora, porque a recuperação precisa da página seguinte ainda
        // na tela, e ela mesma confere pela tela (erro de leitura vira "não faltou nada").
        if (diff == 1) {
            pending = null
            return Event.Skipped(listOf(expected), r.current)
        }
        // Qualquer outra diferença só vale quando duas leituras seguidas concordam: um erro do OCR
        // ("173" lido como "178") não pausa a captura nem acusa páginas que não faltaram.
        val p = pending
        if (p == null || p.second != diff || index <= p.first) {
            pending = Triple(index, diff, (expected until r.current).toList())
            return Event.None
        }
        pending = null
        return when {
            diff in 2..maxSkip -> Event.Skipped(p.third, r.current)
            diff > maxSkip -> Event.Jumped(r.current, expected)
            diff < -backTolerance -> Event.WentBack(r.current, expected)
            else -> {
                // Pequena diferença para trás que se repete: a base estava errada. Corrige, senão
                // as próximas páginas puladas ficariam escondidas nessa diferença.
                accept(index, r.current)
                Event.None
            }
        }
    }

    /**
     * Aceita [page] como a página na posição [index] (nova base). Depois de [Event.Skipped] ou
     * [Event.Jumped], quem chama decide: aceita se a leitura se confirmou (ou não dá para
     * verificar), ou não aceita se era erro do OCR.
     */
    fun accept(index: Int, page: Int) {
        baseIndex = index
        basePage = page
    }

    companion object {
        /** Todos os "N / total" e "página N de M" do texto (a confirmação escolhe o certo). */
        fun candidates(text: String): List<Reading> =
            PageIndicator.pageCandidates(text).map { (c, t) -> Reading(c, t) }
    }
}
