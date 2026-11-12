package com.printbook.app.capture.analysis

import kotlin.math.max
import kotlin.math.min

/**
 * Ritmo do modo Turbo ajustado ao leitor, em vez de tempos fixos. Lógica pura (testes em JVM).
 *
 * Sinais recebidos a cada virada:
 *  - [onSettled]: quanto tempo a página levou para estabilizar depois do gesto;
 *  - [onMidTurn]: um quadro saiu no meio da virada (duas folhas lado a lado);
 *  - [onSlowPage]: a página demorou além do normal para carregar;
 *  - [onCleanPage]: página salva sem nenhum desses problemas.
 *
 * O que ajusta:
 *  - [intervalMs]: intervalo mínimo entre viradas. Sobe com os 10% mais lentos das páginas
 *    recentes (leitor lento) e com as penalidades de problemas, que somem aos poucos a cada
 *    página limpa. Fica sempre entre [minIntervalMs] e [maxIntervalMs];
 *  - [stableSamples]: amostras estáveis exigidas. Logo após um quadro no meio da virada, exige
 *    mais por algumas páginas;
 *  - [sharpStableMs]: tempo com a nitidez no máximo antes de capturar.
 */
class AdaptivePace(
    private val minIntervalMs: Long = 900,
    private val maxIntervalMs: Long = 2_500,
    private val baseStable: Int = 3,
    private val cautiousStable: Int = 5,
    private val window: Int = 12,
) {
    private val settles = ArrayDeque<Long>()
    private var penaltyMs = 0.0
    private var cautiousPages = 0

    fun onSettled(ms: Long) {
        settles.addLast(ms.coerceAtLeast(0))
        while (settles.size > window) settles.removeFirst()
    }

    fun onMidTurn() {
        penaltyMs = min(penaltyMs + MID_TURN_PENALTY_MS, MAX_PENALTY_MS)
        cautiousPages = CAUTIOUS_PAGES
    }

    fun onSlowPage() {
        penaltyMs = min(penaltyMs + SLOW_PAGE_PENALTY_MS, MAX_PENALTY_MS)
    }

    fun onCleanPage() {
        penaltyMs *= DECAY
        if (penaltyMs < 20) penaltyMs = 0.0
        if (cautiousPages > 0) cautiousPages--
    }

    /** Tempo de estabilização dos 10% mais lentos das páginas recentes (null com poucas amostras). */
    val slowSettleMs: Long?
        get() {
            if (settles.size < MIN_SAMPLES) return null
            val sorted = settles.sorted()
            // Percentil 90 por posição mais próxima: posição ceil(0,9 × n), contando de 1.
            val rank = kotlin.math.ceil(sorted.size * 0.9).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

    val intervalMs: Long
        get() {
            val fromReader = slowSettleMs?.let { max(0L, it - FAST_SETTLE_MS) } ?: 0L
            return (minIntervalMs + fromReader + penaltyMs.toLong()).coerceIn(minIntervalMs, maxIntervalMs)
        }

    val stableSamples: Int get() = if (cautiousPages > 0) cautiousStable else baseStable

    val sharpStableMs: Long get() = if (cautiousPages > 0 || penaltyMs > 0) CAREFUL_SHARP_MS else FAST_SHARP_MS

    override fun toString(): String =
        "intervalo ${intervalMs} ms, amostras $stableSamples, nitidez ${sharpStableMs} ms, lentas ${slowSettleMs ?: "-"} ms"

    companion object {
        /** Abaixo disso a estabilização é a de um leitor rápido (não pede intervalo maior). */
        const val FAST_SETTLE_MS = 500L
        const val MID_TURN_PENALTY_MS = 400.0
        const val SLOW_PAGE_PENALTY_MS = 600.0
        const val MAX_PENALTY_MS = 1_500.0
        /** A penalidade cai 15% a cada página limpa (some em ~15 páginas). */
        const val DECAY = 0.85
        /** Páginas com amostras extras depois de um quadro no meio da virada. */
        const val CAUTIOUS_PAGES = 10
        const val MIN_SAMPLES = 3
        const val FAST_SHARP_MS = 150L
        const val CAREFUL_SHARP_MS = 250L
    }
}
