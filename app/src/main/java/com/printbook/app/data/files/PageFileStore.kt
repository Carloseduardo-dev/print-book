package com.printbook.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.net.Uri
import com.printbook.app.data.prefs.PageQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imagens das páginas em filesDir/books/{bookId}/{uuid}.webp (privado, fora da galeria e do backup).
 * O nome do arquivo não depende da posição: reordenar só mexe no banco.
 */
@Singleton
class PageFileStore @Inject constructor(@ApplicationContext private val appContext: Context) {

    private val root = File(appContext.filesDir, "books")

    fun bookDir(bookId: Long): File = File(root, bookId.toString())

    fun file(bookId: Long, fileName: String): File = File(bookDir(bookId), fileName)

    data class Written(val fileName: String, val bytes: Long, val encodeMs: Long)

    /** Escrita atômica: grava em .tmp e renomeia. */
    fun write(bookId: Long, bitmap: Bitmap, quality: PageQuality = PageQuality.MAXIMA): Written {
        val dir = bookDir(bookId).apply { mkdirs() }
        // Páginas no modo Texto levam o prefixo "g-" (cinza): a compactação sabe que já estão no menor formato.
        val name = (if (quality == PageQuality.TEXTO) TEXT_PREFIX else "") + "${UUID.randomUUID()}.webp"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        val start = System.nanoTime()
        try {
            tmp.outputStream().use { encode(bitmap, quality, it) }
            check(tmp.renameTo(target)) { "Falha ao gravar $name" }
        } finally {
            tmp.delete()
        }
        val encodeMs = (System.nanoTime() - start) / 1_000_000
        return Written(name, target.length(), encodeMs)
    }

    private fun encode(bitmap: Bitmap, quality: PageQuality, out: java.io.OutputStream) {
        val ok = when (quality) {
            PageQuality.MAXIMA -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_EFFORT, out)
            PageQuality.ECONOMICA -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, LOSSY_QUALITY, out)
            PageQuality.TEXTO -> {
                val gray = toGray(bitmap)
                try {
                    gray.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_EFFORT, out)
                } finally {
                    gray.recycle()
                }
            }
        }
        check(ok) { "Falha ao comprimir a página" }
    }

    /**
     * Tons de cinza (modo Texto). Com os 3 canais iguais, o WebP sem perda fica ~43% menor que a
     * Econômica e ~70% menor que a Máxima (medido em 30 páginas reais), sem perder nitidez.
     */
    private fun toGray(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Página criada a partir de uma imagem (galeria): arquivo gravado e dimensões. */
    data class ImportedPage(val written: Written, val width: Int, val height: Int)

    /**
     * Grava uma imagem da galeria (Photo Picker) como página: decodifica já reduzida a
     * [PAGE_IMPORT_MAX_WIDTH] e salva na qualidade escolhida. Lança se a imagem não abrir.
     */
    fun writePageFromUri(bookId: Long, uri: Uri, quality: PageQuality): ImportedPage {
        val bitmap = decodeScaled(uri, PAGE_IMPORT_MAX_WIDTH, PAGE_IMPORT_MAX_HEIGHT)
        try {
            return ImportedPage(write(bookId, bitmap, quality), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Página gravada no modo Texto (tons de cinza, sem perda)? */
    fun isText(fileName: String): Boolean = fileName.startsWith(TEXT_PREFIX)

    /** Formato atual de uma página (lido do cabeçalho do arquivo). */
    fun kind(bookId: Long, fileName: String): WebpFormat.Kind {
        val f = file(bookId, fileName)
        if (!f.exists()) return WebpFormat.Kind.UNKNOWN
        val header = ByteArray(WebpFormat.HEADER_BYTES)
        val n = f.inputStream().use { it.read(header) }
        return WebpFormat.kind(if (n < header.size) header.copyOf(n.coerceAtLeast(0)) else header)
    }

    /** Recodifica uma página existente em [quality] (compactar livro). Devolve o novo arquivo. */
    fun recode(bookId: Long, fileName: String, quality: PageQuality): Written {
        val bitmap = BitmapFactory.decodeFile(file(bookId, fileName).path) ?: error("Página ilegível: $fileName")
        try {
            return write(bookId, bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Grava a capa própria a partir de uma imagem (Photo Picker): decodifica já reduzida a
     * [COVER_MAX_WIDTH], salva em WebP 90 e devolve o nome do arquivo. Lança se a imagem não abrir
     * ou a gravação falhar (o chamador trata).
     */
    fun writeCover(bookId: Long, uri: Uri): String {
        val bitmap = decodeScaled(uri, COVER_MAX_WIDTH, COVER_MAX_HEIGHT)
        try {
            val dir = bookDir(bookId).apply { mkdirs() }
            // Nome único a cada troca: evita o cache de imagem mostrar a capa antiga.
            val name = "cover-${UUID.randomUUID()}.webp"
            val tmp = File(dir, "$name.tmp")
            try {
                tmp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, LOSSY_QUALITY, it)) { "Falha ao comprimir a capa" } }
                check(tmp.renameTo(File(dir, name))) { "Falha ao gravar a capa" }
            } finally {
                tmp.delete()
            }
            return name
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Decodifica a imagem já reduzida para caber em [maxWidth] x [maxHeight], mantendo a proporção.
     * Limitar só a largura não basta: um print longo de rolagem (1440x30000) abriria inteiro.
     */
    private fun decodeScaled(uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            fitWithin(info.size.width, info.size.height, maxWidth, maxHeight)?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }

    fun delete(bookId: Long, fileName: String) {
        file(bookId, fileName).delete()
    }

    fun deleteBook(bookId: Long) {
        bookDir(bookId).deleteRecursively()
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal companion object {
        /** Em WEBP_LOSSLESS, "quality" é o esforço de compressão (medido: ~145 ms/página no A56). */
        const val LOSSLESS_EFFORT = 75
        const val LOSSY_QUALITY = 90
        const val COVER_MAX_WIDTH = 1080
        const val COVER_MAX_HEIGHT = 2400
        const val TEXT_PREFIX = "g-"
        /** Largura máxima de uma imagem importada como página (fotos de câmera são bem maiores). */
        const val PAGE_IMPORT_MAX_WIDTH = 1600
        /** Altura máxima: limita a memória de prints longos (1600x4000 ARGB = 25 MB). */
        const val PAGE_IMPORT_MAX_HEIGHT = 4000

        /**
         * Tamanho reduzido que cabe em [maxW] x [maxH] mantendo a proporção (nunca menor que 1 px),
         * ou null se a imagem já cabe.
         */
        fun fitWithin(width: Int, height: Int, maxW: Int, maxH: Int): Pair<Int, Int>? {
            if (width <= 0 || height <= 0 || (width <= maxW && height <= maxH)) return null
            val scale = minOf(maxW.toDouble() / width, maxH.toDouble() / height)
            return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        }
    }
}
