package com.printbook.app.capture.profile

import com.printbook.app.capture.analysis.BlackFrameDetector
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retângulo em pixels, [left, right) x [top, bottom). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Cálculos de recorte em funções puras (testáveis em JVM). */
object CropMath {

    fun toPixels(rect: NormRect, width: Int, height: Int): PixelRect = PixelRect(
        left = (rect.left * width).roundToInt().coerceIn(0, width - 1),
        top = (rect.top * height).roundToInt().coerceIn(0, height - 1),
        right = (rect.right * width).roundToInt().coerceIn(1, width),
        bottom = (rect.bottom * height).roundToInt().coerceIn(1, height),
    )

    /** A barra superior do leitor está visível? (linha de sondagem majoritariamente escura). */
    fun isChromeVisible(pixels: IntArray, width: Int, height: Int, probeY: Float): Boolean {
        val y = (probeY * height).roundToInt().coerceIn(0, height - 1)
        var dark = 0
        var total = 0
        var x = 0
        while (x < width) {
            if (BlackFrameDetector.luma(pixels[y * width + x]) < 48) dark++
            total++
            x += 4
        }
        return dark.toDouble() / total > 0.85
    }

    /** Região final do perfil: recorte fixo ∩ recorte das barras (se visíveis). */
    fun profileRect(profile: CaptureProfile, pixels: IntArray, width: Int, height: Int): PixelRect {
        var rect = profile.crop
        val chrome = profile.chromeCrop
        if (chrome != null && isChromeVisible(pixels, width, height, profile.chromeProbeY)) {
            rect = NormRect(
                maxOf(rect.left, chrome.left), maxOf(rect.top, chrome.top),
                minOf(rect.right, chrome.right), minOf(rect.bottom, chrome.bottom),
            )
        }
        return toPixels(rect, width, height)
    }

    /**
     * Remove faixas de cor uniforme nas bordas (margens), mantendo [padding] px de folga.
     *
     * - A cor de fundo é a **predominante na borda** da região (não um pixel isolado, que pode
     *   cair na sombra de uma barra do leitor).
     * - Uma linha/coluna conta como margem se até [maxOutliers] (fração) dos pixels amostrados
     *   destoarem do fundo (ex.: alça de rolagem na borda) ou se ela for uma faixa de cor única
     *   (ex.: sombra logo abaixo da barra superior).
     */
    fun trimUniformMargins(
        pixels: IntArray,
        width: Int,
        within: PixelRect,
        tolerance: Int = 14,
        step: Int = 3,
        padding: Int = 12,
        maxOutliers: Double = 0.03,
    ): PixelRect {
        val bg = dominantBorderColor(pixels, width, within, step)

        fun close(a: Int, b: Int): Boolean =
            abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tolerance &&
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tolerance &&
                abs((a and 0xFF) - (b and 0xFF)) <= tolerance

        /**
         * [samples] é margem? Quase tudo igual ao fundo; ou, só para linhas ([allowBand]), uma
         * faixa de cor única (sombra horizontal das barras). Colunas internas de uma página
         * podem ser de cor única, por isso a regra da faixa não vale para elas.
         */
        fun isMargin(samples: IntArray, count: Int, allowBand: Boolean): Boolean {
            if (count == 0) return true
            var outliers = 0
            for (i in 0 until count) if (!close(samples[i], bg)) outliers++
            if (outliers <= count * maxOutliers) return true
            if (!allowBand) return false
            val first = samples[0]
            for (i in 1 until count) if (!close(samples[i], first)) return false
            return true
        }

        val buf = IntArray(maxOf(within.width, within.height) / step + 2)
        fun rowIsMargin(y: Int): Boolean {
            var n = 0
            var x = within.left
            while (x < within.right) { buf[n++] = pixels[y * width + x]; x += step }
            return isMargin(buf, n, allowBand = true)
        }
        fun colIsMargin(x: Int, top: Int, bottom: Int): Boolean {
            var n = 0
            var y = top
            while (y < bottom) { buf[n++] = pixels[y * width + x]; y += step }
            return isMargin(buf, n, allowBand = false)
        }

        /**
         * Avança de [start] na direção [dir] enquanto for margem. Uma faixa estreita (até
         * [edgeSkip] px) colada à borda que não é margem (ex.: alça de rolagem) é pulada se
         * houver margem logo depois dela.
         */
        fun scan(start: Int, endExclusive: Int, dir: Int, edgeSkip: Int, isMargin: (Int) -> Boolean): Int {
            var pos = start
            while (pos != endExclusive) {
                if (isMargin(pos)) { pos += dir; continue }
                if (kotlin.math.abs(pos - start) >= edgeSkip) break
                var k = 1
                var found = -1
                while (k <= edgeSkip && pos + dir * k != endExclusive) {
                    if (isMargin(pos + dir * k)) { found = pos + dir * k; break }
                    k++
                }
                if (found < 0) break
                pos = found
            }
            return pos
        }

        val skipX = (within.width * EDGE_SKIP_FRACTION).toInt().coerceAtLeast(2)
        val skipY = (within.height * EDGE_SKIP_FRACTION).toInt().coerceAtLeast(2)
        val top = scan(within.top, within.bottom - 1, +1, skipY) { rowIsMargin(it) }
        if (top >= within.bottom - 1) return within // tudo uniforme: página em branco, mantém
        val bottom = scan(within.bottom - 1, top, -1, skipY) { rowIsMargin(it) } + 1
        val left = scan(within.left, within.right - 1, +1, skipX) { colIsMargin(it, top, bottom) }
        val right = scan(within.right - 1, left, -1, skipX) { colIsMargin(it, top, bottom) } + 1

        return PixelRect(
            left = (left - padding).coerceAtLeast(within.left),
            top = (top - padding).coerceAtLeast(within.top),
            right = (right + padding).coerceAtMost(within.right),
            bottom = (bottom + padding).coerceAtMost(within.bottom),
        )
    }

    /** Faixa máxima (fração do lado) colada à borda que pode ser pulada ao cortar margens. */
    private const val EDGE_SKIP_FRACTION = 0.025

    /** Cor mais frequente (quantizada) ao longo da borda da região. */
    internal fun dominantBorderColor(pixels: IntArray, width: Int, r: PixelRect, step: Int): Int {
        val counts = HashMap<Int, Int>()
        val firstSeen = HashMap<Int, Int>()
        fun add(p: Int) {
            val key = (((p shr 16) and 0xF0) shl 16) or (((p shr 8) and 0xF0) shl 8) or (p and 0xF0)
            counts[key] = (counts[key] ?: 0) + 1
            if (key !in firstSeen) firstSeen[key] = p
        }
        var x = r.left
        while (x < r.right) { add(pixels[r.top * width + x]); add(pixels[(r.bottom - 1) * width + x]); x += step }
        var y = r.top
        while (y < r.bottom) { add(pixels[y * width + r.left]); add(pixels[y * width + r.right - 1]); y += step }
        val best = counts.maxByOrNull { it.value }?.key ?: return pixels[r.top * width + r.left]
        return firstSeen.getValue(best)
    }
}
