package com.printbook.app.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.printbook.app.data.db.dao.BookDao
import com.printbook.app.data.db.dao.PageDao
import com.printbook.app.data.files.PageFileStore
import com.printbook.app.data.files.WebpFormat
import com.printbook.app.data.prefs.PageQuality
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Compacta um livro já salvo: recodifica as páginas em Econômica (só as que estão sem perda) ou
 * em Texto (todas, para tirar a cor). Ids, ordem, capa, marcadores e texto do OCR não mudam.
 * Se a versão nova não ficar menor, mantém a página original.
 */
@HiltWorker
class CompactBookWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pageDao: PageDao,
    private val bookDao: BookDao,
    private val files: PageFileStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1)
        val quality = inputData.getString(KEY_QUALITY)?.let { runCatching { PageQuality.valueOf(it) }.getOrNull() }
        if (bookId < 0 || quality == null || quality == PageQuality.MAXIMA) return Result.failure()

        val pages = pageDao.getForBook(bookId)
        var freed = 0L
        pages.forEachIndexed { index, page ->
            setProgress(workDataOf(KEY_DONE to index, KEY_TOTAL to pages.size))
            val needsRecode = quality == PageQuality.TEXTO ||
                files.kind(bookId, page.fileName) == WebpFormat.Kind.LOSSLESS
            if (!needsRecode) return@forEachIndexed
            try {
                val written = files.recode(bookId, page.fileName, quality)
                if (written.bytes < page.byteSize) {
                    pageDao.updateFile(page.id, written.fileName, written.bytes)
                    files.delete(bookId, page.fileName)
                    freed += page.byteSize - written.bytes
                } else {
                    files.delete(bookId, written.fileName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Página ${page.id} não compactada", e)
            }
        }
        bookDao.refreshStats(bookId)
        Log.i(TAG, "Livro $bookId compactado em ${quality.label}: ${freed / 1024} KB liberados")
        return Result.success(workDataOf(KEY_FREED to freed, KEY_TOTAL to pages.size))
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_QUALITY = "quality"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FREED = "freed"
        private const val TAG = "CompactBookWorker"
    }
}
