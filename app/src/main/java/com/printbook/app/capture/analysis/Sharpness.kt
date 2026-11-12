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
 * Decide quando a página nova terminou de "ficar nítida": libera quando a nitidez fica perto do
 * máximo visto nesta virada por pelo menos [minStableMs] (e [minSamples] medições). O tempo mínimo
 * evita liberar uma versão provisória borrada que fica parada na tela antes da nítida chegar
 * (tela parada não gera quadros novos, então duas medições seguidas sempre "batem").
 * Páginas quase sem conteúdo (valor muito baixo) liberam direto.
 */
class SharpnessGate(
    private val minStableMs: Long = 300,
    private val ratio: Double = 0.98,
    private val minSamples: Int = 2,
    private val blankBelow: Double = 0.5,
) {
    private var max = 0.0
    private var stableSince = -1L
    private var stableCount = 0

    fun reset() {
        max = 0.0
        stableSince = -1L
        stableCount = 0
    }

    /** @return true quando a página pode ser capturada. [nowMs] = relógio monotônico em ms. */
    fun onSample(value: Double, nowMs: Long): Boolean {
        if (value > max * (2 - ratio)) {
            // Subiu de verdade: recomeça a contagem de estabilidade.
            stableSince = -1L
            stableCount = 0
        }
        if (value > max) max = value
        if (max < blankBelow) return true
        if (value >= max * ratio) {
            if (stableSince < 0) stableSince = nowMs
            stableCount++
        } else {
            stableSince = -1L
            stableCount = 0
        }
        return stableCount >= minSamples && nowMs - stableSince >= minStableMs
    }
}
