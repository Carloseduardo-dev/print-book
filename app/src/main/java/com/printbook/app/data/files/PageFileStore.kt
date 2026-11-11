package com.printbook.app.data.files

import android.content.Context
import android.graphics.Bitmap
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
class PageFileStore @Inject constructor(@ApplicationContext context: Context) {

    private val root = File(context.filesDir, "books")

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

    /** Grava a capa própria (WebP 90, até 1080 px de largura) e devolve o nome do arquivo. */
    fun writeCover(bookId: Long, bitmap: Bitmap): String {
        val dir = bookDir(bookId).apply { mkdirs() }
        val scaled = if (bitmap.width > COVER_MAX_WIDTH) {
            Bitmap.createScaledBitmap(bitmap, COVER_MAX_WIDTH, bitmap.height * COVER_MAX_WIDTH / bitmap.width, true)
        } else bitmap
        // Nome único a cada troca: evita o cache de imagem mostrar a capa antiga.
        val name = "cover-${UUID.randomUUID()}.webp"
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, LOSSY_QUALITY, it) }
        if (scaled !== bitmap) scaled.recycle()
        check(tmp.renameTo(File(dir, name))) { "Falha ao gravar a capa" }
        return name
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
