package com.printbook.app.data.files

import android.content.Context
import android.graphics.Bitmap
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
    fun write(bookId: Long, bitmap: Bitmap): Written {
        val dir = bookDir(bookId).apply { mkdirs() }
        val name = "${UUID.randomUUID()}.webp"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        val start = System.nanoTime()
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_EFFORT, it) }
        val encodeMs = (System.nanoTime() - start) / 1_000_000
        check(tmp.renameTo(target)) { "Falha ao gravar $name" }
        return Written(name, target.length(), encodeMs)
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
    }
}
