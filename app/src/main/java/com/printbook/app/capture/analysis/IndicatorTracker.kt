package com.printbook.app.capture.analysis

/**
 * Confere o número da página lido no rodapé a cada captura, sem regra por app. Lógica pura.
 *
 * O rodapé pode ter vários números no formato "N / total" (página, capítulo, data, erro do OCR).
 * Um padrão só passa a ser confiável quando sobe exatamente +1 por página avançada em duas
 * leituras seguidas. Depois disso, cada leitura é comparada com o esperado:
 *  - igual: tudo certo;
 *  - 1 a [maxSkip] páginas à frente: páginas puladas ([Event.Skipped]);
 *  - muito à frente: o leitor saltou ([Event.Jumped]);
 *  - atrás além da tolerância: o leitor voltou sozinho ([Event.WentBack]).
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

    /** Leituras ainda não confirmadas, por total. */
    private val pending = mutableMapOf<Int, ArrayDeque<Pair<Int, Int>>>()

    var total: Int? = null
        private set
    private var baseIndex = 0
    private var basePage = 0

    val confirmed: Boolean get() = total != null

    /** Página esperada na posição [index] (null antes de confirmar). */
    fun expectedAt(index: Int): Int? = total?.let { basePage + (index - baseIndex) }

    fun onReading(index: Int, candidates: List<Reading>): Event {
        val t = total
        if (t == null) {
            for (c in candidates) {
                val list = pending.getOrPut(c.total) { ArrayDeque() }
                val prev = list.lastOrNull()
                list.addLast(index to c.current)
                while (list.size > 3) list.removeFirst()
                if (prev != null && index > prev.first && c.current - prev.second == index - prev.first) {
                    total = c.total
                    accept(index, c.current)
                    pending.clear()
                    return Event.Confirmed(c.current, c.total)
                }
            }
            return Event.None
        }
        val r = candidates.firstOrNull { it.total == t } ?: return Event.None
        val expected = basePage + (index - baseIndex)
        val diff = r.current - expected
        return when {
            diff == 0 -> { accept(index, r.current); Event.None }
            diff in 1..maxSkip -> Event.Skipped((expected until r.current).toList(), r.current)
            diff > maxSkip -> Event.Jumped(r.current, expected)
            diff < -backTolerance -> Event.WentBack(r.current, expected)
            else -> Event.None // pequena diferença para trás: erro de leitura, ignora
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
        private val slash = Regex("""(?<![\d/])(\d{1,5})\s*/\s*(\d{1,5})(?![\d/])""")
        private val pageOf = Regex("""p[áa]g(?:ina|\.)?\s*(\d{1,5})\s+de\s+(\d{1,5})""", RegexOption.IGNORE_CASE)

        /** Todos os "N / total" e "página N de M" do texto (a confirmação escolhe o certo). */
        fun candidates(text: String): List<Reading> {
            val found = mutableListOf<Reading>()
            for (rx in listOf(pageOf, slash)) {
                rx.findAll(text).forEach { m ->
                    val c = m.groupValues[1].toInt()
                    val t = m.groupValues[2].toInt()
                    if (t > 1 && c in 1..t) found += Reading(c, t)
                }
            }
            return found.distinct()
        }
    }
}
