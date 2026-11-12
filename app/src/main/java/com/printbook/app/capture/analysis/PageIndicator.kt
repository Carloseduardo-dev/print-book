package com.printbook.app.capture.analysis

/**
 * Progresso lido do rodapé do app de leitura (texto do OCR), para "capturar o livro todo".
 *
 *  - Minha Biblioteca: "13 / 553"
 *  - Kindle: "Localização 120 de 5400 · 2%"
 *  - Play Livros: "212 páginas restantes · 37%" (às vezes "... no capítulo", que não serve de total)
 */
data class PageIndicator(
    val current: Int? = null,
    val total: Int? = null,
    /** Páginas que faltam DEPOIS da atual. */
    val remaining: Int? = null,
    val percent: Int? = null,
    /** "Localização" do Kindle não é página: o total serve só como proporção. */
    val isLocation: Boolean = false,
) {
    /** Páginas a capturar contando a atual, quando dá para saber. */
    val pagesToCapture: Int?
        get() = when {
            current != null && total != null && !isLocation -> total - current + 1
            remaining != null -> remaining + 1
            else -> null
        }

    companion object {
        /** "N / total" (não pega datas como 12/11/2026). */
        private val slash = Regex("""(?<![\d/])(\d{1,5})\s*/\s*(\d{1,5})(?![\d/])""")
        private val pageOf = Regex("""p[áa]g(?:ina|\.)?\s*(\d{1,5})\s+de\s+(\d{1,5})""", RegexOption.IGNORE_CASE)

        /**
         * Todos os pares (página, total) do texto, de "página N de M" e "N / total". Usado aqui e
         * pela conferência a cada página ([IndicatorTracker]): os dois leem o rodapé do mesmo jeito.
         */
        fun pageCandidates(text: String): List<Pair<Int, Int>> {
            val found = mutableListOf<Pair<Int, Int>>()
            for (rx in listOf(pageOf, slash)) {
                rx.findAll(text).forEach { m ->
                    val c = m.groupValues[1].toInt()
                    val t = m.groupValues[2].toInt()
                    if (t > 1 && c in 1..t) found += c to t
                }
            }
            return found.distinct()
        }
        private val location = Regex("""localiza[çc][ãa]o\s+(\d{1,6})\s+de\s+(\d{1,6})""", RegexOption.IGNORE_CASE)
        private val remainingRx = Regex("""(\d{1,5})\s+p[áa]ginas?\s+restantes?(\s+no\s+cap[íi]tulo)?""", RegexOption.IGNORE_CASE)
        private val percentRx = Regex("""(?<!\d)(\d{1,3})\s*%""")
        /** Página sem número, antes da página 1: "Capa / 553", "iv / 553". Só o total serve. */
        private val labelSlash = Regex("""(?<![\p{L}\d])([\p{L}]{1,12})\s*/\s*(\d{1,5})(?!\d)""")

        /** @return null se o texto não tem nenhum indicador reconhecível. */
        fun parse(text: String): PageIndicator? {
            val percent = percentRx.find(text)?.groupValues?.get(1)?.toInt()?.takeIf { it in 0..100 }

            location.find(text)?.let { m ->
                val (c, t) = m.destructured
                if (c.toInt() <= t.toInt()) return PageIndicator(c.toInt(), t.toInt(), percent = percent, isLocation = true)
            }
            pageCandidates(text).firstOrNull()?.let { (c, t) -> return PageIndicator(c, t, percent = percent) }
            labelSlash.find(text)?.let { m ->
                val total = m.groupValues[2].toInt()
                if (total > 1) return PageIndicator(total = total, percent = percent)
            }
            remainingRx.find(text)?.let { m ->
                val chapterOnly = m.groupValues[2].isNotBlank()
                return PageIndicator(remaining = if (chapterOnly) null else m.groupValues[1].toInt(), percent = percent)
            }
            return percent?.let { PageIndicator(percent = it) }
        }
    }
}
