package com.printbook.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.printbook.app.data.db.dao.PageTextDao
import com.printbook.app.data.prefs.PageQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    /** Compacta um livro em segundo plano (uma fila por livro). */
    fun enqueueCompact(bookId: Long, quality: PageQuality) {
        val request = OneTimeWorkRequestBuilder<CompactBookWorker>()
            .setInputData(workDataOf(CompactBookWorker.KEY_BOOK_ID to bookId, CompactBookWorker.KEY_QUALITY to quality.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("compact-$bookId", ExistingWorkPolicy.KEEP, request)
    }

    /** Progresso da compactação de um livro (null = nada rodando nem terminado nesta execução do app). */
    fun observeCompact(bookId: Long): Flow<CompactStatus?> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("compact-$bookId").map { infos ->
            val info = infos.lastOrNull() ?: return@map null
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> CompactStatus.Running(
                    info.progress.getInt(CompactBookWorker.KEY_DONE, 0),
                    info.progress.getInt(CompactBookWorker.KEY_TOTAL, 0),
                )
                WorkInfo.State.SUCCEEDED -> CompactStatus.Done(
                    info.outputData.getLong(CompactBookWorker.KEY_FREED, 0),
                    info.outputData.getLong(CompactBookWorker.KEY_FINISHED_AT, 0),
                )
                else -> null
            }
        }

    /** Livro apagado: cancela a compactação dele (o id pode ser reaproveitado por um livro novo). */
    fun cancelCompact(bookId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("compact-$bookId")
    }

    /** Backfill: indexa livros prontos que ainda têm páginas sem OCR (ex.: capturados antes da Fase 4). */
    suspend fun enqueuePendingOcr() {
        pageTextDao.booksWithPendingOcr().forEach(::enqueueOcr)
    }

    private companion object {
        const val TAG_OCR = "ocr"
    }
}

sealed interface CompactStatus {
    data class Running(val done: Int, val total: Int) : CompactStatus
    data class Done(val freedBytes: Long, val finishedAt: Long) : CompactStatus {
        /**
         * O resultado é deste livro e recente? O WorkManager guarda o histórico por "compact-<id>"
         * e o banco reaproveita o id de um livro apagado: sem isso, um livro novo herdaria a
         * mensagem do antigo ("já estava compactado"). Resultados antigos também deixam de aparecer.
         */
        fun isCurrent(bookCreatedAt: Long, now: Long): Boolean =
            finishedAt >= bookCreatedAt && now - finishedAt in 0..RESULT_TTL_MS

        companion object {
            const val RESULT_TTL_MS = 30 * 60 * 1000L
        }
    }
}
