package com.printbook.app.capture.analysis

/**
 * Ritmo da captura (ms por página) pelas últimas [window] páginas, para estimar o tempo restante.
 * Lógica pura: recebe os instantes (ms) em que cada página foi capturada.
 */
class PaceMeter(private val window: Int = 12) {
    private val times = ArrayDeque<Long>()

    fun onPage(nowMs: Long) {
        times.addLast(nowMs)
        while (times.size > window + 1) times.removeFirst()
    }

    /** Média de ms por página, ou null com menos de 2 páginas. */
    val msPerPage: Double?
        get() = if (times.size < 2) null else (times.last() - times.first()).toDouble() / (times.size - 1)

    /** Tempo estimado (ms) para [pagesLeft] páginas, ou null sem ritmo medido ainda. */
    fun etaMs(pagesLeft: Int): Long? = msPerPage?.let { (it * pagesLeft.coerceAtLeast(0)).toLong() }

    companion object {
        /** "~14 min", "~40 s", "~1 h 05 min". */
        fun format(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(1)
            return when {
                s < 60 -> "~$s s"
                s < 3600 -> "~${(s + 30) / 60} min"
                else -> "~${s / 3600} h ${"%02d".format((s % 3600) / 60)} min"
            }
        }
    }
}
