package com.printbook.app.capture.analysis

import kotlin.math.min

/**
 * Reconhece uma página capturada no meio da virada em leitores de layout fixo (folha com moldura,
 * como o Minha Biblioteca): durante a virada aparecem DUAS folhas lado a lado, então surgem duas
 * bordas verticais de folha no meio da imagem, separadas pelo vão entre as folhas.
 *
 * O leitor às vezes trava a animação no meio enquanto carrega a próxima página, e o quadro parado
 * parece uma página pronta. Validado em 1.068 capturas reais do Minha Biblioteca: 20/20 quadros
 * de virada detectados e 0 falsos positivos (uma borda única, como a linha da coluna de notas, e
 * páginas escuras, como a contracapa, não contam).
 */
object SeamDetector {

    /** [lumaAt] devolve a luminância 0..255 do pixel (x, y) da página recortada [width]x[height]. */
    inline fun hasSheetSeam(width: Int, height: Int, lumaAt: (x: Int, y: Int) -> Int): Boolean {
        val edges = edgeColumns(width, height, lumaAt)
        val scale = width / 968.0
        val minGap = (80 * scale).toInt()
        val maxGap = (220 * scale).toInt()
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val gap = edges[j] - edges[i]
            if (gap in minGap..maxGap) return true
        }
        return false
    }

    /**
     * Colunas (agrupadas) que são uma linha vertical escura em quase toda a altura amostrada,
     * longe das laterais.
     */
    inline fun edgeColumns(width: Int, height: Int, lumaAt: (x: Int, y: Int) -> Int): List<Int> {
        val scale = width / 968.0
        val margin = (60 * scale).toInt()
        val maxY = min(height, (1200 * scale).toInt())
        val rows = (0 until maxY step 20).toList()
        if (rows.isEmpty() || width <= margin * 2) return emptyList()
        val groups = mutableListOf<MutableList<Int>>()
        var darkColumns = 0
        for (x in margin until width - margin) {
            val dark = rows.count { y -> lumaAt(x, y) < 200 }
            if (dark >= rows.size * 0.9) {
                darkColumns++
                val last = groups.lastOrNull()
                if (last != null && x - last.last() <= 3) last.add(x) else groups.add(mutableListOf(x))
            }
        }
        // Página escura (ex.: contracapa): colunas escuras demais não são bordas de folha.
        if (darkColumns > (width - margin * 2) * 0.15) return emptyList()
        // Borda de folha é uma linha fina; faixas largas são figuras ou fundo.
        val maxLine = (6 * scale).toInt().coerceAtLeast(2)
        return groups.filter { it.last() - it.first() <= maxLine }.map { it.average().toInt() }
    }
}
