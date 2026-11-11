package com.printbook.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.printbook.app.data.db.dao.PageTextDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pageTextDao: PageTextDao,
) {
    /** Enfileira o OCR de um livro (uma fila por livro; não duplica se já estiver rodando). */
    fun enqueueOcr(bookId: Long) {
        val request = OneTimeWorkRequestBuilder<OcrWorker>()
            .setInputData(workDataOf(OcrWorker.KEY_BOOK_ID to bookId))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .addTag(TAG_OCR)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ocr-$bookId", ExistingWorkPolicy.KEEP, request)
    }

    /** Backfill: indexa livros prontos que ainda têm páginas sem OCR (ex.: capturados antes da Fase 4). */
    suspend fun enqueuePendingOcr() {
        pageTextDao.booksWithPendingOcr().forEach(::enqueueOcr)
    }

    private companion object {
        const val TAG_OCR = "ocr"
    }
}
