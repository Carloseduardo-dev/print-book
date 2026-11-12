package com.printbook.app.data.files

/**
 * Lê o tipo de compressão de um WebP pelo cabeçalho RIFF (sem decodificar a imagem):
 * "VP8L" = sem perda (Máxima e Texto), "VP8 " = com perda (Econômica). O modo Texto se distingue
 * pelo prefixo do nome do arquivo (ver PageFileStore.isText).
 */
object WebpFormat {
    enum class Kind { LOSSLESS, LOSSY, UNKNOWN }

    /** Bytes suficientes para achar o primeiro bloco de imagem na prática. */
    const val HEADER_BYTES = 64

    fun kind(header: ByteArray): Kind {
        if (header.size < 16 || !header.ascii(0, "RIFF") || !header.ascii(8, "WEBP")) return Kind.UNKNOWN
        var offset = 12
        while (offset + 8 <= header.size) {
            when {
                header.ascii(offset, "VP8L") -> return Kind.LOSSLESS
                header.ascii(offset, "VP8 ") -> return Kind.LOSSY
            }
            // Pula o bloco (VP8X, ICCP, ALPH...): tamanho little-endian + padding para par.
            val size = (header[offset + 4].toInt() and 0xFF) or
                ((header[offset + 5].toInt() and 0xFF) shl 8) or
                ((header[offset + 6].toInt() and 0xFF) shl 16) or
                ((header[offset + 7].toInt() and 0xFF) shl 24)
            if (size < 0) return Kind.UNKNOWN
            offset += 8 + size + (size and 1)
        }
        return Kind.UNKNOWN
    }

    private fun ByteArray.ascii(at: Int, text: String): Boolean =
        at + text.length <= size && text.indices.all { this[at + it] == text[it].code.toByte() }
}
