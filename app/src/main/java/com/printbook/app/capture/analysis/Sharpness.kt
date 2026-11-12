package com.printbook.app.capture.analysis

import kotlin.math.abs

/**
 * Nitidez de um trecho da tela em resolução real: média do gradiente de luminância (|dx| + |dy|).
 * Texto nítido tem bordas fortes; a versão provisória em baixa resolução que alguns leitores
 * desenham antes (Kindle, Play Livros) tem bordas suaves e valor bem menor.
 */
object Sharpness {

    /** [lumaAt] devolve a luminância 0..255 do pixel (x, y) do trecho [width]x[height]. */
    inline fun measure(width: Int, height: Int, lumaAt: (x: Int, y: Int) -> Int): Double {
        if (width < 2 || height < 2) return 0.0
        var sum = 0L
        for (y in 0 until height - 1) {
            for (x in 0 until width - 1) {
                val c = lumaAt(x, y)
                sum += abs(c - lumaAt(x + 1, y)) + abs(c - lumaAt(x, y + 1))
            }
        }
        return sum.toDouble() / ((width - 1) * (height - 1))
    }
}

/**
 * Decide quando a página nova terminou de "ficar nítida": libera quando as duas últimas medições
 * estão perto do máximo visto nesta virada (a nitidez parou de subir). Páginas quase sem
 * conteúdo (valor muito baixo) liberam direto.
 */
class SharpnessGate(
    private val ratio: Double = 0.98,
    private val minSamples: Int = 2,
    private val blankBelow: Double = 0.5,
) {
    private var max = 0.0
    private val recent = ArrayDeque<Double>()

    fun reset() {
        max = 0.0
        recent.clear()
    }

    /** @return true quando a página pode ser capturada. */
    fun onSample(value: Double): Boolean {
        if (value > max) max = value
        recent.addLast(value)
        while (recent.size > minSamples) recent.removeFirst()
        if (max < blankBelow) return true
        return recent.size >= minSamples && recent.all { it >= max * ratio }
    }
}
