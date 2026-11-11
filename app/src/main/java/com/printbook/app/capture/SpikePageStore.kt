package com.printbook.app.capture

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armazenamento temporário da Fase 1 (spike): filesDir/spike/NNNN.webp.
 * Na Fase 2 é substituído pelo PageFileStore (books/{bookId}/{pageId}.webp).
 */
@Singleton
class SpikePageStore @Inject constructor(@ApplicationContext context: Context) {

    private val dir = File(context.filesDir, "spike")

    data class Saved(val file: File, val encodeMs: Long)

    fun save(bitmap: Bitmap): Saved {
        dir.mkdirs()
        val index = (dir.listFiles()?.size ?: 0) + 1
        val target = File(dir, "%04d.webp".format(index))
        val tmp = File(dir, "${target.name}.tmp")
        val start = System.nanoTime()
        tmp.outputStream().use { out ->
            // Em WEBP_LOSSLESS, "quality" controla o esforço de compressão, não a qualidade.
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, LOSSLESS_EFFORT, out)
        }
        val encodeMs = (System.nanoTime() - start) / 1_000_000
        check(tmp.renameTo(target)) { "Falha ao gravar ${target.name}" }
        return Saved(target, encodeMs)
    }

    fun count(): Int = dir.listFiles { f -> f.extension == "webp" }?.size ?: 0

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.deleteRecursively()
    }

    private companion object {
        const val LOSSLESS_EFFORT = 75
    }
}
