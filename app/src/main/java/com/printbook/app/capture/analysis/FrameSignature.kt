package com.printbook.app.capture.analysis

import kotlin.math.abs

/**
 * Miniatura em tons de cinza (luminância 0..255) de um quadro. Usada para:
 *  - detectar quando a tela parou de mudar (modo semiautomático);
 *  - detectar página repetida (dedupe / fim do livro).
 *
 * Comparação por diferença absoluta média (0 = idênticos, 255 = opostos). Mais confiável que
 * hashes de 64 bits para páginas de texto, que ficam parecidas em baixa resolução.
 */
class FrameSignature(val width: Int, val height: Int, val luma: ByteArray) {

    init {
        require(luma.size == width * height) { "Tamanho inconsistente" }
    }

    fun meanAbsDiff(other: FrameSignature): Double {
        require(other.width == width && other.height == height) { "Assinaturas de tamanhos diferentes" }
        var sum = 0L
        for (i in luma.indices) {
            sum += abs((luma[i].toInt() and 0xFF) - (other.luma[i].toInt() and 0xFF))
        }
        return sum.toDouble() / luma.size
    }

    companion object {
        const val DEFAULT_WIDTH = 72
        const val DEFAULT_HEIGHT = 156

        /**
         * Amostra uma grade [width]x[height] usando [pixelAt] (ARGB) sobre uma imagem de
         * [srcWidth]x[srcHeight]. Pega o pixel central de cada célula.
         */
        inline fun sample(
            srcWidth: Int,
            srcHeight: Int,
            width: Int = DEFAULT_WIDTH,
            height: Int = DEFAULT_HEIGHT,
            pixelAt: (x: Int, y: Int) -> Int,
        ): FrameSignature {
            val out = ByteArray(width * height)
            var i = 0
            for (gy in 0 until height) {
                val y = ((gy * 2 + 1) * srcHeight / (height * 2)).coerceIn(0, srcHeight - 1)
                for (gx in 0 until width) {
                    val x = ((gx * 2 + 1) * srcWidth / (width * 2)).coerceIn(0, srcWidth - 1)
                    out[i++] = BlackFrameDetector.luma(pixelAt(x, y)).toByte()
                }
            }
            return FrameSignature(width, height, out)
        }
    }
}
