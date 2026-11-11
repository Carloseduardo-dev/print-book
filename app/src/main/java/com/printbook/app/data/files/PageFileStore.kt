package com.printbook.app.data.files

import android.content.Context
import android.graphics.Bitmap
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
        val name = "${UUID.randomUUID()}.webp"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        val start = System.nanoTime()
        tmp.outputStream().use {
            when (quality) {
                PageQuality.MAXIMA -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_EFFORT, it)
                PageQuality.ECONOMICA -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, LOSSY_QUALITY, it)
            }
        }
        val encodeMs = (System.nanoTime() - start) / 1_000_000
        check(tmp.renameTo(target)) { "Falha ao gravar $name" }
        return Written(name, target.length(), encodeMs)
    }

    /**
     * Grava a capa própria a partir de uma imagem (Photo Picker): decodifica já reduzida a
     * [COVER_MAX_WIDTH], salva em WebP 90 e devolve o nome do arquivo. Lança se a imagem não abrir
     * ou a gravação falhar (o chamador trata).
     */
    fun writeCover(bookId: Long, uri: Uri): String {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            if (w > COVER_MAX_WIDTH) decoder.setTargetSize(COVER_MAX_WIDTH, info.size.height * COVER_MAX_WIDTH / w)
        }
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

    fun delete(bookId: Long, fileName: String) {
        file(bookId, fileName).delete()
    }

    fun deleteBook(bookId: Long) {
        bookDir(bookId).deleteRecursively()
    }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        /** Em WEBP_LOSSLESS, "quality" é o esforço de compressão (medido: ~145 ms/página no A56). */
        const val LOSSLESS_EFFORT = 75
        const val LOSSY_QUALITY = 90
        const val COVER_MAX_WIDTH = 1080
    }
}
