package com.printbook.app.capture.analysis

/**
 * Detecta quadros pretos (app de origem com FLAG_SECURE ou tela apagada).
 * Trabalha com pixels ARGB amostrados, sem dependência de Android, para ser testável em JVM.
 *
 * Regra do projeto: quadro preto => avisar e pausar. Nunca tentar contornar a proteção.
 */
object BlackFrameDetector {

    /** Luminância (0..255) abaixo da qual um pixel conta como "preto". */
    private const val DARK_LUMA = 12

    /** Fração mínima de pixels escuros para considerar o quadro preto. */
    private const val DARK_FRACTION = 0.995

    fun isBlack(argbSamples: IntArray): Boolean {
        if (argbSamples.isEmpty()) return true
        var dark = 0
        for (p in argbSamples) {
            if (luma(p) < DARK_LUMA) dark++
        }
        return dark.toDouble() / argbSamples.size >= DARK_FRACTION
    }

    internal fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
