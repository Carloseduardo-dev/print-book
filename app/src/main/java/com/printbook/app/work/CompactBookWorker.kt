package com.printbook.app.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.printbook.app.data.prefs.PageQuality
import com.printbook.app.data.repository.BookRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Compacta um livro já salvo: recodifica as páginas em Econômica (só as que estão sem perda) ou
 * em Texto (as que ainda não estão). Ids, ordem, capa, marcadores e texto do OCR não mudam.
 * Cada página é trocada sob o lock do repositório (não corre junto com excluir livro/páginas).
 */
@HiltWorker
class CompactBookWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val books: BookRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1)
        val quality = inputData.getString(KEY_QUALITY)?.let { runCatching { PageQuality.valueOf(it) }.getOrNull() }
        if (bookId < 0 || quality == null || quality == PageQuality.MAXIMA) return Result.failure()

        val ids = books.pageIds(bookId)
        var freed = 0L
        try {
            ids.forEachIndexed { index, pageId ->
                setProgress(workDataOf(KEY_DONE to index, KEY_TOTAL to ids.size))
                try {
                    freed += books.compactPage(bookId, pageId, quality)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Página $pageId não compactada", e)
                }
            }
        } finally {
            // Mesmo se o WorkManager parar no meio, o tamanho do livro fica certo.
            withContext(NonCancellable) { books.refreshStats(bookId) }
        }
        Log.i(TAG, "Livro $bookId compactado em ${quality.label}: ${freed / 1024} KB liberados")
        return Result.success(workDataOf(KEY_FREED to freed, KEY_TOTAL to ids.size, KEY_FINISHED_AT to System.currentTimeMillis()))
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_QUALITY = "quality"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FREED = "freed"
        /** Quando terminou: a mensagem de resultado só aparece por um tempo depois disso. */
        const val KEY_FINISHED_AT = "finishedAt"
        private const val TAG = "CompactBookWorker"
    }
}
