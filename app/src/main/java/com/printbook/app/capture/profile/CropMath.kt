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
     * Uma linha/coluna é "uniforme" se todos os pixels amostrados estão a até [tolerance]
     * (por canal) da cor do canto superior esquerdo da região.
     */
    fun trimUniformMargins(
        pixels: IntArray,
        width: Int,
        within: PixelRect,
        tolerance: Int = 14,
        step: Int = 3,
        padding: Int = 12,
    ): PixelRect {
        val bg = pixels[within.top * width + within.left]
        fun similar(p: Int): Boolean =
            abs(((p shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) <= tolerance &&
                abs(((p shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) <= tolerance &&
                abs((p and 0xFF) - (bg and 0xFF)) <= tolerance

        fun rowUniform(y: Int): Boolean {
            var x = within.left
            while (x < within.right) { if (!similar(pixels[y * width + x])) return false; x += step }
            return true
        }
        fun colUniform(x: Int, top: Int, bottom: Int): Boolean {
            var y = top
            while (y < bottom) { if (!similar(pixels[y * width + x])) return false; y += step }
            return true
        }

        var top = within.top
        while (top < within.bottom - 1 && rowUniform(top)) top++
        if (top >= within.bottom - 1) return within // tudo uniforme: página em branco, mantém
        var bottom = within.bottom
        while (bottom - 1 > top && rowUniform(bottom - 1)) bottom--
        var left = within.left
        while (left < within.right - 1 && colUniform(left, top, bottom)) left++
        var right = within.right
        while (right - 1 > left && colUniform(right - 1, top, bottom)) right--

        return PixelRect(
            left = (left - padding).coerceAtLeast(within.left),
            top = (top - padding).coerceAtLeast(within.top),
            right = (right + padding).coerceAtMost(within.right),
            bottom = (bottom + padding).coerceAtMost(within.bottom),
        )
    }
}
