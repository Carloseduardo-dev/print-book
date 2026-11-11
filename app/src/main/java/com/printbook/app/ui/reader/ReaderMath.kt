package com.printbook.app.ui.reader

import kotlin.math.roundToInt

/** Funções puras do leitor (testáveis em JVM). */
object ReaderMath {
    /** Converte a posição do slider (0..lastIndex, contínua) na página mais próxima. */
    fun scrubPage(value: Float, lastIndex: Int): Int =
        if (lastIndex <= 0) 0 else value.roundToInt().coerceIn(0, lastIndex)

    /** Fração 0..1 de uma página no livro, para posicionar o balão de pré-visualização. */
    fun fraction(page: Int, lastIndex: Int): Float =
        if (lastIndex <= 0) 0f else (page.toFloat() / lastIndex).coerceIn(0f, 1f)
}
