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
        private val slash = Regex("""(?<!\d)(\d{1,5})\s*/\s*(\d{1,5})(?!\d)""")
        private val pageOf = Regex("""p[áa]gina\s+(\d{1,5})\s+de\s+(\d{1,5})""", RegexOption.IGNORE_CASE)
        private val location = Regex("""localiza[çc][ãa]o\s+(\d{1,6})\s+de\s+(\d{1,6})""", RegexOption.IGNORE_CASE)
        private val remainingRx = Regex("""(\d{1,5})\s+p[áa]ginas?\s+restantes?(\s+no\s+cap[íi]tulo)?""", RegexOption.IGNORE_CASE)
        private val percentRx = Regex("""(?<!\d)(\d{1,3})\s*%""")

        /** @return null se o texto não tem nenhum indicador reconhecível. */
        fun parse(text: String): PageIndicator? {
            val percent = percentRx.find(text)?.groupValues?.get(1)?.toInt()?.takeIf { it in 0..100 }

            location.find(text)?.let { m ->
                val (c, t) = m.destructured
                if (c.toInt() <= t.toInt()) return PageIndicator(c.toInt(), t.toInt(), percent = percent, isLocation = true)
            }
            pageOf.find(text)?.let { m ->
                val (c, t) = m.destructured
                if (c.toInt() in 1..t.toInt()) return PageIndicator(c.toInt(), t.toInt(), percent = percent)
            }
            slash.findAll(text).forEach { m ->
                val (c, t) = m.destructured
                if (c.toInt() in 1..t.toInt() && t.toInt() > 1) return PageIndicator(c.toInt(), t.toInt(), percent = percent)
            }
            remainingRx.find(text)?.let { m ->
                val chapterOnly = m.groupValues[2].isNotBlank()
                return PageIndicator(remaining = if (chapterOnly) null else m.groupValues[1].toInt(), percent = percent)
            }
            return percent?.let { PageIndicator(percent = it) }
        }
    }
}
